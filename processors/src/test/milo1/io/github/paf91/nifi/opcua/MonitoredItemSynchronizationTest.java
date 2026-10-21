/* SPDX-License-Identifier: Apache-2.0 */
package io.github.paf91.nifi.opcua;

import static org.eclipse.milo.opcua.stack.core.types.builtin.unsigned.Unsigned.uint;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import org.apache.nifi.processor.ProcessContext;
import org.apache.nifi.util.*;
import org.eclipse.milo.opcua.sdk.client.OpcUaClient;
import org.eclipse.milo.opcua.sdk.client.subscriptions.*;
import org.eclipse.milo.opcua.stack.core.StatusCodes;
import org.eclipse.milo.opcua.stack.core.UaException;
import org.eclipse.milo.opcua.stack.core.types.builtin.*;
import org.eclipse.milo.opcua.stack.core.types.structured.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.*;
import java.util.concurrent.*;

class MonitoredItemSynchronizationTest {
    private final OpcUaClient client = mock(OpcUaClient.class, RETURNS_DEEP_STUBS);
    private OpcUaSubscription subscription;

    @BeforeEach
    void createSubscription() throws Exception {
        when(client.getTransport().getConfig().getExecutor())
                .thenReturn(mock(ExecutorService.class));
        when(client.getOperationLimits().maxMonitoredItemsPerCall())
                .thenReturn(Optional.of(uint(50)));
        when(client.createSubscriptionAsync(anyDouble(), any(), any(), any(), anyBoolean(), any()))
                .thenReturn(
                        CompletableFuture.completedFuture(
                                new CreateSubscriptionResponse(
                                        null, uint(1), 1000.0, uint(60), uint(20))));
        // Keep Milo's creation and synchronization logic real; simulate service responses only.
        subscription = new OpcUaSubscription(client, 1000);
        subscription.createAsync().toCompletableFuture().get(1, TimeUnit.SECONDS);
    }

    private OpcUaMonitoredItem addItem(String identifier) {
        OpcUaMonitoredItem item = OpcUaMonitoredItem.newDataItem(new NodeId(2, identifier));
        subscription.addMonitoredItem(item);
        return item;
    }

    private void respondWith(StatusCode... statuses) throws Exception {
        MonitoredItemCreateResult[] results = new MonitoredItemCreateResult[statuses.length];
        for (int i = 0; i < statuses.length; i++) {
            results[i] =
                    new MonitoredItemCreateResult(statuses[i], uint(i + 1), 1000.0, uint(10), null);
        }
        when(client.createMonitoredItems(any(), any(), anyList()))
                .thenReturn(new CreateMonitoredItemsResponse(null, results, new DiagnosticInfo[0]));
    }

    private TestRunner runner() {
        return ProcessorTest.configured(
                new SubscribeOpcUa() {
                    @Override
                    protected AutoCloseable openConnection(
                            ProcessContext context, ConnectionAttempt attempt) {
                        OpcConnection.synchronizeMonitoredItems(subscription, attempt);
                        return () -> {};
                    }
                });
    }

    private String failureLog(TestRunner runner) {
        try {
            runner.run(1, false, true);
            assertEquals(1, runner.getLogger().getErrorMessages().size());
            LogMessage log = runner.getLogger().getErrorMessages().get(0);
            assertNull(log.getThrowable());
            return log.toString();
        } finally {
            runner.shutdown();
        }
    }

    @Test
    void logsActualRejectedNodeAfterSuccessfulItems() throws Exception {
        addItem("valid-tag");
        addItem("does-not-exist");
        respondWith(StatusCode.GOOD, new StatusCode(StatusCodes.Bad_NodeIdUnknown));
        String log = failureLog(runner());
        assertTrue(log.contains("does-not-exist"), log);
        assertTrue(log.contains("Bad_NodeIdUnknown"), log);
        assertTrue(log.contains("0x80340000"), log);
        assertFalse(log.contains("valid-tag"), log);
    }

    @Test
    void logsServiceFailureWithoutAnItemStatus() throws Exception {
        addItem("service-failure-tag");
        when(client.createMonitoredItems(any(), any(), anyList()))
                .thenThrow(new UaException(StatusCodes.Bad_TooManyOperations));
        String log = failureLog(runner());
        assertTrue(log.contains("service-failure-tag"), log);
        assertTrue(log.contains("Bad_TooManyOperations"), log);
    }

    @Test
    void redactsPasswordsInRejectedNodeDetails() throws Exception {
        String password = "test-secret";
        String keyPassword = "key-secret";
        addItem("tag-" + password + "-" + keyPassword);
        respondWith(new StatusCode(StatusCodes.Bad_NodeIdUnknown));
        TestRunner runner = runner();
        runner.setProperty(SubscribeOpcUa.PASSWORD, password);
        runner.setProperty(SubscribeOpcUa.KEY_PASSWORD, keyPassword);
        String log = failureLog(runner);
        assertTrue(log.contains("tag-[redacted]-[redacted]"), log);
        assertTrue(log.contains("Bad_NodeIdUnknown"), log);
        assertFalse(log.contains(password), log);
        assertFalse(log.contains(keyPassword), log);
    }

    @Test
    void acceptsSuccessfulSynchronization() throws Exception {
        OpcUaMonitoredItem item = addItem("valid-tag");
        respondWith(StatusCode.GOOD);
        assertDoesNotThrow(
                () ->
                        OpcConnection.synchronizeMonitoredItems(
                                subscription, new ConnectionAttempt()));
        assertTrue(item.getCreateResult().orElseThrow().isGood());
    }
}
