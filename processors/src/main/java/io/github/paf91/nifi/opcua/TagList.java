/* SPDX-License-Identifier: Apache-2.0 */
package io.github.paf91.nifi.opcua;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.*;

/** Shared parser for configuration validation and subscription creation. */
final class TagList {
    private static final ObjectMapper JSON =
            new ObjectMapper()
                    .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                    .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    static List<TagDefinition> parse(
            String text, Map<String, String> individual, double sampling, int queue) {
        List<TagDefinition> tags = new ArrayList<>();
        Map<String, String> aliases = new HashMap<>();
        Map<TagAddress, String> nodes = new HashMap<>();
        String input = text == null ? "" : text.trim();
        if (input.startsWith("\uFEFF")) input = input.substring(1).trim();
        if (input.startsWith("[") || input.startsWith("{")) {
            JsonNode array;
            try {
                array = JSON.readTree(input);
            } catch (java.io.IOException e) {
                throw new IllegalArgumentException("Tags: invalid JSON array");
            }
            if (!array.isArray())
                throw new IllegalArgumentException(
                        "Tags: JSON must be an array of NodeIds or tag objects");
            for (int i = 0; i < array.size(); i++) {
                String source = "Tags entry " + (i + 1);
                try {
                    JsonNode entry = array.get(i);
                    String alias;
                    String value;
                    if (entry.isTextual()) {
                        alias = entry.textValue().trim();
                        value = alias;
                    } else if (entry.isObject()) {
                        ObjectNode options = ((ObjectNode) entry).deepCopy();
                        JsonNode name = options.remove("alias");
                        if (name != null
                                && (!name.isTextual() || name.textValue().trim().isEmpty()))
                            throw new IllegalArgumentException("alias must be a nonempty string");
                        if (!options.hasNonNull("nodeId") || !options.get("nodeId").isTextual())
                            throw new IllegalArgumentException("nodeId must be a string");
                        alias =
                                name == null
                                        ? options.get("nodeId").textValue()
                                        : name.textValue().trim();
                        value = options.toString();
                    } else
                        throw new IllegalArgumentException(
                                "Expected a NodeId string or tag object");
                    add(
                            tags,
                            aliases,
                            nodes,
                            TagDefinition.parse(alias, value, sampling, queue),
                            source);
                } catch (IllegalArgumentException e) {
                    throw at(source, e);
                }
            }
        } else {
            String[] lines = input.split("\\r\\n|\\n|\\r", -1);
            for (int i = 0; i < lines.length; i++) {
                String line = lines[i].trim();
                if (line.isEmpty() || line.startsWith("#")) continue;
                String source = "Tags line " + (i + 1);
                try {
                    String alias = line, value = line;
                    // A NodeId may itself contain pipes, commas, semicolons or equals signs.
                    // Recognize a complete NodeId prefix before considering the alias separator.
                    if (!line.startsWith("nsu=") && !line.matches("^(?:ns=[0-9]+;)?[isgb]=.*")) {
                        int separator = line.indexOf('|');
                        if (separator >= 0) {
                            alias = line.substring(0, separator).trim();
                            value = line.substring(separator + 1).trim();
                        }
                    }
                    add(
                            tags,
                            aliases,
                            nodes,
                            TagDefinition.parse(alias, value, sampling, queue),
                            source);
                } catch (IllegalArgumentException e) {
                    throw at(source, e);
                }
            }
        }
        for (Map.Entry<String, String> entry : new TreeMap<>(individual).entrySet()) {
            String source = "Tag property '" + entry.getKey() + "'";
            try {
                add(
                        tags,
                        aliases,
                        nodes,
                        TagDefinition.parse(entry.getKey(), entry.getValue(), sampling, queue),
                        source);
            } catch (IllegalArgumentException e) {
                throw at(source, e);
            }
        }
        if (tags.isEmpty())
            throw new IllegalArgumentException(
                    "Enter at least one NodeId in Tags or add an individual tag property");
        return tags;
    }

    private static void add(
            List<TagDefinition> tags,
            Map<String, String> aliases,
            Map<TagAddress, String> nodes,
            TagDefinition tag,
            String source) {
        if (aliases.containsKey(tag.alias()))
            throw new IllegalArgumentException(
                    "Duplicate alias '"
                            + tag.alias()
                            + "'; already defined at "
                            + aliases.get(tag.alias()));
        if (nodes.containsKey(tag.address()))
            throw new IllegalArgumentException(
                    "Duplicate NodeId; already defined at " + nodes.get(tag.address()));
        aliases.put(tag.alias(), source);
        nodes.put(tag.address(), source);
        tags.add(tag);
    }

    private static IllegalArgumentException at(String source, IllegalArgumentException cause) {
        return new IllegalArgumentException(source + ": " + cause.getMessage());
    }
}
