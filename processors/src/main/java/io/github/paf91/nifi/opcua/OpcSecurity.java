/* SPDX-License-Identifier: Apache-2.0 */
package io.github.paf91.nifi.opcua;

import org.eclipse.milo.opcua.stack.core.security.SecurityPolicy;
import org.eclipse.milo.opcua.stack.core.types.enumerated.MessageSecurityMode;
import org.eclipse.milo.opcua.stack.core.types.enumerated.UserTokenType;
import org.eclipse.milo.opcua.stack.core.types.structured.EndpointDescription;
import org.eclipse.milo.opcua.stack.core.types.structured.UserTokenPolicy;

import java.net.URI;
import java.util.List;
import java.util.Optional;

/** Exact endpoint selection shared by both Milo adapters; never downgrades security. */
final class OpcSecurity {
    static final String USERNAME = "Username/Password";
    static final String ANONYMOUS = "Anonymous";
    static final String DEFAULT_APPLICATION_URI = "urn:nifi:opcua:subscriber";
    static final String TCP_PROFILE =
            "http://opcfoundation.org/UA-Profile/Transport/uatcp-uasc-uabinary";
    static final OpcSecurity DEFAULT =
            new OpcSecurity("Basic256Sha256", "SignAndEncrypt", USERNAME);

    private final SecurityPolicy policy;
    private final MessageSecurityMode mode;
    private final UserTokenType identity;

    OpcSecurity(String policy, String mode, String authentication) {
        this.policy = SecurityPolicy.valueOf(policy);
        this.mode = MessageSecurityMode.valueOf(mode);
        if (this.mode == MessageSecurityMode.Invalid
                || (this.policy == SecurityPolicy.None) != (this.mode == MessageSecurityMode.None))
            throw new IllegalArgumentException(
                    "Security Policy None requires Security Mode None; other policies require Sign"
                            + " or SignAndEncrypt");
        if (USERNAME.equals(authentication)) identity = UserTokenType.UserName;
        else if (ANONYMOUS.equals(authentication)) identity = UserTokenType.Anonymous;
        else
            throw new IllegalArgumentException(
                    "Select Anonymous or Username/Password authentication");
    }

    SecurityPolicy policy() {
        return policy;
    }

    MessageSecurityMode mode() {
        return mode;
    }

    boolean usernameAuthentication() {
        return identity == UserTokenType.UserName;
    }

    boolean requiresClientIdentity() {
        return policy != SecurityPolicy.None;
    }

    private Optional<SecurityPolicy> tokenSecurityPolicy(
            EndpointDescription endpoint, UserTokenPolicy token) {
        String uri = token.getSecurityPolicyUri();
        return SecurityPolicy.fromUriSafe(
                uri == null || uri.isEmpty() ? endpoint.getSecurityPolicyUri() : uri);
    }

    UserTokenPolicy userToken(EndpointDescription endpoint) {
        UserTokenPolicy[] tokens = endpoint.getUserIdentityTokens();
        if (tokens != null) {
            for (UserTokenPolicy token : tokens) {
                if (token != null
                        && token.getTokenType() == identity
                        && (!usernameAuthentication()
                                || tokenSecurityPolicy(endpoint, token).isPresent())) return token;
            }
        }
        throw new IllegalArgumentException(
                "Endpoint does not offer a supported "
                        + (usernameAuthentication() ? USERNAME : ANONYMOUS)
                        + " token");
    }

    boolean matches(EndpointDescription endpoint) {
        if (endpoint == null
                || !policy.getUri().equals(endpoint.getSecurityPolicyUri())
                || mode != endpoint.getSecurityMode()
                || !TCP_PROFILE.equals(endpoint.getTransportProfileUri())) return false;
        try {
            URI uri = URI.create(endpoint.getEndpointUrl());
            if (!"opc.tcp".equals(uri.getScheme()) || uri.getHost() == null) return false;
            userToken(endpoint);
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }

    EndpointDescription selectEndpoint(List<EndpointDescription> endpoints) {
        return endpoints.stream()
                .filter(this::matches)
                .findFirst()
                .orElseThrow(
                        () ->
                                new IllegalArgumentException(
                                        "No OPC TCP endpoint for "
                                                + policy.name()
                                                + " / "
                                                + mode.name()
                                                + " / "
                                                + (usernameAuthentication()
                                                        ? USERNAME
                                                        : ANONYMOUS)));
    }

    boolean requiresServerTrust(EndpointDescription endpoint) {
        return requiresClientIdentity()
                || (usernameAuthentication()
                        && tokenSecurityPolicy(endpoint, userToken(endpoint))
                                        .orElseThrow(
                                                () ->
                                                        new IllegalArgumentException(
                                                                "Unsupported username token"
                                                                        + " security policy"))
                                != SecurityPolicy.None);
    }
}
