/* SPDX-License-Identifier: Apache-2.0 */
package io.github.paf91.nifi.opcua;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.eclipse.milo.opcua.stack.core.types.builtin.*;
import org.eclipse.milo.opcua.stack.core.types.enumerated.DataChangeTrigger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.*;

class TagResolutionTest {
    static final String STANDARD = "http://opcfoundation.org/UA/";
    static final String[] TABLE = {
        STANDARD, "urn:server", "urn:example:realtime", "urn:example:minute"
    };

    @ParameterizedTest
    @ValueSource(
            strings = {
                "i=0",
                "i=4294967295",
                "s=Some;Identifier|with=punctuation_Ω",
                "g=12345678-1234-1234-1234-123456789abc",
                "b=AQID"
            })
    void supportsEveryIdentifierType(String identifier) {
        TagAddress address = TagAddress.parse("nsu=urn:example:minute;" + identifier);
        assertEquals(NodeId.parse("ns=3;" + identifier), address.resolve(TABLE));
        assertEquals(address, TagAddress.parse(address.text()));
    }

    @Test
    void uriEscapingDoesNotTreatPlusAsSpaceOrLoseLiteralPercentEscapes() {
        String uri = "urn:example:a+b;c%20d:Ω";
        TagAddress address = TagAddress.parse("nsu=urn:example:a+b%3Bc%2520d:%CE%A9;s=value");
        assertEquals(uri, address.namespaceUri());
        assertEquals(new NodeId(1, "value"), address.resolve(new String[] {STANDARD, uri}));
        assertEquals(address, TagAddress.parse(address.text()));
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "nsu=relative;s=x",
                "nsu=;s=x",
                "nsu=urn:x",
                "nsu=urn:x;ns=2;s=x",
                "nsu=urn:x;s=x\n",
                "nsu=urn:%;s=x",
                "nsu=urn:%GG;s=x",
                "nsu=urn:%FF;s=x",
                "nsu=urn:%00;s=x",
                "nsu=urn:x;q=x",
                "ns=0;i=0",
                "ns=65536;s=x"
            })
    void rejectsInvalidAddresses(String address) {
        assertThrows(RuntimeException.class, () -> TagAddress.parse(address));
        assertThrows(
                IllegalArgumentException.class,
                () -> TagDefinition.parse("tag", "{\"nodeId\":" + quote(address) + "}", 100, 10));
    }

    private static String quote(String text) {
        try {
            return new ObjectMapper().writeValueAsString(text);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    @Test
    void neverFallsBackForMissingAmbiguousOrNullNamespaces() {
        TagAddress address = TagAddress.parse("nsu=urn:example:minute;s=value");
        for (String[] namespaces :
                Arrays.asList(
                        null,
                        new String[] {STANDARD},
                        new String[] {STANDARD, "urn:example:minute", "urn:example:minute"}))
            assertThrows(IllegalArgumentException.class, () -> address.resolve(namespaces));
        assertThrows(
                IllegalArgumentException.class,
                () -> TagAddress.parse("nsu=" + STANDARD + ";i=0").resolve(TABLE));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        TagResolver.namespaceArray(
                                new DataValue(new Variant(TABLE), StatusCode.BAD, null, null)));
        assertThrows(
                IllegalArgumentException.class,
                () -> TagResolver.namespaceArray(new DataValue(new Variant("wrong type"))));
        assertThrows(IllegalArgumentException.class, () -> TagResolver.namespaceArray(null));
    }

    @Test
    void resolvesAgainWithoutRelabelingRetainedRecords() throws Exception {
        List<TagDefinition> configured =
                TagList.parse(
                        "minute | nsu=urn:example:minute;s=Temperature\nreal | ns=2;s=Temperature",
                        Collections.emptyMap(),
                        1000,
                        10);
        List<TagDefinition> first = TagResolver.resolve(configured, TABLE);
        List<TagDefinition> next =
                TagResolver.resolve(
                        configured,
                        new String[] {
                            STANDARD,
                            "urn:server",
                            "urn:example:realtime",
                            "urn:another",
                            "urn:example:minute"
                        });
        assertEquals("ns=3;s=Temperature", first.get(0).nodeId().toParseableString());
        assertEquals("ns=4;s=Temperature", next.get(0).nodeId().toParseableString());
        assertEquals(first.get(1).nodeId(), next.get(1).nodeId());
        assertThrows(IllegalStateException.class, () -> configured.get(0).nodeId());
        com.fasterxml.jackson.databind.JsonNode record =
                new ObjectMapper()
                        .readTree(
                                NotificationJson.encode(
                                        first.get(0), new DataValue(new Variant(42))));
        assertEquals("ns=3;s=Temperature", record.get("nodeId").asText());
        assertEquals(
                "nsu=urn:example:minute;s=Temperature", record.get("configuredNodeId").asText());
        assertEquals("urn:example:minute", record.get("namespaceUri").asText());
        assertFalse(
                new ObjectMapper()
                        .readTree(
                                NotificationJson.encode(
                                        first.get(1), new DataValue(new Variant(42))))
                        .has("configuredNodeId"));
    }

    @Test
    void rejectsAliasesOfTheSameResolvedNodeButAllowsDifferentNamespaces() {
        List<TagDefinition> configured =
                TagList.parse(
                        "a | ns=3;s=Temperature\nb | nsu=urn:example:minute;s=Temperature",
                        Collections.emptyMap(),
                        1000,
                        10);
        assertThrows(IllegalArgumentException.class, () -> TagResolver.resolve(configured, TABLE));
        List<TagDefinition> different =
                TagList.parse(
                        "a | nsu=urn:example:realtime;s=Temperature\n"
                                + "b | nsu=urn:example:minute;s=Temperature",
                        Collections.emptyMap(),
                        1000,
                        10);
        assertEquals(2, TagResolver.resolve(different, TABLE).size());
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        TagList.parse(
                                "a | nsu=urn:ex%61mple:minute;s=Temperature\n"
                                        + "b | nsu=urn:example:minute;s=Temperature",
                                Collections.emptyMap(), 1000, 10));
    }

    @Test
    void acceptsUriAddressesInAllConfigurationForms() {
        List<TagDefinition> line =
                TagList.parse("nsu=urn:example:minute;s=A|B", Collections.emptyMap(), 1000, 10);
        List<TagDefinition> json =
                TagList.parse(
                        "[{\"nodeId\":\"nsu=urn:example:minute;s=A|B\",\"alias\":\"x\"}]",
                        Collections.emptyMap(),
                        1000,
                        10);
        List<TagDefinition> dynamic =
                TagList.parse(
                        null,
                        Collections.singletonMap("x", "nsu=urn:example:minute;s=A|B"),
                        1000,
                        10);
        assertEquals(line.get(0).address(), json.get(0).address());
        assertEquals(line.get(0).address(), dynamic.get(0).address());
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "\"deadbandType\":\"Absolute\"",
                "\"deadbandValue\":1",
                "\"deadbandType\":\"absolute\",\"deadbandValue\":1",
                "\"deadbandType\":null",
                "\"deadbandType\":\"None\",\"deadbandValue\":1",
                "\"deadbandType\":\"Absolute\",\"deadbandValue\":-1",
                "\"deadbandType\":\"Percent\",\"deadbandValue\":100.01",
                "\"deadbandType\":\"Absolute\",\"deadbandValue\":1e999",
                "\"deadbandType\":\"Absolute\",\"deadbandValue\":\"NaN\""
            })
    void rejectsInvalidDeadbands(String options) {
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        TagDefinition.parse(
                                "tag", "{\"nodeId\":\"ns=2;s=x\"," + options + "}", 100, 10));
    }

    @Test
    void filterPreservesQualityChangesAndAcceptsBoundaryValues() {
        for (String type : Arrays.asList("Absolute", "Percent")) {
            for (double value : new double[] {0, 0.5, 100}) {
                TagDefinition tag =
                        TagDefinition.parse(
                                "tag",
                                "{\"nodeId\":\"nsu=urn:example:minute;s=x\",\"deadbandType\":\""
                                        + type
                                        + "\",\"deadbandValue\":"
                                        + value
                                        + "}",
                                100,
                                10);
                assertEquals(DataChangeTrigger.StatusValue, tag.filter().getTrigger());
                assertEquals(
                        type.equals("Absolute") ? 1 : 2, tag.filter().getDeadbandType().intValue());
                assertEquals(value, tag.filter().getDeadbandValue());
                assertEquals(
                        value,
                        TagResolver.resolve(Collections.singletonList(tag), TABLE)
                                .get(0)
                                .filter()
                                .getDeadbandValue());
            }
        }
        assertNull(TagDefinition.parse("tag", "ns=2;s=x", 100, 10).filter());
        assertNull(
                TagDefinition.parse(
                                "tag",
                                "{\"nodeId\":\"ns=2;s=x\",\"deadbandType\":\"None\"}",
                                100,
                                10)
                        .filter());
    }
}
