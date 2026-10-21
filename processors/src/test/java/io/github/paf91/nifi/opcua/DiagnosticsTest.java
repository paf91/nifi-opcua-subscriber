/* SPDX-License-Identifier: Apache-2.0 */
package io.github.paf91.nifi.opcua;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.*;

import org.apache.nifi.components.ConfigVerificationResult;
import org.apache.nifi.processor.*;
import org.apache.nifi.util.TestRunner;
import org.eclipse.milo.opcua.stack.core.types.builtin.*;
import org.eclipse.milo.opcua.stack.core.types.enumerated.*;
import org.eclipse.milo.opcua.stack.core.types.structured.*;
import org.junit.jupiter.api.Test;

import java.util.*;
import java.util.concurrent.atomic.*;
import java.util.function.Consumer;

class DiagnosticsTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void quietSubscriptionKeepAliveDoesNotPretendToBeAFreshValue() {
        ConnectionDiagnostics report = new ConnectionDiagnostics();
        assertFalse(report.sessionActive(new NodeId(1, "one")));
        report.keepAlive();
        assertNotNull(report.snapshot().get("lastKeepAliveReceivedAt"));
        assertNull(report.snapshot().get("lastNotificationReceivedAt"));
        report.notification(
                new DataValue(new Variant("must-not-appear"), StatusCode.GOOD, null, null));
        String goodTime = (String) report.snapshot().get("lastGoodValueReceivedAt");
        assertNotNull(goodTime);
        report.notification(new DataValue(new Variant(123), StatusCode.BAD, null, null));
        assertEquals(goodTime, report.snapshot().get("lastGoodValueReceivedAt"));
        assertEquals(2L, report.snapshot().get("notificationsReceived"));
        assertFalse(report.snapshot().toString().contains("must-not-appear"));
        report.sessionInactive();
        assertEquals(false, report.snapshot().get("sessionActive"));
        assertFalse(report.sessionActive(new NodeId(1, "one")));
        assertTrue(report.sessionActive(new NodeId(1, "two")));
        assertEquals(3, report.snapshot().get("sessionActivations"));
        assertEquals(true, report.snapshot().get("settingsMayBeStale"));
    }

    @Test
    void boundsMetadataAndRemovesEndpointCredentialsBeforeRendering() {
        ConnectionDiagnostics report =
                new ConnectionDiagnostics(value -> value.replace("very\nsecret", "[redacted]"));
        EndpointDescription endpoint =
                new EndpointDescription(
                        "opc.tcp://name:password@localhost:4840/path?token=hidden#fragment",
                        null,
                        ByteString.NULL_VALUE,
                        MessageSecurityMode.None,
                        "policy",
                        new UserTokenPolicy[0],
                        "transport",
                        null);
        report.endpoints(Collections.nCopies(100, endpoint));
        report.selected(endpoint);
        report.namespaces(new String[200]);
        TagDefinition tag =
                TagDefinition.parse(
                        String.join("", Collections.nCopies(505, "a")) + "very\nsecret",
                        "ns=2;s=x",
                        100,
                        10);
        for (int i = 0; i < 200; i++) report.item(tag, 500, 1);
        Map<String, Object> snapshot = report.snapshot();
        assertEquals(50, ((List<?>) snapshot.get("advertisedEndpoints")).size());
        assertEquals(128, ((List<?>) snapshot.get("namespaces")).size());
        assertEquals(100, ((List<?>) snapshot.get("monitoredItems")).size());
        assertEquals(true, snapshot.get("itemDetailsTruncated"));
        String text = snapshot.toString();
        for (String secret :
                Arrays.asList("name:", "password", "hidden", "fragment", "very", "secret"))
            assertFalse(text.contains(secret), secret);
        assertTrue(text.contains("opc.tcp://localhost:4840/path"));
    }

    @Test
    void verificationClosesItsOwnConnectionWithoutChangingDeliveryState() throws Exception {
        AtomicBoolean closed = new AtomicBoolean();
        AtomicReference<ConnectionAttempt> attempt = new AtomicReference<>();
        SubscribeOpcUa processor =
                new ProcessorTest.OfflineProcessor() {
                    @Override
                    protected AutoCloseable verifyConnection(
                            OpcSettings settings,
                            ConnectionAttempt generation,
                            ConnectionDiagnostics report) {
                        attempt.set(generation);
                        report.sessionActive(new NodeId(1, "verify"));
                        report.stage("Subscribed");
                        return () -> closed.set(true);
                    }
                };
        TestRunner r = ProcessorTest.configured(processor);
        processor.start(r.getProcessContext());
        try {
            processor.acceptForTest(ByteLimitsTest.TAG, new DataValue(new Variant(21)));
            List<ConfigVerificationResult> result =
                    processor.verify(r.getProcessContext(), null, Collections.emptyMap());
            assertEquals(ConfigVerificationResult.Outcome.SUCCESSFUL, result.get(0).getOutcome());
            assertTrue(closed.get());
            assertThrows(Exception.class, () -> attempt.get().check());
            assertEquals(
                    0L,
                    processor.diagnosticSnapshot(r.getProcessContext()).get("connectionAttempts"));
            r.run(1, false, false);
            r.assertTransferCount(SubscribeOpcUa.SUCCESS, 1);
            r.assertTransferCount(SubscribeOpcUa.DIAGNOSTICS, 0);
        } finally {
            processor.stop();
        }
    }

    @Test
    void verificationRedactsSecretsBeforeNormalizingOrTruncatingMetadata() {
        SubscribeOpcUa p =
                new SubscribeOpcUa() {
                    @Override
                    protected AutoCloseable verifyConnection(
                            OpcSettings settings,
                            ConnectionAttempt generation,
                            ConnectionDiagnostics report)
                            throws Exception {
                        report.namespaces(
                                new String[] {
                                    String.join("", Collections.nCopies(505, "a"))
                                            + "private\npassword"
                                });
                        throw new IllegalArgumentException(
                                "server says private\npassword and private");
                    }
                };
        TestRunner r = ProcessorTest.configured(p);
        r.setProperty(SubscribeOpcUa.PASSWORD, "private\npassword");
        r.setProperty(SubscribeOpcUa.KEY_PASSWORD, "private");
        ConfigVerificationResult result =
                p.verify(r.getProcessContext(), null, Collections.emptyMap()).get(0);
        assertEquals(ConfigVerificationResult.Outcome.FAILED, result.getOutcome());
        assertFalse(result.getExplanation().contains("private"));
        assertFalse(result.getExplanation().contains("password"));
        assertFalse(result.getExplanation().contains("\n"));
        assertTrue(result.getExplanation().contains("[redacted]"));
    }

    @Test
    void diagnosticCommitFailureRetriesReportAndDataTogether() throws Exception {
        ProcessorTest.OfflineProcessor p = new ProcessorTest.OfflineProcessor();
        TestRunner r = ProcessorTest.configured(p);
        r.setProperty(SubscribeOpcUa.DIAGNOSTICS_INTERVAL, "3600");
        p.start(r.getProcessContext());
        try {
            p.acceptForTest(
                    ByteLimitsTest.TAG,
                    new DataValue(new Variant("measurement-must-not-appear-in-health")));
            ProcessSession failed = spy(r.getProcessSessionFactory().createSession());
            AtomicReference<Consumer<Throwable>> failure = new AtomicReference<>();
            doAnswer(
                            call -> {
                                failure.set(call.getArgument(1));
                                return null;
                            })
                    .when(failed)
                    .commitAsync(any(Runnable.class), any());
            p.onTrigger(r.getProcessContext(), failed);
            ProcessSession waiting = spy(r.getProcessSessionFactory().createSession());
            p.onTrigger(r.getProcessContext(), waiting);
            verify(waiting, never()).create();
            failed.rollback();
            failure.get().accept(new IllegalStateException("failed"));
            r.run(2, false, false);
            r.assertTransferCount(SubscribeOpcUa.SUCCESS, 1);
            r.assertTransferCount(SubscribeOpcUa.DIAGNOSTICS, 1);
            byte[] content =
                    r.getContentAsByteArray(
                            r.getFlowFilesForRelationship(SubscribeOpcUa.DIAGNOSTICS).get(0));
            JsonNode health = JSON.readTree(content);
            assertEquals(
                    1, health.get("buffer").get("records").asInt()); // snapshot before data commit
            assertTrue(health.get("buffer").get("reserved").asBoolean());
            assertFalse(
                    new String(content, java.nio.charset.StandardCharsets.UTF_8)
                            .contains("measurement-must-not-appear"));
        } finally {
            p.stop();
        }
    }

    @Test
    void canReportFailureWithNoMeasurementsAndRetryAfterSerializationFailure() throws Exception {
        SubscribeOpcUa p =
                new SubscribeOpcUa() {
                    @Override
                    protected AutoCloseable openConnection(ProcessContext c, ConnectionAttempt a) {
                        throw new IllegalStateException("test-only-password cannot connect");
                    }
                };
        TestRunner r = ProcessorTest.configured(p);
        r.setProperty(SubscribeOpcUa.DIAGNOSTICS_INTERVAL, "60");
        p.start(r.getProcessContext());
        try {
            ProcessSession failed = spy(r.getProcessSessionFactory().createSession());
            doThrow(new IllegalStateException("repository unavailable")).when(failed).create();
            assertThrows(RuntimeException.class, () -> p.onTrigger(r.getProcessContext(), failed));
            verify(failed).rollback();
            r.run(1, false, false);
            r.assertTransferCount(SubscribeOpcUa.SUCCESS, 0);
            r.assertTransferCount(SubscribeOpcUa.DIAGNOSTICS, 1);
            JsonNode health =
                    JSON.readTree(
                            r.getContentAsByteArray(
                                    r.getFlowFilesForRelationship(SubscribeOpcUa.DIAGNOSTICS)
                                            .get(0)));
            assertEquals("RETRYING", health.get("state").asText());
            assertEquals(1, health.get("connectionAttempts").asInt());
            assertFalse(health.get("lastFailure").asText().contains("test-only-password"));
        } finally {
            p.stop();
        }
    }

    @Test
    void oldPendingReportCannotDelayReportsAfterConfigurationChange() throws Exception {
        ProcessorTest.OfflineProcessor p = new ProcessorTest.OfflineProcessor();
        TestRunner r = ProcessorTest.configured(p);
        r.setProperty(SubscribeOpcUa.DIAGNOSTICS_INTERVAL, "3600");
        p.start(r.getProcessContext());
        ProcessSession pending = spy(r.getProcessSessionFactory().createSession());
        AtomicReference<Runnable> committed = new AtomicReference<>();
        doAnswer(
                        call -> {
                            committed.set(call.getArgument(0));
                            return null;
                        })
                .when(pending)
                .commitAsync(any(Runnable.class), any());
        p.onTrigger(r.getProcessContext(), pending);
        p.stop();
        r.setProperty(SubscribeOpcUa.DIAGNOSTICS_INTERVAL, "1");
        p.start(r.getProcessContext());
        try {
            pending.commitAsync();
            committed.get().run();
            r.run(1, false, false);
            r.assertTransferCount(SubscribeOpcUa.DIAGNOSTICS, 2);
            r.assertTransferCount(SubscribeOpcUa.SUCCESS, 0);
        } finally {
            p.stop();
        }
    }
}
