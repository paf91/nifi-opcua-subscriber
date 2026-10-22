/* SPDX-License-Identifier: Apache-2.0 */
package io.github.paf91.nifi.opcua;

import static org.eclipse.milo.opcua.stack.core.types.builtin.unsigned.Unsigned.uint;

import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.util.concurrent.DefaultThreadFactory;

import org.eclipse.milo.opcua.sdk.client.OpcUaClient;
import org.eclipse.milo.opcua.sdk.client.api.config.OpcUaClientConfig;
import org.eclipse.milo.opcua.sdk.client.api.config.OpcUaClientConfigBuilder;
import org.eclipse.milo.opcua.sdk.client.api.identity.AnonymousProvider;
import org.eclipse.milo.opcua.sdk.client.api.identity.UsernameProvider;
import org.eclipse.milo.opcua.sdk.client.api.subscriptions.*;
import org.eclipse.milo.opcua.stack.client.DiscoveryClient;
import org.eclipse.milo.opcua.stack.client.UaStackClient;
import org.eclipse.milo.opcua.stack.client.UaStackClientConfig;
import org.eclipse.milo.opcua.stack.client.security.ClientCertificateValidator;
import org.eclipse.milo.opcua.stack.client.security.DefaultClientCertificateValidator;
import org.eclipse.milo.opcua.stack.core.AttributeId;
import org.eclipse.milo.opcua.stack.core.Identifiers;
import org.eclipse.milo.opcua.stack.core.StatusCodes;
import org.eclipse.milo.opcua.stack.core.UaException;
import org.eclipse.milo.opcua.stack.core.security.*;
import org.eclipse.milo.opcua.stack.core.types.builtin.*;
import org.eclipse.milo.opcua.stack.core.types.builtin.unsigned.UInteger;
import org.eclipse.milo.opcua.stack.core.types.enumerated.*;
import org.eclipse.milo.opcua.stack.core.types.structured.*;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;

final class OpcConnection implements AutoCloseable {
    private OpcUaClient client;
    private NioEventLoopGroup eventLoop;
    private UaSubscription subscription;
    private DefaultTrustListManager trust;
    private volatile boolean active = true;
    private final long timeout;
    private final AtomicBoolean closed = new AtomicBoolean();
    private final ConnectionAttempt attempt;
    private final ConnectionDiagnostics diagnostics;

    OpcConnection(
            OpcSettings settings,
            BiConsumer<TagDefinition, DataValue> consumer,
            Runnable recovery,
            Runnable dataLost)
            throws Exception {
        this(settings, consumer, recovery, dataLost, new ConnectionAttempt());
    }

    OpcConnection(
            OpcSettings settings,
            BiConsumer<TagDefinition, DataValue> consumer,
            Runnable recovery,
            Runnable dataLost,
            ConnectionAttempt attempt)
            throws Exception {
        this(settings, consumer, recovery, dataLost, attempt, new ConnectionDiagnostics());
    }

