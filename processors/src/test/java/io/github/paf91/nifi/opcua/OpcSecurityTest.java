/* SPDX-License-Identifier: Apache-2.0 */
package io.github.paf91.nifi.opcua;

import static org.eclipse.milo.opcua.stack.core.types.builtin.unsigned.Unsigned.ubyte;
import static org.junit.jupiter.api.Assertions.*;

import org.apache.nifi.util.TestRunner;
import org.eclipse.milo.opcua.stack.core.security.SecurityPolicy;
import org.eclipse.milo.opcua.stack.core.types.enumerated.*;
import org.eclipse.milo.opcua.stack.core.types.structured.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.*;
import java.util.stream.Stream;

class OpcSecurityTest {
    static Stream<Object[]> configurations() {
        List<Object[]> cases = new ArrayList<>();
        for (String policy :
                Arrays.asList(
                        "None",
                        "Basic128Rsa15",
                        "Basic256",
                        "Basic256Sha256",
                        "Aes128_Sha256_RsaOaep",
                        "Aes256_Sha256_RsaPss"))
            for (String mode : Arrays.asList("None", "Sign", "SignAndEncrypt"))
                for (String authentication :
                        Arrays.asList(OpcSecurity.USERNAME, OpcSecurity.ANONYMOUS))
                    cases.add(new Object[] {policy, mode, authentication});
        return cases.stream();
    }

    static EndpointDescription endpoint(String policy, String mode, UserTokenPolicy... tokens) {
        return new EndpointDescription(
                "opc.tcp://localhost:4840",
                null,
                null,
                MessageSecurityMode.valueOf(mode),
                SecurityPolicy.valueOf(policy).getUri(),
                tokens,
                OpcSecurity.TCP_PROFILE,
                ubyte(0));
    }

    static UserTokenPolicy token(UserTokenType type, String policyUri) {
        return new UserTokenPolicy(type.name(), type, null, null, policyUri);
    }

    @ParameterizedTest(name = "{0} / {1} / {2}")
    @MethodSource("configurations")
    void validatesEveryPolicyModeAndAuthenticationCombination(
            String policy, String mode, String authentication) {
        TestRunner runner = ProcessorTest.configured(new ProcessorTest.OfflineProcessor());
        runner.setProperty(SubscribeOpcUa.SECURITY_POLICY, policy);
        runner.setProperty(SubscribeOpcUa.SECURITY_MODE, mode);
        runner.setProperty(SubscribeOpcUa.AUTHENTICATION, authentication);
        if ("None".equals(policy)) {
            runner.removeProperty(SubscribeOpcUa.CERTIFICATE);
            runner.removeProperty(SubscribeOpcUa.PRIVATE_KEY);
            runner.removeProperty(SubscribeOpcUa.PKI);
        }
        if (OpcSecurity.ANONYMOUS.equals(authentication)) {
            runner.removeProperty(SubscribeOpcUa.USERNAME);
            runner.removeProperty(SubscribeOpcUa.PASSWORD);
        }
        boolean valid = "None".equals(policy) == "None".equals(mode);
        if (!valid) {
            runner.assertNotValid();
            assertThrows(
                    IllegalArgumentException.class,
                    () -> new OpcSecurity(policy, mode, authentication));
            return;
        }
        runner.assertValid();
        OpcSecurity security = new OpcSecurity(policy, mode, authentication);
        UserTokenType type =
                security.usernameAuthentication()
                        ? UserTokenType.UserName
                        : UserTokenType.Anonymous;
        EndpointDescription selected = endpoint(policy, mode, token(type, null));
        assertSame(selected, security.selectEndpoint(Collections.singletonList(selected)));
        assertEquals(!"None".equals(policy), security.requiresClientIdentity());
        assertEquals(!"None".equals(policy), security.requiresServerTrust(selected));
        UserTokenType wrongType =
                security.usernameAuthentication()
                        ? UserTokenType.Anonymous
                        : UserTokenType.UserName;
        assertFalse(security.matches(endpoint(policy, mode, token(wrongType, null))));
    }

