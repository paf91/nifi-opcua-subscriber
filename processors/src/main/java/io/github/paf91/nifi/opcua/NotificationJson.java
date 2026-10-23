/* SPDX-License-Identifier: Apache-2.0 */
package io.github.paf91.nifi.opcua;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.eclipse.milo.opcua.stack.core.types.builtin.*;
import org.eclipse.milo.opcua.stack.core.types.builtin.unsigned.*;

import java.io.*;
import java.lang.reflect.Array;
import java.time.Instant;
import java.util.*;

final class NotificationJson {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final char[] HEX = "0123456789ABCDEF".toCharArray();
    static final int MAX_VALUE_DEPTH = 128;

    static byte[] encode(TagDefinition tag, DataValue value) throws java.io.IOException {
        return encode(tag, value, Integer.MAX_VALUE);
    }

    static byte[] encode(TagDefinition tag, DataValue value, int maximumBytes) throws IOException {
        String received = Instant.now().toString();
        Object raw = value.getValue() == null ? null : value.getValue().getValue();
        StatusCode status = value.getStatusCode();
        LimitedOutput output = new LimitedOutput(maximumBytes);
        try (JsonGenerator generator = JSON.getFactory().createGenerator(output)) {
            generator.writeStartObject();
            generator.writeStringField("tag", tag.alias());
            generator.writeStringField("nodeId", nodeIdText(tag.nodeId(), maximumBytes));
            if (tag.address().namespaceUri() != null) {
                generator.writeStringField("configuredNodeId", tag.address().text());
                generator.writeStringField("namespaceUri", tag.address().namespaceUri());
            }
            generator.writeFieldName("value");
            writeValue(generator, raw);
            generator.writeStringField(
                    "valueType", raw == null ? null : raw.getClass().getSimpleName());
            generator.writeStringField(
                    "statusCode", status == null ? null : statusText(status.getValue()));
            generator.writeStringField(
                    "quality",
                    status == null
                            ? "Unknown"
                            : status.isGood() ? "Good" : status.isBad() ? "Bad" : "Uncertain");
            generator.writeStringField("sourceTimestamp", timestamp(value.getSourceTime()));
            generator.writeStringField("receivedTimestamp", received);
            generator.writeEndObject();
        }
        return output.bytes.toByteArray();
    }

    private static String statusText(long value) {
        // OPC UA status codes are UInt32; retain the old representation for unusual SDK values.
        if ((value & 0xffffffff00000000L) != 0)
            return "0x" + Long.toHexString(value).toUpperCase(Locale.ROOT);
        char[] text = new char[10];
        text[0] = '0';
        text[1] = 'x';
        for (int i = 9; i >= 2; i--, value >>>= 4) text[i] = HEX[(int) (value & 15)];
        return new String(text);
    }

    private static void writeValue(JsonGenerator generator, Object value) throws IOException {
        writeValue(generator, value, 0);
    }

    static void writeValue(JsonGenerator generator, Object value, int depth) throws IOException {
        if (depth > MAX_VALUE_DEPTH) throw new IOException("OPC UA value nesting is too deep");
        if (OpcValue.writeMatrix(generator, value, depth)) return;
        if (value instanceof Variant)
            writeValue(generator, ((Variant) value).getValue(), depth + 1);
        else if (value instanceof ByteString) {
            ByteString binary = (ByteString) value;
            if (binary.isNull()) generator.writeNull();
            else generator.writeBinary(binary.bytes());
        } else if (value != null && value.getClass().isArray()) {
            generator.writeStartArray();
            for (int i = 0; i < Array.getLength(value); i++)
                writeValue(generator, Array.get(value, i), depth + 1);
            generator.writeEndArray();
        } else if (value instanceof ExtensionObject) {
            ExtensionObject extension = (ExtensionObject) value;
            Object body = extension.getBody();
            generator.writeStartObject();
            generator.writeStringField(
                    "encoding",
                    body instanceof ByteString
                            ? "opcua-binary"
                            : body instanceof XmlElement ? "opcua-xml" : "opcua-json");
            generator.writeStringField(
                    OpcValue.jsonEncoded(extension) ? "typeId" : "encodingId",
                    nodeIdText(
                            OpcValue.encodingId(extension),
                            ((LimitedOutput) generator.getOutputTarget()).limit));
            generator.writeFieldName("body");
            // Preserve opaque bodies without decoding them or materializing a display string.
            writeValue(generator, body, depth + 1);
            generator.writeEndObject();
        } else if (value instanceof XmlElement) {
            generator.writeString(((XmlElement) value).getFragment());
        } else if (value instanceof NodeId) {
            generator.writeString(
                    nodeIdText(
                            (NodeId) value, ((LimitedOutput) generator.getOutputTarget()).limit));
        } else generator.writeObject(normalize(value));
    }

