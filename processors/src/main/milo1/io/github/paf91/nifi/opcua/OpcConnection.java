/* SPDX-License-Identifier: Apache-2.0 */
package io.github.paf91.nifi.opcua;

import static org.eclipse.milo.opcua.stack.core.types.builtin.unsigned.Unsigned.uint;

import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.util.concurrent.DefaultThreadFactory;

import org.eclipse.milo.opcua.sdk.client.DiscoveryClient;
import org.eclipse.milo.opcua.sdk.client.OpcUaClient;
import org.eclipse.milo.opcua.sdk.client.OpcUaClientConfig;
import org.eclipse.milo.opcua.sdk.client.identity.AnonymousProvider;
import org.eclipse.milo.opcua.sdk.client.identity.UsernameProvider;
import org.eclipse.milo.opcua.sdk.client.subscriptions.*;
import org.eclipse.milo.opcua.stack.core.StatusCodes;
import org.eclipse.milo.opcua.stack.core.UaException;
import org.eclipse.milo.opcua.stack.core.security.*;
import org.eclipse.milo.opcua.stack.core.types.builtin.*;
import org.eclipse.milo.opcua.stack.core.types.builtin.unsigned.UInteger;
import org.eclipse.milo.opcua.stack.core.types.enumerated.*;
import org.eclipse.milo.opcua.stack.core.types.structured.EndpointDescription;
import org.eclipse.milo.opcua.stack.core.types.structured.RequestHeader;
import org.eclipse.milo.opcua.stack.transport.client.tcp.*;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;

