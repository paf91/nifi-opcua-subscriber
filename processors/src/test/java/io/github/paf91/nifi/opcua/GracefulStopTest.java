/* SPDX-License-Identifier: Apache-2.0 */
package io.github.paf91.nifi.opcua;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.apache.nifi.flowfile.FlowFile;
import org.apache.nifi.processor.*;
import org.apache.nifi.processor.exception.ProcessException;
import org.apache.nifi.processor.io.OutputStreamCallback;
import org.apache.nifi.util.*;
import org.eclipse.milo.opcua.stack.core.types.builtin.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.*;

@Timeout(20)
class GracefulStopTest {
    private static final class Fixture implements AutoCloseable {
        final LifecycleTest.Tracking processor = new LifecycleTest.Tracking();
        final TestRunner runner = ProcessorTest.configured(processor);
        final AtomicInteger created = new AtomicInteger();
        volatile UnaryOperator<ProcessSession> decorate = UnaryOperator.identity();
        final ProcessSessionFactory factory =
                () -> {
                    created.incrementAndGet();
                    return decorate.apply(runner.getProcessSessionFactory().createSession());
                };

        Fixture() throws Exception {
            runner.setProperty(SubscribeOpcUa.STOP_DRAIN_TIMEOUT, "1000");
            runner.setProperty(SubscribeOpcUa.BATCH, "2");
            processor.start(runner.getProcessContext());
            trigger();
            created.set(0);
        }

        void trigger() {
            processor.onTrigger(runner.getProcessContext(), factory);
        }

        void add(int count) {
            for (int i = 0; i < count; i++)
                processor.acceptForTest(MemoryBoundsTest.TAG, new DataValue(new Variant(i)));
        }

        Map<?, ?> buffer() {
            return (Map<?, ?>)
                    processor.diagnosticSnapshot(runner.getProcessContext()).get("buffer");
        }

        int remaining() {
            return (Integer) buffer().get("records");
        }

        long workers() {
            return Thread.getAllStackTraces().keySet().stream()
                    .filter(
                            t ->
                                    t.isAlive()
                                            && t.getName()
                                                    .equals(
                                                            "opcua-stop-drain-"
                                                                    + processor.getIdentifier()))
                    .count();
        }

        void assertValues(int count) throws Exception {
            List<Integer> values = new ArrayList<>();
            ObjectMapper json = new ObjectMapper();
            for (MockFlowFile flow : runner.getFlowFilesForRelationship(SubscribeOpcUa.SUCCESS)) {
                for (com.fasterxml.jackson.databind.JsonNode item :
                        json.readTree(runner.getContentAsByteArray(flow)))
                    values.add(item.get("value").intValue());
                flow.assertAttributeEquals("opcua.endpoint", "opc.tcp://localhost:4840");
                flow.assertAttributeEquals("opcua.security.policy", "Basic256Sha256");
            }
            List<Integer> expected = new ArrayList<>();
            for (int i = 0; i < count; i++) expected.add(i);
            assertEquals(expected, values);
        }

        @Override
        public void close() throws Exception {
            runner.setProperty(SubscribeOpcUa.STOP_DRAIN_TIMEOUT, "0");
            processor.stop();
            await(() -> workers() == 0);
        }
    }

