/* SPDX-License-Identifier: Apache-2.0 */
package io.github.paf91.nifi.opcua;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.eclipse.milo.opcua.stack.core.types.builtin.NodeId;

import java.util.Set;

/**
 * One monitored tag from the bulk list or an individual property. Never infer NodeIds from aliases.
 */
final class TagDefinition {
    private final String alias;
    private final NodeId nodeId;
    private final double samplingIntervalMs;
    private final int queueSize;
    private final boolean discardOldest;

    TagDefinition(
            String alias,
            NodeId nodeId,
            double samplingIntervalMs,
            int queueSize,
            boolean discardOldest) {
        this.alias = alias;
        this.nodeId = nodeId;
        this.samplingIntervalMs = samplingIntervalMs;
        this.queueSize = queueSize;
        this.discardOldest = discardOldest;
    }

    String alias() {
        return alias;
    }

    NodeId nodeId() {
        return nodeId;
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
                            "nodeId", "samplingIntervalMs", "queueSize", "discardOldest"));

    static TagDefinition parse(String alias, String text, double sampling, int queue) {
        if (alias == null || alias.trim().isEmpty() || text == null || text.trim().isEmpty())
            throw new IllegalArgumentException("A tag requires a nonempty alias and NodeId");
        String id = text.trim();
        boolean discard = true;
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
            } catch (java.io.IOException e) {
                throw new IllegalArgumentException("Invalid tag JSON");
            }
        }
        if (!Double.isFinite(sampling) || sampling < 0 || sampling > 86400000)
            throw new IllegalArgumentException("samplingIntervalMs must be between 0 and 86400000");
        if (queue < 1 || queue > 1000000)
            throw new IllegalArgumentException("queueSize must be between 1 and 1000000");
        try {
            NodeId nodeId = NodeId.parse(id);
            if (nodeId.isNull()) throw new IllegalArgumentException();
            return new TagDefinition(alias, nodeId, sampling, queue, discard);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException(
                    "Invalid NodeId; use e.g. ns=2;s=Example.Tank.Temperature");
        }
    }
}
