/* SPDX-License-Identifier: Apache-2.0 */
package io.github.paf91.nifi.opcua;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.*;

import io.netty.buffer.*;

import org.eclipse.milo.opcua.stack.core.serialization.OpcUaBinaryStreamDecoder;
import org.eclipse.milo.opcua.stack.core.serialization.SerializationContext;
import org.eclipse.milo.opcua.stack.core.types.builtin.*;
import org.junit.jupiter.api.Test;

class MatrixEncodingTest {
    @Test
    void realWireMatrixRetainsItsNumericArrayShape() throws Exception {
        // OPC UA Binary Variant: Int32, array + dimensions, 4 elements, 2x2.
        ByteBuf wire = Unpooled.buffer();
        try {
            wire.writeByte(0xC6).writeIntLE(4);
            for (int i = 1; i <= 4; i++) wire.writeIntLE(i);
            wire.writeIntLE(2).writeIntLE(2).writeIntLE(2);
            SerializationContext context = mock(SerializationContext.class, RETURNS_DEEP_STUBS);
            when(context.getEncodingLimits().getMaxRecursionDepth()).thenReturn(128);
            when(context.getEncodingLimits().getMaxMessageSize()).thenReturn(65536);
            Variant decoded = new OpcUaBinaryStreamDecoder(context).setBuffer(wire).readVariant();
            assertEquals(0, wire.readableBytes());
            JsonNode record =
                    new ObjectMapper()
                            .readTree(
                                    NotificationJson.encode(
                                            TagDefinition.parse("matrix", "ns=2;s=Matrix", 100, 10),
                                            new DataValue(decoded)));
            assertEquals(
                    new ObjectMapper().readTree("[[1,2],[3,4]]"),
                    record.get("value"),
                    "An OPC UA numeric matrix must remain a numeric JSON array");
        } finally {
            wire.release();
        }
    }
}
