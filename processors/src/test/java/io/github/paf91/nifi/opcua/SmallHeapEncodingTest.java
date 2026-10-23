/* SPDX-License-Identifier: Apache-2.0 */
package io.github.paf91.nifi.opcua;

import static org.junit.jupiter.api.Assertions.*;

import org.eclipse.milo.opcua.stack.core.types.builtin.*;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;

class SmallHeapEncodingTest {
    @Test
    void largeValuesAreRejectedNormallyInAnIsolatedSmallHeap() throws Exception {
        List<String> modes = new ArrayList<>(Arrays.asList("binary", "extension"));
        try {
            Class.forName("org.eclipse.milo.opcua.stack.core.types.builtin.Matrix");
            modes.add("matrix");
        } catch (ClassNotFoundException legacy) {
            /* Legacy matrices are decoded as Java arrays. */
        }
        for (String mode : modes) {
            Path log = Paths.get("target", "small-heap-" + mode + ".log");
            Process child =
                    new ProcessBuilder(
                                    Paths.get(System.getProperty("java.home"), "bin", "java")
                                            .toString(),
                                    "-Xms32m",
                                    "-Xmx32m",
                                    "-cp",
                                    System.getProperty("java.class.path"),
                                    Probe.class.getName(),
                                    mode)
                            .redirectErrorStream(true)
                            .redirectOutput(log.toFile())
                            .start();
            try {
                assertTrue(
                        child.waitFor(20, TimeUnit.SECONDS), "Small-heap encoder stalled: " + mode);
                String output = new String(Files.readAllBytes(log), StandardCharsets.UTF_8);
                assertEquals(0, child.exitValue(), output);
                assertTrue(output.contains("RecordTooLargeException"), output);
                System.out.print(output);
            } finally {
                if (child.isAlive()) {
                    child.destroyForcibly();
                    assertTrue(child.waitFor(5, TimeUnit.SECONDS));
                }
            }
        }
    }

    public static class Probe {
        public static void main(String[] args) throws Exception {
            Object raw;
            if (args[0].equals("matrix")) {
                Class<?> matrix =
                        Class.forName("org.eclipse.milo.opcua.stack.core.types.builtin.Matrix");
                raw =
                        matrix.getConstructor(Object.class, int[].class)
                                .newInstance(new int[1000000], new int[] {1000000, 1});
            } else {
                byte[] data = new byte[1536 * 1024];
                Arrays.fill(data, (byte) -1);
                ByteString body = new ByteString(data);
                raw =
                        args[0].equals("binary")
                                ? body
                                : MemoryBoundsTest.extension(body, ByteString.class);
            }
            try {
                NotificationJson.encode(
                        MemoryBoundsTest.TAG, new DataValue(new Variant(raw)), 1024);
                throw new AssertionError("The oversized value must be rejected");
            } catch (IOException failure) {
                for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
                    if (cause instanceof NotificationJson.RecordTooLargeException) {
                        System.out.println("SMALL_HEAP " + args[0] + " RecordTooLargeException");
                        return;
                    }
                }
                throw failure;
            }
        }
    }
}
