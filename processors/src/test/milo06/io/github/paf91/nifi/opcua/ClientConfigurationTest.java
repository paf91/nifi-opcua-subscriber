/* SPDX-License-Identifier: Apache-2.0 */
package io.github.paf91.nifi.opcua;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import io.netty.channel.nio.NioEventLoopGroup;

import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.eclipse.milo.opcua.sdk.client.api.config.OpcUaClientConfig;
import org.eclipse.milo.opcua.stack.client.security.ClientCertificateValidator;
import org.eclipse.milo.opcua.stack.core.StatusCodes;
import org.eclipse.milo.opcua.stack.core.UaException;
import org.eclipse.milo.opcua.stack.core.security.SecurityPolicy;
import org.eclipse.milo.opcua.stack.core.types.builtin.*;
import org.eclipse.milo.opcua.stack.core.types.enumerated.*;
import org.eclipse.milo.opcua.stack.core.types.structured.*;
import org.eclipse.milo.opcua.stack.core.util.SelfSignedCertificateBuilder;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.nio.charset.StandardCharsets;
import java.security.*;
import java.security.cert.X509Certificate;
import java.util.*;
import java.util.stream.Stream;

class ClientConfigurationTest {
    static KeyPair key;
    static X509Certificate certificate;
    static ByteString nonce;

    @BeforeAll
    static void identity() throws Exception {
        Security.addProvider(new BouncyCastleProvider());
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        key = generator.generateKeyPair();
        certificate =
                new SelfSignedCertificateBuilder(key)
                        .setCommonName("Test")
                        .setCountryCode("US")
                        .setApplicationUri("urn:test:server")
                        .addDnsName("localhost")
                        .build();
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        nonce = ByteString.of(bytes);
    }

    static Stream<Object[]> configurations() {
        return OpcSecurityTest.configurations()
                .filter(c -> "None".equals(c[0]) == "None".equals(c[1]));
    }

    private static OpcSettings settings(OpcSecurity security, String uri) {
        return new OpcSettings(
                "opc.tcp://localhost:4840",
                uri,
                null,
                null,
                "",
                null,
                security.usernameAuthentication() ? "user" : null,
                security.usernameAuthentication() ? "test-password" : null,
                100,
                1000,
                Collections.emptyList(),
                security);
    }

    private static OpcUaClientConfig config(
            OpcSettings settings,
            EndpointDescription endpoint,
            ClientIdentity identity,
            ClientCertificateValidator validator) {
        return OpcConnection.clientConfig(
                settings, endpoint, identity, validator, mock(NioEventLoopGroup.class));
    }

    private static EndpointDescription endpoint(
            String policy, String mode, UserTokenPolicy... tokens) throws Exception {
        EndpointDescription bare = OpcSecurityTest.endpoint(policy, mode, tokens);
        ApplicationDescription server =
                new ApplicationDescription(
                        "urn:test:server",
                        "urn:test",
                        LocalizedText.english("Test"),
                        ApplicationType.Server,
                        null,
                        null,
                        new String[0]);
        return new EndpointDescription(
                bare.getEndpointUrl(),
                server,
                ByteString.of(certificate.getEncoded()),
                bare.getSecurityMode(),
                bare.getSecurityPolicyUri(),
                tokens,
                bare.getTransportProfileUri(),
                bare.getSecurityLevel());
    }

    @ParameterizedTest(name = "{0} / {1} / {2}")
    @MethodSource("configurations")
    void buildsUsableIdentityTokensWithConditionalClientCertificates(
            String policy, String mode, String authentication) throws Exception {
        OpcSecurity security = new OpcSecurity(policy, mode, authentication);
        EndpointDescription endpoint =
                endpoint(
                        policy,
                        mode,
                        OpcSecurityTest.token(
                                security.usernameAuthentication()
                                        ? UserTokenType.UserName
                                        : UserTokenType.Anonymous,
                                null));
        ClientIdentity identity =
                security.requiresClientIdentity()
                        ? new ClientIdentity(
                                key, new X509Certificate[] {certificate}, "urn:test:server")
                        : null;
        ClientCertificateValidator validator = OpcConnection.rejectUnconfiguredTrust();
        OpcUaClientConfig config = config(settings(security, null), endpoint, identity, validator);
        assertEquals(security.requiresClientIdentity(), config.getKeyPair().isPresent());
        assertEquals(security.requiresClientIdentity(), config.getCertificate().isPresent());
        assertEquals(
                identity == null ? OpcSecurity.DEFAULT_APPLICATION_URI : identity.applicationUri(),
                config.getApplicationUri());
        assertSame(validator, config.getCertificateValidator());
        UserIdentityToken token =
                config.getIdentityProvider().getIdentityToken(endpoint, nonce).getToken();
        if (security.usernameAuthentication()) {
            assertTrue(token instanceof UserNameIdentityToken);
            UserNameIdentityToken username = (UserNameIdentityToken) token;
            assertEquals("user", username.getUserName());
            if (security.requiresClientIdentity()) {
                assertNotNull(username.getEncryptionAlgorithm());
                assertFalse(
                        Arrays.equals(
                                "test-password".getBytes(StandardCharsets.UTF_8),
                                username.getPassword().bytes()));
            } else {
                assertNull(username.getEncryptionAlgorithm());
                assertArrayEquals(
                        "test-password".getBytes(StandardCharsets.UTF_8),
                        username.getPassword().bytes());
            }
        } else assertTrue(token instanceof AnonymousIdentityToken);
    }

