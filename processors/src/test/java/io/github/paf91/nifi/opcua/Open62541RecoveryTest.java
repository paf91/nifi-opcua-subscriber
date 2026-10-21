/* SPDX-License-Identifier: Apache-2.0 */
package io.github.paf91.nifi.opcua;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.*;

import org.apache.nifi.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

@EnabledIfEnvironmentVariable(named = "OPCUA_OPEN62541_BINARY", matches = ".+")
class Open62541RecoveryTest {
    @TempDir Path dir;
    private Open62541Test server;
    private SubscribeOpcUa processor;
    private TestRunner runner;

    @BeforeEach
    void setup() throws Exception {
        server = new Open62541Test();
        server.dir = dir;
        server.setup();
        server.start(false, "None");
        processor = new SubscribeOpcUa();
        runner = server.processor(processor);
    }

    @AfterEach
    void cleanup(TestInfo info) throws Exception {
        Files.deleteIfExists(dir.resolve("pause.flag"));
        if (runner != null) runner.shutdown();
        if (server != null) {
            server.stop();
            server.saveLog(info);
        }
    }

    private void awaitValue(double wanted) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(25);
        while (System.nanoTime() < deadline) {
            runner.run(1, false, false);
            for (MockFlowFile f : runner.getFlowFilesForRelationship(SubscribeOpcUa.SUCCESS)) {
                for (JsonNode value :
                        new ObjectMapper().readTree(runner.getContentAsByteArray(f))) {
                    if (value.get("value").asDouble() == wanted
                            && "Good".equals(value.get("quality").asText())) return;
                }
            }
            runner.clearTransferState();
            Thread.sleep(100);
        }
        fail(
                "No fresh expected value "
                        + wanted
                        + "; "
                        + processor.diagnosticSnapshot(runner.getProcessContext()));
    }

    @Test
    void quietSubscriptionReceivesKeepAlivesWithoutReconnecting() throws Exception {
        runner.run(1, false, true);
        awaitValue(0);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        Map<?, ?> state = null;
        while (System.nanoTime() < deadline) {
            runner.run(1, false, false);
            state = processor.diagnosticSnapshot(runner.getProcessContext());
            if (((Map<?, ?>) state.get("connection")).get("lastKeepAliveReceivedAt") != null) break;
            Thread.sleep(200);
        }
        assertEquals(1L, state.get("connectionAttempts"), state.toString());
        Map<?, ?> connection = (Map<?, ?>) state.get("connection");
        assertNotNull(connection.get("lastKeepAliveReceivedAt"), state.toString());
        assertEquals(1L, connection.get("notificationsReceived"), state.toString());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void recoversAfterServerStopsAnsweringWithoutClosingTcp(boolean uri) throws Exception {
        if (!uri) runner.setProperty("value", "ns=2;s=Value");
        runner.run(1, false, true);
        awaitValue(0);
        runner.clearTransferState();
        Files.createFile(dir.resolve("pause.flag"));
        try {
            Thread.sleep(18000);
            runner.run(1, false, false);
        } finally {
            Files.deleteIfExists(dir.resolve("pause.flag"));
        }
        server.write(73, 0);
        awaitValue(73);
    }
}
