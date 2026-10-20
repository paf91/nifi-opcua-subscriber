/* SPDX-License-Identifier: Apache-2.0 */
package io.github.paf91.nifi.opcua;

import java.util.*;

/** Peek before commit: callbacks can append, but cannot evict an uncommitted batch. */
final class NotificationBuffer {
    private final int capacity;
    private boolean reserved;
    private final ArrayDeque<byte[]> queue = new ArrayDeque<>();

    NotificationBuffer(int capacity) {
        this.capacity = capacity;
    }

    synchronized boolean offer(byte[] value) {
        if (queue.size() >= capacity) return false;
        queue.addLast(value);
        return true;
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
        if (reserved) return Collections.emptyList();
        List<byte[]> batch = peek(maximum);
        reserved = !batch.isEmpty();
        return batch;
    }

    synchronized void release() {
        reserved = false;
    }

    synchronized void acknowledge(int count) {
        for (int i = 0; i < count; i++) queue.removeFirst();
        reserved = false;
    }

    synchronized int size() {
        return queue.size();
    }
}
