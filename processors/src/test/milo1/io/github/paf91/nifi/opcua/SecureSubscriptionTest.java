/* SPDX-License-Identifier: Apache-2.0 */
package io.github.paf91.nifi.opcua;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.*;

import org.apache.nifi.util.*;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.openssl.jcajce.JcaPEMWriter;
import org.eclipse.milo.opcua.sdk.server.EndpointConfig;
import org.eclipse.milo.opcua.sdk.server.OpcUaServer;
import org.eclipse.milo.opcua.sdk.server.OpcUaServerConfig;
import org.eclipse.milo.opcua.sdk.server.identity.UsernameIdentityValidator;
import org.eclipse.milo.opcua.stack.core.security.*;
import org.eclipse.milo.opcua.stack.core.security.DefaultServerCertificateValidator;
import org.eclipse.milo.opcua.stack.core.types.builtin.*;
import org.eclipse.milo.opcua.stack.core.types.enumerated.*;
import org.eclipse.milo.opcua.stack.core.types.structured.*;
import org.eclipse.milo.opcua.stack.core.util.SelfSignedCertificateBuilder;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.nio.file.*;
import java.security.*;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;

class SecureSubscriptionTest {
    @TempDir Path dir;
    OpcUaServer server;
    FileBasedTrustListManager serverTrust;
    X509Certificate clientCert, serverCert;
    KeyPair clientKey;
    String endpoint;

    @BeforeEach
    void startServer() throws Exception {
        startServer(null);
    }

    void startServer(String usernameTokenPolicy) throws Exception {
        Security.addProvider(new BouncyCastleProvider());
        KeyPairGenerator g = KeyPairGenerator.getInstance("RSA");
        g.initialize(2048);
        KeyPair serverKey = g.generateKeyPair();
        startServer(usernameTokenPolicy, serverKey, certificate(serverKey, "urn:test:server"));
    }

