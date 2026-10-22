/* SPDX-License-Identifier: Apache-2.0 */
package io.github.paf91.nifi.opcua;

import org.eclipse.milo.opcua.stack.core.security.DefaultTrustListManager;

import java.nio.file.Path;
import java.security.cert.X509Certificate;
import java.util.List;

final class TestServerTrust {
    static void validate(
            ServerCertificateValidation mode,
            Path pki,
            List<X509Certificate> chain,
            String uri,
            String host)
            throws Exception {
        if (mode == ServerCertificateValidation.NONE) {
            OpcConnection.serverValidator(mode, null).validateCertificateChain(chain, uri, host);
        } else {
            try (DefaultTrustListManager trust = new DefaultTrustListManager(pki.toFile())) {
                OpcConnection.serverValidator(mode, trust)
                        .validateCertificateChain(chain, uri, host);
            }
        }
    }
}
