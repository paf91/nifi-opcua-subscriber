/* SPDX-License-Identifier: Apache-2.0 */
package io.github.paf91.nifi.opcua;

import static org.junit.jupiter.api.Assertions.*;

import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x509.*;
import org.bouncycastle.cert.jcajce.*;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.eclipse.milo.opcua.stack.core.StatusCodes;
import org.eclipse.milo.opcua.stack.core.UaException;
import org.eclipse.milo.opcua.stack.core.security.SecurityPolicy;
import org.eclipse.milo.opcua.stack.core.types.builtin.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;

import java.math.BigInteger;
import java.nio.file.*;
import java.security.*;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Stream;

/** The same live adversarial matrix runs against both Milo adapters. */
class ServerCertificateValidationTest {
    @TempDir Path dir;
    private SecureSubscriptionTest fixture;

    enum Scenario {
        VALID,
        HOSTNAME,
        URI,
        EXPIRED,
        UNTRUSTED,
        MISSING_PKI,
        PKI_IS_FILE,
        KEY_USAGE,
        INVALID_SIGNATURE
    }

    static Stream<Object[]> configurations() {
        List<Object[]> cases = new ArrayList<>();
        for (ServerCertificateValidation mode : ServerCertificateValidation.values())
            for (Scenario scenario : Scenario.values())
                for (boolean encryptedTokenOnly : new boolean[] {false, true})
                    cases.add(new Object[] {mode, scenario, encryptedTokenOnly});
        return cases.stream();
    }

    @ParameterizedTest(name = "{0} / {1} / encrypted token only={2}")
    @MethodSource("configurations")
    void appliesOnlyTheSelectedServerChecks(
            ServerCertificateValidation mode, Scenario scenario, boolean encryptedTokenOnly)
            throws Exception {
        start(scenario, encryptedTokenOnly);
        Path pki = dir.resolve("pki");
        if (scenario == Scenario.UNTRUSTED) pki = dir.resolve("untrusted");
        if (scenario == Scenario.MISSING_PKI) pki = null;
        if (scenario == Scenario.PKI_IS_FILE) {
            pki = dir.resolve("not-a-directory");
            Files.write(pki, new byte[] {1});
        }
        OpcSettings settings = settings(mode, pki, "test-password", encryptedTokenOnly);
        ConnectionDiagnostics report = new ConnectionDiagnostics();
        boolean accepted =
                mode == ServerCertificateValidation.NONE
                        || scenario == Scenario.VALID
                        || (mode == ServerCertificateValidation.CERTIFICATE
                                && scenario == Scenario.HOSTNAME);
        if (accepted) {
            receivesData(settings, report);
        } else {
            Exception failure =
                    assertThrows(
                            Exception.class,
                            () -> connect(settings, report, new LinkedBlockingQueue<>()));
            Long expected = null;
            switch (scenario) {
                case HOSTNAME:
                    expected = StatusCodes.Bad_CertificateHostNameInvalid;
                    break;
                case URI:
                    expected = StatusCodes.Bad_CertificateUriInvalid;
                    break;
                case EXPIRED:
                    expected = StatusCodes.Bad_CertificateTimeInvalid;
                    break;
                case KEY_USAGE:
                    expected = StatusCodes.Bad_CertificateUseNotAllowed;
                    break;
                default:
                    break;
            }
            if (expected != null)
                assertEquals(
                        new StatusCode(expected),
                        UaException.extractStatusCode(failure).orElse(null),
                        failure.toString());
            if (scenario == Scenario.UNTRUSTED || scenario == Scenario.INVALID_SIGNATURE) {
                StatusCode status = UaException.extractStatusCode(failure).orElse(null);
                assertNotNull(status, failure.toString());
                assertTrue(
                        status.getValue() == StatusCodes.Bad_CertificateUntrusted
                                || status.getValue() == StatusCodes.Bad_CertificateInvalid
                                || status.getValue() == StatusCodes.Bad_SecurityChecksFailed,
                        failure.toString());
            }
        }
        assertEquals(mode.value(), report.snapshot().get("serverCertificateValidation"));
        if (mode == ServerCertificateValidation.NONE && scenario == Scenario.UNTRUSTED)
            assertFalse(Files.exists(pki), "None must not initialize or write a PKI directory");
    }

    @ParameterizedTest
    @EnumSource(ServerCertificateValidation.class)
    void serverValidationDoesNotBypassPasswordAuthentication(ServerCertificateValidation mode)
            throws Exception {
        start(Scenario.VALID, false);
        Exception failure =
                assertThrows(
                        Exception.class,
                        () ->
                                connect(
                                        settings(mode, dir.resolve("pki"), "wrong-password", false),
                                        new ConnectionDiagnostics(),
                                        new LinkedBlockingQueue<>()));
        StatusCode code = UaException.extractStatusCode(failure).orElse(null);
        assertNotNull(code, failure.toString());
        assertTrue(
                code.getValue() == StatusCodes.Bad_UserAccessDenied
                        || code.getValue() == StatusCodes.Bad_IdentityTokenRejected,
                failure.toString());
    }