    void startServer(
            String usernameTokenPolicy, KeyPair serverKey, X509Certificate serverCertificate)
            throws Exception {
        Security.addProvider(new BouncyCastleProvider());
        KeyPairGenerator g = KeyPairGenerator.getInstance("RSA");
        g.initialize(2048);
        clientKey = g.generateKeyPair();
        clientCert = certificate(clientKey, "urn:test:client");
        serverCert = serverCertificate;
        Files.write(dir.resolve("client.der"), clientCert.getEncoded());
        try (JcaPEMWriter w =
                new JcaPEMWriter(Files.newBufferedWriter(dir.resolve("client.pem")))) {
            w.writeObject(clientKey.getPrivate());
        }
        Files.createDirectories(dir.resolve("pki/trusted/certs"));
        Files.write(dir.resolve("pki/trusted/certs/server.der"), serverCert.getEncoded());
        serverTrust = FileBasedTrustListManager.createAndInitialize(dir.resolve("server-pki"));
        serverTrust.addTrustedCertificate(clientCert);
        int port;
        try (java.net.ServerSocket socket = new java.net.ServerSocket(0)) {
            port = socket.getLocalPort();
        }
        endpoint = "opc.tcp://localhost:" + port + "/test";
        EndpointConfig.Builder builder =
                EndpointConfig.newBuilder()
                        .setBindAddress("127.0.0.1")
                        .setHostname("localhost")
                        .setBindPort(port)
                        .setPath("/test")
                        .setCertificate(serverCert)
                        .addTokenPolicy(OpcUaServerConfig.USER_TOKEN_POLICY_ANONYMOUS);
        Set<EndpointConfig> endpoints = new LinkedHashSet<>();
        for (String policy :
                Arrays.asList(
                        "None",
                        "Basic128Rsa15",
                        "Basic256",
                        "Basic256Sha256",
                        "Aes128_Sha256_RsaOaep",
                        "Aes256_Sha256_RsaPss")) {
            for (MessageSecurityMode mode :
                    Arrays.asList(
                            MessageSecurityMode.None,
                            MessageSecurityMode.Sign,
                            MessageSecurityMode.SignAndEncrypt)) {
                if ("None".equals(policy) != (mode == MessageSecurityMode.None)) continue;
                endpoints.add(
                        builder.copy()
                                .setSecurityPolicy(SecurityPolicy.valueOf(policy))
                                .setSecurityMode(mode)
                                // Milo 0.6's server validator needs an explicit token policy.
                                .addTokenPolicy(
                                        new UserTokenPolicy(
                                                "username",
                                                UserTokenType.UserName,
                                                null,
                                                null,
                                                usernameTokenPolicy == null
                                                        ? SecurityPolicy.valueOf(policy).getUri()
                                                        : usernameTokenPolicy))
                                .build());
            }
        }
        MemoryCertificateQuarantine quarantine = new MemoryCertificateQuarantine();
        DefaultApplicationGroup group =
                DefaultApplicationGroup.createAndInitialize(
                        serverTrust,
                        new MemoryCertificateStore(),
                        new RsaSha256CertificateFactory() {
                            @Override
                            protected KeyPair createRsaSha256KeyPair() {
                                return serverKey;
                            }

                            @Override
                            protected X509Certificate[] createRsaSha256CertificateChain(
                                    KeyPair key) {
                                return new X509Certificate[] {serverCert};
                            }
                        },
                        new DefaultServerCertificateValidator(serverTrust, quarantine));
        server =
                new OpcUaServer(
                        OpcUaServerConfig.builder()
                                .setApplicationUri("urn:test:server")
                                .setApplicationName(LocalizedText.english("Test"))
                                .setEndpoints(endpoints)
                                .setCertificateManager(
                                        new DefaultCertificateManager(quarantine, group))
                                .setIdentityValidator(
                                        new org.eclipse.milo.opcua.sdk.server.identity
                                                .CompositeValidator(
                                                org.eclipse.milo.opcua.sdk.server.identity
                                                        .AnonymousIdentityValidator.INSTANCE,
                                                new UsernameIdentityValidator(
                                                        c ->
                                                                "user".equals(c.getUsername())
                                                                        && "test-password"
                                                                                .equals(
                                                                                        c
                                                                                                .getPassword()))))
                                .setBuildInfo(
                                        new BuildInfo(
                                                "urn:test",
                                                "test",
                                                "test",
                                                "1",
                                                "1",
                                                DateTime.now()))
                                .setProductUri("urn:test")
                                .build(),
                        profile ->
                                new org.eclipse.milo.opcua.stack.transport.server.tcp
                                        .OpcTcpServerTransport(
                                        org.eclipse.milo.opcua.stack.transport.server.tcp
                                                .OpcTcpServerTransportConfig.newBuilder()
                                                .build()));
        server.startup().get(10, TimeUnit.SECONDS);
    }

    static X509Certificate certificate(KeyPair pair, String uri) throws Exception {
        return new SelfSignedCertificateBuilder(pair)
                .setCommonName("Test OPC UA")
                .setCountryCode("US")
                .setApplicationUri(uri)
                .addDnsName("localhost")
                .addIpAddress("127.0.0.1")
                .build();
    }

    OpcSettings settings(Path trust, String password, String node) {
        return new OpcSettings(
                endpoint,
                "urn:test:client",
                dir.resolve("client.der"),
                dir.resolve("client.pem"),
                "",
                trust,
                "user",
                password,
                100,
                5000,
                Collections.singletonList(TagDefinition.parse("time", node, 100, 10)));
    }

    @AfterEach
    void closeServer() throws Exception {
        if (server != null) server.shutdown().get(10, TimeUnit.SECONDS);
        if (serverTrust != null) serverTrust.close();
    }

    static java.util.stream.Stream<Object[]> configurations() {
        return OpcSecurityTest.configurations()
                .filter(c -> "None".equals(c[0]) == "None".equals(c[1]));
    }

