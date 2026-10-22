/* SPDX-License-Identifier: Apache-2.0 */
package io.github.paf91.nifi.opcua;

import static org.junit.jupiter.api.Assertions.*;

import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.openssl.jcajce.JcaPEMWriter;
import org.eclipse.milo.opcua.stack.core.util.SelfSignedCertificateBuilder;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.security.cert.X509Certificate;

class ClientIdentityFormatTest {
    @TempDir Path dir;
    static KeyPair key;
    static X509Certificate certificate;

    @BeforeAll
    static void createIdentity() throws Exception {
        Security.addProvider(new BouncyCastleProvider());
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        key = generator.generateKeyPair();
        certificate =
                new SelfSignedCertificateBuilder(key)
                        .setCommonName("Test")
                        .setCountryCode("US")
                        .setApplicationUri("urn:test:client")
                        .build();
    }

    @ParameterizedTest
    @ValueSource(strings = {"pem", "openssl-pem", "der"})
    void readsPrivateKeyIncludingOpenSslExportPreamble(String format) throws Exception {
        Path certFile = dir.resolve("client.der");
        Path keyFile = dir.resolve("client.key");
        Files.write(certFile, certificate.getEncoded());
        if ("der".equals(format)) Files.write(keyFile, key.getPrivate().getEncoded());
        else {
            StringWriter text = new StringWriter();
            if ("openssl-pem".equals(format))
                text.write(
                        "Bag Attributes\n"
                                + "    friendlyName: client\n"
                                + "    localKeyID: 01 02 03 04\n"
                                + "Key Attributes: <No Attributes>\n");
            try (JcaPEMWriter writer = new JcaPEMWriter(text)) {
                writer.writeObject(key.getPrivate());
            }
            Files.write(keyFile, text.toString().getBytes(StandardCharsets.US_ASCII));
        }
        char[] password = "unused-for-unencrypted-key".toCharArray();
        ClientIdentity loaded = ClientIdentity.load(certFile, keyFile, password, null);
        assertEquals("urn:test:client", loaded.applicationUri());
        assertEquals(certificate, loaded.chain()[0]);
        assertArrayEquals(
                key.getPrivate().getEncoded(), loaded.keyPair().getPrivate().getEncoded());
        assertArrayEquals(new char[password.length], password);
    }

    @ParameterizedTest
    @ValueSource(strings = {"key", "uri"})
    void disablingServerValidationStillValidatesClientIdentity(String mismatch) throws Exception {
        Path certFile = dir.resolve("client.der"), keyFile = dir.resolve("client.key");
        Files.write(certFile, certificate.getEncoded());
        PrivateKey privateKey = key.getPrivate();
        if ("key".equals(mismatch)) {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            privateKey = generator.generateKeyPair().getPrivate();
        }
        Files.write(keyFile, privateKey.getEncoded());
        OpcSettings settings =
                new OpcSettings(
                        "opc.tcp://localhost:4840",
                        "uri".equals(mismatch) ? "urn:wrong:client" : null,
                        certFile,
                        keyFile,
                        "",
                        null,
                        "user",
                        "test-password",
                        100,
                        1000,
                        java.util.Collections.emptyList(),
                        OpcSecurity.DEFAULT,
                        ServerCertificateValidation.NONE);
        assertThrows(IllegalArgumentException.class, () -> ClientIdentity.load(settings));
    }
}
