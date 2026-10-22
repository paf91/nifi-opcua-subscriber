/* SPDX-License-Identifier: Apache-2.0 */
package io.github.paf91.nifi.opcua;

import static org.eclipse.milo.opcua.stack.core.types.builtin.unsigned.Unsigned.uint;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import org.eclipse.milo.opcua.sdk.client.*;
import org.eclipse.milo.opcua.sdk.client.subscriptions.*;
import org.eclipse.milo.opcua.stack.core.StatusCodes;
import org.eclipse.milo.opcua.stack.core.UaException;
import org.eclipse.milo.opcua.stack.core.types.UaResponseMessageType;
import org.eclipse.milo.opcua.stack.core.types.builtin.*;
import org.eclipse.milo.opcua.stack.core.types.enumerated.UserTokenType;
import org.eclipse.milo.opcua.stack.core.types.structured.*;
import org.eclipse.milo.opcua.stack.transport.client.OpcClientTransport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

class BoundedMonitoredItemSetupTest {
    private final ConnectionAttempt attempt = new ConnectionAttempt();
    private final OpcClientTransport transport = mock(OpcClientTransport.class, RETURNS_DEEP_STUBS);
    private BoundedOpcUaClient client;
    private OpcUaSession session;
    private OpcUaSubscription subscription;

