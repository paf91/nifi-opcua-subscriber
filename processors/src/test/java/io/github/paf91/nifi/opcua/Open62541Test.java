/* SPDX-License-Identifier: Apache-2.0 */
package io.github.paf91.nifi.opcua;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.*;

import org.apache.nifi.components.ConfigVerificationResult;
import org.apache.nifi.util.*;
import org.eclipse.milo.opcua.stack.core.types.builtin.*;
import org.eclipse.milo.opcua.stack.core.util.SelfSignedCertificateBuilder;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;

import java.nio.file.*;
import java.security.*;
import java.security.cert.X509Certificate;
import java.util.*;
import java.util.concurrent.*;

/** Runs the independent C server fixture; no external or production endpoint is contacted. */
@EnabledIfEnvironmentVariable(named = "OPCUA_OPEN62541_BINARY", matches = ".+")
class Open62541Test {
    static final String NS = "urn:nifi:interop:tags";
    static final String URI_NODE = "nsu=" + NS + ";s=Value";
    static final String POLICY_PREFIX = "http://opcfoundation.org/UA/SecurityPolicy#";
    @TempDir Path dir;
    Process server;
    int port, sequence;

    @BeforeEach
    void setup() throws Exception {
        Security.addProvider(new org.bouncycastle.jce.provider.BouncyCastleProvider());
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        for (String role : Arrays.asList("client", "server")) {
            KeyPair pair = generator.generateKeyPair();
            X509Certificate cert =
                    new SelfSignedCertificateBuilder(pair)
                            .setCommonName("Interop test " + role)
                            .setCountryCode("US")
                            .setApplicationUri("urn:nifi:interop:" + role)
                            .addDnsName("localhost")
                            .addIpAddress("127.0.0.1")
                            .build();
            Files.write(dir.resolve(role + ".der"), cert.getEncoded());
            Files.write(dir.resolve(role + "-key.der"), pair.getPrivate().getEncoded());
        }
        Files.createDirectories(dir.resolve("pki/trusted/certs"));
        Files.copy(dir.resolve("server.der"), dir.resolve("pki/trusted/certs/server.der"));
        try (java.net.ServerSocket socket = new java.net.ServerSocket(0)) {
            port = socket.getLocalPort();
        }
    }

