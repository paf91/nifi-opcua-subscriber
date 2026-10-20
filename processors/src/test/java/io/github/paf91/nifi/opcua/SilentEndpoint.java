/* SPDX-License-Identifier: Apache-2.0 */
package io.github.paf91.nifi.opcua;

import java.net.*;
import java.util.concurrent.*;

/** Accepts a TCP connection, reads HEL, but never sends ACK. */
final class SilentEndpoint implements AutoCloseable {
    final ServerSocket server = new ServerSocket(0);
    final CountDownLatch accepted = new CountDownLatch(1);
    final CountDownLatch disconnected = new CountDownLatch(1);
    final ExecutorService worker = Executors.newSingleThreadExecutor();
    volatile Socket socket;

    SilentEndpoint() throws Exception {
        worker.submit(
                () -> {
                    try {
                        socket = server.accept();
                        socket.setSoTimeout(5000);
                        accepted.countDown();
                        byte[] data = new byte[4096];
                        while (socket.getInputStream().read(data) != -1) {}
                        disconnected.countDown();
                    } catch (java.io.IOException ignored) {
                    }
                });
    }

    String url() {
        return "opc.tcp://localhost:" + server.getLocalPort() + "/discovery";
    }

    public void close() throws Exception {
        server.close();
        if (socket != null) socket.close();
        worker.shutdownNow();
        worker.awaitTermination(2, TimeUnit.SECONDS);
    }
}