    @ParameterizedTest
    @ValueSource(strings = {"key", "uri"})
    void insecureModeStillRejectsInvalidClientIdentity(String problem) throws Exception {
        start(Scenario.VALID, false);
        if ("key".equals(problem)) Files.write(dir.resolve("client.pem"), new byte[] {0});
        OpcSettings settings =
                new OpcSettings(
                        fixture.endpoint,
                        "uri".equals(problem) ? "urn:wrong:client" : "urn:test:client",
                        dir.resolve("client.der"),
                        dir.resolve("client.pem"),
                        "",
                        null,
                        "user",
                        "test-password",
                        100,
                        5000,
                        tags(),
                        OpcSecurity.DEFAULT,
                        ServerCertificateValidation.NONE);
        ConnectionDiagnostics report = new ConnectionDiagnostics();
        assertThrows(Exception.class, () -> connect(settings, report, new LinkedBlockingQueue<>()));
        assertEquals("Loading client identity", report.snapshot().get("stage"));
    }

    private void start(Scenario scenario, boolean encryptedTokenOnly) throws Exception {
        Security.addProvider(new BouncyCastleProvider());
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        KeyPair key = generator.generateKeyPair();
        X509Certificate certificate = certificate(scenario, key);
        fixture = new SecureSubscriptionTest();
        fixture.dir = dir;
        fixture.startServer(
                encryptedTokenOnly ? SecurityPolicy.Basic256Sha256.getUri() : null,
                key,
                certificate);
    }

    static X509Certificate certificate(Scenario scenario, KeyPair key) throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        X500Name name = new X500Name("CN=Test OPC UA server,C=US");
        Instant now = Instant.now();
        JcaX509v3CertificateBuilder builder =
                new JcaX509v3CertificateBuilder(
                        name,
                        BigInteger.ONE,
                        Date.from(now.minusSeconds(172800)),
                        Date.from(now.plusSeconds(scenario == Scenario.EXPIRED ? -86400 : 86400)),
                        name,
                        key.getPublic());
        builder.addExtension(Extension.basicConstraints, true, new BasicConstraints(false));
        builder.addExtension(
                Extension.keyUsage,
                true,
                new KeyUsage(
                        scenario == Scenario.KEY_USAGE
                                ? KeyUsage.cRLSign
                                : KeyUsage.digitalSignature
                                        | KeyUsage.nonRepudiation
                                        | KeyUsage.keyEncipherment
                                        | KeyUsage.dataEncipherment
                                        | KeyUsage.keyCertSign));
        builder.addExtension(
                Extension.extendedKeyUsage,
                false,
                new ExtendedKeyUsage(
                        new KeyPurposeId[] {
                            KeyPurposeId.id_kp_serverAuth, KeyPurposeId.id_kp_clientAuth
                        }));
        builder.addExtension(
                Extension.subjectAlternativeName,
                false,
                new GeneralNames(
                        new GeneralName[] {
                            new GeneralName(
                                    GeneralName.uniformResourceIdentifier,
                                    scenario == Scenario.URI
                                            ? "urn:wrong:server"
                                            : "urn:test:server"),
                            new GeneralName(
                                    GeneralName.dNSName,
                                    scenario == Scenario.HOSTNAME
                                            ? "unlisted.example.invalid"
                                            : "localhost"),
                            new GeneralName(
                                    GeneralName.iPAddress,
                                    scenario == Scenario.HOSTNAME ? "127.0.0.2" : "127.0.0.1")
                        }));
        return new JcaX509CertificateConverter()
                .setProvider("BC")
                .getCertificate(
                        builder.build(
                                new JcaContentSignerBuilder("SHA256withRSA")
                                        .setProvider("BC")
                                        .build(
                                                scenario == Scenario.INVALID_SIGNATURE
                                                        ? generator.generateKeyPair().getPrivate()
                                                        : key.getPrivate())));
    }

    private OpcSettings settings(
            ServerCertificateValidation mode,
            Path pki,
            String password,
            boolean encryptedTokenOnly) {
        return new OpcSettings(
                fixture.endpoint,
                null,
                encryptedTokenOnly ? null : dir.resolve("client.der"),
                encryptedTokenOnly ? null : dir.resolve("client.pem"),
                "",
                pki,
                "user",
                password,
                100,
                5000,
                tags(),
                encryptedTokenOnly
                        ? new OpcSecurity("None", "None", OpcSecurity.USERNAME)
                        : OpcSecurity.DEFAULT,
                mode);
    }

    private static List<TagDefinition> tags() {
        return Collections.singletonList(TagDefinition.parse("time", "ns=0;i=2258", 100, 10));
    }

    private static OpcConnection connect(
            OpcSettings settings, ConnectionDiagnostics report, BlockingQueue<DataValue> values)
            throws Exception {
        return new OpcConnection(
                settings,
                (tag, value) -> values.offer(value),
                () -> {},
                () -> {},
                new ConnectionAttempt(),
                report);
    }

    private static void receivesData(OpcSettings settings, ConnectionDiagnostics report)
            throws Exception {
        BlockingQueue<DataValue> values = new LinkedBlockingQueue<>();
        try (OpcConnection connection = connect(settings, report, values)) {
            DataValue value = values.poll(10, TimeUnit.SECONDS);
            assertNotNull(value, "Session must deliver a real monitored value");
            assertTrue(value.getStatusCode().isGood());
        }
    }

    @AfterEach
    void close() throws Exception {
        if (fixture != null) fixture.closeServer();
    }
}
