/* SPDX-License-Identifier: Apache-2.0 */
package io.github.paf91.nifi.opcua;

import static org.junit.jupiter.api.Assertions.*;

import org.eclipse.milo.opcua.stack.core.StatusCodes;
import org.eclipse.milo.opcua.stack.core.UaException;
import org.junit.jupiter.api.Test;

import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

class BoundedRequestTest {
    @Test
    void timesOutSessionAcquisitionWithoutSendingWhenTheSessionEventuallyArrives() {
        ConnectionAttempt attempt = new ConnectionAttempt();
        CompletableFuture<String> session = new CompletableFuture<>();
        AtomicInteger sent = new AtomicInteger();
        CompletableFuture<String> request =
                session.thenCompose(
                        value -> {
                            sent.incrementAndGet();
                            return CompletableFuture.completedFuture("response");
                        });
        CompletionException failure =
                assertTimeoutPreemptively(
                        java.time.Duration.ofSeconds(2),
                        () ->
                                assertThrows(
                                        CompletionException.class,
                                        () -> BoundedRequest.execute(attempt, 50, () -> request)));
        assertTrue(failure.getCause() instanceof TimeoutException);
        assertTrue(request.isCancelled());
        assertFalse(session.isCancelled(), "Do not cancel the SDK's shared session future");
        session.complete("recovered session");
        assertEquals(0, sent.get());
    }

    @Test
    void cancellationStopsTheCurrentWaitAndDoesNotLeakItsInterrupt() throws Exception {
        ConnectionAttempt attempt = new ConnectionAttempt();
        CountDownLatch entered = new CountDownLatch(1);
        CompletableFuture<String> response = new CompletableFuture<>();
        ExecutorService worker = Executors.newSingleThreadExecutor();
        AtomicBoolean interruptedAfterwards = new AtomicBoolean(true);
        try {
            Future<?> work =
                    worker.submit(
                            () -> {
                                try {
                                    attempt.blocking(
                                            () -> {
                                                try {
                                                    BoundedRequest.execute(
                                                            attempt,
                                                            10000,
                                                            () -> {
                                                                entered.countDown();
                                                                return response;
                                                            });
                                                } catch (UaException e) {
                                                    throw new CompletionException(e);
                                                }
                                            });
                                } finally {
                                    interruptedAfterwards.set(
                                            Thread.currentThread().isInterrupted());
                                }
                            });
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            attempt.cancel();
            assertThrows(ExecutionException.class, () -> work.get(2, TimeUnit.SECONDS));
            assertTrue(response.isCancelled());
            assertFalse(interruptedAfterwards.get());
            AtomicBoolean submitted = new AtomicBoolean();
            assertThrows(
                    CancellationException.class,
                    () ->
                            attempt.callIfActive(
                                    () -> {
                                        submitted.set(true);
                                        return CompletableFuture.completedFuture("late request");
                                    }));
            assertFalse(submitted.get());
        } finally {
            attempt.cancel();
            worker.shutdownNow();
        }
    }

    @Test
    void rejectsAnAlreadyCancelledAttemptBeforeStartingTheOperation() {
        ConnectionAttempt attempt = new ConnectionAttempt();
        attempt.cancel();
        assertThrows(
                CancellationException.class,
                () ->
                        BoundedRequest.execute(
                                attempt,
                                100,
                                () -> {
                                    throw new AssertionError("Cancelled operation was started");
                                }));
    }

    @Test
    void eachHealthyBatchGetsItsOwnDeadline() throws Exception {
        ConnectionAttempt attempt = new ConnectionAttempt();
        ScheduledExecutorService responses = Executors.newSingleThreadScheduledExecutor();
        try {
            for (int i = 0; i < 5; i++) {
                CompletableFuture<Integer> response = new CompletableFuture<>();
                int batch = i;
                responses.schedule(() -> response.complete(batch), 100, TimeUnit.MILLISECONDS);
                assertEquals(
                        batch, BoundedRequest.execute(attempt, 300, () -> response).intValue());
            }
        } finally {
            responses.shutdownNow();
        }
    }

    @Test
    void preservesServiceErrorsForSdkAdaptiveBatchingButAbortsOnTimeout() {
        UaException tooMany = new UaException(StatusCodes.Bad_TooManyOperations);
        CompletableFuture<Object> rejected = new CompletableFuture<>();
        rejected.completeExceptionally(tooMany);
        assertSame(
                tooMany,
                assertThrows(
                        UaException.class,
                        () ->
                                BoundedRequest.execute(
                                        new ConnectionAttempt(), 100, () -> rejected)));
        CompletableFuture<Object> timedOut = new CompletableFuture<>();
        timedOut.completeExceptionally(new UaException(StatusCodes.Bad_Timeout));
        CompletionException failure =
                assertThrows(
                        CompletionException.class,
                        () -> BoundedRequest.execute(new ConnectionAttempt(), 100, () -> timedOut));
        assertEquals(
                StatusCodes.Bad_Timeout,
                UaException.extractStatusCode(failure).orElseThrow(AssertionError::new).getValue());
    }
}
