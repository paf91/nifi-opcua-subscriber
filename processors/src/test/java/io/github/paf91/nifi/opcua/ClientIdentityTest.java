/* SPDX-License-Identifier: Apache-2.0 */
package io.github.paf91.nifi.opcua;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import org.junit.jupiter.api.Test;

import java.security.cert.X509Certificate;
import java.util.*;

class ClientIdentityTest {
    private X509Certificate certificate(String... uris) throws Exception {
        X509Certificate certificate = mock(X509Certificate.class);
        Collection<List<?>> names = new ArrayList<>();
        names.add(Arrays.asList(2, "client.example"));
        for (String uri : uris) names.add(Arrays.asList(6, uri));
        when(certificate.getSubjectAlternativeNames()).thenReturn(names);
        return certificate;
    }

    @Test
    void readsCertificateIdentityWhenOverrideIsUnset() throws Exception {
        X509Certificate certificate = certificate("urn:example:client");
        assertEquals("urn:example:client", ClientIdentity.resolveApplicationUri(certificate, null));
        assertEquals("urn:example:client", ClientIdentity.resolveApplicationUri(certificate, ""));
    }

    @Test
    void checksExplicitOverride() throws Exception {
        X509Certificate certificate = certificate("urn:example:client");
        assertEquals(
                "urn:example:client",
                ClientIdentity.resolveApplicationUri(certificate, "urn:example:client"));
        assertThrows(
                IllegalArgumentException.class,
                () -> ClientIdentity.resolveApplicationUri(certificate, "urn:wrong"));
    }

    @Test
    void rejectsMissingAmbiguousOrMalformedCertificateIdentity() throws Exception {
        for (X509Certificate certificate :
                Arrays.asList(
                        certificate(),
                        certificate("urn:one", "urn:two"),
                        certificate("relative/path"),
                        certificate("")))
            assertThrows(
                    IllegalArgumentException.class,
                    () -> ClientIdentity.resolveApplicationUri(certificate, null));
    }
}