    @ParameterizedTest(name = "live {0} / {1} / {2}")
    @MethodSource("configurations")
    void receivesDataUsingConfiguredSecurityAndAuthentication(
            String policy, String mode, String authentication) throws Exception {
        OpcSecurity security = new OpcSecurity(policy, mode, authentication);
        OpcSettings settings =
                new OpcSettings(
                        endpoint,
                        null,
                        security.requiresClientIdentity() ? dir.resolve("client.der") : null,
                        security.requiresClientIdentity() ? dir.resolve("client.pem") : null,
                        "",
                        security.requiresClientIdentity() ? dir.resolve("pki") : null,
                        security.usernameAuthentication() ? "user" : null,
                        security.usernameAuthentication() ? "test-password" : null,
                        100,
                        5000,
                        Collections.singletonList(
                                TagDefinition.parse("time", "ns=0;i=2258", 100, 10)),
                        security);
        BlockingQueue<DataValue> values = new LinkedBlockingQueue<>();
        try (OpcConnection connection =
                new OpcConnection(
                        settings, (tag, value) -> values.offer(value), () -> {}, () -> {})) {
            DataValue first = values.poll(10, TimeUnit.SECONDS);
            assertNotNull(first);
            assertTrue(first.getStatusCode().isGood());
            assertNotNull(values.poll(10, TimeUnit.SECONDS));
        }
    }

    @ParameterizedTest(name = "encrypted token on None channel: {0}")
    @org.junit.jupiter.params.provider.ValueSource(strings = {"trusted", "untrusted", "missing"})
    void encryptedUsernameOnUnsecuredChannelRequiresServerTrust(String trustState)
            throws Exception {
        closeServer();
        startServer(SecurityPolicy.Basic256Sha256.getUri());
        Path trust =
                "missing".equals(trustState)
                        ? null
                        : dir.resolve("trusted".equals(trustState) ? "pki" : "empty-pki");
        OpcSettings settings =
                new OpcSettings(
                        endpoint,
                        null,
                        null,
                        null,
                        "",
                        trust,
                        "user",
                        "test-password",
                        100,
                        5000,
                        Collections.singletonList(
                                TagDefinition.parse("time", "ns=0;i=2258", 100, 10)),
                        new OpcSecurity("None", "None", OpcSecurity.USERNAME));
        if (!"trusted".equals(trustState)) {
            assertThrows(
                    Exception.class,
                    () -> new OpcConnection(settings, (tag, value) -> {}, () -> {}, () -> {}));
            return;
        }
        BlockingQueue<DataValue> values = new LinkedBlockingQueue<>();
        try (OpcConnection connection =
                new OpcConnection(
                        settings, (tag, value) -> values.offer(value), () -> {}, () -> {})) {
            DataValue first = values.poll(10, TimeUnit.SECONDS);
            assertNotNull(first);
            assertTrue(first.getStatusCode().isGood());
            assertNotNull(values.poll(10, TimeUnit.SECONDS));
        }
    }

    @Test
    void unsecuredChannelStillRejectsWrongPassword() {
        OpcSettings settings =
                new OpcSettings(
                        endpoint,
                        null,
                        null,
                        null,
                        "",
                        null,
                        "user",
                        "incorrect",
                        100,
                        5000,
                        Collections.singletonList(
                                TagDefinition.parse("time", "ns=0;i=2258", 100, 10)),
                        new OpcSecurity("None", "None", OpcSecurity.USERNAME));
        assertThrows(
                Exception.class,
                () -> new OpcConnection(settings, (tag, value) -> {}, () -> {}, () -> {}));
    }

    @Test
    void receivesEncryptedAuthenticatedDataChanges() throws Exception {
        BlockingQueue<DataValue> values = new LinkedBlockingQueue<>();
        try (OpcConnection connection =
                new OpcConnection(
                        settings(dir.resolve("pki"), "test-password", "ns=0;i=2258"),
                        (t, v) -> values.offer(v),
                        () -> {},
                        () -> {})) {
            DataValue first = values.poll(10, TimeUnit.SECONDS);
            assertNotNull(first);
            assertTrue(first.getStatusCode().isGood());
            assertNotNull(values.poll(10, TimeUnit.SECONDS));
        }
    }