    @Test
    void secureDefaultsRequireCertificatesAndUsername() {
        TestRunner runner = ProcessorTest.configured(new ProcessorTest.OfflineProcessor());
        runner.assertValid();
        for (org.apache.nifi.components.PropertyDescriptor property :
                Arrays.asList(
                        SubscribeOpcUa.CERTIFICATE,
                        SubscribeOpcUa.PRIVATE_KEY,
                        SubscribeOpcUa.PKI,
                        SubscribeOpcUa.USERNAME,
                        SubscribeOpcUa.PASSWORD)) {
            String previous = runner.getProcessContext().getProperty(property).getValue();
            runner.removeProperty(property);
            runner.assertNotValid();
            runner.setProperty(property, previous);
        }
    }

    @Test
    void neverDowngradesWhenTheRequestedEndpointIsAbsent() {
        EndpointDescription none = endpoint("None", "None", token(UserTokenType.UserName, null));
        EndpointDescription sign =
                endpoint("Basic256Sha256", "Sign", token(UserTokenType.UserName, null));
        assertThrows(
                IllegalArgumentException.class,
                () -> OpcSecurity.DEFAULT.selectEndpoint(Arrays.asList(none, sign)));
        EndpointDescription secure =
                endpoint("Basic256Sha256", "SignAndEncrypt", token(UserTokenType.UserName, null));
        assertSame(secure, OpcSecurity.DEFAULT.selectEndpoint(Arrays.asList(none, sign, secure)));
    }

    @Test
    void encryptedUsernameTokenNeedsServerTrustWithoutClientIdentity() {
        OpcSecurity security = new OpcSecurity("None", "None", OpcSecurity.USERNAME);
        EndpointDescription encryptedToken =
                endpoint(
                        "None",
                        "None",
                        token(UserTokenType.UserName, SecurityPolicy.Basic256Sha256.getUri()));
        assertTrue(security.matches(encryptedToken));
        assertFalse(security.requiresClientIdentity());
        assertTrue(security.requiresServerTrust(encryptedToken));
        assertFalse(
                security.requiresServerTrust(
                        endpoint("None", "None", token(UserTokenType.UserName, null))));
    }

    @Test
    void ignoresUnsupportedTokensWithoutIgnoringTheRequestedIdentity() {
        OpcSecurity security = new OpcSecurity("None", "None", OpcSecurity.USERNAME);
        UserTokenPolicy supported =
                token(UserTokenType.UserName, SecurityPolicy.Basic256Sha256.getUri());
        EndpointDescription endpoint =
                endpoint(
                        "None",
                        "None",
                        null,
                        token(UserTokenType.Anonymous, null),
                        token(UserTokenType.UserName, "urn:unsupported-policy"),
                        supported);
        assertSame(supported, security.userToken(endpoint));
        assertTrue(security.matches(endpoint));
        assertFalse(security.matches(endpoint("None", "None", (UserTokenPolicy[]) null)));
        assertFalse(security.matches(endpoint("None", "None")));
    }

    @Test
    void unencryptedConfigurationDoesNotReadUnusedIdentityFiles() throws Exception {
        OpcSettings settings =
                new OpcSettings(
                        "opc.tcp://localhost:4840",
                        null,
                        java.nio.file.Paths.get("/does-not-exist/client.der"),
                        java.nio.file.Paths.get("/does-not-exist/client.key"),
                        "",
                        null,
                        null,
                        null,
                        100,
                        1000,
                        Collections.emptyList(),
                        new OpcSecurity("None", "None", OpcSecurity.ANONYMOUS));
        assertNull(ClientIdentity.load(settings));
        assertEquals(
                OpcSecurity.DEFAULT_APPLICATION_URI, ClientIdentity.applicationUri(settings, null));
    }
}
