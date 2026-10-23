/* SPDX-License-Identifier: Apache-2.0 */
package io.github.paf91.nifi.opcua;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import org.apache.nifi.annotation.notification.PrimaryNodeState;
import org.apache.nifi.processor.*;
import org.apache.nifi.util.*;
import org.eclipse.milo.opcua.stack.core.types.builtin.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.Arrays;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

@Timeout(20)
class CancellationIsolationTest {
    @Test
    void stopAndRevocationCloseTransportWhileRepositoryWriteIsStalled() throws Exception {
        for (boolean revoke : Arrays.asList(false, true)) {
            AtomicInteger closed = new AtomicInteger();
            AtomicReference<ConnectionAttempt> attempt = new AtomicReference<>();
            SubscribeOpcUa processor =
                    new SubscribeOpcUa() {
                        @Override
                        protected AutoCloseable openConnection(
                                ProcessContext context, ConnectionAttempt generation) {
                            attempt.set(generation);
                            return closed::incrementAndGet;
                        }
                    };
            TestRunner runner = ProcessorTest.configured(processor);
            if (revoke) {
                runner.setIsConfiguredForClustering(true);
                runner.setClustered(true);
                runner.setConnected(true);
                ((MockProcessContext) runner.getProcessContext()).setPrimaryNode(true);
            }
            processor.start(runner.getProcessContext());
            processor.onTrigger(
                    runner.getProcessContext(), runner.getProcessSessionFactory().createSession());
            processor.acceptForTest(MemoryBoundsTest.TAG, new DataValue(new Variant(1)));
            ProcessSession session = spy(runner.getProcessSessionFactory().createSession());
            CountDownLatch writing = new CountDownLatch(1), release = new CountDownLatch(1);
            doAnswer(
                            call -> {
                                writing.countDown();
                                assertTrue(release.await(10, TimeUnit.SECONDS));
                                return call.callRealMethod();
                            })
                    .when(session)
                    .write(any(), any(org.apache.nifi.processor.io.OutputStreamCallback.class));
            ExecutorService workers = Executors.newFixedThreadPool(2);
            Future<?> trigger =
                    workers.submit(() -> processor.onTrigger(runner.getProcessContext(), session));
            try {
                assertTrue(writing.await(3, TimeUnit.SECONDS));
                if (revoke) ((MockProcessContext) runner.getProcessContext()).setPrimaryNode(false);
                workers.submit(
                                () -> {
                                    if (revoke)
                                        processor.primaryChanged(
                                                PrimaryNodeState.PRIMARY_NODE_REVOKED);
                                    else processor.unscheduled();
                                })
                        .get(2, TimeUnit.SECONDS);
                assertTrue(attempt.get().isCancelled());
                assertEquals(
                        1,
                        closed.get(),
                        "Transport cleanup must not depend on the repository write");
                assertFalse(trigger.isDone());
            } finally {
                release.countDown();
                trigger.get(3, TimeUnit.SECONDS);
                processor.stop();
                workers.shutdownNow();
                assertTrue(workers.awaitTermination(3, TimeUnit.SECONDS));
            }
            assertEquals(1, closed.get());
        }
    }

    @Test
    void aConnectionReturningAfterCancellationIsClosedWithoutBeingPublished() throws Exception {
        CountDownLatch opening = new CountDownLatch(1), release = new CountDownLatch(1);
        AtomicInteger closed = new AtomicInteger();
        SubscribeOpcUa processor =
                new SubscribeOpcUa() {
                    @Override
                    protected AutoCloseable openConnection(
                            ProcessContext context, ConnectionAttempt generation) throws Exception {
                        opening.countDown();
                        assertTrue(release.await(10, TimeUnit.SECONDS));
                        return closed::incrementAndGet; // Simulate an opener that completes despite
                        // cancellation.
                    }
                };
        TestRunner runner = ProcessorTest.configured(processor);
        processor.start(runner.getProcessContext());
        ExecutorService workers = Executors.newFixedThreadPool(2);
        Future<?> trigger =
                workers.submit(
                        () ->
                                processor.onTrigger(
                                        runner.getProcessContext(),
                                        runner.getProcessSessionFactory().createSession()));
        try {
            assertTrue(opening.await(3, TimeUnit.SECONDS));
            workers.submit(processor::unscheduled).get(2, TimeUnit.SECONDS);
            assertFalse(trigger.isDone());
            release.countDown();
            trigger.get(3, TimeUnit.SECONDS);
            assertEquals(1, closed.get());
            assertEquals(
                    0L,
                    processor
                            .diagnosticSnapshot(runner.getProcessContext())
                            .get("successfulConnections"));
        } finally {
            release.countDown();
            trigger.get(3, TimeUnit.SECONDS);
            processor.stop();
            workers.shutdownNow();
            assertTrue(workers.awaitTermination(3, TimeUnit.SECONDS));
        }
        assertEquals(1, closed.get());
    }

    @Test
    void cancellationDuringEncodingFencesTheFinalEnqueue() throws Exception {
        CountDownLatch encoding = new CountDownLatch(1), release = new CountDownLatch(1);
        SubscribeOpcUa processor =
                new SubscribeOpcUa() {
                    @Override
                    byte[] encodeNotification(TagDefinition tag, DataValue value, int limit)
                            throws java.io.IOException {
                        encoding.countDown();
                        try {
                            assertTrue(release.await(10, TimeUnit.SECONDS));
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            throw new java.io.IOException(e);
                        }
                        return super.encodeNotification(tag, value, limit);
                    }
                };
        TestRunner runner = ProcessorTest.configured(processor);
        processor.start(runner.getProcessContext());
        NotificationBuffer destination = new NotificationBuffer(10, 4096, 1024);
        ConnectionAttempt generation = new ConnectionAttempt();
        DataValue value = new DataValue(new Variant(42));
        ExecutorService workers = Executors.newFixedThreadPool(2);
        Future<?> callback =
                workers.submit(
                        () ->
                                processor.accept(
                                        destination, MemoryBoundsTest.TAG, value, generation));
        try {
            assertTrue(encoding.await(3, TimeUnit.SECONDS));
            workers.submit(generation::cancel).get(1, TimeUnit.SECONDS);
            assertTrue(generation.isCancelled());
            assertFalse(callback.isDone());
        } finally {
            release.countDown();
            callback.get(3, TimeUnit.SECONDS);
            processor.stop();
            workers.shutdownNow();
            assertTrue(workers.awaitTermination(3, TimeUnit.SECONDS));
        }
        assertEquals(0, destination.size());
        assertEquals(
                0L,
                processor
                        .diagnosticSnapshot(runner.getProcessContext())
                        .get("notificationsDropped"));
    }
}