    OpcConnection(
            OpcSettings settings,
            BiConsumer<TagDefinition, DataValue> consumer,
            Runnable recovery,
            Runnable dataLost,
            ConnectionAttempt attempt,
            ConnectionDiagnostics diagnostics)
            throws Exception {
        this.attempt = attempt;
        this.diagnostics = diagnostics;
        diagnostics.serverCertificateValidation(settings.serverCertificateValidation());
        timeout = settings.requestTimeout();
        try {
            attempt.check();
            diagnostics.stage("Loading client identity");
            ClientIdentity identity = ClientIdentity.load(settings);
            if (identity != null) diagnostics.certificate("clientCertificate", identity.chain()[0]);
            eventLoop =
                    new NioEventLoopGroup(
                            1, new DefaultThreadFactory("nifi-opcua-subscriber-transport", true));
            diagnostics.stage("Discovering endpoints");
            List<EndpointDescription> endpoints = discover(settings.endpoint());
            diagnostics.endpoints(endpoints);
            diagnostics.stage("Selecting security and authentication");
            EndpointDescription endpoint = settings.security().selectEndpoint(endpoints);
            diagnostics.selected(endpoint);
            diagnostics.stage("Configuring server trust");
            ClientCertificateValidator validator = rejectUnconfiguredTrust();
            if (settings.serverCertificateValidation() == ServerCertificateValidation.NONE) {
                validator = serverValidator(settings.serverCertificateValidation(), null);
            } else if (settings.security().requiresServerTrust(endpoint)) {
                if (settings.pki() == null)
                    throw new IllegalArgumentException(
                            "PKI Directory is required to validate this endpoint's server"
                                    + " certificate, including encrypted username tokens");
                trust = new DefaultTrustListManager(settings.pki().toFile());
                validator = serverValidator(settings.serverCertificateValidation(), trust);
            }
            attempt.check();
            client =
                    OpcUaClient.create(
                            clientConfig(settings, endpoint, identity, validator, eventLoop));
            final boolean resolveUris = TagResolver.needsNamespaces(settings.tags());
            client.addSessionActivityListener(
                    new org.eclipse.milo.opcua.sdk.client.SessionActivityListener() {
                        @Override
                        public void onSessionActive(
                                org.eclipse.milo.opcua.sdk.client.api.UaSession session) {
                            ifActive(
                                    () -> {
                                        boolean changed =
                                                diagnostics.sessionActive(session.getSessionId());
                                        if (changed && resolveUris) {
                                            // A transferred subscription contains old numeric
                                            // NodeIds. Recreate
                                            // it
                                            // from the original URI configuration before accepting
                                            // more values.
                                            active = false;
                                            recovery.run();
                                            attempt.cancel();
                                        }
                                    });
                        }

                        @Override
                        public void onSessionInactive(
                                org.eclipse.milo.opcua.sdk.client.api.UaSession session) {
                            ifActive(diagnostics::sessionInactive);
                        }
                    });
            diagnostics.stage("Opening authenticated session");
            attempt.await(client.connect(), timeout);
            diagnostics.stage("Reading namespace table");
            String[] namespaces = null;
            try {
                namespaces = readNamespaces(client, attempt, timeout);
                diagnostics.namespaces(namespaces);
            } catch (Exception e) {
                attempt.check();
                if (resolveUris) throw e;
                diagnostics.namespaceUnavailable();
            }
            List<TagDefinition> resolvedTags = TagResolver.resolve(settings.tags(), namespaces);
            diagnostics.stage("Creating subscription and monitored items");
            client.getSubscriptionManager()
                    .addSubscriptionListener(
                            new UaSubscriptionManager.SubscriptionListener() {
                                @Override
                                public void onKeepAlive(UaSubscription s, DateTime time) {
                                    ifActive(diagnostics::keepAlive);
                                }

                                @Override
                                public void onSubscriptionTransferFailed(
                                        UaSubscription s, StatusCode status) {
                                    ifActive(recovery);
                                }

                                @Override
                                public void onStatusChanged(UaSubscription s, StatusCode status) {
                                    if (status.isBad()) ifActive(recovery);
                                }

                                @Override
                                public void onSubscriptionWatchdogTimerElapsed(UaSubscription s) {
                                    ifActive(recovery);
                                }

                                @Override
                                public void onNotificationDataLost(UaSubscription s) {
                                    ifActive(dataLost);
                                }
                            });
            subscription =
                    attempt.await(
                            client.getSubscriptionManager()
                                    .createSubscription(settings.publishingInterval()),
                            timeout);
            createMonitoredItems(
                    client,
                    subscription,
                    resolvedTags,
                    (tag, value) -> {
                        ifActive(
                                () -> {
                                    diagnostics.notification(value);
                                    consumer.accept(tag, value);
                                });
                    },
                    attempt,
                    timeout,
                    diagnostics);
            diagnostics.subscription(
                    settings.publishingInterval(), subscription.getRevisedPublishingInterval());
            diagnostics.stage("Subscribed");
        } catch (Exception e) {
            close();
            throw e;
        }
    }

    static void createMonitoredItems(
            OpcUaClient client,
            UaSubscription subscription,
            List<TagDefinition> tags,
            BiConsumer<TagDefinition, DataValue> consumer,
            ConnectionAttempt attempt,
            long timeout)
            throws Exception {
        createMonitoredItems(
                client,
                subscription,
                tags,
                consumer,
                attempt,
                timeout,
                new ConnectionDiagnostics());
    }