    @BeforeEach
    void setup() throws Exception {
        EndpointDescription endpoint =
                OpcSecurityTest.endpoint(
                        "None", "None", OpcSecurityTest.token(UserTokenType.Anonymous, null));
        OpcSettings settings =
                new OpcSettings(
                        endpoint.getEndpointUrl(),
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
        when(transport.getConfig().getExecutor()).thenReturn(mock(ExecutorService.class));
        client =
                spy(
                        new BoundedOpcUaClient(
                                OpcConnection.clientConfig(
                                        settings,
                                        endpoint,
                                        List.of(endpoint),
                                        null,
                                        OpcConnection.rejectUnconfiguredTrust()),
                                transport,
                                attempt));
        session = mock(OpcUaSession.class);
        when(session.getAuthenticationToken()).thenReturn(new NodeId(0, 1));
        doReturn(CompletableFuture.completedFuture(session)).when(client).getSessionAsync();
        OperationLimits limits = mock(OperationLimits.class);
        when(limits.maxMonitoredItemsPerCall()).thenReturn(Optional.of(uint(50)));
        doReturn(limits).when(client).getOperationLimits();
        doReturn(
                        CompletableFuture.completedFuture(
                                new CreateSubscriptionResponse(
                                        null, uint(1), 1000.0, uint(60), uint(20))))
                .when(client)
                .createSubscriptionAsync(anyDouble(), any(), any(), any(), anyBoolean(), any());
        subscription = new OpcUaSubscription(client, 1000);
        subscription.setMaxMonitoredItemsPerCall(uint(100));
        subscription.createAsync().toCompletableFuture().get(1, TimeUnit.SECONDS);
    }

    private void addItems(int count) {
        for (int i = 0; i < count; i++)
            subscription.addMonitoredItem(OpcUaMonitoredItem.newDataItem(new NodeId(2, "tag" + i)));
    }

    private static CreateMonitoredItemsResponse accepted(CreateMonitoredItemsRequest request) {
        MonitoredItemCreateResult[] results =
                new MonitoredItemCreateResult[request.getItemsToCreate().length];
        for (int i = 0; i < results.length; i++)
            results[i] =
                    new MonitoredItemCreateResult(
                            StatusCode.GOOD,
                            request.getItemsToCreate()[i]
                                    .getRequestedParameters()
                                    .getClientHandle(),
                            1000.0,
                            uint(10),
                            null);
        return new CreateMonitoredItemsResponse(null, results, new DiagnosticInfo[0]);
    }

    @Test
    void lostSessionIsBoundedAndLateRecoveryDoesNotSendTheAbandonedRequest() {
        addItems(201);
        CompletableFuture<OpcUaSession> recovery = new CompletableFuture<>();
        doReturn(recovery).when(client).getSessionAsync();
        Exception failure =
                assertTimeoutPreemptively(
                        Duration.ofSeconds(2),
                        () ->
                                assertThrows(
                                        Exception.class,
                                        () ->
                                                OpcConnection.synchronizeMonitoredItems(
                                                        subscription, attempt)));
        assertTrue(hasTimeout(failure), failure.toString());
        assertFalse(recovery.isCancelled());
        recovery.complete(session);
        verify(transport, never()).sendRequestMessage(any(CreateMonitoredItemsRequest.class));
    }

    @Test
    void lazyOperationLimitReadAlsoHasADeadlineIncludingSessionAcquisition() throws Exception {
        addItems(1);
        doCallRealMethod().when(client).getOperationLimits();
        CompletableFuture<OpcUaSession> recovery = new CompletableFuture<>();
        doReturn(recovery).when(client).getSessionAsync();
        Exception failure =
                assertTimeoutPreemptively(
                        Duration.ofSeconds(2),
                        () ->
                                assertThrows(
                                        Exception.class,
                                        () ->
                                                OpcConnection.synchronizeMonitoredItems(
                                                        subscription, attempt)));
        assertTrue(hasTimeout(failure), failure.toString());
        recovery.complete(session);
        verify(transport, never()).sendRequestMessage(any(ReadRequest.class));
        verify(transport, never()).sendRequestMessage(any(CreateMonitoredItemsRequest.class));
    }

    @Test
    void cancellationStopsAfterTheFirstPendingBatchAndClearsItsInterrupt() throws Exception {
        addItems(201);
        CountDownLatch sent = new CountDownLatch(1);
        when(transport.sendRequestMessage(any(CreateMonitoredItemsRequest.class)))
                .thenAnswer(
                        call -> {
                            sent.countDown();
                            return new CompletableFuture<UaResponseMessageType>();
                        });
        ExecutorService worker = Executors.newSingleThreadExecutor();
        AtomicBoolean interruptedAfterwards = new AtomicBoolean(true);
        try {
            Future<?> setup =
                    worker.submit(
                            () -> {
                                try {
                                    OpcConnection.synchronizeMonitoredItems(subscription, attempt);
                                } finally {
                                    interruptedAfterwards.set(
                                            Thread.currentThread().isInterrupted());
                                }
                            });
            assertTrue(sent.await(2, TimeUnit.SECONDS));
            attempt.cancel();
            assertThrows(ExecutionException.class, () -> setup.get(2, TimeUnit.SECONDS));
            verify(transport, times(1)).sendRequestMessage(any(CreateMonitoredItemsRequest.class));
            assertFalse(interruptedAfterwards.get());
        } finally {
            attempt.cancel();
            worker.shutdownNow();
        }
    }

    @Test
    void serviceResponseTimeoutAbortsBeforeTheNextPartition() {
        addItems(201);
        when(transport.sendRequestMessage(any(CreateMonitoredItemsRequest.class)))
                .thenReturn(new CompletableFuture<>());
        Exception failure =
                assertTimeoutPreemptively(
                        Duration.ofSeconds(2),
                        () ->
                                assertThrows(
                                        Exception.class,
                                        () ->
                                                OpcConnection.synchronizeMonitoredItems(
                                                        subscription, attempt)));
        assertTrue(hasTimeout(failure), failure.toString());
        verify(transport, times(1)).sendRequestMessage(any(CreateMonitoredItemsRequest.class));
    }

    @Test
    void healthyLargeSubscriptionHasPerBatchRatherThanWholeSetupDeadline() throws Exception {
        addItems(201);
        List<Integer> sizes = new CopyOnWriteArrayList<>();
        ScheduledExecutorService replies = Executors.newSingleThreadScheduledExecutor();
        when(transport.sendRequestMessage(any(CreateMonitoredItemsRequest.class)))
                .thenAnswer(
                        call -> {
                            CreateMonitoredItemsRequest request = call.getArgument(0);
                            sizes.add(request.getItemsToCreate().length);
                            CompletableFuture<UaResponseMessageType> response =
                                    new CompletableFuture<>();
                            replies.schedule(
                                    () -> response.complete(accepted(request)),
                                    100,
                                    TimeUnit.MILLISECONDS);
                            return response;
                        });
        try {
            assertDoesNotThrow(
                    () -> OpcConnection.synchronizeMonitoredItems(subscription, attempt));
            assertEquals(List.of(50, 50, 50, 50, 1), sizes);
            assertTrue(subscription.isMonitoredItemsSynchronized());
        } finally {
            replies.shutdownNow();
        }
    }

    @Test
    void sdkCanStillAdaptToAnUnadvertisedSmallerServerLimit() throws Exception {
        addItems(3);
        List<Integer> sizes = new ArrayList<>();
        when(transport.sendRequestMessage(any(CreateMonitoredItemsRequest.class)))
                .thenAnswer(
                        call -> {
                            CreateMonitoredItemsRequest request = call.getArgument(0);
                            sizes.add(request.getItemsToCreate().length);
                            if (request.getItemsToCreate().length > 1)
                                return CompletableFuture.failedFuture(
                                        new UaException(StatusCodes.Bad_TooManyOperations));
                            return CompletableFuture.completedFuture(accepted(request));
                        });
        assertDoesNotThrow(() -> OpcConnection.synchronizeMonitoredItems(subscription, attempt));
        assertEquals(List.of(3, 1, 1, 1), sizes);
        assertTrue(subscription.isMonitoredItemsSynchronized());
    }

    @Test
    void sendBoundaryRejectsLateSetupButStillAllowsCleanupAfterCancellation() {
        attempt.cancel();
        assertThrows(
                CancellationException.class,
                () ->
                        client.sendRequestAsync(
                                new CreateMonitoredItemsRequest(
                                        null,
                                        uint(1),
                                        org.eclipse.milo.opcua.stack.core.types.enumerated
                                                .TimestampsToReturn.Both,
                                        new MonitoredItemCreateRequest[0])));
        verify(transport, never()).sendRequestMessage(any(CreateMonitoredItemsRequest.class));
        DeleteSubscriptionsRequest cleanup =
                new DeleteSubscriptionsRequest(
                        null,
                        new org.eclipse.milo.opcua.stack.core.types.builtin.unsigned.UInteger[] {
                            uint(1)
                        });
        when(transport.sendRequestMessage(cleanup))
                .thenReturn(
                        CompletableFuture.completedFuture(
                                new DeleteSubscriptionsResponse(
                                        null,
                                        new StatusCode[] {StatusCode.GOOD},
                                        new DiagnosticInfo[0])));
        assertDoesNotThrow(() -> client.sendRequestAsync(cleanup).join());
        verify(transport).sendRequestMessage(cleanup);
    }

    private static boolean hasTimeout(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause())
            if (cause instanceof TimeoutException) return true;
        return false;
    }

    @Test
    void completedSetupPreservesSdkRecoveryErrorsButStillHonorsCancellation() {
        client.setupComplete();
        when(transport.sendRequestMessage(any(CreateMonitoredItemsRequest.class)))
                .thenReturn(
                        CompletableFuture.failedFuture(new UaException(StatusCodes.Bad_Timeout)));
        UaException failure =
                assertThrows(
                        UaException.class,
                        () ->
                                client.createMonitoredItems(
                                        uint(1),
                                        org.eclipse.milo.opcua.stack.core.types.enumerated
                                                .TimestampsToReturn.Both,
                                        Collections.emptyList()));
        assertEquals(StatusCodes.Bad_Timeout, failure.getStatusCode().getValue());
        attempt.cancel();
        assertThrows(
                CancellationException.class,
                () ->
                        client.createMonitoredItems(
                                uint(1),
                                org.eclipse.milo.opcua.stack.core.types.enumerated
                                        .TimestampsToReturn.Both,
                                Collections.emptyList()));
        verify(transport, times(1)).sendRequestMessage(any(CreateMonitoredItemsRequest.class));
    }
}