    @Test
    void refusesEncryptedPasswordTokenWithoutConfiguredTrust() throws Exception {
        OpcSecurity security = new OpcSecurity("None", "None", OpcSecurity.USERNAME);
        EndpointDescription endpoint =
                endpoint(
                        "None",
                        "None",
                        OpcSecurityTest.token(
                                UserTokenType.UserName, SecurityPolicy.Basic256Sha256.getUri()));
        OpcUaClientConfig config =
                config(
                        settings(security, null),
                        endpoint,
                        null,
                        OpcConnection.rejectUnconfiguredTrust());
        UaException error =
                assertThrows(
                        UaException.class,
                        () -> config.getIdentityProvider().getIdentityToken(endpoint, nonce));
        assertEquals(StatusCodes.Bad_CertificateUntrusted, error.getStatusCode().getValue());
    }

    @Test
    void validatesServerCertificateBeforeEncryptingTokenOnUnsecuredChannel() throws Exception {
        OpcSecurity security = new OpcSecurity("None", "None", OpcSecurity.USERNAME);
        EndpointDescription endpoint =
                endpoint(
                        "None",
                        "None",
                        OpcSecurityTest.token(
                                UserTokenType.UserName, SecurityPolicy.Basic256Sha256.getUri()));
        ClientCertificateValidator validator = mock(ClientCertificateValidator.class);
        OpcUaClientConfig config =
                config(settings(security, "urn:test:plain-client"), endpoint, null, validator);
        UserNameIdentityToken token =
                (UserNameIdentityToken)
                        config.getIdentityProvider().getIdentityToken(endpoint, nonce).getToken();
        verify(validator)
                .validateCertificateChain(
                        eq(Collections.singletonList(certificate)),
                        eq("urn:test:server"),
                        eq("localhost"));
        assertEquals("urn:test:plain-client", config.getApplicationUri());
        assertFalse(config.getCertificate().isPresent());
        javax.crypto.Cipher cipher =
                javax.crypto.Cipher.getInstance("RSA/ECB/OAEPWithSHA-1AndMGF1Padding");
        cipher.init(javax.crypto.Cipher.DECRYPT_MODE, key.getPrivate());
        java.nio.ByteBuffer plaintext =
                java.nio.ByteBuffer.wrap(cipher.doFinal(token.getPassword().bytes()))
                        .order(java.nio.ByteOrder.LITTLE_ENDIAN);
        byte[] password = "test-password".getBytes(StandardCharsets.UTF_8);
        assertEquals(password.length + nonce.length(), plaintext.getInt());
        byte[] actualPassword = new byte[password.length];
        plaintext.get(actualPassword);
        assertArrayEquals(password, actualPassword);
        byte[] actualNonce = new byte[nonce.length()];
        plaintext.get(actualNonce);
        assertArrayEquals(nonce.bytes(), actualNonce);
    }

    @Test
    void selectsSupportedUsernameTokenWhenServerAdvertisesSeveral() throws Exception {
        OpcSecurity security = new OpcSecurity("None", "None", OpcSecurity.USERNAME);
        UserTokenPolicy supported =
                new UserTokenPolicy(
                        "supported",
                        UserTokenType.UserName,
                        null,
                        null,
                        SecurityPolicy.None.getUri());
        EndpointDescription endpoint =
                endpoint(
                        "None",
                        "None",
                        OpcSecurityTest.token(UserTokenType.UserName, "urn:unsupported"),
                        supported);
        OpcUaClientConfig config =
                config(
                        settings(security, null),
                        endpoint,
                        null,
                        OpcConnection.rejectUnconfiguredTrust());
        assertEquals(
                "supported",
                config.getIdentityProvider()
                        .getIdentityToken(endpoint, nonce)
                        .getToken()
                        .getPolicyId());
    }
}
