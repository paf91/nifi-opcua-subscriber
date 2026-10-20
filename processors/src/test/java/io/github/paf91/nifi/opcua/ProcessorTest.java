/* SPDX-License-Identifier: Apache-2.0 */
package io.github.paf91.nifi.opcua;

import static org.eclipse.milo.opcua.stack.core.types.builtin.unsigned.Unsigned.ulong;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.*;

import org.apache.nifi.processor.*;
import org.apache.nifi.util.*;
import org.eclipse.milo.opcua.stack.core.types.builtin.*;
import org.junit.jupiter.api.Test;

import java.util.*;

class ProcessorTest {
    static final String NODE = "ns=2;s=Demo.Boolean";

    static class OfflineProcessor extends SubscribeOpcUa {
        @Override
        protected AutoCloseable openConnection(ProcessContext context, ConnectionAttempt attempt) {
            return () -> {};
        }
    }

    static TestRunner configured(SubscribeOpcUa processor) {
        TestRunner r = TestRunners.newTestRunner(processor);
        r.setProperty(SubscribeOpcUa.ENDPOINT, "opc.tcp://localhost:4840");
        r.setProperty(SubscribeOpcUa.APPLICATION_URI, "urn:test:client");
        r.setProperty(SubscribeOpcUa.CERTIFICATE, "/tmp/test-client.der");
        r.setProperty(SubscribeOpcUa.PRIVATE_KEY, "/tmp/test-client.pem");
        r.setProperty(SubscribeOpcUa.PKI, "/tmp/test-pki");
        r.setProperty(SubscribeOpcUa.USERNAME, "user");
        r.setProperty(SubscribeOpcUa.PASSWORD, "test-only-password");
        r.setProperty("DemoSwitch", NODE);
        return r;
    }

    @Test
    void validatesDynamicTagsAndRequiresOne() {
        TestRunner r = configured(new OfflineProcessor());
        r.assertValid();
        r.removeProperty("DemoSwitch");
        r.assertNotValid();
        r.setProperty("DemoSwitch", "{\"nodeId\":\"" + NODE + "\",\"queueSize\":1.5}");
        r.assertNotValid();
        r.setProperty("DemoSwitch", NODE);
        r.setProperty(SubscribeOpcUa.ENDPOINT, "opc.tcp://user:pass@host:4840");
        r.assertNotValid();
    }

    @Test
    void parsesExactIdsAndOverrides() {
        TagDefinition t =
                TagDefinition.parse(
                        "DemoSwitch",
                        "{\"nodeId\":\""
                                + NODE
                                + "\",\"samplingIntervalMs\":250,\"queueSize\":50,\"discardOldest\":false}",
                        1000,
                        100);
        assertEquals(NODE, t.nodeId().toParseableString());
        assertEquals(250, t.samplingIntervalMs());
        assertEquals(50, t.queueSize());
        assertFalse(t.discardOldest());
        assertEquals(
                "ns=2;s=sensor_Ω",
                TagDefinition.parse("weight", "ns=2;s=sensor_Ω", 1000, 100)
                        .nodeId()
                        .toParseableString());
        for (String invalid :
                Arrays.asList(
                        "",
                        "not a node",
                        "{\"nodeId\":\"" + NODE + "\",\"queueSize\":0}",
                        "{\"nodeId\":\"" + NODE + "\",\"typo\":1}"))
            assertThrows(
                    IllegalArgumentException.class,
                    () -> TagDefinition.parse("tag", invalid, 1000, 100));
    }

