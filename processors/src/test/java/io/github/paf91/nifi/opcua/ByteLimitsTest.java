/* SPDX-License-Identifier: Apache-2.0 */
package io.github.paf91.nifi.opcua;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.apache.nifi.processor.ProcessSession;
import org.apache.nifi.util.TestRunner;
import org.eclipse.milo.opcua.stack.core.types.builtin.*;
import org.junit.jupiter.api.Test;

import java.util.*;
import java.util.concurrent.atomic.AtomicReference;

class ByteLimitsTest {
    static final TagDefinition TAG =
            TagDefinition.parse("temperature", "ns=2;s=Temperature", 100, 10);

    @Test
    void byteCapacityIncludesReservedRecordsUntilCommit() {
        NotificationBuffer buffer = new NotificationBuffer(10, 7, 4);
        byte[] first = {1, 2, 3}, second = {4, 5, 6, 7};
        assertTrue(buffer.offer(first));
        assertTrue(buffer.offer(second));
        assertFalse(buffer.offer(new byte[] {8}));
        assertEquals(Arrays.asList(first), buffer.reserve(10, 8)); // second needs a comma as well
        assertEquals(7L, buffer.snapshot().get("bytes"));
        assertTrue(buffer.reserve(10, 100).isEmpty());
        buffer.release();
        assertEquals(Arrays.asList(first, second), buffer.reserve(10, 10));
        buffer.acknowledge(2);
        assertEquals(0L, buffer.snapshot().get("bytes"));
        assertEquals(7L, buffer.snapshot().get("peakBytes"));
        assertTrue(buffer.offer(second));
        assertFalse(buffer.offer(new byte[5]));
    }

    @Test
    void randomizedByteAndCountLimitsPreserveOrderAcrossRetries() {
        Random random = new Random(152834);
        NotificationBuffer buffer = new NotificationBuffer(19, 207, 43);
        Deque<byte[]> expected = new ArrayDeque<>();
        List<byte[]> reserved = Collections.emptyList();
        long bytes = 0, peak = 0;
        for (int i = 0; i < 25000; i++) {
            switch (random.nextInt(4)) {
                case 0:
                    byte[] record = new byte[1 + random.nextInt(55)];
                    boolean fits =
                            expected.size() < 19
                                    && bytes + record.length <= 207
                                    && record.length <= 43;
                    assertEquals(fits, buffer.offer(record));
                    if (fits) {
                        expected.add(record);
                        bytes += record.length;
                        peak = Math.max(peak, bytes);
                    }
                    break;
                case 1:
                    int count = 1 + random.nextInt(25), limit = 2 + random.nextInt(300);
                    List<byte[]> batch = buffer.reserve(count, limit);
                    if (!reserved.isEmpty()) assertTrue(batch.isEmpty());
                    else {
                        List<byte[]> wanted = new ArrayList<>();
                        int size = 2;
                        for (byte[] next : expected) {
                            int added = next.length + (wanted.isEmpty() ? 0 : 1);
                            if (wanted.size() == count || size + added > limit) break;
                            wanted.add(next);
                            size += added;
                        }
                        assertEquals(wanted, batch);
                        reserved = batch;
                    }
                    break;
                case 2:
                    if (!reserved.isEmpty()) {
                        buffer.acknowledge(reserved.size());
                        for (byte[] ignored : reserved) bytes -= expected.removeFirst().length;
                        reserved = Collections.emptyList();
                    }
                    break;
                default:
                    buffer.release();
                    reserved = Collections.emptyList();
            }
            assertEquals(bytes, buffer.snapshot().get("bytes"));
            assertEquals(peak, buffer.snapshot().get("peakBytes"));
            assertEquals(new ArrayList<>(expected), buffer.peek(100));
        }
    }

    @Test
    void oversizedUtf8BinaryAndArrayValuesAreRejectedDuringEncoding() throws Exception {
        for (Object raw :
                Arrays.asList(
                        String.join("", Collections.nCopies(4000, "Ω")),
                        new ByteString(new byte[12000]),
                        new int[10000],
                        new Variant[] {new Variant(new long[10000])})) {
            assertThrows(
                    NotificationJson.RecordTooLargeException.class,
                    () -> NotificationJson.encode(TAG, new DataValue(new Variant(raw)), 1024));
        }
        byte[] encoded = NotificationJson.encode(TAG, new DataValue(new Variant("Ω")), 1024);
        assertTrue(encoded.length <= 1024);
        assertEquals("Ω", new ObjectMapper().readTree(encoded).get("value").asText());
        byte[] binary =
                NotificationJson.encode(
                        TAG,
                        new DataValue(new Variant(new ByteString(new byte[] {1, 2, 3}))),
                        1024);
        assertEquals("AQID", new ObjectMapper().readTree(binary).get("value").asText());
    }

