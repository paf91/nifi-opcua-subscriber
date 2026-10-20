/* SPDX-License-Identifier: Apache-2.0 */
package io.github.paf91.nifi.opcua.demo;

import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.eclipse.milo.opcua.sdk.core.AccessLevel;
import org.eclipse.milo.opcua.sdk.server.OpcUaServer;
import org.eclipse.milo.opcua.sdk.server.api.*;
import org.eclipse.milo.opcua.sdk.server.api.config.OpcUaServerConfig;
import org.eclipse.milo.opcua.sdk.server.identity.UsernameIdentityValidator;
import org.eclipse.milo.opcua.sdk.server.nodes.UaVariableNode;
import org.eclipse.milo.opcua.sdk.server.util.SubscriptionModel;
import org.eclipse.milo.opcua.stack.core.Identifiers;
import org.eclipse.milo.opcua.stack.core.security.*;
import org.eclipse.milo.opcua.stack.core.types.builtin.*;
import org.eclipse.milo.opcua.stack.core.types.enumerated.*;
import org.eclipse.milo.opcua.stack.core.types.structured.BuildInfo;
import org.eclipse.milo.opcua.stack.core.util.SelfSignedCertificateBuilder;
import org.eclipse.milo.opcua.stack.server.EndpointConfiguration;
import org.eclipse.milo.opcua.stack.server.security.DefaultServerCertificateValidator;

import java.nio.file.*;
import java.security.*;
import java.security.cert.*;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.*;
import java.util.concurrent.*;

/** Local-only demonstration. No production credentials or production server connection. */
public final class DemoServer {
    static final class Identity {
        final KeyPair key;
        final X509Certificate cert;

        Identity(KeyPair key, X509Certificate cert) {
            this.key = key;
            this.cert = cert;
        }
    }

