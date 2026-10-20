/* SPDX-License-Identifier: Apache-2.0 */
package io.github.paf91.nifi.opcua;

import static org.junit.jupiter.api.Assertions.*;

import org.apache.nifi.util.TestRunner;
import org.junit.jupiter.api.Test;

import java.util.*;

class TagListTest {
    private List<TagDefinition> parse(String text) {
        return TagList.parse(text, Collections.emptyMap(), 1000, 100);
    }

    @Test
    void acceptsPastedLinesCommentsAliasesAndWindowsNewlines() {
        List<TagDefinition> tags =
                parse(
                        "\uFEFF# Discrete signals\r\n\r\n"
                                + "DemoSwitch | ns=2;s=Demo.Boolean\r\n"
                                + "ns=0;i=2258\r\n");
        assertEquals(2, tags.size());
        assertEquals("DemoSwitch", tags.get(0).alias());
        assertEquals("ns=0;i=2258", tags.get(1).alias());
        assertEquals(1000, tags.get(0).samplingIntervalMs());
    }

    @Test
    void preservesPunctuationAndUnicodeInNodeIdentifiers() {
        String id = "ns=2;s=Plant|Line=A,B;Signal#sensor_Ω";
        assertEquals(id, parse(id).get(0).nodeId().toParseableString());
        assertEquals(id, parse("Weight | " + id).get(0).nodeId().toParseableString());
    }

    @Test
    void acceptsLargeListAndAppliesProcessorDefaults() {
        StringBuilder lines = new StringBuilder();
        for (int i = 0; i < 1000; i++)
            lines.append("Tag").append(i).append(" | ns=2;s=Tag").append(i).append('\n');
        List<TagDefinition> tags = TagList.parse(lines.toString(), Collections.emptyMap(), 250, 20);
        assertEquals(1000, tags.size());
        assertEquals("Tag999", tags.get(999).alias());
        assertEquals(250, tags.get(999).samplingIntervalMs());
        assertEquals(20, tags.get(999).queueSize());
    }

    @Test
    void parsesJsonOptionsAndOptionalAliases() {
        List<TagDefinition> tags =
                parse(
                        "[\"ns=0;i=2258\",{\"alias\":\"DemoSwitch\",\"nodeId\":\"ns=2;s=DemoSwitch\",\"samplingIntervalMs\":0,\"queueSize\":50,\"discardOldest\":false},{\"nodeId\":\"ns=2;s=Example.Pump.Running\"}]");
        assertEquals(3, tags.size());
        assertEquals("DemoSwitch", tags.get(1).alias());
        assertEquals(0, tags.get(1).samplingIntervalMs());
        assertEquals(50, tags.get(1).queueSize());
        assertFalse(tags.get(1).discardOldest());
        assertEquals("ns=2;s=Example.Pump.Running", tags.get(2).alias());
    }

    @Test
    void mergesIndividualPropertiesAndRejectsDuplicatesAcrossInputs() {
        assertEquals(
                2,
                TagList.parse(
                                "ns=0;i=2258",
                                Collections.singletonMap("DemoSwitch", "ns=2;s=DemoSwitch"),
                                1000,
                                100)
                        .size());
        assertTrue(
                assertThrows(
                                IllegalArgumentException.class,
                                () ->
                                        TagList.parse(
                                                "DemoSwitch | ns=2;s=DemoSwitch",
                                                Collections.singletonMap(
                                                        "DemoSwitch",
                                                        "ns=2;s=Example.Pump.Running"),
                                                1000,
                                                100))
                        .getMessage()
                        .contains("Duplicate alias"));
        assertTrue(
                assertThrows(
                                IllegalArgumentException.class,
                                () ->
                                        TagList.parse(
                                                "DemoSwitch | ns=2;s=DemoSwitch",
                                                Collections.singletonMap(
                                                        "another", "ns=2;s=DemoSwitch"),
                                                1000,
                                                100))
                        .getMessage()
                        .contains("Duplicate NodeId"));
        assertThrows(IllegalArgumentException.class, () -> parse("i=2258\nns=0;i=2258"));
    }

    @Test
    void reportsLineOrEntryAndRejectsMalformedOrEmptyLists() {
        assertTrue(
                assertThrows(
                                IllegalArgumentException.class,
                                () -> parse("ns=0;i=2258\n# comment\ninvalid"))
                        .getMessage()
                        .startsWith("Tags line 3:"));
        assertTrue(
                assertThrows(
                                IllegalArgumentException.class,
                                () ->
                                        parse(
                                                "[\"ns=0;i=2258\",{\"nodeId\":\"ns=2;s=A\",\"queueSize\":0}]"))
                        .getMessage()
                        .startsWith("Tags entry 2:"));
        for (String invalid :
                Arrays.asList(
                        "",
                        "# no tags",
                        "[]",
                        "{}",
                        "[null]",
                        "[42]",
                        "[{}]",
                        "[{\"alias\":false,\"nodeId\":\"i=2258\"}]",
                        "[\"i=2258\"] trailing",
                        "[{\"nodeId\":\"i=2258\",\"nodeId\":\"i=2259\"}]")) {
            assertThrows(IllegalArgumentException.class, () -> parse(invalid), invalid);
        }
    }

    @Test
    void validatesBulkOnlyConfigurationAndOptionalApplicationUri() {
        TestRunner runner = ProcessorTest.configured(new ProcessorTest.OfflineProcessor());
        runner.removeProperty("DemoSwitch");
        runner.removeProperty(SubscribeOpcUa.APPLICATION_URI);
        runner.setProperty(SubscribeOpcUa.TAG_LIST, "DemoSwitch | ns=2;s=Demo.Boolean");
        runner.assertValid();
        runner.setProperty("DemoSwitch", "ns=2;s=another");
        runner.assertNotValid();
        runner.removeProperty("DemoSwitch");
        runner.setProperty(SubscribeOpcUa.TAG_LIST, "# empty list");
        runner.assertNotValid();
        runner.setProperty(SubscribeOpcUa.TAG_LIST, "[\"ns=0;i=2258\"]");
        runner.assertValid();
    }
}