    @Test
    void rejectsLimitsThatCouldLeaveRecordsUndrainable() {
        TestRunner runner = ProcessorTest.configured(new ProcessorTest.OfflineProcessor());
        runner.setProperty(SubscribeOpcUa.RECORD_BYTES, "1024");
        runner.setProperty(SubscribeOpcUa.BUFFER_BYTES, "1023");
        runner.assertNotValid();
        runner.setProperty(SubscribeOpcUa.BUFFER_BYTES, "1024");
        runner.setProperty(SubscribeOpcUa.BATCH_BYTES, "1025");
        runner.assertNotValid();
        runner.setProperty(SubscribeOpcUa.BATCH_BYTES, "1026");
        runner.assertValid();
    }

    @Test
    void reportsOversizedSeparatelyFromFullBuffer() throws Exception {
        ProcessorTest.OfflineProcessor p = new ProcessorTest.OfflineProcessor();
        TestRunner r = ProcessorTest.configured(p);
        r.setProperty(SubscribeOpcUa.BUFFER, "1");
        r.setProperty(SubscribeOpcUa.RECORD_BYTES, "1024");
        p.start(r.getProcessContext());
        try {
            p.acceptForTest(TAG, new DataValue(new Variant(new byte[10000])));
            p.acceptForTest(TAG, new DataValue(new Variant(1)));
            p.acceptForTest(TAG, new DataValue(new Variant(2)));
            Map<String, Object> health = p.diagnosticSnapshot(r.getProcessContext());
            assertEquals(2L, health.get("notificationsDropped"));
            assertEquals(1L, health.get("oversizedNotifications"));
            assertEquals(0L, health.get("encodingFailures"));
            r.run(1, false, false);
            r.assertTransferCount(SubscribeOpcUa.SUCCESS, 1);
            assertEquals(Long.valueOf(2), r.getCounterValue("OPC UA notifications dropped"));
        } finally {
            p.stop();
        }
    }

    @Test
    void retainedDataKeepsItsOriginalLimitsAndIdentityUntilCommit() throws Exception {
        ProcessorTest.OfflineProcessor p = new ProcessorTest.OfflineProcessor();
        TestRunner r = ProcessorTest.configured(p);
        r.setProperty(SubscribeOpcUa.RECORD_BYTES, "1200");
        r.setProperty(SubscribeOpcUa.BATCH_BYTES, "1202");
        p.start(r.getProcessContext());
        for (int i = 0; i < 2; i++)
            p.acceptForTest(
                    TAG,
                    new DataValue(new Variant(String.join("", Collections.nCopies(700, "a")))));
        ProcessSession pending = spy(r.getProcessSessionFactory().createSession());
        AtomicReference<Runnable> committed = new AtomicReference<>();
        doAnswer(
                        call -> {
                            committed.set(call.getArgument(0));
                            return null;
                        })
                .when(pending)
                .commitAsync(any(Runnable.class), any());
        p.onTrigger(r.getProcessContext(), pending);
        p.stop();
        r.setProperty(SubscribeOpcUa.RECORD_BYTES, "100");
        r.setProperty(SubscribeOpcUa.BATCH_BYTES, "102");
        r.setProperty(SubscribeOpcUa.ENDPOINT, "opc.tcp://changed:4840");
        p.start(r.getProcessContext());
        try {
            ProcessSession blocked = spy(r.getProcessSessionFactory().createSession());
            p.onTrigger(r.getProcessContext(), blocked);
            verify(blocked, never()).create();
            pending.commitAsync();
            committed.get().run();
            r.run(1, false, false);
            r.assertTransferCount(SubscribeOpcUa.SUCCESS, 2);
            r.getFlowFilesForRelationship(SubscribeOpcUa.SUCCESS)
                    .forEach(
                            f -> {
                                assertTrue(r.getContentAsByteArray(f).length <= 1202);
                                f.assertAttributeEquals(
                                        "opcua.endpoint", "opc.tcp://localhost:4840");
                            });
            r.run(1, false, false);
            Map<?, ?> buffer =
                    (Map<?, ?>) p.diagnosticSnapshot(r.getProcessContext()).get("buffer");
            assertEquals(100, buffer.get("maximumRecordBytes"));
            assertEquals(102L, buffer.get("maximumFlowFileBytes"));
        } finally {
            p.stop();
        }
    }
}
