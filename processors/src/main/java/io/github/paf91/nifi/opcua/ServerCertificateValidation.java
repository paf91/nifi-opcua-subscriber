/* SPDX-License-Identifier: Apache-2.0 */
package io.github.paf91.nifi.opcua;

import org.eclipse.milo.opcua.stack.core.util.validation.ValidationCheck;

import java.util.EnumSet;
import java.util.Set;

/** Server identity verification, independent of channel security and user authentication. */
enum ServerCertificateValidation {
    FULL("Full"),
    CERTIFICATE("Certificate"),
    NONE("None");

    private final String value;

    ServerCertificateValidation(String value) {
        this.value = value;
    }

    String value() {
        return value;
    }

    static ServerCertificateValidation parse(String value) {
        for (ServerCertificateValidation mode : values()) if (mode.value.equals(value)) return mode;
        throw new IllegalArgumentException("Unknown Server Certificate Validation: " + value);
    }

    Set<ValidationCheck> optionalChecks() {
        if (this == NONE)
            throw new IllegalStateException(
                    "None requires bypassing the server certificate validator");
        // Never mutate Milo's shared defaults or another processor's configuration.
        Set<ValidationCheck> checks = EnumSet.copyOf(ValidationCheck.ALL_OPTIONAL_CHECKS);
        if (this == CERTIFICATE) checks.remove(ValidationCheck.HOSTNAME);
        return checks;
    }
}
