/* SPDX-License-Identifier: Apache-2.0 */
package io.github.paf91.nifi.opcua;

final class OpcValue {
    static Object nestedArray(Object value) {
        // Milo 0.6 already decodes matrices as nested Java arrays.
        return value;
    }
}
