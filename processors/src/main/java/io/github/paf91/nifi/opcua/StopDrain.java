/* SPDX-License-Identifier: Apache-2.0 */
package io.github.paf91.nifi.opcua;

import org.apache.nifi.processor.*;

import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

/** One best-effort drain. No repository operation is performed by the lifecycle thread. */
final class StopDrain implements Runnable {
    private final NotificationBuffer buffer;
    // Keep the factory alive with its sessions, including after a timed-out asynchronous commit.
    private final ProcessSessionFactory factory;
    private final ProcessContext context;
    private final BooleanSupplier permitted;
    private final int batchSize;
    private final long batchBytes;
    private final String endpoint, policy, mode;
    private final long deadline;
    private final CountDownLatch finished = new CountDownLatch(1);
    private final AtomicBoolean cancelled = new AtomicBoolean();
    private final Thread worker;
    private volatile String outcome = "drain deadline expired";

    private static final class Unavailable extends Exception {
        Unavailable(String reason) {
            super(reason);
        }
    }

    StopDrain(
            NotificationBuffer buffer,
            ProcessSessionFactory factory,
            ProcessContext context,
            BooleanSupplier permitted,
            int batchSize,
            long batchBytes,
            String endpoint,
            String policy,
            String mode,
            int timeoutMillis,
            String threadName) {
        this.buffer = buffer;
        this.factory = factory;
        this.context = context;
        this.permitted = permitted;
        this.batchSize = batchSize;
        this.batchBytes = batchBytes;
        this.endpoint = endpoint;
        this.policy = policy;
        this.mode = mode;
        deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
        worker = new Thread(this, threadName);
        worker.setDaemon(true);
    }

    void start() {
        worker.start();
    }

    boolean isFinished() {
        // Future.cancel() can report done while an uninterruptible write is still running.
        return finished.getCount() == 0;
    }

    void cancel() {
        cancelled.set(true);
        worker.interrupt();
    }

    String await() {
        try {
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0 || !finished.await(remaining, TimeUnit.NANOSECONDS)) {
                cancel();
                return "drain deadline expired";
            }
            return outcome;
        } catch (InterruptedException e) {
            cancel();
            Thread.currentThread().interrupt();
            return "stop interrupted";
        }
    }

    private void check() throws InterruptedException, TimeoutException, Unavailable {
        if (cancelled.get() || Thread.currentThread().isInterrupted())
            throw new InterruptedException();
        if (deadline - System.nanoTime() <= 0) throw new TimeoutException();
        if (!permitted.getAsBoolean())
            throw new Unavailable("processor restarted or lost ownership");
        if (!context.getAvailableRelationships().contains(SubscribeOpcUa.SUCCESS))
            throw new Unavailable("success relationship is backpressured");
    }

    @Override
    public void run() {
        try {
            while (buffer.size() > 0) {
                check();
                buffer.awaitAvailable(deadline);
                check();
                List<byte[]> records = buffer.reserve(batchSize, batchBytes);
                if (records.isEmpty()) continue;
                deliver(records);
            }
            outcome = "drained";
        } catch (InterruptedException e) {
            outcome = "drain interrupted or cancelled";
            Thread.currentThread().interrupt();
        } catch (TimeoutException e) {
            outcome = "drain deadline expired";
        } catch (Unavailable e) {
            outcome = e.getMessage();
        } catch (Exception e) {
            // Only the exception type is reported; repository messages can contain sensitive data.
            outcome = "drain failed (" + e.getClass().getSimpleName() + ")";
        } finally {
            finished.countDown();
        }
    }

    private void deliver(List<byte[]> records) throws Exception {
        ProcessSession session = null;
        boolean submitted = false;
        AtomicBoolean settled = new AtomicBoolean();
        CompletableFuture<Void> committed = new CompletableFuture<>();
        try {
            check();
            session = factory.createSession();
            check();
            NotificationBatch.write(session, records, endpoint, policy, mode);
            check();
            session.commitAsync(
                    () -> {
                        if (settled.compareAndSet(false, true)) buffer.acknowledge(records.size());
                        committed.complete(null);
                    },
                    error -> {
                        if (settled.compareAndSet(false, true)) buffer.release();
                        committed.completeExceptionally(error);
                    });
            submitted = true;
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) throw new TimeoutException();
            committed.get(remaining, TimeUnit.NANOSECONDS);
        } finally {
            if (!submitted && !settled.get()) {
                // The session belongs only to this worker. Never roll it back concurrently from
                // the timeout thread, and never release records before rollback returns.
                try {
                    if (session != null) session.rollback();
                } finally {
                    if (settled.compareAndSet(false, true)) buffer.release();
                }
            }
            // Once submitted, only the commit callback can release/acknowledge the reservation.
        }
    }
}
