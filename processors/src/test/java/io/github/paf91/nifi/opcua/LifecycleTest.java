/* SPDX-License-Identifier: Apache-2.0 */
package io.github.paf91.nifi.opcua;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import org.apache.nifi.annotation.notification.PrimaryNodeState;
import org.apache.nifi.processor.*;
import org.apache.nifi.util.*;
import org.eclipse.milo.opcua.stack.core.types.builtin.*;
import org.junit.jupiter.api.Test;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

class LifecycleTest {
    private static void setPrimary(TestRunner runner, boolean primary) {
        // Set live state without TestRunner's synchronous notification; these tests
        // explicitly control notification order, as NiFi delivers events asynchronously.
        ((MockProcessContext) runner.getProcessContext()).setPrimaryNode(primary);
    }

    static class Tracking extends SubscribeOpcUa {
        int opened, closed;
        ConnectionAttempt generation;

        @Override
        protected AutoCloseable openConnection(ProcessContext context, ConnectionAttempt attempt) {
            generation = attempt;
            opened++;
            return () -> closed++;
        }

        void value(ConnectionAttempt source, int value) {
            source.ifActive(
                    () ->
                            acceptForTest(
                                    TagDefinition.parse("tag", ProcessorTest.NODE, 100, 10),
                                    new DataValue(new Variant(value))));
        }
    }

    @Test
    void schedulingNeverConnectsAndOnlyPrimaryCanOpen() throws Exception {
        Tracking first = new Tracking(), second = new Tracking();
        TestRunner a = ProcessorTest.configured(first), b = ProcessorTest.configured(second);
        a.setConnected(true);
        a.setClustered(true);
        a.setIsConfiguredForClustering(true);
        setPrimary(a, true);
        b.setConnected(true);
        b.setClustered(true);
        b.setIsConfiguredForClustering(true);
        setPrimary(b, false);
        first.start(a.getProcessContext());
        second.start(b.getProcessContext());
        assertEquals(0, first.opened);
        assertEquals(0, second.opened);
        first.onTrigger(a.getProcessContext(), a.getProcessSessionFactory().createSession());
        second.onTrigger(b.getProcessContext(), b.getProcessSessionFactory().createSession());
        assertEquals(1, first.opened);
        assertEquals(0, second.opened);
        ConnectionAttempt old = first.generation;
        setPrimary(a, false);
        first.primaryChanged(PrimaryNodeState.PRIMARY_NODE_REVOKED);
        assertEquals(1, first.closed);
        assertTrue(old.isCancelled());
        // Notifications from the old lease are fenced, including after a later re-election.
        first.value(old, 999);
        setPrimary(b, true);
        second.primaryChanged(PrimaryNodeState.ELECTED_PRIMARY_NODE);
        second.onTrigger(b.getProcessContext(), b.getProcessSessionFactory().createSession());
        assertEquals(1, second.opened);
        setPrimary(b, false);
        second.primaryChanged(PrimaryNodeState.PRIMARY_NODE_REVOKED);
        setPrimary(a, true);
        first.primaryChanged(PrimaryNodeState.ELECTED_PRIMARY_NODE);
        first.onTrigger(a.getProcessContext(), a.getProcessSessionFactory().createSession());
        assertEquals(2, first.opened);
        a.assertTransferCount(SubscribeOpcUa.SUCCESS, 0);
        first.stop();
        second.stop();
    }