    private static String nodeIdText(NodeId value, int limit) throws RecordTooLargeException {
        Object identifier = value.getIdentifier();
        if ((identifier instanceof String && ((String) identifier).length() > limit)
                || (identifier instanceof ByteString
                        && ((ByteString) identifier).length() > limit * 3L / 4))
            throw new RecordTooLargeException();
        return value.toParseableString();
    }

    static final class RecordTooLargeException extends IOException {
        RecordTooLargeException() {
            super("Encoded OPC UA notification exceeds Maximum Record Bytes");
        }
    }

    private static final class LimitedOutput extends OutputStream {
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        private final int limit;

        LimitedOutput(int limit) {
            this.limit = limit;
        }

        @Override
        public void write(int value) throws IOException {
            if (bytes.size() >= limit) throw new RecordTooLargeException();
            bytes.write(value);
        }

        @Override
        public void write(byte[] value, int offset, int length) throws IOException {
            if (length > limit - bytes.size()) throw new RecordTooLargeException();
            bytes.write(value, offset, length);
        }
    }

    private static String timestamp(DateTime date) {
        return date == null || date.getUtcTime() == 0 ? null : date.getJavaInstant().toString();
    }

    static Object normalize(Object value) {
        if (value == null) return null;
        if (value instanceof ULong)
            return value.toString(); // exact beyond JSON/JavaScript integer precision
        if (value instanceof UInteger || value instanceof UShort || value instanceof UByte)
            return ((Number) value).longValue();
        if (value instanceof Long) return value.toString();
        if (value instanceof Double && !Double.isFinite((Double) value)) return value.toString();
        if (value instanceof Float && !Float.isFinite((Float) value)) return value.toString();
        if (value instanceof String
                || value instanceof Boolean
                || value instanceof Byte
                || value instanceof Short
                || value instanceof Integer
                || value instanceof Float
                || value instanceof Double) return value;
        if (value instanceof ByteString) {
            ByteString bytes = (ByteString) value;
            return bytes.isNull() ? null : Base64.getEncoder().encodeToString(bytes.bytes());
        }
        if (value instanceof DateTime) return timestamp((DateTime) value);
        if (value instanceof NodeId) return ((NodeId) value).toParseableString();
        if (value instanceof LocalizedText) {
            LocalizedText text = (LocalizedText) value;
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("text", text.getText());
            result.put("locale", text.getLocale());
            return result;
        }
        if (value instanceof Variant) return normalize(((Variant) value).getValue());
        if (value.getClass().isArray()) {
            List<Object> values = new ArrayList<>();
            for (int i = 0; i < Array.getLength(value); i++)
                values.add(normalize(Array.get(value, i)));
            return values;
        }
        // Unknown implementations can allocate arbitrarily in toString(). Do not invoke them.
        Map<String, Object> fallback = new LinkedHashMap<>();
        boolean boundedDisplay = value instanceof UUID || value instanceof StatusCode;
        fallback.put("encoding", boundedDisplay ? "display" : "unsupported");
        fallback.put("type", value.getClass().getName());
        if (boundedDisplay) fallback.put("text", value.toString());
        return fallback;
    }
}