    @Test
    void rejectsUntrustedServer() {
        assertThrows(
                Exception.class,
                () ->
                        new OpcConnection(
                                settings(dir.resolve("empty-pki"), "test-password", "ns=0;i=2258"),
                                (t, v) -> {},
                                () -> {},
                                () -> {}));
    }

    @Test
    void rejectsWrongPassword() {
        assertThrows(
                Exception.class,
                () ->
                        new OpcConnection(
                                settings(dir.resolve("pki"), "incorrect", "ns=0;i=2258"),
                                (t, v) -> {},
                                () -> {},
                                () -> {}));
    }

    @Test
    void rejectsUnknownMonitoredNode() {
        assertThrows(
                Exception.class,
                () ->
                        new OpcConnection(
                                settings(
                                        dir.resolve("pki"),
                                        "test-password",
                                        "ns=2;s=does-not-exist"),
                                (t, v) -> {},
                                () -> {},
                                () -> {}));
    }

    @Test
    void logsRejectedNodeAndStatusFromRealSecureSubscription() {
        TestRunner runner = ProcessorTest.configured(new SubscribeOpcUa());
        runner.setProperty(SubscribeOpcUa.ENDPOINT, endpoint);
        runner.setProperty(SubscribeOpcUa.CERTIFICATE, dir.resolve("client.der").toString());
        runner.setProperty(SubscribeOpcUa.PRIVATE_KEY, dir.resolve("client.pem").toString());
        runner.setProperty(SubscribeOpcUa.PKI, dir.resolve("pki").toString());
        runner.setProperty(SubscribeOpcUa.PASSWORD, "test-password");
        runner.setProperty("DemoSwitch", "ns=2;s=does-not-exist");
        try {
            runner.run(1, false, true);
            assertEquals(1, runner.getLogger().getErrorMessages().size());
            LogMessage failure = runner.getLogger().getErrorMessages().get(0);
            String message = failure.toString();
            assertTrue(message.contains("does-not-exist"), message);
            assertTrue(message.contains("Bad_NodeIdUnknown"), message);
            assertTrue(message.contains("0x80340000"), message);
            assertFalse(message.contains("test-password"), message);
            assertNull(failure.getThrowable());
        } finally {
            runner.shutdown();
        }
    }

