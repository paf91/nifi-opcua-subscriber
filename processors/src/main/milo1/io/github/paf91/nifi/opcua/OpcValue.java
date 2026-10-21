/* SPDX-License-Identifier: Apache-2.0 */
package io.github.paf91.nifi.opcua;

import org.eclipse.milo.opcua.stack.core.types.builtin.Matrix;

final class OpcValue {
    static Object nestedArray(Object value) {
        return value instanceof Matrix ? ((Matrix) value).nestedArrayValue() : value;
    }
}
