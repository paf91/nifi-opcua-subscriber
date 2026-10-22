/* SPDX-License-Identifier: Apache-2.0 */
package io.github.paf91.nifi.opcua;

import org.eclipse.milo.opcua.stack.core.security.FileBasedTrustListManager;

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
            OpcConnection.serverValidator(mode, null)
                    .validateCertificateChain(chain, uri, new String[] {host});
        } else {
            try (FileBasedTrustListManager trust =
                    FileBasedTrustListManager.createAndInitialize(pki)) {
                OpcConnection.serverValidator(mode, trust)
                        .validateCertificateChain(chain, uri, new String[] {host});
            }
        }
    }
}
