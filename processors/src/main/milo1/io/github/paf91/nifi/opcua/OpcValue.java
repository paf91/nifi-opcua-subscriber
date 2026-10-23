/* SPDX-License-Identifier: Apache-2.0 */
package io.github.paf91.nifi.opcua;

import com.fasterxml.jackson.core.JsonGenerator;

import org.eclipse.milo.opcua.stack.core.types.builtin.ExtensionObject;
import org.eclipse.milo.opcua.stack.core.types.builtin.Matrix;
import org.eclipse.milo.opcua.stack.core.types.builtin.NodeId;

import java.io.IOException;
import java.lang.reflect.Array;

final class OpcValue {
    static boolean writeMatrix(JsonGenerator generator, Object value, int depth)
            throws IOException {
        if (!(value instanceof Matrix)) return false;
        Matrix matrix = (Matrix) value;
        Object elements = matrix.getElements();
        if (elements == null) {
            generator.writeNull();
            return true;
        }
        int[] dimensions = matrix.getDimensions();
        if (dimensions.length == 0 || dimensions.length > NotificationJson.MAX_VALUE_DEPTH - depth)
            throw new IOException("Invalid or too deeply nested OPC UA matrix");
        boolean empty = false;
        for (int dimension : dimensions) {
            if (dimension < 0) throw new IOException("Negative OPC UA matrix dimension");
            if (dimension == 0) empty = true;
        }
        long count = empty ? 0 : 1;
        if (!empty) {
            for (int dimension : dimensions) {
                count *= dimension;
                if (count > Integer.MAX_VALUE) throw new IOException("OPC UA matrix is too large");
            }
        }
        if (!elements.getClass().isArray() || count != Array.getLength(elements))
            throw new IOException("OPC UA matrix dimensions do not match its elements");
        writeDimension(generator, elements, dimensions, 0, 0, depth);
        return true;
    }

    private static int writeDimension(
            JsonGenerator generator,
            Object elements,
            int[] dimensions,
            int axis,
            int offset,
            int depth)
            throws IOException {
        generator.writeStartArray();
        for (int i = 0; i < dimensions[axis]; i++) {
            if (axis + 1 == dimensions.length)
                NotificationJson.writeValue(
                        generator, Array.get(elements, offset++), depth + axis + 1);
            else offset = writeDimension(generator, elements, dimensions, axis + 1, offset, depth);
        }
        generator.writeEndArray();
        return offset;
    }

    static NodeId encodingId(ExtensionObject value) {
        return value.getEncodingOrTypeId();
    }

    static boolean jsonEncoded(ExtensionObject value) {
        return value instanceof ExtensionObject.Json;
    }
}
