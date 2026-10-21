/* SPDX-License-Identifier: Apache-2.0 */
package io.github.paf91.nifi.opcua;

import org.eclipse.milo.opcua.stack.core.types.builtin.NodeId;
import org.eclipse.milo.opcua.stack.core.types.builtin.unsigned.Unsigned;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

/** Configured address, independent of the namespace indexes assigned to a session. */
final class TagAddress {
    private final NodeId identifier;
    private final String namespaceUri;

    private TagAddress(NodeId identifier, String namespaceUri) {
        this.identifier = identifier;
        this.namespaceUri = namespaceUri;
    }

    static TagAddress numeric(NodeId nodeId) {
        return new TagAddress(nodeId, null);
    }

    static TagAddress parse(String text) {
        if (text.codePoints().anyMatch(Character::isISOControl))
            throw new IllegalArgumentException("NodeId cannot contain control characters");
        if (!text.startsWith("nsu=")) {
            NodeId id = NodeId.parse(text);
            if (id.isNull()) throw new IllegalArgumentException("Null NodeId");
            return numeric(id);
        }
        int end = text.indexOf(';', 4);
        if (end < 5 || !text.substring(end + 1).matches("^[isgb]=.*"))
            throw new IllegalArgumentException("Use nsu=<namespace URI>;<identifier>");
        String uri = decodeUri(text.substring(4, end));
        if (!URI.create(uri).isAbsolute())
            throw new IllegalArgumentException("Namespace URI must be absolute");
        // Index 1 is a placeholder, never used for a request. It permits i=0 in a nonzero
        // namespace.
        return new TagAddress(NodeId.parse("ns=1;" + text.substring(end + 1)), uri);
    }

    private static String decodeUri(String encoded) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            for (int i = 0; i < encoded.length(); ) {
                if (encoded.charAt(i) == '%') {
                    if (i + 2 >= encoded.length()) throw new IllegalArgumentException();
                    int high = Character.digit(encoded.charAt(i + 1), 16);
                    int low = Character.digit(encoded.charAt(i + 2), 16);
                    if (high < 0 || low < 0) throw new IllegalArgumentException();
                    bytes.write((high << 4) | low);
                    i += 3;
                } else {
                    int cp = encoded.codePointAt(i);
                    byte[] utf8 =
                            new String(Character.toChars(cp)).getBytes(StandardCharsets.UTF_8);
                    bytes.write(utf8, 0, utf8.length);
                    i += Character.charCount(cp);
                }
            }
            String value =
                    StandardCharsets.UTF_8
                            .newDecoder()
                            .decode(ByteBuffer.wrap(bytes.toByteArray()))
                            .toString();
            if (value.codePoints().anyMatch(Character::isISOControl))
                throw new IllegalArgumentException();
            return value;
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid percent-encoded namespace URI");
        }
    }

    String namespaceUri() {
        return namespaceUri;
    }

    NodeId resolve(String[] namespaces) {
        if (namespaceUri == null) return identifier;
        int match = -1;
        if (namespaces != null) {
            for (int i = 0; i < namespaces.length; i++) {
                if (namespaceUri.equals(namespaces[i])) {
                    if (match >= 0)
                        throw new IllegalArgumentException(
                                "Ambiguous namespace URI: " + namespaceUri);
                    match = i;
                }
            }
        }
        if (match < 0)
            throw new IllegalArgumentException(
                    "Server NamespaceArray does not contain " + namespaceUri);
        if (match > 65535) throw new IllegalArgumentException("Namespace index exceeds UInt16");
        NodeId resolved = identifier.withNamespaceIndex(Unsigned.ushort(match));
        if (resolved.isNull())
            throw new IllegalArgumentException("Namespace URI resolves to a null NodeId");
        return resolved;
    }

    String text() {
        if (namespaceUri == null) return identifier.toParseableString();
        String id = identifier.toParseableString();
        return "nsu="
                + namespaceUri.replace("%", "%25").replace(";", "%3B")
                + id.substring(id.indexOf(';'));
    }

    @Override
    public boolean equals(Object other) {
        if (!(other instanceof TagAddress)) return false;
        TagAddress that = (TagAddress) other;
        return identifier.equals(that.identifier)
                && Objects.equals(namespaceUri, that.namespaceUri);
    }

    @Override
    public int hashCode() {
        return Objects.hash(identifier, namespaceUri);
    }
}
