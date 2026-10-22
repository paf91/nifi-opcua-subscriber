/* SPDX-License-Identifier: Apache-2.0 */
package io.github.paf91.nifi.opcua;

import static org.junit.jupiter.api.Assertions.*;

import org.eclipse.milo.opcua.stack.core.types.builtin.DataValue;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.nio.file.*;
import java.util.Collections;
import java.util.concurrent.*;

/** Opt-in interoperability check against an explicitly configured external test server. */
@EnabledIfEnvironmentVariable(named = "OPCUA_TEST_ENDPOINT", matches = ".+")
class ExternalServerTest {
    private static String value(String name, String fallback) {
        String configured = System.getenv(name);
        return configured == null || configured.isEmpty() ? fallback : configured;
    }

    private static String required(String name) {
        String configured = value(name, null);
        assertNotNull(configured, "Set " + name + " for this interoperability test");
        return configured;
    }

    private static Path path(String name, boolean required) {
        String configured = required ? required(name) : value(name, null);
        return configured == null ? null : Paths.get(configured);
    }

    @Test
    void externalEndpointSubscribesAfterCloseAndReconnect() throws Exception {
        OpcSecurity security =
                new OpcSecurity(
                        value("OPCUA_TEST_POLICY", "Basic256Sha256"),
                        value("OPCUA_TEST_MODE", "SignAndEncrypt"),
                        value("OPCUA_TEST_AUTH", OpcSecurity.USERNAME));
        OpcSettings settings =
                new OpcSettings(
                        required("OPCUA_TEST_ENDPOINT"),
                        value("OPCUA_TEST_APPLICATION_URI", null),
                        path("OPCUA_TEST_CERTIFICATE", security.requiresClientIdentity()),
                        path("OPCUA_TEST_KEY", security.requiresClientIdentity()),
                        value("OPCUA_TEST_KEY_PASSWORD", ""),
                        path(
                                "OPCUA_TEST_PKI",
                                security.requiresClientIdentity()
                                        && !"None"
                                                .equals(
                                                        value(
                                                                "OPCUA_TEST_SERVER_VALIDATION",
                                                                "Full"))),
                        security.usernameAuthentication() ? required("OPCUA_TEST_USERNAME") : null,
                        security.usernameAuthentication() ? required("OPCUA_TEST_PASSWORD") : null,
                        500,
                        10000,
                        Collections.singletonList(
                                TagDefinition.parse(
                                        "interop", required("OPCUA_TEST_NODE_ID"), 500, 10)),
                        security,
                        ServerCertificateValidation.parse(
                                value("OPCUA_TEST_SERVER_VALIDATION", "Full")));
        for (int attempt = 0; attempt < 2; attempt++) {
            BlockingQueue<DataValue> values = new LinkedBlockingQueue<>();
            try (OpcConnection connection =
                    new OpcConnection(
                            settings, (tag, value) -> values.offer(value), () -> {}, () -> {})) {
                DataValue first = values.poll(20, TimeUnit.SECONDS);
                assertNotNull(first, "No initial data-change notification from external server");
                assertNotNull(first.getStatusCode());
                assertTrue(
                        first.getStatusCode().isGood(),
                        "External server returned " + first.getStatusCode());
                assertNotNull(first.getValue());
            }
        }
    }
}