    @Test
    void delayedRevocationCannotDisableReelectedPrimary() throws Exception {
        Tracking p = new Tracking();
        TestRunner r = ProcessorTest.configured(p);
        r.setIsConfiguredForClustering(true);
        r.setClustered(true);
        r.setConnected(true);
        setPrimary(r, true);
        p.start(r.getProcessContext());
        try {
            p.onTrigger(r.getProcessContext(), r.getProcessSessionFactory().createSession());
            ConnectionAttempt first = p.generation;
            setPrimary(r, false);
            p.primaryChanged(PrimaryNodeState.PRIMARY_NODE_REVOKED);
            assertTrue(first.isCancelled());
            assertEquals(1, p.closed);

            setPrimary(r, true);
            p.primaryChanged(PrimaryNodeState.ELECTED_PRIMARY_NODE);
            p.onTrigger(r.getProcessContext(), r.getProcessSessionFactory().createSession());
            ConnectionAttempt reelected = p.generation;
            assertEquals(2, p.opened);

            // An older revocation can arrive after the election and the new connection.
            p.primaryChanged(PrimaryNodeState.PRIMARY_NODE_REVOKED);
            assertFalse(reelected.isCancelled());
            assertEquals(1, p.closed);
            p.value(reelected, 42);
            r.run(1, false, false);
            r.assertTransferCount(SubscribeOpcUa.SUCCESS, 1);
            assertEquals(2, p.opened, "A stale event must not force a new subscription");

            p.stop();
            p.start(r.getProcessContext());
            p.onTrigger(r.getProcessContext(), r.getProcessSessionFactory().createSession());
            assertEquals(
                    3, p.opened, "Start must use current ownership without another election event");
        } finally {
            p.stop();
        }
    }

    @Test
    void delayedElectionCannotKeepAnUnownedConnection() throws Exception {
        for (boolean disconnected : Arrays.asList(false, true)) {
            Tracking p = new Tracking();
            TestRunner r = ProcessorTest.configured(p);
            r.setIsConfiguredForClustering(true);
            r.setClustered(true);
            r.setConnected(true);
            setPrimary(r, true);
            p.start(r.getProcessContext());
            try {
                p.onTrigger(r.getProcessContext(), r.getProcessSessionFactory().createSession());
                ConnectionAttempt old = p.generation;
                if (disconnected) r.setConnected(false);
                else setPrimary(r, false);

                // The delivered event describes an election that has already been superseded.
                p.primaryChanged(PrimaryNodeState.ELECTED_PRIMARY_NODE);
                assertTrue(old.isCancelled());
                assertEquals(1, p.closed);
                p.value(old, 999);
                p.onTrigger(r.getProcessContext(), r.getProcessSessionFactory().createSession());
                assertEquals(1, p.opened);
                r.assertTransferCount(SubscribeOpcUa.SUCCESS, 0);
            } finally {
                p.stop();
            }
        }
    }

    @Test
    void retainedBufferDrainsBeforeReconnecting() throws Exception {
        Tracking p = new Tracking();
        TestRunner r = ProcessorTest.configured(p);
        p.start(r.getProcessContext());
        p.onTrigger(r.getProcessContext(), r.getProcessSessionFactory().createSession());
        p.value(p.generation, 42);
        p.stop();
        p.start(r.getProcessContext());
        r.run(1, false, false);
        r.assertTransferCount(SubscribeOpcUa.SUCCESS, 1);
        assertEquals(1, p.opened, "Drain must not require a new connection");
        r.run(1, true, false);
        assertEquals(2, p.opened);
    }

    @Test
    void changedConfigCannotRelabelBufferedValues() throws Exception {
        Tracking p = new Tracking();
        TestRunner r = ProcessorTest.configured(p);
        p.start(r.getProcessContext());
        p.acceptForTest(
                TagDefinition.parse("tag", ProcessorTest.NODE, 100, 10),
                new DataValue(new Variant(1)));
        p.stop();
        r.setProperty(SubscribeOpcUa.ENDPOINT, "opc.tcp://different:4840");
        p.start(r.getProcessContext());
        r.run(1, false, false);
        r.getFlowFilesForRelationship(SubscribeOpcUa.SUCCESS)
                .get(0)
                .assertAttributeEquals("opcua.endpoint", "opc.tcp://localhost:4840");
        assertEquals(0, p.opened);
        r.run(1, false, false);
        p.value(p.generation, 2);
        r.run(1, true, false);
        r.getFlowFilesForRelationship(SubscribeOpcUa.SUCCESS)
                .get(1)
                .assertAttributeEquals("opcua.endpoint", "opc.tcp://different:4840");
    }

