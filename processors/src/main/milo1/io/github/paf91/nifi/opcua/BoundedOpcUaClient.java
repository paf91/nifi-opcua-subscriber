/* SPDX-License-Identifier: Apache-2.0 */
package io.github.paf91.nifi.opcua;

import org.eclipse.milo.opcua.sdk.client.OpcUaClient;
import org.eclipse.milo.opcua.sdk.client.OpcUaClientConfig;
import org.eclipse.milo.opcua.stack.core.AttributeId;
import org.eclipse.milo.opcua.stack.core.UaException;
import org.eclipse.milo.opcua.stack.core.types.*;
import org.eclipse.milo.opcua.stack.core.types.builtin.*;
import org.eclipse.milo.opcua.stack.core.types.builtin.unsigned.UInteger;
import org.eclipse.milo.opcua.stack.core.types.enumerated.TimestampsToReturn;
import org.eclipse.milo.opcua.stack.core.types.structured.*;
import org.eclipse.milo.opcua.stack.transport.client.OpcClientTransport;

import java.util.*;
import java.util.concurrent.CompletableFuture;

/** Retains Milo's subscription bookkeeping and batching while bounding its blocking setup calls. */
class BoundedOpcUaClient extends OpcUaClient {
    private final ConnectionAttempt attempt;
    private final long timeout;
    private volatile boolean initialSetup = true;

    BoundedOpcUaClient(
            OpcUaClientConfig config, OpcClientTransport transport, ConnectionAttempt attempt) {
        super(config, transport);
        this.attempt = attempt;
        timeout = config.getRequestTimeout().longValue();
    }

    void setupComplete() {
        initialSetup = false;
    }

    @Override
    public CreateMonitoredItemsResponse createMonitoredItems(
            UInteger subscriptionId,
            TimestampsToReturn timestamps,
            List<MonitoredItemCreateRequest> requests)
            throws UaException {
        attempt.check();
        // After initial setup, leave automatic subscription recovery's error handling to Milo.
        if (!initialSetup) return super.createMonitoredItems(subscriptionId, timestamps, requests);
        return BoundedRequest.execute(
                attempt,
                timeout,
                () -> super.createMonitoredItemsAsync(subscriptionId, timestamps, requests));
    }

    @Override
    public List<DataValue> readValues(
            double maxAge, TimestampsToReturn timestamps, List<NodeId> nodes) throws UaException {
        attempt.check();
        if (!initialSetup) return super.readValues(maxAge, timestamps, nodes);
        // Milo lazily reads operation limits here, including its single-node fallback through
        // readValue(). Await the original read future so cancellation also fences session recovery.
        List<ReadValueId> requests = new ArrayList<>(nodes.size());
        for (NodeId node : nodes)
            requests.add(
                    new ReadValueId(node, AttributeId.Value.uid(), null, QualifiedName.NULL_VALUE));
        ReadResponse response =
                BoundedRequest.execute(
                        attempt, timeout, () -> super.readAsync(maxAge, timestamps, requests));
        return Arrays.asList(response.getResults());
    }

    @Override
    public CompletableFuture<UaResponseMessageType> sendRequestAsync(UaRequestMessageType request) {
        // A session can arrive on another thread after Stop. Fence at the actual send boundary
        // as well as before each blocking operation. Keep deletion/close requests available.
        if (request instanceof CreateMonitoredItemsRequest || request instanceof ReadRequest)
            return attempt.callIfActive(() -> super.sendRequestAsync(request));
        return super.sendRequestAsync(request);
    }
}
