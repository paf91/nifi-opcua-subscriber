/* SPDX-License-Identifier: Apache-2.0 */
package io.github.paf91.nifi.opcua;

import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** Peek before commit: callbacks can append, but cannot evict an uncommitted batch. */
final class NotificationBuffer {
    private final int capacity;
    private final long byteCapacity;
    private final int maximumRecordBytes;
    private long bytes;
    private long peakBytes;
    private boolean reserved;
    private final ArrayDeque<byte[]> queue = new ArrayDeque<>();

    NotificationBuffer(int capacity) {
        this(capacity, Long.MAX_VALUE, Integer.MAX_VALUE);
    }

    NotificationBuffer(int capacity, long byteCapacity, int maximumRecordBytes) {
        if (capacity < 1
                || byteCapacity < 1
                || maximumRecordBytes < 1
                || maximumRecordBytes > byteCapacity)
            throw new IllegalArgumentException(
                    "Buffer and record limits must be positive; record limit must fit buffer");
        this.capacity = capacity;
        this.byteCapacity = byteCapacity;
        this.maximumRecordBytes = maximumRecordBytes;
    }

    synchronized boolean offer(byte[] value) {
        Objects.requireNonNull(value);
        if (value.length > maximumRecordBytes
                || queue.size() >= capacity
                || value.length > byteCapacity - bytes) return false;
        queue.addLast(value);
        bytes += value.length;
        peakBytes = Math.max(peakBytes, bytes);
        return true;
    }

    /** A cheap admission snapshot; offer still enforces both limits atomically. */
    synchronized int encodingLimit() {
        if (queue.size() >= capacity) return 0;
        return (int) Math.min(maximumRecordBytes, byteCapacity - bytes);
    }

    synchronized List<byte[]> peek(int maximum) {
        List<byte[]> batch = new ArrayList<>();
        for (byte[] item : queue) {
            if (batch.size() == maximum) break;
            batch.add(item);
        }
        return batch;
    }

    synchronized List<byte[]> reserve(int maximum) {
        return reserve(maximum, Long.MAX_VALUE);
    }

    synchronized List<byte[]> reserve(int maximum, long maximumBytes) {
        if (reserved) return Collections.emptyList();
        List<byte[]> batch = new ArrayList<>();
        long encoded = 2; // JSON array brackets, plus commas between records.
        for (byte[] item : queue) {
            long addition = item.length + (batch.isEmpty() ? 0L : 1L);
            if (batch.size() == maximum || addition > maximumBytes - encoded) break;
            batch.add(item);
            encoded += addition;
        }
        reserved = !batch.isEmpty();
        return batch;
    }

    synchronized void release() {
        reserved = false;
        notifyAll();
    }

    synchronized void acknowledge(int count) {
        if (count < 0 || count > queue.size())
            throw new IllegalArgumentException("Invalid acknowledgement count");
        for (int i = 0; i < count; i++) bytes -= queue.removeFirst().length;
        reserved = false;
        notifyAll();
    }

    /** Wait only for the previous batch's outcome; never steal an in-flight reservation. */
    synchronized void awaitAvailable(long deadline) throws InterruptedException, TimeoutException {
        while (reserved) {
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) throw new TimeoutException();
            TimeUnit.NANOSECONDS.timedWait(this, remaining);
        }
    }

    synchronized int size() {
        return queue.size();
    }

    int maximumRecordBytes() {
        return maximumRecordBytes;
    }

    synchronized Map<String, Object> snapshot() {
        Map<String, Object> state = new LinkedHashMap<>();
        state.put("records", queue.size());
        state.put("recordCapacity", capacity);
        state.put("bytes", bytes);
        state.put("byteCapacity", byteCapacity);
        state.put("peakBytes", peakBytes);
        state.put("reserved", reserved);
        return state;
    }
}