    private static void await(BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) Thread.sleep(5);
        assertTrue(condition.getAsBoolean());
    }

    @Test
    void closesIngressThenDrainsAllBatchesWithoutWarning() throws Exception {
        try (Fixture f = new Fixture()) {
            f.add(5);
            f.decorate =
                    session -> {
                        assertEquals(
                                1,
                                f.processor.closed,
                                "The subscription must close before stop-time I/O");
                        assertTrue(f.processor.generation.isCancelled());
                        return session;
                    };
            f.processor.stop();
            assertEquals(0, f.remaining());
            f.runner.assertTransferCount(SubscribeOpcUa.SUCCESS, 3);
            f.assertValues(5);
            assertEquals(3, f.runner.getProvenanceEvents().size());
            assertTrue(f.runner.getLogger().getWarnMessages().isEmpty());
            await(() -> f.workers() == 0);
            f.processor.stop();
            assertEquals(3, f.created.get());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"disabled", "backpressure", "revoked", "disconnected"})
    void unavailableDrainRetainsDataWithoutOpeningAWriteSession(String reason) throws Exception {
        try (Fixture f = new Fixture()) {
            f.add(1);
            if (reason.equals("disabled"))
                f.runner.setProperty(SubscribeOpcUa.STOP_DRAIN_TIMEOUT, "0");
            if (reason.equals("backpressure"))
                f.runner.setRelationshipUnavailable(SubscribeOpcUa.SUCCESS);
            if (reason.equals("revoked") || reason.equals("disconnected")) {
                f.runner.setIsConfiguredForClustering(true);
                f.runner.setClustered(true);
                f.runner.setConnected(!reason.equals("disconnected"));
                ((MockProcessContext) f.runner.getProcessContext())
                        .setPrimaryNode(reason.equals("disconnected"));
            }
            f.processor.stop();
            assertEquals(1, f.remaining());
            assertEquals(0, f.created.get());
            assertEquals(1, f.runner.getLogger().getWarnMessages().size());
            assertEquals(1, f.processor.closed);
        }
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "factory",
                "create",
                "write",
                "attributes",
                "provenance",
                "transfer",
                "commit",
                "callback",
                "cancelled"
            })
    void failuresStopWithoutRetryingAndRetainRecordsForStart(String stage) throws Exception {
        try (Fixture f = new Fixture()) {
            f.add(2);
            f.decorate =
                    real -> {
                        RuntimeException error = new IllegalStateException("injected failure");
                        if (stage.equals("factory")) throw error;
                        ProcessSession session = spy(real);
                        switch (stage) {
                            case "create":
                                doThrow(error).when(session).create();
                                break;
                            case "write":
                                doThrow(error)
                                        .when(session)
                                        .write(
                                                any(FlowFile.class),
                                                any(OutputStreamCallback.class));
                                break;
                            case "attributes":
                                doThrow(error).when(session).putAllAttributes(any(), anyMap());
                                break;
                            case "provenance":
                                doThrow(error).when(session).getProvenanceReporter();
                                break;
                            case "transfer":
                                doThrow(error)
                                        .when(session)
                                        .transfer(any(FlowFile.class), any(Relationship.class));
                                break;
                            case "commit":
                                doThrow(error)
                                        .when(session)
                                        .commitAsync(any(Runnable.class), any());
                                break;
                            case "cancelled":
                                doThrow(
                                                new CancellationException(
                                                        "repository-secret-must-not-be-logged"))
                                        .when(session)
                                        .create();
                                break;
                            case "callback":
                                doAnswer(
                                                call -> {
                                                    session.rollback();
                                                    ((Consumer<Throwable>) call.getArgument(1))
                                                            .accept(error);
                                                    return null;
                                                })
                                        .when(session)
                                        .commitAsync(any(Runnable.class), any());
                                break;
                            default:
                                throw new AssertionError(stage);
                        }
                        return session;
                    };
            f.processor.stop();
            assertEquals(2, f.remaining());
            assertEquals(false, f.buffer().get("reserved"));
            assertEquals(
                    1, f.created.get(), "No repeated writes to a failing repository during Stop");
            assertEquals(1, f.runner.getLogger().getWarnMessages().size());
            assertTrue(
                    f.runner.getLogger().getWarnMessages().stream()
                            .noneMatch(
                                    message ->
                                            message.getMsg()
                                                    .contains(
                                                            "repository-secret-must-not-be-logged")));
            f.runner.assertTransferCount(SubscribeOpcUa.SUCCESS, 0);
            f.decorate = UnaryOperator.identity();
            f.processor.start(f.runner.getProcessContext());
            f.trigger();
            assertEquals(1, f.processor.opened, "Retained records must drain without reconnecting");
            f.assertValues(2);
        }
    }

    @Test
    void blockedWriteCannotHoldStopOrCreateWorkersOnRepeatedStopStart() throws Exception {
        try (Fixture f = new Fixture()) {
            f.runner.setProperty(SubscribeOpcUa.STOP_DRAIN_TIMEOUT, "100");
            f.add(2);
            CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
            AtomicReference<ProcessSession> stuck = new AtomicReference<>();
            f.decorate =
                    real -> {
                        ProcessSession session = spy(real);
                        stuck.set(session);
                        doAnswer(
                                        call -> {
                                            entered.countDown();
                                            boolean done = false;
                                            while (!done) {
                                                try {
                                                    done = release.await(5, TimeUnit.SECONDS);
                                                } catch (InterruptedException ignored) {
                                                    /* Simulate uninterruptible disk I/O. */
                                                }
                                            }
                                            return call.callRealMethod();
                                        })
                                .when(session)
                                .write(any(FlowFile.class), any(OutputStreamCallback.class));
                        return session;
                    };
            ExecutorService caller = Executors.newSingleThreadExecutor();
            try {
                Future<?> stopped = caller.submit(f.processor::stop);
                assertTrue(entered.await(3, TimeUnit.SECONDS));
                stopped.get(2, TimeUnit.SECONDS);
                assertEquals(1, f.processor.closed);
                assertEquals(1, f.workers());
                assertEquals(true, f.buffer().get("reserved"));
                f.decorate = UnaryOperator.identity();
                for (int i = 0; i < 4; i++) {
                    f.processor.start(f.runner.getProcessContext());
                    f.trigger();
                    caller.submit(f.processor::stop).get(2, TimeUnit.SECONDS);
                    assertEquals(1, f.workers());
                    assertEquals(2, f.remaining());
                }
                release.countDown();
                await(() -> f.workers() == 0);
                verify(stuck.get(), never()).commitAsync(any(Runnable.class), any());
                verify(stuck.get()).rollback();
                assertEquals(false, f.buffer().get("reserved"));
                f.processor.start(f.runner.getProcessContext());
                f.trigger();
                f.assertValues(2);
            } finally {
                release.countDown();
                caller.shutdownNow();
                assertTrue(caller.awaitTermination(3, TimeUnit.SECONDS));
            }
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void timedOutCommitKeepsItsReservationUntilTheActualOutcome(boolean succeeds) throws Exception {
        try (Fixture f = new Fixture()) {
            f.runner.setProperty(SubscribeOpcUa.STOP_DRAIN_TIMEOUT, "100");
            f.runner.setProperty(SubscribeOpcUa.BATCH, "1");
            f.add(2);
            AtomicReference<ProcessSession> pending = new AtomicReference<>();
            AtomicReference<Runnable> success = new AtomicReference<>();
            AtomicReference<Consumer<Throwable>> failure = new AtomicReference<>();
            f.decorate =
                    real -> {
                        ProcessSession session = spy(real);
                        pending.set(session);
                        doAnswer(
                                        call -> {
                                            success.set(call.getArgument(0));
                                            failure.set(call.getArgument(1));
                                            return null;
                                        })
                                .when(session)
                                .commitAsync(any(Runnable.class), any());
                        return session;
                    };
            f.processor.stop();
            await(() -> f.workers() == 0);
            assertNotNull(success.get());
            assertEquals(2, f.remaining());
            assertEquals(true, f.buffer().get("reserved"));
            f.decorate = UnaryOperator.identity();
            f.runner.setProperty(SubscribeOpcUa.ENDPOINT, "opc.tcp://changed:4840");
            f.processor.start(f.runner.getProcessContext());
            f.trigger();
            assertEquals(2, f.remaining(), "Start cannot duplicate an unresolved stop-time commit");
            if (succeeds) {
                pending.get().commitAsync();
                success.get().run();
            } else {
                pending.get().rollback();
                failure.get().accept(new RuntimeException("late failure"));
                f.trigger();
            }
            f.trigger();
            assertEquals(0, f.remaining());
            assertEquals(1, f.processor.opened);
            f.assertValues(2);
        }
    }

    @Test
    void existingCommitCompletesBeforeTheStopDrainClaimsAnotherBatch() throws Exception {
        try (Fixture f = new Fixture()) {
            f.runner.setProperty(SubscribeOpcUa.BATCH, "1");
            f.add(2);
            ProcessSession pending = spy(f.runner.getProcessSessionFactory().createSession());
            AtomicReference<Runnable> committed = new AtomicReference<>();
            doAnswer(
                            call -> {
                                committed.set(call.getArgument(0));
                                return null;
                            })
                    .when(pending)
                    .commitAsync(any(Runnable.class), any());
            f.processor.onTrigger(f.runner.getProcessContext(), pending);
            ExecutorService caller = Executors.newSingleThreadExecutor();
            try {
                Future<?> stopped = caller.submit(f.processor::stop);
                await(() -> f.workers() == 1);
                assertEquals(0, f.created.get());
                pending.commitAsync();
                committed.get().run();
                stopped.get(2, TimeUnit.SECONDS);
                f.assertValues(2);
                assertEquals(0, f.remaining());
                assertTrue(f.runner.getLogger().getWarnMessages().isEmpty());
            } finally {
                caller.shutdownNow();
                assertTrue(caller.awaitTermination(3, TimeUnit.SECONDS));
            }
        }
    }

    @Test
    void emptyOrNeverTriggeredProcessorStopsWithoutCreatingDrainWorkers() throws Exception {
        SubscribeOpcUa processor = new ProcessorTest.OfflineProcessor();
        TestRunner runner = ProcessorTest.configured(processor);
        processor.stop();
        processor.start(runner.getProcessContext());
        processor.stop();
        assertTrue(runner.getLogger().getWarnMessages().isEmpty());
        processor.start(runner.getProcessContext());
        processor.acceptForTest(MemoryBoundsTest.TAG, new DataValue(new Variant(1)));
        processor.stop();
        assertEquals(1, runner.getLogger().getWarnMessages().size());
        runner.assertTransferCount(SubscribeOpcUa.SUCCESS, 0);
    }

    @Test
    void drainTimeoutIsValidated() {
        TestRunner runner = ProcessorTest.configured(new ProcessorTest.OfflineProcessor());
        for (String value : Arrays.asList("-1", "30001", "invalid")) {
            runner.setProperty(SubscribeOpcUa.STOP_DRAIN_TIMEOUT, value);
            runner.assertNotValid();
        }
        for (String value : Arrays.asList("0", "100", "30000")) {
            runner.setProperty(SubscribeOpcUa.STOP_DRAIN_TIMEOUT, value);
            runner.assertValid();
        }
    }

    @Test
    void backpressureIsRecheckedBetweenBatches() throws Exception {
        try (Fixture f = new Fixture()) {
            f.add(5);
            f.decorate =
                    real -> {
                        ProcessSession session = spy(real);
                        doAnswer(
                                        call -> {
                                            Object result = call.callRealMethod();
                                            f.runner.setRelationshipUnavailable(
                                                    SubscribeOpcUa.SUCCESS);
                                            return result;
                                        })
                                .when(session)
                                .commitAsync(any(Runnable.class), any());
                        return session;
                    };
            f.processor.stop();
            assertEquals(3, f.remaining());
            assertEquals(1, f.created.get());
            f.runner.assertTransferCount(SubscribeOpcUa.SUCCESS, 1);
            assertEquals(1, f.runner.getLogger().getWarnMessages().size());
        }
    }

    @Test
    void stopDrainHonorsTheFlowFileByteLimit() throws Exception {
        try (Fixture f = new Fixture()) {
            f.processor.stop();
            f.runner.setProperty(SubscribeOpcUa.RECORD_BYTES, "1024");
            f.runner.setProperty(SubscribeOpcUa.BATCH_BYTES, "1026");
            f.runner.setProperty(SubscribeOpcUa.BATCH, "100");
            f.processor.start(f.runner.getProcessContext());
            f.trigger();
            for (int i = 0; i < 3; i++)
                f.processor.acceptForTest(
                        MemoryBoundsTest.TAG,
                        new DataValue(new Variant(String.join("", Collections.nCopies(600, "a")))));
            f.processor.stop();
            f.runner.assertTransferCount(SubscribeOpcUa.SUCCESS, 3);
            for (MockFlowFile flow : f.runner.getFlowFilesForRelationship(SubscribeOpcUa.SUCCESS)) {
                assertTrue(f.runner.getContentAsByteArray(flow).length <= 1026);
                flow.assertAttributeEquals("record.count", "1");
            }
            assertEquals(0, f.remaining());
            assertTrue(f.runner.getLogger().getWarnMessages().isEmpty());
        }
    }

    @Test
    void lossOfPrimaryDuringWritePreventsANewCommit() throws Exception {
        try (Fixture f = new Fixture()) {
            f.runner.setIsConfiguredForClustering(true);
            f.runner.setClustered(true);
            f.runner.setConnected(true);
            ((MockProcessContext) f.runner.getProcessContext()).setPrimaryNode(true);
            f.add(1);
            f.decorate =
                    real -> {
                        ProcessSession session = spy(real);
                        doAnswer(
                                        call -> {
                                            ((MockProcessContext) f.runner.getProcessContext())
                                                    .setPrimaryNode(false);
                                            return call.callRealMethod();
                                        })
                                .when(session)
                                .write(any(FlowFile.class), any(OutputStreamCallback.class));
                        return session;
                    };
            f.processor.stop();
            assertEquals(1, f.remaining());
            assertEquals(false, f.buffer().get("reserved"));
            f.runner.assertTransferCount(SubscribeOpcUa.SUCCESS, 0);
        }
    }

    @Test
    void blockedRollbackCannotHoldStopOrReleaseItsReservationEarly() throws Exception {
        try (Fixture f = new Fixture()) {
            f.runner.setProperty(SubscribeOpcUa.STOP_DRAIN_TIMEOUT, "100");
            f.add(1);
            CountDownLatch rollback = new CountDownLatch(1), release = new CountDownLatch(1);
            f.decorate =
                    real -> {
                        ProcessSession session = spy(real);
                        doThrow(new ProcessException("write failed"))
                                .when(session)
                                .write(any(FlowFile.class), any(OutputStreamCallback.class));
                        doAnswer(
                                        call -> {
                                            rollback.countDown();
                                            while (release.getCount() != 0) {
                                                try {
                                                    release.await(1, TimeUnit.SECONDS);
                                                } catch (InterruptedException ignored) {
                                                    /* Uninterruptible cleanup. */
                                                }
                                            }
                                            return call.callRealMethod();
                                        })
                                .when(session)
                                .rollback();
                        return session;
                    };
            ExecutorService caller = Executors.newSingleThreadExecutor();
            try {
                Future<?> stopped = caller.submit(f.processor::stop);
                assertTrue(rollback.await(3, TimeUnit.SECONDS));
                stopped.get(2, TimeUnit.SECONDS);
                assertEquals(true, f.buffer().get("reserved"));
                assertEquals(1, f.processor.closed);
                release.countDown();
                await(() -> f.workers() == 0);
                assertEquals(false, f.buffer().get("reserved"));
                f.decorate = UnaryOperator.identity();
                f.processor.start(f.runner.getProcessContext());
                f.trigger();
                f.assertValues(1);
            } finally {
                release.countDown();
                caller.shutdownNow();
                assertTrue(caller.awaitTermination(3, TimeUnit.SECONDS));
            }
        }
    }

    @Test
    void interruptionEndsStopWaitAndPreservesTheInterruptFlag() throws Exception {
        try (Fixture f = new Fixture()) {
            f.runner.setProperty(SubscribeOpcUa.STOP_DRAIN_TIMEOUT, "5000");
            f.add(1);
            CountDownLatch commit = new CountDownLatch(1);
            AtomicReference<ProcessSession> pending = new AtomicReference<>();
            AtomicReference<Consumer<Throwable>> failed = new AtomicReference<>();
            AtomicBoolean interrupted = new AtomicBoolean();
            f.decorate =
                    real -> {
                        ProcessSession session = spy(real);
                        pending.set(session);
                        doAnswer(
                                        call -> {
                                            failed.set(call.getArgument(1));
                                            commit.countDown();
                                            return null;
                                        })
                                .when(session)
                                .commitAsync(any(Runnable.class), any());
                        return session;
                    };
            Thread caller =
                    new Thread(
                            () -> {
                                f.processor.stop();
                                interrupted.set(Thread.currentThread().isInterrupted());
                            });
            try {
                caller.start();
                assertTrue(commit.await(3, TimeUnit.SECONDS));
                caller.interrupt();
                caller.join(2000);
                assertFalse(caller.isAlive());
                assertTrue(interrupted.get());
                assertEquals(true, f.buffer().get("reserved"));
            } finally {
                caller.interrupt();
                caller.join(2000);
                if (pending.get() != null && failed.get() != null) {
                    pending.get().rollback();
                    failed.get().accept(new ProcessException("late failure"));
                }
            }
        }
    }

    @Test
    void aBlockedCommitCallCanFinishLaterWithoutDuplicatingTheBatch() throws Exception {
        try (Fixture f = new Fixture()) {
            f.runner.setProperty(SubscribeOpcUa.STOP_DRAIN_TIMEOUT, "100");
            f.add(1);
            CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
            f.decorate =
                    real -> {
                        ProcessSession session = spy(real);
                        doAnswer(
                                        call -> {
                                            entered.countDown();
                                            while (release.getCount() != 0) {
                                                try {
                                                    release.await(1, TimeUnit.SECONDS);
                                                } catch (InterruptedException ignored) {
                                                    /* Commit has already started. */
                                                }
                                            }
                                            return call.callRealMethod();
                                        })
                                .when(session)
                                .commitAsync(any(Runnable.class), any());
                        return session;
                    };
            ExecutorService caller = Executors.newSingleThreadExecutor();
            try {
                Future<?> stopped = caller.submit(f.processor::stop);
                assertTrue(entered.await(3, TimeUnit.SECONDS));
                stopped.get(2, TimeUnit.SECONDS);
                assertEquals(true, f.buffer().get("reserved"));
                f.decorate = UnaryOperator.identity();
                f.processor.start(f.runner.getProcessContext());
                f.trigger();
                assertEquals(1, f.remaining());
                release.countDown();
                await(() -> f.workers() == 0);
                assertEquals(0, f.remaining());
                f.assertValues(1);
                f.trigger();
                f.runner.assertTransferCount(SubscribeOpcUa.SUCCESS, 1);
            } finally {
                release.countDown();
                caller.shutdownNow();
                assertTrue(caller.awaitTermination(3, TimeUnit.SECONDS));
            }
        }
    }
}
