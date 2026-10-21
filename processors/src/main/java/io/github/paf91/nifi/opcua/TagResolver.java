/* SPDX-License-Identifier: Apache-2.0 */
package io.github.paf91.nifi.opcua;

import org.eclipse.milo.opcua.stack.core.types.builtin.*;

import java.util.*;

final class TagResolver {
    static boolean needsNamespaces(List<TagDefinition> tags) {
        return tags.stream().anyMatch(tag -> tag.address().namespaceUri() != null);
    }

    static String[] namespaceArray(DataValue value) {
        if (value == null
                || value.getStatusCode() == null
                || !value.getStatusCode().isGood()
                || value.getValue() == null
                || !(value.getValue().getValue() instanceof String[]))
            throw new IllegalArgumentException(
                    "Could not read server NamespaceArray as a Good String array"
                            + (value == null ? "" : ": " + value.getStatusCode()));
        return ((String[]) value.getValue().getValue()).clone();
    }

    static List<TagDefinition> resolve(List<TagDefinition> tags, String[] namespaces) {
        List<TagDefinition> resolved = new ArrayList<>();
        Map<NodeId, String> seen = new HashMap<>();
        for (TagDefinition tag : tags) {
            NodeId id;
            try {
                id = tag.address().resolve(namespaces);
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("Tag '" + tag.alias() + "': " + e.getMessage());
            }
            String previous = seen.put(id, tag.alias());
            if (previous != null)
                throw new IllegalArgumentException(
                        "Tags '"
                                + previous
                                + "' and '"
                                + tag.alias()
                                + "' resolve to the same NodeId "
                                + id.toParseableString());
            resolved.add(tag.resolved(id));
        }
        return resolved;
    }
}
