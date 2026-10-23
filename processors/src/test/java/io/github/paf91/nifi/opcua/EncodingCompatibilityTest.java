/* SPDX-License-Identifier: Apache-2.0 */
package io.github.paf91.nifi.opcua;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.*;

import org.eclipse.milo.opcua.stack.core.types.builtin.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Instant;
import java.util.*;

class EncodingCompatibilityTest {
    @ParameterizedTest
    @ValueSource(longs = {0, 1, 255, 65535, 0x40000480L, 0x80170000L, 0xffffffffL, -1L})
    void statusHexAndQualityRemainUnchanged(long code) throws Exception {
        StatusCode status = new StatusCode(code);
        JsonNode record =
                new ObjectMapper()
                        .readTree(
                                NotificationJson.encode(
                                        MemoryBoundsTest.TAG,
                                        new DataValue(new Variant(2), status, null, null),
                                        1024));
        assertEquals(
                String.format(Locale.ROOT, "0x%08X", status.getValue()),
                record.get("statusCode").asText());
        assertEquals(
                status.isGood() ? "Good" : status.isBad() ? "Bad" : "Uncertain",
                record.get("quality").asText());
        List<String> fields = new ArrayList<>();
        record.fieldNames().forEachRemaining(fields::add);
        assertEquals(
                Arrays.asList(
                        "tag",
                        "nodeId",
                        "value",
                        "valueType",
                        "statusCode",
                        "quality",
                        "sourceTimestamp",
                        "receivedTimestamp"),
                fields);
        assertTrue(record.get("sourceTimestamp").isNull());
        assertNotNull(Instant.parse(record.get("receivedTimestamp").asText()));
    }

    @Test
    void diagnosticTimestampsKeepTheirMeaningAndJsonStringRepresentation() {
        ConnectionDiagnostics diagnostics = new ConnectionDiagnostics();
        assertNull(diagnostics.snapshot().get("lastNotificationReceivedAt"));
        diagnostics.keepAlive();
        diagnostics.notification(new DataValue(new Variant(1)));
        Map<String, Object> good = diagnostics.snapshot();
        assertEquals(good.get("lastNotificationReceivedAt"), good.get("lastGoodValueReceivedAt"));
        diagnostics.notification(
                new DataValue(new Variant(2), new StatusCode(0x80000000L), null, null));
        Map<String, Object> bad = diagnostics.snapshot();
        assertEquals(good.get("lastGoodValueReceivedAt"), bad.get("lastGoodValueReceivedAt"));
        assertEquals(2L, bad.get("notificationsReceived"));
        for (String key :
                Arrays.asList(
                        "lastKeepAliveReceivedAt",
                        "lastNotificationReceivedAt",
                        "lastGoodValueReceivedAt"))
            assertNotNull(Instant.parse((String) bad.get(key)));
    }
}
