/* SPDX-License-Identifier: Apache-2.0 */
package io.github.paf91.nifi.opcua;

import static org.junit.jupiter.api.Assertions.*;

import org.eclipse.milo.opcua.stack.core.types.builtin.*;
import org.junit.jupiter.api.Test;

import java.lang.management.ManagementFactory;

class EncodingPerformanceTest {
    private static volatile long consumed;

    @Test
    void diagnosticsDoNotFormatTimestampsOnEveryCallback() {
        ConnectionDiagnostics diagnostics = new ConnectionDiagnostics();
        DataValue value = new DataValue(new Variant(42));
        for (int i = 0; i < 20000; i++) diagnostics.notification(value);
        long before = MemoryBoundsTest.allocatedBytes();
        for (int i = 0; i < 50000; i++) diagnostics.notification(value);
        long allocated = MemoryBoundsTest.allocatedBytes() - before;
        System.out.println(
                "PERFORMANCE diagnostics iterations=50000 allocatedBytesPerNotification="
                        + allocated / 50000);
        assertEquals(70000L, diagnostics.snapshot().get("notificationsReceived"));
        if (!Boolean.getBoolean("opcua.performance.baseline"))
            assertTrue(
                    allocated / 50000 < 128,
                    "Diagnostic allocation per notification: " + allocated / 50000);
    }

    @Test
    void scalarEncodingAllocationsRemainSmall() throws Exception {
        DataValue value = new DataValue(new Variant(42.5), new StatusCode(0x40920480L), null, null);
        for (int i = 0; i < 20000; i++)
            consumed += NotificationJson.encode(MemoryBoundsTest.TAG, value, 4096).length;
        java.lang.management.ThreadMXBean cpu = ManagementFactory.getThreadMXBean();
        long before = MemoryBoundsTest.allocatedBytes();
        long started = cpu.getCurrentThreadCpuTime();
        int iterations = 50000;
        for (int i = 0; i < iterations; i++)
            consumed += NotificationJson.encode(MemoryBoundsTest.TAG, value, 4096).length;
        long elapsed = cpu.getCurrentThreadCpuTime() - started;
        long allocated = MemoryBoundsTest.allocatedBytes() - before;
        System.out.println(
                "PERFORMANCE scalar iterations="
                        + iterations
                        + " allocatedBytesPerRecord="
                        + allocated / iterations
                        + " cpuNanosPerRecord="
                        + elapsed / iterations);
        if (!Boolean.getBoolean("opcua.performance.baseline"))
            assertTrue(
                    allocated / iterations < 3000,
                    "Scalar allocation per record: " + allocated / iterations);
    }
}
