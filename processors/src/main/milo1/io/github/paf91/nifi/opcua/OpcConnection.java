/* SPDX-License-Identifier: Apache-2.0 */
package io.github.paf91.nifi.opcua;

import static org.eclipse.milo.opcua.stack.core.types.builtin.unsigned.Unsigned.uint;

import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.util.concurrent.DefaultThreadFactory;

import org.eclipse.milo.opcua.sdk.client.DiscoveryClient;
import org.eclipse.milo.opcua.sdk.client.OpcUaClient;
import org.eclipse.milo.opcua.sdk.client.OpcUaClientConfig;
import org.eclipse.milo.opcua.sdk.client.identity.UsernameProvider;
import org.eclipse.milo.opcua.sdk.client.subscriptions.*;
import org.eclipse.milo.opcua.stack.core.security.*;
import org.eclipse.milo.opcua.stack.core.types.builtin.*;
import org.eclipse.milo.opcua.stack.core.types.builtin.unsigned.UInteger;
import org.eclipse.milo.opcua.stack.core.types.enumerated.*;
import org.eclipse.milo.opcua.stack.core.types.structured.EndpointDescription;
import org.eclipse.milo.opcua.stack.core.types.structured.RequestHeader;
import org.eclipse.milo.opcua.stack.core.util.validation.ValidationCheck;
import org.eclipse.milo.opcua.stack.transport.client.tcp.*;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;

final class OpcConnection implements AutoCloseable {
    private OpcUaClient client;
    private NioEventLoopGroup eventLoop;
    private OpcUaSubscription subscription;
    private FileBasedTrustListManager trust;
    private volatile boolean active = true;
    private final long timeout;
    private final AtomicBoolean closed = new AtomicBoolean();
    private final ConnectionAttempt attempt;

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
        this.attempt = attempt;
        timeout = settings.requestTimeout();
        try {
            attempt.check();
            ClientIdentity identity =
                    ClientIdentity.load(
                            settings.certificate(),
                            settings.privateKey(),
                            settings.privateKeyPassword().toCharArray(),
                            settings.applicationUri());
            eventLoop =
                    new NioEventLoopGroup(
                            1, new DefaultThreadFactory("nifi-opcua-subscriber-transport", true));
            trust = FileBasedTrustListManager.createAndInitialize(settings.pki());
            var validator =
                    new DefaultClientCertificateValidator(
                            trust,
                            ValidationCheck.ALL_OPTIONAL_CHECKS,
                            new MemoryCertificateQuarantine());
            List<EndpointDescription> endpoints = discover(settings.endpoint());
            EndpointDescription endpoint =
                    endpoints.stream()
                            .filter(OpcConnection::secureEndpoint)
                            .findFirst()
                            .orElseThrow(
                                    () ->
                                            new IllegalArgumentException(
                                                    "No Basic256Sha256 / SignAndEncrypt username"
                                                            + " endpoint"));
            attempt.check();
            client =
                    OpcUaClient.create(
                            OpcUaClientConfig.builder()
                                    .setEndpoint(endpoint)
                                    .setApplicationName(
                                            LocalizedText.english("NiFi OPC UA Subscriber"))
                                    .setApplicationUri(identity.applicationUri())
                                    .setKeyPair(identity.keyPair())
                                    .setCertificate(identity.chain()[0])
                                    .setCertificateChain(identity.chain())
                                    .setCertificateValidator(validator)
                                    .setIdentityProvider(
                                            new UsernameProvider(
                                                    settings.username(), settings.password()))
                                    .setDiscoveryEndpoints(endpoints)
                                    .setSessionEndpointValidationEnabled(true)
                                    .setRequestTimeout(uint(timeout))
                                    .setMaxResponseMessageSize(uint(16 * 1024 * 1024))
                                    .build(),
                            transport ->
                                    transport
                                            .setEventLoop(eventLoop)
                                            .setConnectTimeout(uint(timeout))
                                            .setAcknowledgeTimeout(uint(timeout)));
            attempt.await(client.connectAsync(), timeout);
            subscription = new OpcUaSubscription(client, settings.publishingInterval());
            subscription.setMaxMonitoredItemsPerCall(uint(100));
            subscription.setSubscriptionListener(
                    new OpcUaSubscription.SubscriptionListener() {
                        @Override
                        public void onTransferFailed(OpcUaSubscription s, StatusCode status) {
                            if (active) recovery.run();
                        }

                        @Override
                        public void onStatusChanged(OpcUaSubscription s, StatusCode status) {
                            if (active && status.isBad()) recovery.run();
                        }

                        @Override
                        public void onWatchdogTimerElapsed(OpcUaSubscription s) {
                            if (active) recovery.run();
                        }

                        @Override
                        public void onNotificationDataLost(OpcUaSubscription s) {
                            if (active) dataLost.run();
                        }
                    });
            attempt.await(subscription.createAsync().toCompletableFuture(), timeout);
            for (TagDefinition tag : settings.tags()) {
                var item = OpcUaMonitoredItem.newDataItem(tag.nodeId());
                item.setSamplingInterval(tag.samplingIntervalMs());
                item.setQueueSize(uint(tag.queueSize()));
                item.setDiscardOldest(tag.discardOldest());
                item.setDataValueListener(
                        (ignored, value) -> {
                            if (active) consumer.accept(tag, value);
                        });
                subscription.addMonitoredItem(item);
            }
            synchronizeMonitoredItems(subscription, attempt);
            for (OpcUaMonitoredItem item : subscription.getMonitoredItems()) {
                if (!item.getCreateResult().map(StatusCode::isGood).orElse(false))
                    throw new IllegalStateException(
                            "Server rejected monitored item "
                                    + item.getReadValueId().getNodeId()
                                    + " with "
                                    + item.getCreateResult().orElse(null));
            }
        } catch (Exception e) {
            close();
            throw e;
        }
    }

    static void synchronizeMonitoredItems(
            OpcUaSubscription subscription, ConnectionAttempt attempt) {
        // Milo exposes this operation synchronously. Interrupt only this guarded call on Stop.
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

    static boolean secureEndpoint(EndpointDescription endpoint) {
        return SecurityPolicy.Basic256Sha256.getUri().equals(endpoint.getSecurityPolicyUri())
                && endpoint.getSecurityMode() == MessageSecurityMode.SignAndEncrypt
                && endpoint.getUserIdentityTokens() != null
                && Arrays.stream(endpoint.getUserIdentityTokens())
                        .anyMatch(t -> t.getTokenType() == UserTokenType.UserName);
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) return;
        active = false;
        attempt.cancel();
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
