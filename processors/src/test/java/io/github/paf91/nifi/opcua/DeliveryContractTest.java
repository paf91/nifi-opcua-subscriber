/* SPDX-License-Identifier: Apache-2.0 */
package io.github.paf91.nifi.opcua;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.apache.nifi.flowfile.FlowFile;
import org.apache.nifi.processor.ProcessSession;
import org.apache.nifi.processor.Relationship;
import org.apache.nifi.processor.io.OutputStreamCallback;
import org.apache.nifi.util.TestRunner;
import org.eclipse.milo.opcua.stack.core.types.builtin.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

class DeliveryContractTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final TagDefinition TAG =
            TagDefinition.parse("test", ProcessorTest.NODE, 100, 10);

    @ParameterizedTest
    @ValueSource(strings = {"create", "write", "attributes", "provenance", "transfer", "commit"})
    void repositoryFailuresRetainTheOriginalBatch(String stage) throws Exception {
        ProcessorTest.OfflineProcessor p = new ProcessorTest.OfflineProcessor();
        TestRunner r = ProcessorTest.configured(p);
        p.start(r.getProcessContext());
        try {
            p.acceptForTest(TAG, new DataValue(new Variant(71)));
            ProcessSession session = spy(r.getProcessSessionFactory().createSession());
            RuntimeException failure = new RuntimeException("injected " + stage + " failure");
            switch (stage) {
                case "create":
                    doThrow(failure).when(session).create();
                    break;
                case "write":
                    doThrow(failure)
                            .when(session)
                            .write(any(FlowFile.class), any(OutputStreamCallback.class));
                    break;
                case "attributes":
                    doThrow(failure).when(session).putAllAttributes(any(FlowFile.class), anyMap());
                    break;
                case "provenance":
                    doThrow(failure).when(session).getProvenanceReporter();
                    break;
                case "transfer":
                    doThrow(failure)
                            .when(session)
                            .transfer(any(FlowFile.class), any(Relationship.class));
                    break;
                case "commit":
                    doThrow(failure).when(session).commitAsync(any(Runnable.class), any());
                    break;
                default:
                    throw new AssertionError(stage);
            }
            assertThrows(RuntimeException.class, () -> p.onTrigger(r.getProcessContext(), session));
            verify(session).rollback();
            r.run(1, false, false);
            r.assertTransferCount(SubscribeOpcUa.SUCCESS, 1);
            JsonNode records =
                    JSON.readTree(
                            r.getContentAsByteArray(
                                    r.getFlowFilesForRelationship(SubscribeOpcUa.SUCCESS).get(0)));
            assertEquals(71, records.get(0).get("value").intValue());
            r.run(1, false, false);
            r.assertTransferCount(SubscribeOpcUa.SUCCESS, 1);
        } finally {
            p.stop();
        }
    }

    @Test
    void callbackReportedCommitFailureReleasesReservation() throws Exception {
        ProcessorTest.OfflineProcessor p = new ProcessorTest.OfflineProcessor();
        TestRunner r = ProcessorTest.configured(p);
        p.start(r.getProcessContext());
        try {
            p.acceptForTest(TAG, new DataValue(new Variant(19)));
            ProcessSession session = spy(r.getProcessSessionFactory().createSession());
            AtomicReference<Consumer<Throwable>> onFailure = new AtomicReference<>();
            doAnswer(
                            call -> {
                                onFailure.set(call.getArgument(1));
                                return null;
                            })
                    .when(session)
                    .commitAsync(any(Runnable.class), any());
            p.onTrigger(r.getProcessContext(), session);
            ProcessSession blocked = spy(r.getProcessSessionFactory().createSession());
            p.onTrigger(r.getProcessContext(), blocked);
            verify(blocked, never()).create();
            session.rollback();
            onFailure.get().accept(new RuntimeException("injected commit callback failure"));
            r.run(1, false, false);
            r.assertTransferCount(SubscribeOpcUa.SUCCESS, 1);
        } finally {
            p.stop();
        }
    }

    @Test
    void randomizedBufferOperationsPreserveOrderAndReservations() {
        Random random = new Random(817432L);
        NotificationBuffer buffer = new NotificationBuffer(31);
        Deque<byte[]> expected = new ArrayDeque<>();
        List<byte[]> reserved = Collections.emptyList();
        for (int step = 0; step < 50000; step++) {
            switch (random.nextInt(4)) {
                case 0:
                    byte[] record = new byte[] {(byte) step};
                    boolean fits = expected.size() < 31;
                    assertEquals(fits, buffer.offer(record));
                    if (fits) expected.addLast(record);
                    break;
                case 1:
                    int maximum = 1 + random.nextInt(50);
                    List<byte[]> actual = buffer.reserve(maximum);
                    if (!reserved.isEmpty()) assertTrue(actual.isEmpty());
                    else {
                        List<byte[]> prefix = new ArrayList<>(expected);
                        prefix = prefix.subList(0, Math.min(maximum, prefix.size()));
                        assertEquals(prefix, actual);
                        reserved = actual;
                    }
                    break;
                case 2:
                    if (!reserved.isEmpty()) {
                        buffer.acknowledge(reserved.size());
                        for (byte[] ignored : reserved) expected.removeFirst();
                        reserved = Collections.emptyList();
                    }
                    break;
                case 3:
                    buffer.release();
                    reserved = Collections.emptyList();
                    break;
            }
            assertEquals(expected.size(), buffer.size());
            assertEquals(new ArrayList<>(expected), buffer.peek(100));
        }
    }

    @Test
    void preservesArrayIntegersBytesAndNonFiniteValues() throws Exception {
        assertEquals(
                "[\"-9223372036854775808\",\"9223372036854775807\"]",
                JSON.writeValueAsString(
                        NotificationJson.normalize(new long[] {Long.MIN_VALUE, Long.MAX_VALUE})));
        assertEquals(
                "[[1,2],[3,4]]",
                JSON.writeValueAsString(NotificationJson.normalize(new int[][] {{1, 2}, {3, 4}})));
        assertEquals(
                "[\"NaN\",\"Infinity\",\"-Infinity\"]",
                JSON.writeValueAsString(
                        NotificationJson.normalize(
                                new double[] {
                                    Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY
                                })));
        assertEquals("AQID", NotificationJson.normalize(new ByteString(new byte[] {1, 2, 3})));
        assertEquals("", NotificationJson.normalize(new ByteString(new byte[0])));
        assertNull(NotificationJson.normalize(new ByteString(null)));
    }

    @Test
    void preservesUncertainAndOverflowStatusBits() throws Exception {
        JsonNode uncertain =
                JSON.readTree(
                        NotificationJson.encode(
                                TAG,
                                new DataValue(
                                        new Variant(1), new StatusCode(0x40000000L), null, null)));
        assertEquals("Uncertain", uncertain.get("quality").textValue());
        JsonNode overflow =
                JSON.readTree(
                        NotificationJson.encode(
                                TAG,
                                new DataValue(
                                        new Variant(2), new StatusCode(0x00000480L), null, null)));
        assertEquals("0x00000480", overflow.get("statusCode").textValue());
        assertEquals("Good", overflow.get("quality").textValue());
        assertTrue(overflow.get("sourceTimestamp").isNull());
    }

    @Test
    void cancellationStopsPendingWaitAndFencesCallbacks() throws Exception {
        ConnectionAttempt attempt = new ConnectionAttempt();
        CompletableFuture<Object> response = new CompletableFuture<>();
        CountDownLatch entered = new CountDownLatch(1);
        ExecutorService worker = Executors.newSingleThreadExecutor();
        try {
            Future<?> waiting =
                    worker.submit(
                            () -> {
                                entered.countDown();
                                assertThrows(
                                        CancellationException.class,
                                        () -> attempt.await(response, 300000));
                            });
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            attempt.cancel();
            waiting.get(5, TimeUnit.SECONDS);
            assertTrue(response.isCancelled());
            attempt.ifActive(() -> fail("Cancelled generation accepted a callback"));
        } finally {
            worker.shutdownNow();
        }
    }
}