    @Test
    void pendingCommitSurvivesStopStartWithoutDuplicateReservation() throws Exception {
        Tracking p = new Tracking();
        TestRunner r = ProcessorTest.configured(p);
        p.start(r.getProcessContext());
        p.acceptForTest(
                TagDefinition.parse("tag", ProcessorTest.NODE, 100, 10),
                new DataValue(new Variant(7)));
        ProcessSession session = spy(r.getProcessSessionFactory().createSession());
        AtomicReference<Runnable> committed = new AtomicReference<>();
        doAnswer(
                        call -> {
                            committed.set(call.getArgument(0));
                            return null;
                        })
                .when(session)
                .commitAsync(any(Runnable.class), any());
        p.onTrigger(r.getProcessContext(), session);
        assertNotNull(committed.get());
        p.stop();
        p.start(r.getProcessContext());
        ProcessSession other = spy(r.getProcessSessionFactory().createSession());
        p.onTrigger(r.getProcessContext(), other);
        verify(other, never()).create();
        committed.get().run();
        p.stop();
    }

    @Test
    void stopAndRevocationCancelAnOpeningConnection() throws Exception {
        for (boolean revoke : Arrays.asList(false, true)) {
            CountDownLatch entered = new CountDownLatch(1);
            SubscribeOpcUa p =
                    new SubscribeOpcUa() {
                        @Override
                        protected AutoCloseable openConnection(
                                ProcessContext context, ConnectionAttempt attempt)
                                throws Exception {
                            entered.countDown();
                            attempt.await(new CompletableFuture<>(), 300000);
                            return () -> {};
                        }
                    };
            TestRunner r = ProcessorTest.configured(p);
            if (revoke) {
                r.setIsConfiguredForClustering(true);
                r.setClustered(true);
                r.setConnected(true);
                setPrimary(r, true);
            }
            p.start(r.getProcessContext());
            ExecutorService worker = Executors.newFixedThreadPool(2);
            try {
                Future<?> trigger =
                        worker.submit(
                                () ->
                                        p.onTrigger(
                                                r.getProcessContext(),
                                                r.getProcessSessionFactory().createSession()));
                assertTrue(entered.await(2, TimeUnit.SECONDS));
                if (revoke) setPrimary(r, false);
                long start = System.nanoTime();
                Future<?> cancellation =
                        worker.submit(
                                () -> {
                                    if (revoke)
                                        p.primaryChanged(PrimaryNodeState.PRIMARY_NODE_REVOKED);
                                    else p.unscheduled();
                                });
                cancellation.get(2, TimeUnit.SECONDS);
                trigger.get(2, TimeUnit.SECONDS);
                assertTrue(System.nanoTime() - start < TimeUnit.SECONDS.toNanos(2));
                p.stop();
            } finally {
                worker.shutdownNow();
            }
        }
    }

    @Test
    void cancellationFencesCallbacksAndBoundsWait() throws Exception {
        ConnectionAttempt a = new ConnectionAttempt();
        AtomicInteger n = new AtomicInteger();
        a.ifActive(n::incrementAndGet);
        a.cancel();
        a.ifActive(n::incrementAndGet);
        assertEquals(1, n.get());
        assertThrows(CancellationException.class, () -> a.await(new CompletableFuture<>(), 10000));
        ConnectionAttempt b = new ConnectionAttempt();
        assertThrows(TimeoutException.class, () -> b.await(new CompletableFuture<>(), 50));
    }

    @Test
    void cancellationInterruptDoesNotLeakIntoWorkerReuse() throws Exception {
        ConnectionAttempt attempt = new ConnectionAttempt();
        CountDownLatch entered = new CountDownLatch(1);
        ExecutorService worker = Executors.newSingleThreadExecutor();
        try {
            Future<?> call =
                    worker.submit(
                            () -> {
                                assertThrows(
                                        RuntimeException.class,
                                        () ->
                                                attempt.blocking(
                                                        () -> {
                                                            entered.countDown();
                                                            try {
                                                                new CountDownLatch(1).await();
                                                            } catch (InterruptedException e) {
                                                                Thread.currentThread().interrupt();
                                                                throw new CompletionException(e);
                                                            }
                                                        }));
                                assertFalse(Thread.currentThread().isInterrupted());
                            });
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            attempt.cancel();
            call.get(2, TimeUnit.SECONDS);
        } finally {
            worker.shutdownNow();
        }
    }
}