    void start(boolean pad, String tokenPolicy) throws Exception {
        Files.deleteIfExists(dir.resolve("ready.txt"));
        server =
                new ProcessBuilder(
                                System.getenv("OPCUA_OPEN62541_BINARY"),
                                String.valueOf(port),
                                pad ? "1" : "0",
                                POLICY_PREFIX + tokenPolicy)
                        .directory(dir.toFile())
                        .redirectErrorStream(true)
                        .redirectOutput(
                                ProcessBuilder.Redirect.appendTo(
                                        dir.resolve("server.log").toFile()))
                        .start();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!Files.exists(dir.resolve("ready.txt"))
                && server.isAlive()
                && System.nanoTime() < deadline) Thread.sleep(20);
        assertTrue(
                server.isAlive() && Files.exists(dir.resolve("ready.txt")),
                () -> "open62541 startup failed: " + log());
    }

    String log() {
        try {
            return new String(
                    Files.readAllBytes(dir.resolve("server.log")),
                    java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception e) {
            return e.toString();
        }
    }

    @AfterEach
    void stop() throws Exception {
        if (server != null) {
            server.destroy();
            if (!server.waitFor(5, TimeUnit.SECONDS)) {
                server.destroyForcibly();
                server.waitFor(5, TimeUnit.SECONDS);
            }
        }
    }

    @AfterEach
    void saveLog(TestInfo test) throws Exception {
        if (Files.exists(dir.resolve("server.log"))) {
            Path logs = Paths.get("target", "interop-logs");
            Files.createDirectories(logs);
            Files.copy(
                    dir.resolve("server.log"),
                    logs.resolve(test.getDisplayName().replaceAll("[^a-zA-Z0-9.-]", "_") + ".log"),
                    StandardCopyOption.REPLACE_EXISTING);
        }
    }

    String endpoint() {
        return "opc.tcp://localhost:" + port;
    }

    OpcSettings settings(String policy, String mode, String authentication, String tag) {
        OpcSecurity security = new OpcSecurity(policy, mode, authentication);
        return new OpcSettings(
                endpoint(),
                null,
                security.requiresClientIdentity() ? dir.resolve("client.der") : null,
                security.requiresClientIdentity() ? dir.resolve("client-key.der") : null,
                "",
                dir.resolve("pki"),
                "test-user",
                "test-password",
                100,
                2000,
                Collections.singletonList(TagDefinition.parse("value", tag, 50, 10)),
                security);
    }

    static java.util.stream.Stream<Object[]> configurations() {
        return OpcSecurityTest.configurations()
                .filter(c -> "None".equals(c[0]) == "None".equals(c[1]));
    }

    void write(double value, long status) throws Exception {
        int seq = ++sequence;
        Path temporary = dir.resolve("control.tmp");
        Files.write(
                temporary,
                (seq + " " + value + " " + status)
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8));
        Files.move(
                temporary,
                dir.resolve("control.txt"),
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (System.nanoTime() < deadline) {
            if (Files.exists(dir.resolve("applied.txt"))
                    && String.valueOf(seq)
                            .equals(
                                    new String(
                                            Files.readAllBytes(dir.resolve("applied.txt")),
                                            java.nio.charset.StandardCharsets.UTF_8))) return;
            Thread.sleep(20);
        }
        fail("Server did not apply test value: " + log());
    }

    static DataValue receive(BlockingQueue<DataValue> values) throws Exception {
        DataValue value = values.poll(5, TimeUnit.SECONDS);
        assertNotNull(value, "No data-change notification");
        return value;
    }

    @ParameterizedTest(name = "open62541 {0} / {1} / {2}")
    @MethodSource("configurations")
    void independentSecurityAndAuthenticationMatrix(
            String policy, String mode, String authentication) throws Exception {
        start(false, "None");
        BlockingQueue<DataValue> values = new LinkedBlockingQueue<>();
        ConnectionDiagnostics report = new ConnectionDiagnostics();
        try (OpcConnection connection =
                new OpcConnection(
                        settings(policy, mode, authentication, URI_NODE),
                        (tag, value) -> values.offer(value),
                        () -> {},
                        () -> {},
                        new ConnectionAttempt(),
                        report)) {
            assertTrue(receive(values).getStatusCode().isGood());
            write(42, 0);
            assertEquals(42.0, ((Number) receive(values).getValue().getValue()).doubleValue());
            assertEquals("Subscribed", report.snapshot().get("stage"));
            assertEquals(true, report.snapshot().get("sessionActive"));
            assertEquals(1, report.snapshot().get("monitoredItemCount"));
            assertNotNull(report.snapshot().get("serverCertificate"));
        }
        values.clear();
        write(73, 0);
        assertNull(values.poll(350, TimeUnit.MILLISECONDS), "Callback after connection close");
        assertEquals(false, report.snapshot().get("sessionActive"));
    }

    @Test
    void encryptsUsernameTokensEvenWithoutChannelSecurity() throws Exception {
        start(false, "Basic256Sha256");
        BlockingQueue<DataValue> values = new LinkedBlockingQueue<>();
        try (OpcConnection connection =
                new OpcConnection(
                        settings("None", "None", OpcSecurity.USERNAME, URI_NODE),
                        (tag, value) -> values.offer(value),
                        () -> {},
                        () -> {})) {
            assertTrue(receive(values).getStatusCode().isGood());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"Absolute", "Percent"})
    void serverDeadbandsSuppressSmallChangesButDeliverQualityChanges(String type) throws Exception {
        start(false, "None");
        write(0, 0); // Keep the presence of the status field stable while testing the value
        // deadband.
        String tag =
                "{\"nodeId\":\""
                        + URI_NODE
                        + "\",\"deadbandType\":\""
                        + type
                        + "\",\"deadbandValue\":5}";
        BlockingQueue<DataValue> values = new LinkedBlockingQueue<>();
        try (OpcConnection connection =
                new OpcConnection(
                        settings("None", "None", OpcSecurity.ANONYMOUS, tag),
                        (t, value) -> values.offer(value),
                        () -> {},
                        () -> {})) {
            assertEquals(0.0, ((Number) receive(values).getValue().getValue()).doubleValue());
            write(3, 0);
            assertNull(values.poll(400, TimeUnit.MILLISECONDS));
            write(5, 0);
            assertNull(values.poll(400, TimeUnit.MILLISECONDS)); // threshold is strictly exceeded
            write(6, 0);
            assertEquals(6.0, ((Number) receive(values).getValue().getValue()).doubleValue());
            write(6.1, 0x40000000L);
            assertEquals(0x40000000L, receive(values).getStatusCode().getValue());
            write(6.2, 0);
            assertTrue(receive(values).getStatusCode().isGood());
        }
    }

    @Test
    void rejectsPercentDeadbandWithoutEngineeringRange() throws Exception {
        start(false, "None");
        String tag =
                "{\"nodeId\":\"nsu="
                        + NS
                        + ";s=NoRange\",\"deadbandType\":\"Percent\",\"deadbandValue\":5}";
        Exception error =
                assertThrows(
                        Exception.class,
                        () ->
                                new OpcConnection(
                                        settings("None", "None", OpcSecurity.ANONYMOUS, tag),
                                        (t, value) -> {},
                                        () -> {},
                                        () -> {}));
        assertTrue(
                error.toString().contains("Bad") || error.toString().contains("StatusCode"),
                error.toString());
    }

    TestRunner processor(SubscribeOpcUa p) {
        TestRunner r = ProcessorTest.configured(p);
        r.setProperty(SubscribeOpcUa.ENDPOINT, endpoint());
        r.setProperty(SubscribeOpcUa.SECURITY_POLICY, "None");
        r.setProperty(SubscribeOpcUa.SECURITY_MODE, "None");
        r.setProperty(SubscribeOpcUa.AUTHENTICATION, OpcSecurity.ANONYMOUS);
        r.removeProperty("DemoSwitch");
        r.setProperty("value", URI_NODE);
        r.setProperty(SubscribeOpcUa.PUBLISHING, "100");
        r.setProperty(SubscribeOpcUa.SAMPLING, "50");
        r.setProperty(SubscribeOpcUa.RETRY, "100");
        r.setProperty(SubscribeOpcUa.TIMEOUT, "1500");
        return r;
    }

    @Test
    void verificationChecksLiveTagsAndRejectsMissingNamespaceAndDuplicates() throws Exception {
        start(false, "None");
        SubscribeOpcUa p = new SubscribeOpcUa();
        TestRunner r = processor(p);
        assertEquals(
                ConfigVerificationResult.Outcome.SUCCESSFUL,
                p.verify(r.getProcessContext(), null, Collections.emptyMap()).get(0).getOutcome());
        r.setProperty("duplicate", "ns=2;s=Value");
        ConfigVerificationResult duplicate =
                p.verify(r.getProcessContext(), null, Collections.emptyMap()).get(0);
        assertEquals(ConfigVerificationResult.Outcome.FAILED, duplicate.getOutcome());
        assertTrue(duplicate.getExplanation().contains("same NodeId"));
        r.removeProperty("duplicate");
        r.setProperty("value", "nsu=urn:missing;s=Value");
        assertEquals(
                ConfigVerificationResult.Outcome.FAILED,
                p.verify(r.getProcessContext(), null, Collections.emptyMap()).get(0).getOutcome());
    }

    @Test
    void resolvesMovedNamespaceAfterServerRestartBeforeAcceptingNewValues() throws Exception {
        start(false, "None");
        SubscribeOpcUa p = new SubscribeOpcUa();
        TestRunner r = processor(p);
        try {
            r.run(1, false, true);
            Thread.sleep(300);
            r.run(1, false, false);
            r.assertTransferCount(SubscribeOpcUa.SUCCESS, 1);
            stop();
            Thread.sleep(200);
            start(true, "None");
            write(42, 0);
            boolean fresh = false;
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(25);
            while (!fresh && System.nanoTime() < deadline) {
                r.clearTransferState();
                r.run(1, false, false);
                for (MockFlowFile flow : r.getFlowFilesForRelationship(SubscribeOpcUa.SUCCESS)) {
                    for (JsonNode record :
                            new ObjectMapper().readTree(r.getContentAsByteArray(flow))) {
                        assertNotEquals(
                                -9999.0,
                                record.get("value").asDouble(),
                                "Subscribed using a stale namespace index");
                        if (record.get("value").asDouble() == 42.0) {
                            assertEquals("ns=3;s=Value", record.get("nodeId").asText());
                            assertEquals(URI_NODE, record.get("configuredNodeId").asText());
                            fresh = true;
                        }
                    }
                }
                if (!fresh) Thread.sleep(100);
            }
            assertTrue(fresh, () -> "No values after namespace moved: " + log());
        } finally {
            r.shutdown();
        }
    }

    @Test
    void closingFencesAnInProgressCallbackAndItsDiagnostics() throws Exception {
        start(false, "None");
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        ConnectionDiagnostics report = new ConnectionDiagnostics();
        ExecutorService worker = Executors.newSingleThreadExecutor();
        OpcConnection connection =
                new OpcConnection(
                        settings("None", "None", OpcSecurity.ANONYMOUS, URI_NODE),
                        (tag, value) -> {
                            if (((Number) value.getValue().getValue()).doubleValue() == 99) {
                                entered.countDown();
                                try {
                                    assertTrue(release.await(5, TimeUnit.SECONDS));
                                } catch (InterruptedException e) {
                                    Thread.currentThread().interrupt();
                                }
                            }
                        },
                        () -> {},
                        () -> {},
                        new ConnectionAttempt(),
                        report);
        try {
            write(99, 0);
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            CountDownLatch closing = new CountDownLatch(1);
            Future<?> closed =
                    worker.submit(
                            () -> {
                                closing.countDown();
                                connection.close();
                            });
            assertTrue(closing.await(1, TimeUnit.SECONDS));
            assertThrows(TimeoutException.class, () -> closed.get(100, TimeUnit.MILLISECONDS));
            release.countDown();
            closed.get(3, TimeUnit.SECONDS);
            assertEquals(false, report.snapshot().get("sessionActive"));
            long received = (Long) report.snapshot().get("notificationsReceived");
            write(100, 0);
            Thread.sleep(300);
            assertEquals(received, report.snapshot().get("notificationsReceived"));
        } finally {
            release.countDown();
            connection.close();
            worker.shutdownNow();
        }
    }

    @Test
    void connectsWithAnUnadvertisedTwoItemLimit() throws Exception {
        Files.createFile(dir.resolve("unadvertised-limit.flag"));
        start(false, "None");
        SubscribeOpcUa processor = new SubscribeOpcUa();
        TestRunner runner = processor(processor);
        runner.setProperty("other", "nsu=" + NS + ";s=NoRange");
        runner.setProperty("range", "nsu=" + NS + ";s=Value.EURange");
        ConfigVerificationResult result =
                processor.verify(runner.getProcessContext(), null, Collections.emptyMap()).get(0);
        assertEquals(
                ConfigVerificationResult.Outcome.SUCCESSFUL,
                result.getOutcome(),
                result.getExplanation());
    }
}
