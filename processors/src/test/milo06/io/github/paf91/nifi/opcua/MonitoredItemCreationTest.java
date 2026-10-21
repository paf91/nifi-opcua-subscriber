/* SPDX-License-Identifier: Apache-2.0 */
package io.github.paf91.nifi.opcua;

import static org.eclipse.milo.opcua.stack.core.types.builtin.unsigned.Unsigned.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import org.eclipse.milo.opcua.sdk.client.OpcUaClient;
import org.eclipse.milo.opcua.sdk.client.OpcUaSession;
import org.eclipse.milo.opcua.sdk.client.subscriptions.OpcUaSubscription;
import org.eclipse.milo.opcua.stack.core.Identifiers;
import org.eclipse.milo.opcua.stack.core.StatusCodes;
import org.eclipse.milo.opcua.stack.core.UaException;
import org.eclipse.milo.opcua.stack.core.types.builtin.*;
import org.eclipse.milo.opcua.stack.core.types.enumerated.TimestampsToReturn;
import org.eclipse.milo.opcua.stack.core.types.structured.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

class MonitoredItemCreationTest {
    private final OpcUaClient client = mock(OpcUaClient.class, CALLS_REAL_METHODS);
    private final OpcUaSubscription subscription =
            new OpcUaSubscription(
                    client, uint(1), 1000.0, uint(60), uint(20), uint(65535), true, ubyte(0));
    private final List<CreateMonitoredItemsRequest> sent = new ArrayList<>();
    private int serverLimit = 100;
    private int nextItemId = 1;

    @BeforeEach
    void simulateServerResponses() {
        OpcUaSession session = mock(OpcUaSession.class);
        when(session.getAuthenticationToken()).thenReturn(NodeId.NULL_VALUE);
        doReturn(CompletableFuture.completedFuture(session)).when(client).getSession();
        doReturn(mock(RequestHeader.class)).when(client).newRequestHeader(any(NodeId.class));
        // Exercise Milo's real subscription and request construction, replacing only transport.
        doAnswer(
                        call -> {
                            CreateMonitoredItemsRequest request = call.getArgument(0);
                            sent.add(request);
                            int size = request.getItemsToCreate().length;
                            CompletableFuture<CreateMonitoredItemsResponse> response =
                                    new CompletableFuture<>();
                            if (size > serverLimit) {
                                response.completeExceptionally(
                                        new UaException(StatusCodes.Bad_TooManyOperations));
                            } else {
                                MonitoredItemCreateResult[] results =
                                        new MonitoredItemCreateResult[size];
                                for (int i = 0; i < size; i++) {
                                    results[i] =
                                            new MonitoredItemCreateResult(
                                                    StatusCode.GOOD,
                                                    uint(nextItemId++),
                                                    1000.0,
                                                    uint(10),
                                                    null);
                                }
                                response.complete(
                                        new CreateMonitoredItemsResponse(
                                                null, results, new DiagnosticInfo[0]));
                            }
                            return response;
                        })
                .when(client)
                .sendRequest(any(CreateMonitoredItemsRequest.class));
    }

    private void advertise(DataValue value) {
        doReturn(CompletableFuture.completedFuture(value))
                .when(client)
                .readValue(
                        0.0,
                        TimestampsToReturn.Neither,
                        Identifiers
                                .Server_ServerCapabilities_OperationLimits_MaxMonitoredItemsPerCall);
    }

    private List<TagDefinition> tags(int count) {
        List<TagDefinition> tags = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            tags.add(
                    new TagDefinition(
                            "tag" + i, new NodeId(2, "tag" + i), 100 + i, i + 1, i % 2 == 0));
        }
        return tags;
    }

    private List<Integer> batchSizes() {
        return sent.stream().map(r -> r.getItemsToCreate().length).collect(Collectors.toList());
    }

    @Test
    void respectsServerLimitAndPreservesEveryTagAcrossBatches() throws Exception {
        serverLimit = 50;
        advertise(new DataValue(new Variant(uint(serverLimit))));
        List<TagDefinition> tags = tags(101);
        OpcConnection.createMonitoredItems(
                client, subscription, tags, (tag, value) -> {}, new ConnectionAttempt(), 1000);
        assertEquals(Arrays.asList(50, 50, 1), batchSizes());
        assertEquals(tags.size(), subscription.getMonitoredItems().size());
        List<MonitoredItemCreateRequest> requests =
                sent.stream()
                        .flatMap(r -> Arrays.stream(r.getItemsToCreate()))
                        .collect(Collectors.toList());
        Set<Object> handles = new HashSet<>();
        for (int i = 0; i < tags.size(); i++) {
            TagDefinition tag = tags.get(i);
            MonitoredItemCreateRequest request = requests.get(i);
            assertEquals(tag.nodeId(), request.getItemToMonitor().getNodeId());
            MonitoringParameters parameters = request.getRequestedParameters();
            assertEquals(tag.samplingIntervalMs(), parameters.getSamplingInterval());
            assertEquals(uint(tag.queueSize()), parameters.getQueueSize());
            assertEquals(tag.discardOldest(), parameters.getDiscardOldest());
            assertTrue(handles.add(parameters.getClientHandle()));
        }
    }

    static Stream<DataValue> absentInvalidOrLargerLimits() {
        return Stream.of(
                new DataValue(new StatusCode(StatusCodes.Bad_NodeIdUnknown)),
                new DataValue(new StatusCode(StatusCodes.Bad_UserAccessDenied)),
                new DataValue(Variant.NULL_VALUE),
                new DataValue(new Variant("50")),
                new DataValue(new Variant(uint(0))),
                new DataValue(new Variant(uint(100))),
                new DataValue(new Variant(uint(1000))),
                new DataValue(new Variant(uint(0xffffffffL))));
    }

    @ParameterizedTest
    @MethodSource("absentInvalidOrLargerLimits")
    void retainsLocalCapWhenNoSmallerUsableLimitExists(DataValue limit) throws Exception {
        advertise(limit);
        OpcConnection.createMonitoredItems(
                client, subscription, tags(101), (tag, value) -> {}, new ConnectionAttempt(), 1000);
        assertEquals(Arrays.asList(100, 1), batchSizes());
    }

    @Test
    void timesOutTheLimitReadBeforeCreatingItems() {
        CompletableFuture<DataValue> pending = new CompletableFuture<>();
        doReturn(pending).when(client).readValue(anyDouble(), any(), any(NodeId.class));
        assertThrows(
                TimeoutException.class,
                () ->
                        OpcConnection.createMonitoredItems(
                                client,
                                subscription,
                                tags(1),
                                (tag, value) -> {},
                                new ConnectionAttempt(),
                                25));
        assertTrue(pending.isCancelled());
        assertTrue(sent.isEmpty());
    }
}
