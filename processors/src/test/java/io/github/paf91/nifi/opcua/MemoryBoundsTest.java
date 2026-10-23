/* SPDX-License-Identifier: Apache-2.0 */
package io.github.paf91.nifi.opcua;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.management.ThreadMXBean;

import org.eclipse.milo.opcua.stack.core.types.builtin.*;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.util.*;

class MemoryBoundsTest {
    static final TagDefinition TAG = TagDefinition.parse("memory", "ns=2;s=Memory", 100, 10);

    static long allocatedBytes() {
        ThreadMXBean bean = (ThreadMXBean) ManagementFactory.getThreadMXBean();
        org.junit.jupiter.api.Assumptions.assumeTrue(bean.isThreadAllocatedMemorySupported());
        if (!bean.isThreadAllocatedMemoryEnabled()) bean.setThreadAllocatedMemoryEnabled(true);
        return bean.getThreadAllocatedBytes(Thread.currentThread().getId());
    }

    static ExtensionObject extension(Object body, Class<?> bodyType) throws Exception {
        try {
            return ExtensionObject.class
                    .getConstructor(bodyType, NodeId.class)
                    .newInstance(body, new NodeId(2, 999));
        } catch (NoSuchMethodException modern) {
            return (ExtensionObject)
                    ExtensionObject.class
                            .getMethod("of", bodyType, NodeId.class)
                            .invoke(null, body, new NodeId(2, 999));
        }
    }

    static XmlElement xml(String body) throws Exception {
        try {
            return XmlElement.class.getConstructor(String.class).newInstance(body);
        } catch (NoSuchMethodException modern) {
            return (XmlElement) XmlElement.class.getMethod("of", String.class).invoke(null, body);
        }
    }

    static JsonNode value(Object raw) throws Exception {
        return new ObjectMapper()
                .readTree(NotificationJson.encode(TAG, new DataValue(new Variant(raw)), 4096))
                .get("value");
    }

    static void assertOversized(Object raw, int limit) {
        IOException failure =
                assertThrows(
                        IOException.class,
                        () -> NotificationJson.encode(TAG, new DataValue(new Variant(raw)), limit));
        for (Throwable cause = failure; cause != null; cause = cause.getCause())
            if (cause instanceof NotificationJson.RecordTooLargeException) return;
        fail("Expected the bounded output to reject the record", failure);
    }

    @Test
    void opaqueBinaryAndXmlBodiesRetainContentWithoutDisplayConversion() throws Exception {
        JsonNode binary = value(extension(new ByteString(new byte[] {1, 2, 3}), ByteString.class));
        assertEquals("opcua-binary", binary.get("encoding").asText());
        assertEquals("ns=2;i=999", binary.get("encodingId").asText());
        assertEquals("AQID", binary.get("body").asText());
        assertFalse(binary.has("text"));
        assertTrue(value(extension(new ByteString(null), ByteString.class)).get("body").isNull());
        assertEquals(
                "",
                value(extension(new ByteString(new byte[0]), ByteString.class))
                        .get("body")
                        .asText());
        String text = "<custom value=\"Ω\">\nhello &amp; goodbye</custom>";
        JsonNode xml = value(extension(xml(text), XmlElement.class));
        assertEquals("opcua-xml", xml.get("encoding").asText());
        assertEquals(text, xml.get("body").asText());
        assertTrue(value(extension(xml(null), XmlElement.class)).get("body").isNull());
    }

    @Test
    void oversizedOpaqueValuesDoNotAllocateTheirCompleteDisplayOrBase64Representation()
            throws Exception {
        assertOversized(extension(new ByteString(new byte[4096]), ByteString.class), 1024);
        byte[] body = new byte[1536 * 1024];
        Arrays.fill(body, (byte) -1);
        Object raw = extension(new ByteString(body), ByteString.class);
        long before = allocatedBytes();
        assertOversized(raw, 1024);
        long allocation = allocatedBytes() - before;
        assertTrue(allocation < 512 * 1024, "Temporary allocation: " + allocation);
        System.out.println("MEMORY bounded-extension allocatedBytes=" + allocation);
        assertOversized(
                extension(xml(String.join("", Collections.nCopies(100000, "Ω"))), XmlElement.class),
                1024);
        assertOversized(new NodeId(2, new ByteString(body)), 1024);
    }