    static void createMonitoredItems(
            OpcUaClient client,
            UaSubscription subscription,
            List<TagDefinition> tags,
            BiConsumer<TagDefinition, DataValue> consumer,
            ConnectionAttempt attempt,
            long timeout,
            ConnectionDiagnostics diagnostics)
            throws Exception {
        attempt.check();
        DataValue limit =
                attempt.await(
                        client.readValue(
                                0.0,
                                TimestampsToReturn.Neither,
                                Identifiers
                                        .Server_ServerCapabilities_OperationLimits_MaxMonitoredItemsPerCall),
                        timeout);
        // This optional property can be absent or unreadable. Keep our cap in that case.
        int batchSize = 100;
        if (limit != null
                && limit.getStatusCode() != null
                && limit.getStatusCode().isGood()
                && limit.getValue() != null
                && limit.getValue().getValue() instanceof UInteger) {
            long advertised = ((UInteger) limit.getValue().getValue()).longValue();
            if (advertised > 0) batchSize = (int) Math.min(batchSize, advertised);
        }
        for (int offset = 0; offset < tags.size(); ) {
            attempt.check();
            final List<TagDefinition> chunk =
                    tags.subList(offset, Math.min(offset + batchSize, tags.size()));
            List<MonitoredItemCreateRequest> requests = new ArrayList<>();
            for (TagDefinition tag : chunk) {
                ReadValueId read =
                        new ReadValueId(
                                tag.nodeId(),
                                AttributeId.Value.uid(),
                                null,
                                QualifiedName.NULL_VALUE);
                MonitoringParameters parameters =
                        new MonitoringParameters(
                                subscription.nextClientHandle(),
                                tag.samplingIntervalMs(),
                                tag.filter() == null
                                        ? null
                                        : ExtensionObject.encode(
                                                client.getStaticSerializationContext(),
                                                tag.filter()),
                                uint(tag.queueSize()),
                                tag.discardOldest());
                requests.add(
                        new MonitoredItemCreateRequest(read, MonitoringMode.Reporting, parameters));
            }
            List<UaMonitoredItem> items;
            try {
                items =
                        attempt.await(
                                subscription.createMonitoredItems(
                                        TimestampsToReturn.Both,
                                        requests,
                                        (item, index) ->
                                                item.setValueConsumer(
                                                        (i, value) ->
                                                                consumer.accept(
                                                                        chunk.get(index), value))),
                                timeout);
            } catch (Exception e) {
                // The optional limit can be missing, stale or higher than the effective limit.
                // A service-level rejection creates no items, so retry only this chunk.
                attempt.check();
                if (chunk.size() > 1
                        && UaException.extractStatusCode(e)
                                .map(
                                        status ->
                                                status.getValue()
                                                        == StatusCodes.Bad_TooManyOperations)
                                .orElse(false)) {
                    batchSize = Math.max(1, chunk.size() / 2);
                    continue;
                }
                throw e;
            }
            if (items.size() != requests.size())
                throw new IllegalStateException(
                        "Server returned an incomplete monitored-item response");
            for (int itemIndex = 0; itemIndex < items.size(); itemIndex++) {
                UaMonitoredItem item = items.get(itemIndex);
                if (!item.getStatusCode().isGood())
                    throw new IllegalStateException(
                            "Server rejected monitored item "
                                    + item.getReadValueId().getNodeId()
                                    + " with "
                                    + item.getStatusCode());
                diagnostics.item(
                        chunk.get(itemIndex),
                        item.getRevisedSamplingInterval(),
                        item.getRevisedQueueSize() == null
                                ? 0
                                : item.getRevisedQueueSize().longValue());
            }
            offset += chunk.size();
        }
    }

    private static String[] readNamespaces(
            OpcUaClient client, ConnectionAttempt attempt, long timeout) throws Exception {
        org.eclipse.milo.opcua.stack.core.types.structured.ReadResponse response =
                attempt.await(
                        client.read(
                                0.0,
                                TimestampsToReturn.Neither,
                                Collections.singletonList(
                                        new ReadValueId(
                                                Identifiers.Server_NamespaceArray,
                                                AttributeId.Value.uid(),
                                                null,
                                                QualifiedName.NULL_VALUE))),
                        timeout);
        if (response.getResults() == null || response.getResults().length != 1)
            throw new IllegalArgumentException(
                    "Server returned an incomplete NamespaceArray response");
        return TagResolver.namespaceArray(response.getResults()[0]);
    }

    static OpcUaClientConfig clientConfig(
            OpcSettings settings,
            EndpointDescription endpoint,
            ClientIdentity identity,
            ClientCertificateValidator validator,
            NioEventLoopGroup eventLoop) {
        OpcUaClientConfigBuilder builder =
                OpcUaClientConfig.builder()
                        .setEndpoint(endpoint)
                        .setApplicationName(LocalizedText.english("NiFi OPC UA Subscriber"))
                        .setApplicationUri(ClientIdentity.applicationUri(settings, identity))
                        .setCertificateValidator(validator)
                        .setIdentityProvider(
                                settings.security().usernameAuthentication()
                                        ? new UsernameProvider(
                                                settings.username(),
                                                settings.password(),
                                                validator,
                                                policies -> settings.security().userToken(endpoint))
                                        : new AnonymousProvider())
                        .setEventLoop(eventLoop)
                        .setConnectTimeout(uint(settings.requestTimeout()))
                        .setAcknowledgeTimeout(uint(settings.requestTimeout()))
                        .setRequestTimeout(uint(settings.requestTimeout()));
        if (identity != null)
            builder.setKeyPair(identity.keyPair())
                    .setCertificate(identity.chain()[0])
                    .setCertificateChain(identity.chain());
        return builder.build();
    }

