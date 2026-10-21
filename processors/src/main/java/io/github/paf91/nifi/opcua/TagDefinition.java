/* SPDX-License-Identifier: Apache-2.0 */
package io.github.paf91.nifi.opcua;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.eclipse.milo.opcua.stack.core.types.builtin.NodeId;
import org.eclipse.milo.opcua.stack.core.types.builtin.unsigned.Unsigned;
import org.eclipse.milo.opcua.stack.core.types.enumerated.DataChangeTrigger;
import org.eclipse.milo.opcua.stack.core.types.structured.DataChangeFilter;

import java.util.Set;

/**
 * One monitored tag from the bulk list or an individual property. Never infer NodeIds from aliases.
 */
final class TagDefinition {
    private final String alias;
    private final NodeId nodeId;
    private final TagAddress address;
    private final double samplingIntervalMs;
    private final int queueSize;
    private final boolean discardOldest;
    private final int deadbandType;
    private final double deadbandValue;

    TagDefinition(
            String alias,
            NodeId nodeId,
            double samplingIntervalMs,
            int queueSize,
            boolean discardOldest) {
        this(
                alias,
                TagAddress.numeric(nodeId),
                nodeId,
                samplingIntervalMs,
                queueSize,
                discardOldest,
                0,
                0);
    }

    private TagDefinition(
            String alias,
            TagAddress address,
            NodeId nodeId,
            double samplingIntervalMs,
            int queueSize,
            boolean discardOldest,
            int deadbandType,
            double deadbandValue) {
        this.alias = alias;
        this.address = address;
        this.nodeId = nodeId;
        this.samplingIntervalMs = samplingIntervalMs;
        this.queueSize = queueSize;
        this.discardOldest = discardOldest;
        this.deadbandType = deadbandType;
        this.deadbandValue = deadbandValue;
    }

    String alias() {
        return alias;
    }

    NodeId nodeId() {
        if (nodeId == null)
            throw new IllegalStateException("Namespace URI has not been resolved for tag " + alias);
        return nodeId;
    }

    TagAddress address() {
        return address;
    }

    TagDefinition resolved(NodeId id) {
        return new TagDefinition(
                alias,
                address,
                id,
                samplingIntervalMs,
                queueSize,
                discardOldest,
                deadbandType,
                deadbandValue);
    }

    DataChangeFilter filter() {
        return deadbandType == 0
                ? null
                : new DataChangeFilter(
                        DataChangeTrigger.StatusValue, Unsigned.uint(deadbandType), deadbandValue);
    }

    double samplingIntervalMs() {
        return samplingIntervalMs;
    }

    int queueSize() {
        return queueSize;
    }

    boolean discardOldest() {
        return discardOldest;
    }

    private static final ObjectMapper JSON =
            new ObjectMapper()
                    .enable(
                            com.fasterxml.jackson.core.JsonParser.Feature
                                    .STRICT_DUPLICATE_DETECTION)
                    .enable(
                            com.fasterxml.jackson.databind.DeserializationFeature
                                    .FAIL_ON_TRAILING_TOKENS);
    private static final Set<String> FIELDS =
            new java.util.HashSet<>(
                    java.util.Arrays.asList(
                            "nodeId",
                            "samplingIntervalMs",
                            "queueSize",
                            "discardOldest",
                            "deadbandType",
                            "deadbandValue"));

    static TagDefinition parse(String alias, String text, double sampling, int queue) {
        if (alias == null || alias.trim().isEmpty() || text == null || text.trim().isEmpty())
            throw new IllegalArgumentException("A tag requires a nonempty alias and NodeId");
        String id = text.trim();
        boolean discard = true;
        int deadbandType = 0;
        double deadbandValue = 0;
        if (id.startsWith("{")) {
            try {
                JsonNode node = JSON.readTree(id);
                java.util.Iterator<String> fields = node.fieldNames();
                while (fields.hasNext()) {
                    if (!FIELDS.contains(fields.next()))
                        throw new IllegalArgumentException("Unknown tag option");
                }
                if (!node.hasNonNull("nodeId") || !node.get("nodeId").isTextual())
                    throw new IllegalArgumentException("nodeId must be a string");
                id = node.get("nodeId").textValue();
                if (node.has("samplingIntervalMs")) {
                    if (!node.get("samplingIntervalMs").isNumber())
                        throw new IllegalArgumentException("samplingIntervalMs must be numeric");
                    sampling = node.get("samplingIntervalMs").doubleValue();
                }
                if (node.has("queueSize")) {
                    if (!node.get("queueSize").isIntegralNumber()
                            || !node.get("queueSize").canConvertToInt())
                        throw new IllegalArgumentException("queueSize must be an integer");
                    queue = node.get("queueSize").intValue();
                }
                if (node.has("discardOldest")) {
                    if (!node.get("discardOldest").isBoolean())
                        throw new IllegalArgumentException("discardOldest must be boolean");
                    discard = node.get("discardOldest").booleanValue();
                }
                if (node.has("deadbandType")) {
                    if (!node.get("deadbandType").isTextual())
                        throw new IllegalArgumentException(
                                "deadbandType must be None, Absolute or Percent");
                    String type = node.get("deadbandType").textValue();
                    if ("Absolute".equals(type)) deadbandType = 1;
                    else if ("Percent".equals(type)) deadbandType = 2;
                    else if (!"None".equals(type))
                        throw new IllegalArgumentException(
                                "deadbandType must be None, Absolute or Percent");
                }
                if (node.has("deadbandValue")) {
                    if (!node.has("deadbandType") || !node.get("deadbandValue").isNumber())
                        throw new IllegalArgumentException(
                                "deadbandValue requires deadbandType and a numeric value");
                    deadbandValue = node.get("deadbandValue").doubleValue();
                } else if (deadbandType != 0)
                    throw new IllegalArgumentException(
                            "deadbandValue is required for Absolute or Percent");
            } catch (java.io.IOException e) {
                throw new IllegalArgumentException("Invalid tag JSON");
            }
        }
        if (!Double.isFinite(sampling) || sampling < 0 || sampling > 86400000)
            throw new IllegalArgumentException("samplingIntervalMs must be between 0 and 86400000");
        if (queue < 1 || queue > 1000000)
            throw new IllegalArgumentException("queueSize must be between 1 and 1000000");
        if (!Double.isFinite(deadbandValue)
                || deadbandValue < 0
                || (deadbandType == 2 && deadbandValue > 100)
                || (deadbandType == 0 && deadbandValue != 0))
            throw new IllegalArgumentException(
                    "deadbandValue must be finite and nonnegative, 0–100 for Percent, and 0 for"
                            + " None");
        try {
            TagAddress address = TagAddress.parse(id);
            return new TagDefinition(
                    alias,
                    address,
                    address.namespaceUri() == null ? address.resolve(null) : null,
                    sampling,
                    queue,
                    discard,
                    deadbandType,
                    deadbandValue);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException(
                    "Invalid NodeId; use ns=2;s=Example.Tank.Temperature or"
                            + " nsu=urn:example:minute;s=Temperature: "
                            + e.getMessage());
        }
    }
}