    @Test
    void unsupportedObjectsNeverInvokeArbitraryToString() throws Exception {
        Object unsupported =
                new Object() {
                    @Override
                    public String toString() {
                        throw new AssertionError("Unbounded display conversion");
                    }
                };
        // Call the encoder through a real Variant, including nested arrays.
        JsonNode result = value(new Object[] {unsupported});
        assertEquals("unsupported", result.get(0).get("encoding").asText());
        assertEquals(unsupported.getClass().getName(), result.get(0).get("type").asText());
        assertFalse(result.get(0).has("text"));
        UUID uuid = UUID.fromString("f0782d3f-394f-48b4-80c1-78e71ef53066");
        assertEquals(uuid.toString(), value(uuid).get("text").asText());
    }

    @Test
    void countAndByteSaturationSkipEncodingEntirely() {
        SubscribeOpcUa processor =
                new SubscribeOpcUa() {
                    @Override
                    byte[] encodeNotification(TagDefinition tag, DataValue value, int limit) {
                        throw new AssertionError("Full buffer must reject before encoding");
                    }
                };
        DataValue value = new DataValue(new Variant(1));
        for (NotificationBuffer buffer :
                Arrays.asList(
                        new NotificationBuffer(1, 100, 100), new NotificationBuffer(10, 1, 1))) {
            assertTrue(buffer.offer(new byte[] {1}));
            processor.accept(buffer, TAG, value);
            assertEquals(1, buffer.size());
        }
    }

    @Test
    void discardedLargeUpdatesHaveSmallAllocationCost() {
        SubscribeOpcUa processor = new SubscribeOpcUa();
        NotificationBuffer buffer = new NotificationBuffer(1, 2 * 1024 * 1024, 1024 * 1024);
        assertTrue(buffer.offer(new byte[] {1}));
        DataValue value = new DataValue(new Variant(new ByteString(new byte[512 * 1024])));
        for (int i = 0; i < 20; i++) processor.accept(buffer, TAG, value);
        long before = allocatedBytes();
        for (int i = 0; i < 200; i++) processor.accept(buffer, TAG, value);
        long allocation = allocatedBytes() - before;
        assertTrue(allocation < 512 * 1024, "Allocation for 200 rejected updates: " + allocation);
        assertEquals(1L, buffer.snapshot().get("bytes"));
        System.out.println("MEMORY full-buffer allocatedBytes=" + allocation);
    }

    @Test
    void partialByteCapacityBoundsEncodingAndCountsOverflowWithoutAnEncodingError()
            throws Exception {
        SubscribeOpcUa processor = new SubscribeOpcUa();
        org.apache.nifi.util.TestRunner runner = ProcessorTest.configured(processor);
        NotificationBuffer buffer = new NotificationBuffer(10, 2048, 1024);
        assertTrue(buffer.offer(new byte[1024]));
        assertTrue(buffer.offer(new byte[1000]));
        processor.accept(
                buffer, TAG, new DataValue(new Variant(new ByteString(new byte[512 * 1024]))));
        Map<String, Object> health = processor.diagnosticSnapshot(runner.getProcessContext());
        assertEquals(1L, health.get("notificationsDropped"));
        assertEquals(0L, health.get("oversizedNotifications"));
        assertEquals(0L, health.get("encodingFailures"));
        assertEquals(2024L, buffer.snapshot().get("bytes"));
    }

    @Test
    void excessiveArrayDepthFailsWithoutStackOverflow() {
        Object raw = 1;
        for (int i = 0; i < 300; i++) raw = new Object[] {raw};
        DataValue value = new DataValue(new Variant(raw));
        assertThrows(IOException.class, () -> NotificationJson.encode(TAG, value, 4096));
    }
}
