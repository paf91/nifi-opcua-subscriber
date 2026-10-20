/* SPDX-License-Identifier: Apache-2.0 */
package io.github.paf91.nifi.opcua;

import static org.eclipse.milo.opcua.stack.core.types.builtin.unsigned.Unsigned.uint;

import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.util.concurrent.DefaultThreadFactory;

import org.eclipse.milo.opcua.sdk.client.OpcUaClient;
import org.eclipse.milo.opcua.sdk.client.api.config.OpcUaClientConfig;
import org.eclipse.milo.opcua.sdk.client.api.identity.UsernameProvider;
import org.eclipse.milo.opcua.sdk.client.api.subscriptions.*;
import org.eclipse.milo.opcua.stack.client.DiscoveryClient;
import org.eclipse.milo.opcua.stack.client.UaStackClient;
import org.eclipse.milo.opcua.stack.client.UaStackClientConfig;
import org.eclipse.milo.opcua.stack.client.security.DefaultClientCertificateValidator;
import org.eclipse.milo.opcua.stack.core.AttributeId;
import org.eclipse.milo.opcua.stack.core.security.*;
import org.eclipse.milo.opcua.stack.core.types.builtin.*;
import org.eclipse.milo.opcua.stack.core.types.enumerated.*;
import org.eclipse.milo.opcua.stack.core.types.structured.*;
import org.eclipse.milo.opcua.stack.core.util.validation.ValidationCheck;

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
            trust = new DefaultTrustListManager(settings.pki().toFile());
            DefaultClientCertificateValidator validator =
                    new DefaultClientCertificateValidator(
                            trust, ValidationCheck.ALL_OPTIONAL_CHECKS);
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
                                    .setEventLoop(eventLoop)
                                    .setConnectTimeout(uint(timeout))
                                    .setAcknowledgeTimeout(uint(timeout))
                                    .setRequestTimeout(uint(timeout))
                                    .build());
            attempt.await(client.connect(), timeout);
            client.getSubscriptionManager()
                    .addSubscriptionListener(
                            new UaSubscriptionManager.SubscriptionListener() {
                                @Override
                                public void onSubscriptionTransferFailed(
                                        UaSubscription s, StatusCode status) {
                                    if (active) recovery.run();
                                }

                                @Override
                                public void onStatusChanged(UaSubscription s, StatusCode status) {
                                    if (active && status.isBad()) recovery.run();
                                }

                                @Override
                                public void onSubscriptionWatchdogTimerElapsed(UaSubscription s) {
                                    if (active) recovery.run();
                                }

                                @Override
                                public void onNotificationDataLost(UaSubscription s) {
                                    if (active) dataLost.run();
                                }
                            });
            subscription =
                    attempt.await(
                            client.getSubscriptionManager()
                                    .createSubscription(settings.publishingInterval()),
                            timeout);
            List<TagDefinition> tags = settings.tags();
            for (int offset = 0; offset < tags.size(); offset += 100) {
                final List<TagDefinition> chunk =
                        tags.subList(offset, Math.min(offset + 100, tags.size()));
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
                                    null,
                                    uint(tag.queueSize()),
                                    tag.discardOldest());
                    requests.add(
                            new MonitoredItemCreateRequest(
                                    read, MonitoringMode.Reporting, parameters));
                }
                List<UaMonitoredItem> items =
                        attempt.await(
                                subscription.createMonitoredItems(
                                        TimestampsToReturn.Both,
                                        requests,
                                        (item, index) ->
                                                item.setValueConsumer(
                                                        (i, value) -> {
                                                            if (active)
                                                                consumer.accept(
                                                                        chunk.get(index), value);
                                                        })),
                                timeout);
                if (items.size() != requests.size())
                    throw new IllegalStateException(
                            "Server returned an incomplete monitored-item response");
                for (UaMonitoredItem item : items) {
                    if (!item.getStatusCode().isGood())
                        throw new IllegalStateException(
                                "Server rejected monitored item "
                                        + item.getReadValueId().getNodeId()
                                        + " with "
                                        + item.getStatusCode());
                }
            }
        } catch (Exception e) {
            close();
            throw e;
        }
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
