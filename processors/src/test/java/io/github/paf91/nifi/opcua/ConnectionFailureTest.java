/* SPDX-License-Identifier: Apache-2.0 */
package io.github.paf91.nifi.opcua;

import static org.junit.jupiter.api.Assertions.*;

import org.apache.nifi.processor.ProcessContext;
import org.apache.nifi.util.*;
import org.eclipse.milo.opcua.stack.core.StatusCodes;
import org.eclipse.milo.opcua.stack.core.UaException;
import org.eclipse.milo.opcua.stack.core.types.builtin.*;
import org.junit.jupiter.api.Test;

import java.security.cert.CertificateException;
import java.util.concurrent.*;

class ConnectionFailureTest {
    private TestRunner failingRunner(Exception failure) {
        return ProcessorTest.configured(
                new SubscribeOpcUa() {
                    @Override
                    protected AutoCloseable openConnection(
                            ProcessContext context, ConnectionAttempt attempt) throws Exception {
                        throw failure;
                    }
                });
    }

    private String failureLog(TestRunner runner) {
        try {
            runner.run(1, false, true);
            assertEquals(1, runner.getLogger().getErrorMessages().size());
            LogMessage log = runner.getLogger().getErrorMessages().get(0);
            // A raw throwable would bypass password redaction when NiFi renders its stack trace.
            assertNull(log.getThrowable());
            assertTrue(log.toString().contains("retrying after configured delay"));
            return log.toString();
        } finally {
            runner.shutdown();
        }
    }

    @Test
    void logsRejectedNodeAndStatus() {
        String log =
                failureLog(
                        failingRunner(
                                new IllegalStateException(
                                        "Server rejected monitored item "
                                                + NodeId.parse("ns=2;s=does-not-exist")
                                                + " with "
                                                + new StatusCode(StatusCodes.Bad_NodeIdUnknown))));
        assertTrue(log.contains("does-not-exist"), log);
        assertTrue(log.contains("Bad_NodeIdUnknown"), log);
        assertTrue(log.contains("0x80340000"), log);
    }

    @Test
    void logsWrappedStatusAndUnderlyingCause() {
        String log =
                failureLog(
                        failingRunner(
                                new ExecutionException(
                                        new CompletionException(
                                                new UaException(
                                                        StatusCodes.Bad_CertificateInvalid,
                                                        "Certificate validation failed",
                                                        new CertificateException(
                                                                "Certificate has expired"))))));
        assertTrue(log.contains("UaException"), log);
        assertTrue(log.contains("Bad_CertificateInvalid"), log);
        assertTrue(log.contains("0x80120000"), log);
        assertTrue(log.contains("Certificate validation failed"), log);
        assertTrue(log.contains("CertificateException: Certificate has expired"), log);
        assertFalse(log.contains("ExecutionException"), log);
        assertFalse(log.contains("CompletionException"), log);
    }

    @Test
    void redactsBothPasswordsBeforeNormalizingLogMessages() {
        String password = "test-password\n$\\[]";
        String keyPassword = password + "-key";
        TestRunner runner =
                failingRunner(
                        new IllegalStateException(
                                "Could not connect\r\npassword=" + password,
                                new IllegalArgumentException("key=" + keyPassword + "\u2028end")));
        runner.setProperty(SubscribeOpcUa.PASSWORD, password);
        runner.setProperty(SubscribeOpcUa.KEY_PASSWORD, keyPassword);
        String log = failureLog(runner);
        assertTrue(log.contains("Could not connect"), log);
        assertTrue(log.contains("password=[redacted]"), log);
        assertTrue(log.contains("key=[redacted] end"), log);
        assertFalse(log.contains("test-password"), log);
        assertFalse(log.contains("\r"), log);
        assertFalse(log.contains("\n"), log);
        assertFalse(log.contains("\u2028"), log);
    }

    @Test
    void logsClassWhenFailureHasNoMessage() {
        String log = failureLog(failingRunner(new TimeoutException()));
        assertTrue(log.contains("TimeoutException"), log);
    }
}