    @Test
    void rejectsMismatchedKeyAndApplicationUri() throws Exception {
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        ClientIdentity.load(
                                dir.resolve("client.der"),
                                dir.resolve("client.pem"),
                                new char[0],
                                "urn:wrong"));
        KeyPairGenerator g = KeyPairGenerator.getInstance("RSA");
        g.initialize(2048);
        Files.write(dir.resolve("wrong.der"), g.generateKeyPair().getPrivate().getEncoded());
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        ClientIdentity.load(
                                dir.resolve("client.der"),
                                dir.resolve("wrong.der"),
                                new char[0],
                                "urn:test:client"));
    }

    @Test
    void neverSelectsInsecureOrSignOnlyEndpoints() {
        UserTokenPolicy[] tokens = {OpcUaServerConfig.USER_TOKEN_POLICY_USERNAME};
        for (MessageSecurityMode mode :
                Arrays.asList(MessageSecurityMode.None, MessageSecurityMode.Sign)) {
            EndpointDescription e =
                    new EndpointDescription(
                            endpoint,
                            null,
                            ByteString.NULL_VALUE,
                            mode,
                            SecurityPolicy.Basic256Sha256.getUri(),
                            tokens,
                            "",
                            null);
            assertFalse(OpcSecurity.DEFAULT.matches(e));
        }
    }

    @Test
    void emitsFlowFilesFromRealSecureSubscription() throws Exception {
        org.apache.nifi.util.TestRunner runner = ProcessorTest.configured(new SubscribeOpcUa());
        runner.setProperty(SubscribeOpcUa.ENDPOINT, endpoint);
        runner.setProperty(SubscribeOpcUa.CERTIFICATE, dir.resolve("client.der").toString());
        runner.setProperty(SubscribeOpcUa.PRIVATE_KEY, dir.resolve("client.pem").toString());
        runner.setProperty(SubscribeOpcUa.PKI, dir.resolve("pki").toString());
        runner.setProperty(SubscribeOpcUa.PASSWORD, "test-password");
        runner.setProperty(SubscribeOpcUa.PUBLISHING, "100");
        runner.removeProperty("DemoSwitch");
        runner.setProperty("serverTime", "ns=0;i=2258");
        try {
            runner.run(1, false, true);
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (runner.getFlowFilesForRelationship(SubscribeOpcUa.SUCCESS).isEmpty()
                    && System.nanoTime() < deadline) {
                Thread.sleep(100);
                runner.run(1, false, false);
            }
            assertFalse(runner.getFlowFilesForRelationship(SubscribeOpcUa.SUCCESS).isEmpty());
            assertTrue(
                    new String(
                                    runner.getContentAsByteArray(
                                            runner.getFlowFilesForRelationship(
                                                            SubscribeOpcUa.SUCCESS)
                                                    .get(0)),
                                    java.nio.charset.StandardCharsets.UTF_8)
                            .contains("serverTime"));
        } finally {
            runner.shutdown();
        }
    }

    @Test
    void bulkListsProduceFlowFilesWithCertificateDerivedIdentity() throws Exception {
        for (String tags :
                Arrays.asList(
                        "serverTime | ns=0;i=2258\nserverState | ns=0;i=2259",
                        "[{\"alias\":\"serverTime\",\"nodeId\":\"ns=0;i=2258\",\"samplingIntervalMs\":100},{\"alias\":\"serverState\",\"nodeId\":\"ns=0;i=2259\"}]")) {
            org.apache.nifi.util.TestRunner runner = ProcessorTest.configured(new SubscribeOpcUa());
            runner.setProperty(SubscribeOpcUa.ENDPOINT, endpoint);
            runner.setProperty(SubscribeOpcUa.CERTIFICATE, dir.resolve("client.der").toString());
            runner.setProperty(SubscribeOpcUa.PRIVATE_KEY, dir.resolve("client.pem").toString());
            runner.setProperty(SubscribeOpcUa.PKI, dir.resolve("pki").toString());
            runner.setProperty(SubscribeOpcUa.PASSWORD, "test-password");
            runner.setProperty(SubscribeOpcUa.PUBLISHING, "100");
            runner.removeProperty(SubscribeOpcUa.APPLICATION_URI);
            runner.removeProperty("DemoSwitch");
            runner.setProperty(SubscribeOpcUa.TAG_LIST, tags);
            Set<String> observed = new HashSet<>();
            try {
                runner.assertValid();
                runner.run(1, false, true);
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
                while (observed.size() < 2 && System.nanoTime() < deadline) {
                    Thread.sleep(100);
                    runner.run(1, false, false);
                    for (org.apache.nifi.util.MockFlowFile flow :
                            runner.getFlowFilesForRelationship(SubscribeOpcUa.SUCCESS)) {
                        com.fasterxml.jackson.databind.JsonNode records =
                                new com.fasterxml.jackson.databind.ObjectMapper()
                                        .readTree(runner.getContentAsByteArray(flow));
                        for (com.fasterxml.jackson.databind.JsonNode record : records)
                            observed.add(record.get("tag").asText());
                    }
                }
                assertEquals(new HashSet<>(Arrays.asList("serverTime", "serverState")), observed);
            } finally {
                runner.shutdown();
            }
        }
    }

    @Test
    void discoveryTimeoutClosesTheSocket() throws Exception {
        try (SilentEndpoint silent = new SilentEndpoint()) {
            OpcSettings s =
                    new OpcSettings(
                            silent.url(),
                            "urn:test:client",
                            dir.resolve("client.der"),
                            dir.resolve("client.pem"),
                            "",
                            dir.resolve("pki"),
                            "user",
                            "test-password",
                            100,
                            200,
                            Collections.singletonList(
                                    TagDefinition.parse("time", "ns=0;i=2258", 100, 10)));
            long started = System.nanoTime();
            assertThrows(
                    Exception.class, () -> new OpcConnection(s, (t, v) -> {}, () -> {}, () -> {}));
            assertTrue(System.nanoTime() - started < TimeUnit.SECONDS.toNanos(2));
            assertTrue(silent.accepted.await(1, TimeUnit.SECONDS));
            assertTrue(
                    silent.disconnected.await(2, TimeUnit.SECONDS),
                    "Discovery socket leaked after timeout");
        }
    }

    @Test
    void cancellationDuringDiscoveryClosesTheSocket() throws Exception {
        try (SilentEndpoint silent = new SilentEndpoint()) {
            OpcSettings s =
                    new OpcSettings(
                            silent.url(),
                            "urn:test:client",
                            dir.resolve("client.der"),
                            dir.resolve("client.pem"),
                            "",
                            dir.resolve("pki"),
                            "user",
                            "test-password",
                            100,
                            300000,
                            Collections.singletonList(
                                    TagDefinition.parse("time", "ns=0;i=2258", 100, 10)));
            ConnectionAttempt attempt = new ConnectionAttempt();
            ExecutorService worker = Executors.newSingleThreadExecutor();
            try {
                Future<?> pending =
                        worker.submit(
                                () ->
                                        assertThrows(
                                                Exception.class,
                                                () ->
                                                        new OpcConnection(
                                                                s,
                                                                (t, v) -> {},
                                                                () -> {},
                                                                () -> {},
                                                                attempt)));
                assertTrue(silent.accepted.await(2, TimeUnit.SECONDS));
                attempt.cancel();
                pending.get(2, TimeUnit.SECONDS);
                assertTrue(
                        silent.disconnected.await(2, TimeUnit.SECONDS),
                        "Discovery socket leaked after cancellation");
            } finally {
                worker.shutdownNow();
            }
        }
    }

    @Test
    void recoversAfterServerRestart() throws Exception {
        SubscribeOpcUa processor = new SubscribeOpcUa();
        TestRunner r = ProcessorTest.configured(processor);
        r.setProperty(SubscribeOpcUa.ENDPOINT, endpoint);
        r.setProperty(SubscribeOpcUa.CERTIFICATE, dir.resolve("client.der").toString());
        r.setProperty(SubscribeOpcUa.PRIVATE_KEY, dir.resolve("client.pem").toString());
        r.setProperty(SubscribeOpcUa.PKI, dir.resolve("pki").toString());
        r.setProperty(SubscribeOpcUa.PASSWORD, "test-password");
        r.setProperty(SubscribeOpcUa.PUBLISHING, "100");
        r.setProperty(SubscribeOpcUa.RETRY, "500");
        r.setProperty(SubscribeOpcUa.TIMEOUT, "1000");
        r.removeProperty("DemoSwitch");
        r.setProperty("time", "ns=0;i=2258");
        try {
            r.run(1, false, true);
            Thread.sleep(300);
            r.run(1, false, false);
            server.shutdown().get(10, TimeUnit.SECONDS);
            Thread.sleep(1000);
            server =
                    new org.eclipse.milo.opcua.sdk.server.OpcUaServer(
                            server.getConfig(),
                            profile ->
                                    new org.eclipse.milo.opcua.stack.transport.server.tcp
                                            .OpcTcpServerTransport(
                                            org.eclipse.milo.opcua.stack.transport.server.tcp
                                                    .OpcTcpServerTransportConfig.newBuilder()
                                                    .build()));
            Instant restarted = Instant.now();
            server.startup().get(10, TimeUnit.SECONDS);
            boolean fresh = false;
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
            while (!fresh && System.nanoTime() < deadline) {
                r.clearTransferState();
                r.run(1, false, false);
                for (MockFlowFile f : r.getFlowFilesForRelationship(SubscribeOpcUa.SUCCESS)) {
                    for (JsonNode record :
                            new ObjectMapper().readTree(r.getContentAsByteArray(f))) {
                        if (record.get("value").isTextual()
                                && Instant.parse(record.get("value").asText()).isAfter(restarted))
                            fresh = true;
                    }
                }
                if (!fresh) Thread.sleep(100);
            }
            assertTrue(fresh, "No fresh server-time values after server restart");

        } finally {
            r.shutdown();
        }
    }

    @Test
    void closingOneConnectionDoesNotStopAnother() throws Exception {
        BlockingQueue<DataValue> first = new LinkedBlockingQueue<>(),
                second = new LinkedBlockingQueue<>();
        try (OpcConnection a =
                        new OpcConnection(
                                settings(dir.resolve("pki"), "test-password", "ns=0;i=2258"),
                                (t, v) -> first.offer(v),
                                () -> {},
                                () -> {});
                OpcConnection b =
                        new OpcConnection(
                                settings(dir.resolve("pki"), "test-password", "ns=0;i=2258"),
                                (t, v) -> second.offer(v),
                                () -> {},
                                () -> {})) {
            assertNotNull(first.poll(5, TimeUnit.SECONDS));
            assertNotNull(second.poll(5, TimeUnit.SECONDS));
            a.close();
            second.clear();
            assertNotNull(
                    second.poll(5, TimeUnit.SECONDS),
                    "Closing one connection stopped another processor's transport");
        }
    }

    @Test
    void resolvesNamespaceUriAndReportsNegotiatedSettings() throws Exception {
        ConnectionDiagnostics report = new ConnectionDiagnostics();
        BlockingQueue<TagDefinition> tags = new LinkedBlockingQueue<>();
        try (OpcConnection connection =
                new OpcConnection(
                        settings(
                                dir.resolve("pki"),
                                "test-password",
                                "nsu=http://opcfoundation.org/UA/;i=2258"),
                        (tag, value) -> {
                            if (value.getStatusCode().isGood()) tags.offer(tag);
                        },
                        () -> {},
                        () -> {},
                        new ConnectionAttempt(),
                        report)) {
            TagDefinition tag = tags.poll(5, TimeUnit.SECONDS);
            assertNotNull(tag);
            assertEquals(new NodeId(0, 2258), tag.nodeId());
            assertEquals("http://opcfoundation.org/UA/", tag.address().namespaceUri());
            assertEquals("Subscribed", report.snapshot().get("stage"));
            assertNotNull(report.snapshot().get("revisedPublishingIntervalMs"));
            Map<?, ?> item = (Map<?, ?>) ((List<?>) report.snapshot().get("monitoredItems")).get(0);
            assertEquals(tag.nodeId().toParseableString(), item.get("resolvedNodeId"));
            assertTrue(((Number) item.get("revisedQueueSize")).intValue() > 0);
        }
        assertEquals(false, report.snapshot().get("sessionActive"));
    }

    @Test
    void missingNamespaceFailsBeforeCreatingSubscription() throws Exception {
        Exception failure =
                assertThrows(
                        Exception.class,
                        () ->
                                new OpcConnection(
                                        settings(
                                                dir.resolve("pki"),
                                                "test-password",
                                                "nsu=urn:no:such:namespace;i=2258"),
                                        (tag, value) -> fail("No callback expected"),
                                        () -> {},
                                        () -> {}));
        assertTrue(failure.toString().contains("does not contain"), failure.toString());
        assertTrue(server.getSubscriptions().isEmpty());
    }
}