final class OpcConnection implements AutoCloseable {
    private BoundedOpcUaClient client;
    private NioEventLoopGroup eventLoop;
    private OpcUaSubscription subscription;
    private FileBasedTrustListManager trust;
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
            CertificateValidator validator = rejectUnconfiguredTrust();
            if (settings.serverCertificateValidation() == ServerCertificateValidation.NONE) {
                validator = serverValidator(settings.serverCertificateValidation(), null);
            } else if (settings.security().requiresServerTrust(endpoint)) {
                if (settings.pki() == null)
                    throw new IllegalArgumentException(
                            "PKI Directory is required to validate this endpoint's server"
                                    + " certificate, including encrypted username tokens");
                trust = FileBasedTrustListManager.createAndInitialize(settings.pki());
                validator = serverValidator(settings.serverCertificateValidation(), trust);
            }
            attempt.check();
            client =
                    new BoundedOpcUaClient(
                            clientConfig(settings, endpoint, endpoints, identity, validator),
                            new OpcTcpClientTransport(
                                    OpcTcpClientTransportConfig.newBuilder()
                                            .setEventLoop(eventLoop)
                                            .setConnectTimeout(uint(timeout))
                                            .setAcknowledgeTimeout(uint(timeout))
                                            .build()),
                            attempt);
            final boolean resolveUris = TagResolver.needsNamespaces(settings.tags());
            client.addSessionActivityListener(
                    new org.eclipse.milo.opcua.sdk.client.SessionActivityListener() {
                        @Override
                        public void onSessionActive(
                                org.eclipse.milo.opcua.sdk.client.UaSession session) {
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
                                org.eclipse.milo.opcua.sdk.client.UaSession session) {
                            ifActive(diagnostics::sessionInactive);
                        }
                    });
            diagnostics.stage("Opening authenticated session");
            attempt.await(client.connectAsync(), timeout);
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
            subscription = new OpcUaSubscription(client, settings.publishingInterval());
            subscription.setMaxMonitoredItemsPerCall(uint(100));
            subscription.setSubscriptionListener(
                    new OpcUaSubscription.SubscriptionListener() {
                        @Override
                        public void onKeepAliveReceived(OpcUaSubscription s) {
                            ifActive(diagnostics::keepAlive);
                        }

                        @Override
                        public void onTransferFailed(OpcUaSubscription s, StatusCode status) {
                            ifActive(recovery);
                        }

                        @Override
                        public void onStatusChanged(OpcUaSubscription s, StatusCode status) {
                            if (status.isBad()) ifActive(recovery);
                        }

                        @Override
                        public void onWatchdogTimerElapsed(OpcUaSubscription s) {
                            ifActive(recovery);
                        }

                        @Override
                        public void onNotificationDataLost(OpcUaSubscription s) {
                            ifActive(dataLost);
                        }
                    });
            attempt.await(subscription.createAsync().toCompletableFuture(), timeout);
            for (TagDefinition tag : resolvedTags) {
                var item = OpcUaMonitoredItem.newDataItem(tag.nodeId());
                item.setSamplingInterval(tag.samplingIntervalMs());
                item.setQueueSize(uint(tag.queueSize()));
                item.setDiscardOldest(tag.discardOldest());
                if (tag.filter() != null) item.setFilter(tag.filter());
                item.setDataValueListener(
                        (ignored, value) -> {
                            ifActive(() -> diagnostics.notification(value));
                            // The consumer fences final queue admission; encoding must not hold
                            // the monitor used to cancel a connection.
                            if (active && !attempt.isCancelled()) consumer.accept(tag, value);
                        });
                subscription.addMonitoredItem(item);
            }
            synchronizeMonitoredItems(subscription, attempt);
            recordMonitoredItems(subscription.getMonitoredItems(), resolvedTags, diagnostics);
            diagnostics.subscription(
                    settings.publishingInterval(),
                    subscription.getRevisedPublishingInterval().orElse(0.0));
            client.setupComplete();
            diagnostics.stage("Subscribed");
        } catch (Exception e) {
            close();
            throw e;
        }
    }

    static void recordMonitoredItems(
            List<OpcUaMonitoredItem> items,
            List<TagDefinition> tags,
            ConnectionDiagnostics diagnostics) {
        if (items.size() != tags.size())
            throw new IllegalStateException("Incomplete monitored-item set");
        Map<NodeId, TagDefinition> byNode = new HashMap<>();
        for (TagDefinition tag : tags) byNode.put(tag.nodeId(), tag);
        for (OpcUaMonitoredItem item : items) {
            if (!item.getCreateResult().map(StatusCode::isGood).orElse(false))
                throw new IllegalStateException(
                        "Server rejected monitored item "
                                + item.getReadValueId().getNodeId()
                                + " with "
                                + item.getCreateResult().orElse(null));
            diagnostics.item(
                    byNode.get(item.getReadValueId().getNodeId()),
                    item.getRevisedSamplingInterval().orElse(0.0),
                    item.getRevisedQueueSize().map(Number::longValue).orElse(0L));
        }
    }

    static void synchronizeMonitoredItems(
            OpcUaSubscription subscription, ConnectionAttempt attempt) {
        // BoundedOpcUaClient bounds each service call, including lazy operation-limit reads.
        // Keep interruption scoped so Stop does not contaminate NiFi's reusable worker.
        attempt.blocking(
                () -> {
                    try {
                        subscription.synchronizeMonitoredItems();
                    } catch (MonitoredItemSynchronizationException e) {
                        // Milo stores the useful status in result lists, not in the cause chain.
                        for (var results :
                                List.of(
                                        e.getCreateResults(),
                                        e.getModifyResults(),
                                        e.getDeleteResults())) {
                            for (var result : results) {
                                if (!result.isGood()) {
                                    StatusCode status =
                                            result.serviceResult().isGood()
                                                    ? result.operationResult().orElse(null)
                                                    : result.serviceResult();
                                    throw new IllegalStateException(
                                            "Could not synchronize monitored item "
                                                    + result.monitoredItem()
                                                            .getReadValueId()
                                                            .getNodeId()
                                                    + " with "
                                                    + status,
                                            e);
                                }
                            }
                        }
                        throw new CompletionException(e);
                    } catch (Exception e) {
                        throw new CompletionException(e);
                    }
                });
    }

    private static String[] readNamespaces(
            OpcUaClient client, ConnectionAttempt attempt, long timeout) throws Exception {
        org.eclipse.milo.opcua.stack.core.types.structured.ReadResponse response =
                attempt.await(
                        client.readAsync(
                                0.0,
                                TimestampsToReturn.Neither,
                                Collections.singletonList(
                                        new org.eclipse.milo.opcua.stack.core.types.structured
                                                .ReadValueId(
                                                org.eclipse.milo.opcua.stack.core.Identifiers
                                                        .Server_NamespaceArray,
                                                org.eclipse.milo.opcua.stack.core.AttributeId.Value
                                                        .uid(),
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
            List<EndpointDescription> endpoints,
            ClientIdentity identity,
            CertificateValidator validator) {
        var builder =
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
                        .setDiscoveryEndpoints(endpoints)
                        .setSessionEndpointValidationEnabled(true)
                        .setRequestTimeout(uint(settings.requestTimeout()))
                        .setMaxResponseMessageSize(uint(16 * 1024 * 1024));
        if (identity != null)
            builder.setKeyPair(identity.keyPair())
                    .setCertificate(identity.chain()[0])
                    .setCertificateChain(identity.chain());
        return builder.build();
    }

    static CertificateValidator rejectUnconfiguredTrust() {
        return (chain, applicationUri, hosts) -> {
            throw new UaException(
                    StatusCodes.Bad_CertificateUntrusted, "PKI Directory is not configured");
        };
    }

    static CertificateValidator serverValidator(
            ServerCertificateValidation validation, TrustListManager trust) {
        if (validation != ServerCertificateValidation.NONE)
            return new DefaultClientCertificateValidator(
                    trust, validation.optionalChecks(), new MemoryCertificateQuarantine());
        // Explicit opt-in only; Milo still verifies channel/session signatures.
        return (chain, applicationUri, hosts) -> {};
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
                if (subscription != null) subscription.deleteAsync();
            } finally {
                if (client != null) disconnected = client.disconnectAsync();
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
                        endpoint,
                        new OpcTcpClientTransport(
                                OpcTcpClientTransportConfig.newBuilder()
                                        .setEventLoop(eventLoop)
                                        .setConnectTimeout(uint(timeout))
                                        .setAcknowledgeTimeout(uint(timeout))
                                        .build())) {
                    @Override
                    public RequestHeader newRequestHeader(NodeId token, UInteger ignored) {
                        return super.newRequestHeader(token, uint(timeout));
                    }
                };
        try {
            attempt.await(discovery.connectAsync(), timeout);
            return Arrays.asList(
                    attempt.await(
                                    discovery.getEndpoints(url, new String[0], new String[0]),
                                    timeout)
                            .getEndpoints());
        } finally {
            CompletableFuture<?> closing = discovery.disconnectAsync();
            if (!attempt.isCancelled()) finishDisconnect(closing);
        }
    }
}
