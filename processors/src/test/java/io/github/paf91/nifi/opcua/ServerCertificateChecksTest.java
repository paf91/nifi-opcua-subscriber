/* SPDX-License-Identifier: Apache-2.0 */
package io.github.paf91.nifi.opcua;

import static org.junit.jupiter.api.Assertions.*;

import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x509.*;
import org.bouncycastle.cert.X509v2CRLBuilder;
import org.bouncycastle.cert.jcajce.*;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.eclipse.milo.opcua.stack.core.StatusCodes;
import org.eclipse.milo.opcua.stack.core.UaException;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.math.BigInteger;
import java.nio.file.*;
import java.security.*;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.util.*;
import java.util.stream.Stream;

class ServerCertificateChecksTest {
    @TempDir Path dir;

    static Stream<Object[]> configurations() {
        return ServerCertificateValidationTest.configurations()
                .filter(c -> !(boolean) c[2])
                .filter(
                        c ->
                                c[1] != ServerCertificateValidationTest.Scenario.MISSING_PKI
                                        && c[1]
                                                != ServerCertificateValidationTest.Scenario
                                                        .PKI_IS_FILE);
    }

    @ParameterizedTest(name = "{0} / {1}")
    @MethodSource("configurations")
    void validatesRealCertificatesWithoutANetwork(
            ServerCertificateValidation mode,
            ServerCertificateValidationTest.Scenario scenario,
            boolean ignored)
            throws Exception {
        Security.addProvider(new BouncyCastleProvider());
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        X509Certificate certificate =
                ServerCertificateValidationTest.certificate(scenario, generator.generateKeyPair());
        Path pki = dir.resolve("pki");
        if (scenario != ServerCertificateValidationTest.Scenario.UNTRUSTED) {
            Files.createDirectories(pki.resolve("trusted/certs"));
            Files.write(pki.resolve("trusted/certs/server.der"), certificate.getEncoded());
        }
        boolean accepted =
                mode == ServerCertificateValidation.NONE
                        || scenario == ServerCertificateValidationTest.Scenario.VALID
                        || (mode == ServerCertificateValidation.CERTIFICATE
                                && scenario == ServerCertificateValidationTest.Scenario.HOSTNAME);
        org.junit.jupiter.api.function.Executable validate =
                () ->
                        TestServerTrust.validate(
                                mode,
                                pki,
                                Collections.singletonList(certificate),
                                "urn:test:server",
                                "localhost");
        if (accepted) assertDoesNotThrow(validate);
        else {
            UaException failure = assertThrows(UaException.class, validate);
            switch (scenario) {
                case HOSTNAME:
                    assertEquals(
                            StatusCodes.Bad_CertificateHostNameInvalid,
                            failure.getStatusCode().getValue());
                    break;
                case URI:
                    assertEquals(
                            StatusCodes.Bad_CertificateUriInvalid,
                            failure.getStatusCode().getValue());
                    break;
                case EXPIRED:
                    assertEquals(
                            StatusCodes.Bad_CertificateTimeInvalid,
                            failure.getStatusCode().getValue());
                    break;
                case KEY_USAGE:
                    assertEquals(
                            StatusCodes.Bad_CertificateUseNotAllowed,
                            failure.getStatusCode().getValue());
                    break;
                default:
                    break;
            }
        }
    }

    static Stream<Object[]> caConfigurations() {
        List<Object[]> cases = new ArrayList<>();
        for (ServerCertificateValidation mode : ServerCertificateValidation.values())
            for (String revocation : Arrays.asList("valid", "revoked", "missing"))
                cases.add(new Object[] {mode, revocation});
        return cases.stream();
    }

    @ParameterizedTest(name = "CA chain {0} / CRL {1}")
    @MethodSource("caConfigurations")
    void certificateModeRetainsCaTrustAndRevocationChecks(
            ServerCertificateValidation mode, String revocation) throws Exception {
        Security.addProvider(new BouncyCastleProvider());
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        KeyPair caKey = generator.generateKeyPair(), serverKey = generator.generateKeyPair();
        X500Name caName = new X500Name("CN=Test CA,C=US");
        Instant now = Instant.now();
        Date before = Date.from(now.minusSeconds(3600)), after = Date.from(now.plusSeconds(86400));
        JcaX509v3CertificateBuilder ca =
                new JcaX509v3CertificateBuilder(
                        caName, BigInteger.ONE, before, after, caName, caKey.getPublic());
        ca.addExtension(Extension.basicConstraints, true, new BasicConstraints(true));
        ca.addExtension(
                Extension.keyUsage, true, new KeyUsage(KeyUsage.keyCertSign | KeyUsage.cRLSign));
        org.bouncycastle.operator.ContentSigner signer =
                new JcaContentSignerBuilder("SHA256withRSA")
                        .setProvider("BC")
                        .build(caKey.getPrivate());
        X509Certificate root =
                new JcaX509CertificateConverter()
                        .setProvider("BC")
                        .getCertificate(ca.build(signer));
        JcaX509v3CertificateBuilder leaf =
                new JcaX509v3CertificateBuilder(
                        caName,
                        BigInteger.valueOf(2),
                        before,
                        after,
                        new X500Name("CN=Test server,C=US"),
                        serverKey.getPublic());
        leaf.addExtension(Extension.basicConstraints, true, new BasicConstraints(false));
        leaf.addExtension(
                Extension.keyUsage,
                true,
                new KeyUsage(
                        KeyUsage.digitalSignature
                                | KeyUsage.nonRepudiation
                                | KeyUsage.keyEncipherment
                                | KeyUsage.dataEncipherment));
        leaf.addExtension(
                Extension.extendedKeyUsage,
                false,
                new ExtendedKeyUsage(KeyPurposeId.id_kp_serverAuth));
        leaf.addExtension(
                Extension.subjectAlternativeName,
                false,
                new GeneralNames(
                        new GeneralName[] {
                            new GeneralName(
                                    GeneralName.uniformResourceIdentifier, "urn:test:server"),
                            new GeneralName(GeneralName.dNSName, "localhost")
                        }));
        X509Certificate certificate =
                new JcaX509CertificateConverter()
                        .setProvider("BC")
                        .getCertificate(leaf.build(signer));
        Path pki = dir.resolve("ca-pki");
        Files.createDirectories(pki.resolve("trusted/certs"));
        Files.write(pki.resolve("trusted/certs/ca.der"), root.getEncoded());
        if (!"missing".equals(revocation)) {
            X509v2CRLBuilder crl = new X509v2CRLBuilder(caName, before);
            crl.setNextUpdate(after);
            if ("revoked".equals(revocation))
                crl.addCRLEntry(BigInteger.valueOf(2), before, CRLReason.keyCompromise);
            Files.createDirectories(pki.resolve("trusted/crl"));
            Files.write(pki.resolve("trusted/crl/ca.crl"), crl.build(signer).getEncoded());
        }
        org.junit.jupiter.api.function.Executable validate =
                () ->
                        TestServerTrust.validate(
                                mode,
                                pki,
                                Arrays.asList(certificate, root),
                                "urn:test:server",
                                "localhost");
        if (mode == ServerCertificateValidation.NONE || "valid".equals(revocation))
            assertDoesNotThrow(validate);
        else {
            UaException failure = assertThrows(UaException.class, validate);
            assertEquals(
                    "revoked".equals(revocation)
                            ? StatusCodes.Bad_CertificateRevoked
                            : StatusCodes.Bad_CertificateRevocationUnknown,
                    failure.getStatusCode().getValue());
        }
    }
}
