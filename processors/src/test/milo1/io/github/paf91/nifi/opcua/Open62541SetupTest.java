/* SPDX-License-Identifier: Apache-2.0 */
package io.github.paf91.nifi.opcua;

import static org.eclipse.milo.opcua.stack.core.types.builtin.unsigned.Unsigned.uint;
import static org.junit.jupiter.api.Assertions.*;

import io.netty.channel.nio.NioEventLoopGroup;

import org.eclipse.milo.opcua.sdk.client.*;
import org.eclipse.milo.opcua.sdk.client.subscriptions.*;
import org.eclipse.milo.opcua.stack.core.UaException;
import org.eclipse.milo.opcua.stack.core.types.builtin.NodeId;
import org.eclipse.milo.opcua.stack.core.types.structured.EndpointDescription;
import org.eclipse.milo.opcua.stack.transport.client.tcp.OpcTcpClientTransport;
import org.eclipse.milo.opcua.stack.transport.client.tcp.OpcTcpClientTransportConfig;
import org.junit.jupiter.api.TestInfo;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;

/** Reproduces a real outage between subscription creation and monitored-item synchronization. */
@EnabledIfEnvironmentVariable(named = "OPCUA_OPEN62541_BINARY", matches = ".+")
class Open62541SetupTest {
    @TempDir Path dir;

    @ParameterizedTest
    @ValueSource(ints = {0, 8000})
    void lostSessionDuringSetupRespectsTheOperationTimeout(int delayAfterOutage, TestInfo info)
            throws Exception {
        Open62541Test fixture = new Open62541Test();
        fixture.dir = dir;
        NioEventLoopGroup eventLoop = new NioEventLoopGroup(1);
        BoundedOpcUaClient client = null;
        ExecutorService worker = Executors.newSingleThreadExecutor();
        ConnectionAttempt attempt = new ConnectionAttempt();
        Future<?> setup = null;
        try {
            fixture.setup();
            fixture.start(false, "None");
            OpcSettings settings =
                    new OpcSettings(
                            fixture.endpoint(),
                            null,
                            null,
                            null,
                            "",
                            null,
                            null,
                            null,
                            100,
                            300,
                            Collections.emptyList(),
                            new OpcSecurity("None", "None", OpcSecurity.ANONYMOUS));
            List<EndpointDescription> endpoints =
                    DiscoveryClient.getEndpoints(fixture.endpoint()).get(5, TimeUnit.SECONDS);
            EndpointDescription endpoint = settings.security().selectEndpoint(endpoints);
            client =
                    new BoundedOpcUaClient(
                            OpcConnection.clientConfig(
                                    settings,
                                    endpoint,
                                    endpoints,
                                    null,
                                    OpcConnection.rejectUnconfiguredTrust()),
                            new OpcTcpClientTransport(
                                    OpcTcpClientTransportConfig.newBuilder()
                                            .setEventLoop(eventLoop)
                                            .setConnectTimeout(uint(300))
                                            .setAcknowledgeTimeout(uint(300))
                                            .build()),
                            attempt);
            CountDownLatch inactive = new CountDownLatch(1);
            client.addSessionActivityListener(
                    new SessionActivityListener() {
                        @Override
                        public void onSessionActive(UaSession session) {}

                        @Override
                        public void onSessionInactive(UaSession session) {
                            inactive.countDown();
                        }
                    });
            client.connectAsync().get(5, TimeUnit.SECONDS);
            OpcUaSubscription subscription = new OpcUaSubscription(client, 100);
            subscription.createAsync().toCompletableFuture().get(5, TimeUnit.SECONDS);
            client.getOperationLimits().maxMonitoredItemsPerCall();
            subscription.addMonitoredItem(OpcUaMonitoredItem.newDataItem(new NodeId(2, "Value")));
            fixture.server.destroyForcibly();
            assertTrue(fixture.server.waitFor(5, TimeUnit.SECONDS));
            assertTrue(
                    inactive.await(5, TimeUnit.SECONDS), "Client did not detect the lost session");
            // Exercise immediate failure and an established reconnect loop. This delay is not
            // included in the operation deadline measured below.
            Thread.sleep(delayAfterOutage);
            setup =
                    worker.submit(
                            () -> OpcConnection.synchronizeMonitoredItems(subscription, attempt));
            Future<?> pending = setup;
            ExecutionException failure =
                    assertThrows(
                            ExecutionException.class,
                            () -> pending.get(1800, TimeUnit.MILLISECONDS),
                            "Setup must fail promptly during session recovery (300 ms timeout)");
            assertTrue(isCommunicationFailure(failure), failure.toString());
        } finally {
            attempt.cancel();
            if (setup != null) {
                try {
                    setup.get(3, TimeUnit.SECONDS);
                } catch (Exception ignored) {
                }
            }
            worker.shutdownNow();
            if (client != null) {
                try {
                    client.disconnectAsync().get(2, TimeUnit.SECONDS);
                } catch (Exception ignored) {
                }
            }
            eventLoop.shutdownGracefully(0, 1, TimeUnit.SECONDS).awaitUninterruptibly(1500);
            fixture.stop();
            fixture.saveLog(info);
        }
    }

    private static boolean isCommunicationFailure(Throwable failure) {
        if (UaException.extract(failure).isPresent()) return true;
        for (Throwable cause = failure; cause != null; cause = cause.getCause())
            if (cause instanceof TimeoutException) return true;
        return false;
    }
}
