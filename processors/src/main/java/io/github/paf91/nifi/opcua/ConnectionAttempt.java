/* SPDX-License-Identifier: Apache-2.0 */
package io.github.paf91.nifi.opcua;

import java.util.concurrent.*;

/** One connection generation; cancellation also fences late callbacks. */
final class ConnectionAttempt {
    private volatile boolean cancelled;
    private Thread blockingThread;

    synchronized void cancel() {
        cancelled = true;
        if (blockingThread != null) blockingThread.interrupt();
    }

    boolean isCancelled() {
        return cancelled;
    }

    void check() {
        if (cancelled) throw new CancellationException("OPC UA connection attempt cancelled");
    }

    synchronized void ifActive(Runnable action) {
        if (!cancelled) action.run();
    }

    /** Submit a nonblocking request atomically with respect to cancellation. */
    synchronized <T> T callIfActive(java.util.function.Supplier<T> action) {
        check();
        return action.get();
    }

    <T> T await(CompletableFuture<T> future, long timeoutMillis) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
        try {
            while (true) {
                check();
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) throw new TimeoutException("OPC UA operation timed out");
                try {
                    return future.get(
                            Math.min(remaining, TimeUnit.MILLISECONDS.toNanos(50)),
                            TimeUnit.NANOSECONDS);
                } catch (TimeoutException e) {
                    if (System.nanoTime() >= deadline) throw e;
                }
            }
        } catch (Exception e) {
            future.cancel(false);
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            throw e;
        }
    }

    void blocking(Runnable action) {
        synchronized (this) {
            check();
            blockingThread = Thread.currentThread();
        }
        try {
            action.run();
            check();
        } finally {
            synchronized (this) {
                blockingThread = null;
                // Do not leak our cancellation interrupt into NiFi's reusable worker.
                if (cancelled) Thread.interrupted();
            }
        }
    }
}