    static ClientCertificateValidator rejectUnconfiguredTrust() {
        return new ClientCertificateValidator() {
            @Override
            public void validateCertificateChain(List<java.security.cert.X509Certificate> chain)
                    throws UaException {
                throw new UaException(
                        StatusCodes.Bad_CertificateUntrusted, "PKI Directory is not configured");
            }

            @Override
            public void validateCertificateChain(
                    List<java.security.cert.X509Certificate> chain,
                    String applicationUri,
                    String... hosts)
                    throws UaException {
                validateCertificateChain(chain);
            }
        };
    }

    static ClientCertificateValidator serverValidator(
            ServerCertificateValidation validation, TrustListManager trust) {
        if (validation != ServerCertificateValidation.NONE)
            return new DefaultClientCertificateValidator(trust, validation.optionalChecks());
        // Explicit opt-in only; channel/session signature checks remain Milo's responsibility.
        return new ClientCertificateValidator() {
            @Override
            public void validateCertificateChain(List<java.security.cert.X509Certificate> chain) {}

            @Override
            public void validateCertificateChain(
                    List<java.security.cert.X509Certificate> chain,
                    String applicationUri,
                    String... hosts) {}
        };
    }

    private void ifActive(Runnable action) {
        attempt.ifActive(
                () -> {
                    if (active) action.run();
                });
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) return;
        active = false;
        attempt.cancel();
        diagnostics.sessionInactive();
        CompletableFuture<?> disconnected = null;
        try {
            try {
                if (subscription != null)
                    client.getSubscriptionManager()
                            .deleteSubscription(subscription.getSubscriptionId());
            } finally {
                if (client != null) disconnected = client.disconnect();
            }
        } finally {
            // This group belongs only to this connection. Closing it also aborts incomplete
            // TCP/OPC handshakes, which Milo's orderly disconnect alone may wait to finish.
            try {
                if (disconnected != null) finishDisconnect(disconnected);
                if (eventLoop != null)
                    eventLoop.shutdownGracefully(0, 1, TimeUnit.SECONDS).awaitUninterruptibly(1000);
            } finally {
                try {
                    if (trust != null) trust.close();
                } catch (java.io.IOException ignored) {
                }
            }
        }
        // Never release shared Milo resources used by other processors.
    }

    private void finishDisconnect(CompletableFuture<?> future) {
        try {
            future.get(Math.min(timeout, 1000), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception ignored) {
            /* Disconnect remains requested even if its acknowledgement is late. */
        }
    }

    private List<EndpointDescription> discover(String url) throws Exception {
        try {
            return discoverAt(url);
        } catch (ExecutionException e) {
            attempt.check();
            if (url.endsWith("/discovery")) throw e;
            return discoverAt(url + (url.endsWith("/") ? "" : "/") + "discovery");
        }
    }

    private List<EndpointDescription> discoverAt(String url) throws Exception {
        attempt.check();
        EndpointDescription endpoint =
                new EndpointDescription(
                        url,
                        null,
                        null,
                        MessageSecurityMode.None,
                        SecurityPolicy.None.getUri(),
                        null,
                        "http://opcfoundation.org/UA-Profile/Transport/uatcp-uasc-uabinary",
                        org.eclipse.milo.opcua.stack.core.types.builtin.unsigned.Unsigned.ubyte(0));
        DiscoveryClient discovery =
                new DiscoveryClient(
                        UaStackClient.create(
                                UaStackClientConfig.builder()
                                        .setEndpoint(endpoint)
                                        .setEventLoop(eventLoop)
                                        .setConnectTimeout(uint(timeout))
                                        .setAcknowledgeTimeout(uint(timeout))
                                        .setRequestTimeout(uint(timeout))
                                        .build()));
        try {
            attempt.await(discovery.connect(), timeout);
            return Arrays.asList(
                    attempt.await(
                                    discovery.getEndpoints(url, new String[0], new String[0]),
                                    timeout)
                            .getEndpoints());
        } finally {
            CompletableFuture<?> closing = discovery.disconnect();
            if (!attempt.isCancelled()) finishDisconnect(closing);
        }
    }
}
