/* SPDX-License-Identifier: Apache-2.0 */
package io.github.paf91.nifi.opcua;

import org.bouncycastle.asn1.pkcs.PrivateKeyInfo;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.openssl.*;
import org.bouncycastle.openssl.jcajce.*;
import org.bouncycastle.pkcs.PKCS8EncryptedPrivateKeyInfo;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.*;
import java.security.cert.*;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Arrays;

final class ClientIdentity {
    private final KeyPair keyPair;
    private final X509Certificate[] chain;
    private final String applicationUri;

    ClientIdentity(KeyPair keyPair, X509Certificate[] chain, String applicationUri) {
        this.keyPair = keyPair;
        this.chain = chain;
        this.applicationUri = applicationUri;
    }

    KeyPair keyPair() {
        return keyPair;
    }

    X509Certificate[] chain() {
        return chain;
    }

    String applicationUri() {
        return applicationUri;
    }

    static String resolveApplicationUri(X509Certificate certificate, String override)
            throws CertificateParsingException {
        java.util.List<String> uris = new java.util.ArrayList<>();
        if (certificate.getSubjectAlternativeNames() != null) {
            for (java.util.List<?> name : certificate.getSubjectAlternativeNames()) {
                if (Integer.valueOf(6).equals(name.get(0)) && name.get(1) instanceof String)
                    uris.add((String) name.get(1));
            }
        }
        if (uris.size() != 1 || uris.get(0).trim().isEmpty())
            throw new IllegalArgumentException(
                    "Client certificate must contain exactly one application URI in its subject"
                            + " alternative names");
        String uri = uris.get(0);
        try {
            if (!java.net.URI.create(uri).isAbsolute()) throw new IllegalArgumentException();
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(
                    "Client certificate application URI must be a valid absolute URI");
        }
        if (override != null && !override.trim().isEmpty() && !override.equals(uri))
            throw new IllegalArgumentException(
                    "Application URI override must match the client certificate URI SAN");
        return uri;
    }

    static ClientIdentity load(
            Path certificate, Path keyFile, char[] password, String applicationUri)
            throws Exception {
        X509Certificate[] chain;
        try (InputStream in = Files.newInputStream(certificate)) {
            chain =
                    CertificateFactory.getInstance("X.509")
                            .generateCertificates(in)
                            .toArray(new X509Certificate[0]);
        }
        if (chain.length == 0) throw new IllegalArgumentException("Certificate file is empty");
        X509Certificate leaf = chain[0];
        leaf.checkValidity();
        if (!(leaf.getPublicKey() instanceof RSAPublicKey)
                || ((RSAPublicKey) leaf.getPublicKey()).getModulus().bitLength() < 2048)
            throw new IllegalArgumentException(
                    "Basic256Sha256 requires an RSA certificate with at least 2048 bits");
        String resolvedUri = resolveApplicationUri(leaf, applicationUri);
        byte[] bytes = Files.readAllBytes(keyFile);
        PrivateKey privateKey;
        try {
            if (new String(bytes, 0, Math.min(bytes.length, 32), StandardCharsets.US_ASCII)
                    .contains("-----BEGIN")) {
                BouncyCastleProvider provider = new BouncyCastleProvider();
                JcaPEMKeyConverter converter = new JcaPEMKeyConverter().setProvider(provider);
                try (java.io.StringReader reader =
                                new java.io.StringReader(
                                        new String(bytes, StandardCharsets.US_ASCII));
                        PEMParser parser = new PEMParser(reader)) {
                    Object value = parser.readObject();
                    if (value instanceof PEMEncryptedKeyPair)
                        value =
                                ((PEMEncryptedKeyPair) value)
                                        .decryptKeyPair(
                                                new JcePEMDecryptorProviderBuilder()
                                                        .setProvider(provider)
                                                        .build(password));
                    if (value instanceof PKCS8EncryptedPrivateKeyInfo)
                        value =
                                ((PKCS8EncryptedPrivateKeyInfo) value)
                                        .decryptPrivateKeyInfo(
                                                new JceOpenSSLPKCS8DecryptorProviderBuilder()
                                                        .setProvider(provider)
                                                        .build(password));
                    if (value instanceof PEMKeyPair)
                        privateKey = converter.getKeyPair((PEMKeyPair) value).getPrivate();
                    else if (value instanceof PrivateKeyInfo)
                        privateKey = converter.getPrivateKey((PrivateKeyInfo) value);
                    else throw new IllegalArgumentException("Unsupported PEM private key format");
                }
            } else {
                privateKey =
                        KeyFactory.getInstance("RSA")
                                .generatePrivate(new PKCS8EncodedKeySpec(bytes));
            }
        } finally {
            Arrays.fill(bytes, (byte) 0);
            Arrays.fill(password, '\0');
        }
        Signature signature = Signature.getInstance("SHA256withRSA");
        byte[] challenge = new byte[32];
        new SecureRandom().nextBytes(challenge);
        signature.initSign(privateKey);
        signature.update(challenge);
        byte[] signed = signature.sign();
        signature.initVerify(leaf);
        signature.update(challenge);
        if (!signature.verify(signed))
            throw new IllegalArgumentException("Private key does not match certificate");
        return new ClientIdentity(new KeyPair(leaf.getPublicKey(), privateKey), chain, resolvedUri);
    }
}