    @Test
    void producesJsonBatchesAndProvenance() throws Exception {
        OfflineProcessor p = new OfflineProcessor();
        TestRunner r = configured(p);
        r.setProperty(SubscribeOpcUa.BATCH, "2");
        p.start(r.getProcessContext());
        for (int i = 0; i < 3; i++)
            p.acceptForTest(
                    TagDefinition.parse("DemoSwitch", NODE, 1000, 100),
                    new DataValue(new Variant(i)));
        r.run(2, true, false);
        r.assertTransferCount(SubscribeOpcUa.SUCCESS, 2);
        JsonNode json =
                new ObjectMapper()
                        .readTree(
                                r.getContentAsByteArray(
                                        r.getFlowFilesForRelationship(SubscribeOpcUa.SUCCESS)
                                                .get(0)));
        assertEquals(2, json.size());
        assertEquals(NODE, json.get(0).get("nodeId").asText());
        assertEquals(0, json.get(0).get("value").asInt());
        r.getFlowFilesForRelationship(SubscribeOpcUa.SUCCESS)
                .get(0)
                .assertAttributeEquals("opcua.endpoint", "opc.tcp://localhost:4840");
        assertEquals(2, r.getProvenanceEvents().size());
    }

    @Test
    void failedCommitRetainsBatch() throws Exception {
        OfflineProcessor p = new OfflineProcessor();
        TestRunner r = configured(p);
        p.start(r.getProcessContext());
        p.acceptForTest(
                TagDefinition.parse("DemoSwitch", NODE, 1000, 100),
                new DataValue(new Variant("1")));
        ProcessSession session = spy(r.getProcessSessionFactory().createSession());
        doThrow(new RuntimeException("simulated repository failure"))
                .when(session)
                .commitAsync(any(Runnable.class), any());
        assertThrows(
                org.apache.nifi.processor.exception.ProcessException.class,
                () -> p.onTrigger(r.getProcessContext(), session));
        verify(session).rollback();
        r.run(1, true, false);
        r.assertTransferCount(SubscribeOpcUa.SUCCESS, 1);
    }

    @Test
    void boundedBufferNeverEvictsUncommittedRecords() {
        NotificationBuffer b = new NotificationBuffer(2);
        byte[] first = {1}, second = {2};
        assertTrue(b.offer(first));
        assertTrue(b.offer(second));
        assertSame(first, b.peek(1).get(0));
        assertFalse(b.offer(new byte[] {3}));
        assertSame(first, b.peek(1).get(0));
        b.acknowledge(1);
        assertSame(second, b.peek(1).get(0));
        assertTrue(b.offer(new byte[] {3}));
    }

    @Test
    void overflowRaisesCounter() throws Exception {
        OfflineProcessor p = new OfflineProcessor();
        TestRunner r = configured(p);
        r.setProperty(SubscribeOpcUa.BUFFER, "1");
        p.start(r.getProcessContext());
        for (int i = 0; i < 3; i++)
            p.acceptForTest(
                    TagDefinition.parse("DemoSwitch", NODE, 1000, 100),
                    new DataValue(new Variant(i)));
        r.run(1, true, false);
        assertEquals(Long.valueOf(2), r.getCounterValue("OPC UA notifications dropped"));
    }

    @Test
    void preservesQualityAndExactLargeIntegers() throws Exception {
        TagDefinition tag = TagDefinition.parse("DemoSwitch", NODE, 1000, 100);
        JsonNode json =
                new ObjectMapper()
                        .readTree(
                                NotificationJson.encode(
                                        tag,
                                        new DataValue(
                                                new Variant(ulong("18446744073709551615")),
                                                new StatusCode(0x80000000L),
                                                DateTime.now(),
                                                DateTime.now())));
        assertEquals("18446744073709551615", json.get("value").asText());
        assertEquals("Bad", json.get("quality").asText());
        assertTrue(json.get("sourceTimestamp").asText().endsWith("Z"));
        assertTrue(json.get("receivedTimestamp").asText().endsWith("Z"));
        assertFalse(json.has("schemaVersion"));
        assertFalse(json.has("endpoint"));
        assertFalse(json.has("serverTimestamp"));
        assertEquals(Arrays.asList(1, 2), NotificationJson.normalize(new int[] {1, 2}));
    }
}
