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

    static byte[] encode(TagDefinition tag, DataValue value) throws java.io.IOException {
        return encode(tag, value, Integer.MAX_VALUE);
    }

    static byte[] encode(TagDefinition tag, DataValue value, int maximumBytes) throws IOException {
        Map<String, Object> record = new LinkedHashMap<>();
        record.put("tag", tag.alias());
        record.put("nodeId", tag.nodeId().toParseableString());
        if (tag.address().namespaceUri() != null) {
            record.put("configuredNodeId", tag.address().text());
            record.put("namespaceUri", tag.address().namespaceUri());
        }
        Object raw = value.getValue() == null ? null : value.getValue().getValue();
        record.put("value", raw);
        record.put("valueType", raw == null ? null : raw.getClass().getSimpleName());
        StatusCode status = value.getStatusCode();
        record.put(
                "statusCode", status == null ? null : String.format("0x%08X", status.getValue()));
        record.put(
                "quality",
                status == null
                        ? "Unknown"
                        : status.isGood() ? "Good" : status.isBad() ? "Bad" : "Uncertain");
        record.put("sourceTimestamp", timestamp(value.getSourceTime()));
        record.put("receivedTimestamp", Instant.now().toString());
        LimitedOutput output = new LimitedOutput(maximumBytes);
        try (JsonGenerator generator = JSON.getFactory().createGenerator(output)) {
            generator.writeStartObject();
            for (Map.Entry<String, Object> field : record.entrySet()) {
                generator.writeFieldName(field.getKey());
                if (field.getKey().equals("value")) writeValue(generator, field.getValue());
                else generator.writeObject(field.getValue());
            }
            generator.writeEndObject();
        }
        return output.bytes.toByteArray();
    }

    private static void writeValue(JsonGenerator generator, Object value) throws IOException {
        value = OpcValue.nestedArray(value);
        if (value instanceof Variant) writeValue(generator, ((Variant) value).getValue());
        else if (value instanceof ByteString) {
            ByteString binary = (ByteString) value;
            if (binary.isNull()) generator.writeNull();
            else generator.writeBinary(binary.bytes());
        } else if (value != null && value.getClass().isArray()) {
            generator.writeStartArray();
            for (int i = 0; i < Array.getLength(value); i++)
                writeValue(generator, Array.get(value, i));
            generator.writeEndArray();
        } else generator.writeObject(normalize(value));
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
        value = OpcValue.nestedArray(value);
        if (value == null) return null;
        if (value instanceof ULong)
            return value.toString(); // exact beyond JSON/JavaScript integer precision
        if (value instanceof UInteger || value instanceof UShort || value instanceof UByte)
            return ((Number) value).longValue();
        if (value instanceof Long) return value.toString();
        if (value instanceof Double && !Double.isFinite((Double) value)) return value.toString();
        if (value instanceof Float && !Float.isFinite((Float) value)) return value.toString();
        if (value instanceof String || value instanceof Boolean || value instanceof Number)
            return value;
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
        // Do not silently stringify proprietary structures and pretend their type was preserved.
        Map<String, Object> fallback = new LinkedHashMap<>();
        fallback.put("encoding", "display");
        fallback.put("type", value.getClass().getName());
        fallback.put("text", value.toString());
        return fallback;
    }
}
