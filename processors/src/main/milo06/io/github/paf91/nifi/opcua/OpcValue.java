/* SPDX-License-Identifier: Apache-2.0 */
package io.github.paf91.nifi.opcua;

import com.fasterxml.jackson.core.JsonGenerator;

import org.eclipse.milo.opcua.stack.core.types.builtin.ExtensionObject;
import org.eclipse.milo.opcua.stack.core.types.builtin.NodeId;

final class OpcValue {
    static boolean writeMatrix(JsonGenerator generator, Object value, int depth) {
        // Milo 0.6 already decodes matrices as nested Java arrays.
        return false;
    }

    static NodeId encodingId(ExtensionObject value) {
        return value.getEncodingId();
    }

    static boolean jsonEncoded(ExtensionObject value) {
        return false;
    }
}