    static Identity identity(Path dir, String name, String uri) throws Exception {
        Path certFile = dir.resolve(name + ".der"), keyFile = dir.resolve(name + "-key.der");
        if (Files.exists(certFile) && Files.exists(keyFile)) {
            X509Certificate cert;
            try (java.io.InputStream in = Files.newInputStream(certFile)) {
                cert =
                        (X509Certificate)
                                CertificateFactory.getInstance("X.509").generateCertificate(in);
            }
            PrivateKey key =
                    KeyFactory.getInstance("RSA")
                            .generatePrivate(new PKCS8EncodedKeySpec(Files.readAllBytes(keyFile)));
            return new Identity(new KeyPair(cert.getPublicKey(), key), cert);
        }
        KeyPairGenerator g = KeyPairGenerator.getInstance("RSA");
        g.initialize(2048);
        KeyPair key = g.generateKeyPair();
        X509Certificate cert =
                new SelfSignedCertificateBuilder(key)
                        .setCommonName("Local OPC UA demo")
                        .setCountryCode("US")
                        .setApplicationUri(uri)
                        .addDnsName("opcua")
                        .addDnsName("localhost")
                        .addIpAddress("127.0.0.1")
                        .build();
        Files.write(certFile, cert.getEncoded());
        Files.write(keyFile, key.getPrivate().getEncoded());
        try {
            Files.setPosixFilePermissions(
                    keyFile, java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
        } catch (UnsupportedOperationException ignored) {
        }
        return new Identity(key, cert);
    }

    public static void main(String[] args) throws Exception {
        Security.addProvider(new BouncyCastleProvider());
        Path security = Paths.get(args.length == 0 ? "/security" : args[0]);
        Files.createDirectories(security);
        Identity client = identity(security, "client", "urn:nifi:opcua:demo"),
                serverIdentity = identity(security, "server", "urn:opcua:demo:server");
        Path certs = security.resolve("nifi-pki/trusted/certs");
        Files.createDirectories(certs);
        Files.write(certs.resolve("server.der"), serverIdentity.cert.getEncoded());
        DefaultTrustListManager trust =
                new DefaultTrustListManager(security.resolve("server-pki").toFile());
        trust.addTrustedCertificate(client.cert);
        EndpointConfiguration.Builder base =
                EndpointConfiguration.newBuilder()
                        .setBindAddress("0.0.0.0")
                        .setHostname("opcua")
                        .setBindPort(4840)
                        .setPath("/demo")
                        .setCertificate(serverIdentity.cert)
                        .addTokenPolicy(OpcUaServerConfig.USER_TOKEN_POLICY_USERNAME);
        Set<EndpointConfiguration> endpoints = new HashSet<>();
        endpoints.add(
                base.copy()
                        .setSecurityPolicy(SecurityPolicy.Basic256Sha256)
                        .setSecurityMode(MessageSecurityMode.SignAndEncrypt)
                        .build());
        endpoints.add(
                base.copy()
                        .setPath("/demo/discovery")
                        .setSecurityPolicy(SecurityPolicy.None)
                        .setSecurityMode(MessageSecurityMode.None)
                        .build());
        OpcUaServer server =
                new OpcUaServer(
                        OpcUaServerConfig.builder()
                                .setApplicationUri("urn:opcua:demo:server")
                                .setApplicationName(LocalizedText.english("Local OPC UA demo"))
                                .setEndpoints(endpoints)
                                .setCertificateManager(
                                        new DefaultCertificateManager(
                                                serverIdentity.key, serverIdentity.cert))
                                .setCertificateValidator(
                                        new DefaultServerCertificateValidator(trust))
                                .setIdentityValidator(
                                        new UsernameIdentityValidator(
                                                false,
                                                c ->
                                                        "demo".equals(c.getUsername())
                                                                && "demo-password"
                                                                        .equals(c.getPassword())))
                                .setBuildInfo(
                                        new BuildInfo(
                                                "urn:demo",
                                                "demo",
                                                "demo",
                                                "1",
                                                "1",
                                                DateTime.now()))
                                .setProductUri("urn:demo")
                                .build());
        int loadTags = Integer.parseInt(System.getenv().getOrDefault("DEMO_LOAD_TAGS", "0"));
        if (loadTags < 0 || loadTags > 10000)
            throw new IllegalArgumentException("DEMO_LOAD_TAGS must be 0..10000");
        DemoNamespace namespace = new DemoNamespace(server, loadTags, security);
        namespace.startup();
        server.startup().get();
        Runtime.getRuntime()
                .addShutdownHook(
                        new Thread(
                                () -> {
                                    namespace.shutdown();
                                    try {
                                        server.shutdown().get();
                                        trust.close();
                                    } catch (Exception ignored) {
                                    }
                                }));
        System.out.println(
                "Demo endpoint opc.tcp://opcua:4840/demo; DemoSwitch = ns="
                        + namespace.getNamespaceIndex()
                        + ";s=Demo.Boolean");
        new CountDownLatch(1).await();
    }

    static final class DemoNamespace extends ManagedNamespaceWithLifecycle {
        final SubscriptionModel subscriptions;
        final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor();
        final UaVariableNode tag;
        final List<UaVariableNode> loadNodes = new java.util.ArrayList<>();
        long tick;
        boolean state;

        DemoNamespace(OpcUaServer server, int loadTags, Path security) {
            super(server, "urn:opcua:demo:tags");
            subscriptions = new SubscriptionModel(server, this);
            getLifecycleManager().addLifecycle(subscriptions);
            tag =
                    UaVariableNode.builder(getNodeContext())
                            .setNodeId(newNodeId("Demo.Boolean"))
                            .setBrowseName(newQualifiedName("DemoSwitch"))
                            .setDisplayName(LocalizedText.english("DemoSwitch demo signal"))
                            .setDataType(Identifiers.Boolean)
                            .setTypeDefinition(Identifiers.BaseDataVariableType)
                            .setAccessLevel(AccessLevel.CurrentRead)
                            .setUserAccessLevel(AccessLevel.CurrentRead)
                            .setValue(new DataValue(new Variant(false)))
                            .build();
            getNodeManager().addNode(tag);
            for (int i = 0; i < loadTags; i++) {
                UaVariableNode node =
                        UaVariableNode.builder(getNodeContext())
                                .setNodeId(newNodeId("Load.Tag" + i))
                                .setBrowseName(newQualifiedName("LoadTag" + i))
                                .setDisplayName(LocalizedText.english("Synthetic load tag " + i))
                                .setDataType(Identifiers.Double)
                                .setTypeDefinition(Identifiers.BaseDataVariableType)
                                .setAccessLevel(AccessLevel.CurrentRead)
                                .setUserAccessLevel(AccessLevel.CurrentRead)
                                .setValue(new DataValue(new Variant(0.0)))
                                .build();
                getNodeManager().addNode(node);
                loadNodes.add(node);
            }
            getLifecycleManager()
                    .addStartupTask(
                            () ->
                                    timer.scheduleAtFixedRate(
                                            () -> {
                                                state = !state;
                                                tick++;
                                                for (UaVariableNode node : loadNodes) {
                                                    node.setValue(
                                                            new DataValue(
                                                                    new Variant((double) tick),
                                                                    StatusCode.GOOD,
                                                                    DateTime.now(),
                                                                    DateTime.now()));
                                                }
                                                if (loadTags > 0) {
                                                    String metrics =
                                                            "{\"tick\":"
                                                                    + tick
                                                                    + ",\"sessions\":"
                                                                    + server.getSessionManager()
                                                                            .getAllSessions()
                                                                            .size()
                                                                    + ",\"subscriptions\":"
                                                                    + server.getSubscriptions()
                                                                            .size()
                                                                    + ",\"monitoredItems\":"
                                                                    + server.getMonitoredItemCount()
                                                                            .get()
                                                                    + "}";
                                                    try {
                                                        Path temp = security.resolve("metrics.tmp");
                                                        Files.write(
                                                                temp,
                                                                metrics.getBytes(
                                                                        java.nio.charset
                                                                                .StandardCharsets
                                                                                .UTF_8));
                                                        Files.move(
                                                                temp,
                                                                security.resolve("metrics.json"),
                                                                java.nio.file.StandardCopyOption
                                                                        .REPLACE_EXISTING);
                                                    } catch (java.io.IOException e) {
                                                        System.err.println(
                                                                "Cannot write demo metrics: "
                                                                        + e.getClass()
                                                                                .getSimpleName());
                                                    }
                                                }
                                                tag.setValue(
                                                        new DataValue(
                                                                new Variant(state),
                                                                StatusCode.GOOD,
                                                                DateTime.now(),
                                                                DateTime.now()));
                                            },
                                            1,
                                            1,
                                            TimeUnit.SECONDS));
            getLifecycleManager().addShutdownTask(() -> timer.shutdownNow());
        }

        @Override
        public void onDataItemsCreated(List<DataItem> items) {
            subscriptions.onDataItemsCreated(items);
        }

        @Override
        public void onDataItemsModified(List<DataItem> items) {
            subscriptions.onDataItemsModified(items);
        }

        @Override
        public void onDataItemsDeleted(List<DataItem> items) {
            subscriptions.onDataItemsDeleted(items);
        }

        @Override
        public void onMonitoringModeChanged(List<MonitoredItem> items) {
            subscriptions.onMonitoringModeChanged(items);
        }
    }
}
