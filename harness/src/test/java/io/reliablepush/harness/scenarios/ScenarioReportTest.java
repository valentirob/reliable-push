package io.reliablepush.harness.scenarios;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import javax.xml.parsers.DocumentBuilderFactory;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

class ScenarioReportTest {

    static final List<ScenarioResult> RESULTS = List.of(
            new ScenarioResult("delivered-before-timeout", "PASSED", 8123, null, List.of("[+0 ms] sent")),
            new ScenarioResult("receiver-final-failure", "FAILED", 30001,
                    new ScenarioResult.Failure("state FALLBACK_FAILED", "state FALLBACK_TRIGGERED", "check the receiver"),
                    List.of("[+5 ms] sent", "[+30001 ms] gave up")),
            new ScenarioResult("worker-crash-inside-window", "SKIPPED", 0, null, List.of("external control")));

    static Document parse(String xml) throws Exception {
        return DocumentBuilderFactory.newInstance().newDocumentBuilder()
                .parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void summaryMatchesSchema() {
        String json = ScenarioReport.summaryJson(RESULTS);

        List<Map<String, Object>> parsed = JsonMapper.builder().build().readValue(json, new TypeReference<>() {});
        assertThat(parsed).hasSize(3);
        for (Map<String, Object> entry : parsed) {
            assertThat(new ArrayList<>(entry.keySet()))
                    .containsExactly("scenario", "status", "durationMs", "failure", "logs");
            assertThat(entry.get("status")).isIn("PASSED", "FAILED", "SKIPPED");
            assertThat(entry.get("durationMs")).isInstanceOf(Number.class);
            assertThat(entry.get("logs")).isInstanceOf(List.class);
        }
        assertThat(parsed.get(0)).containsEntry("scenario", "delivered-before-timeout").containsEntry("failure", null);
        assertThat(parsed.get(1).get("failure")).isEqualTo(Map.of(
                "expected", "state FALLBACK_FAILED", "actual", "state FALLBACK_TRIGGERED", "hint", "check the receiver"));
        assertThat(parsed.get(1).get("logs")).isEqualTo(List.of("[+5 ms] sent", "[+30001 ms] gave up"));
        assertThat(ScenarioReport.summaryJson(List.of()).strip()).isEqualTo("[ ]");
    }

    @Test
    void junitXmlHasFailureDetails() throws Exception {
        Document doc = parse(ScenarioReport.junitXml(RESULTS));

        Element suite = doc.getDocumentElement();
        assertThat(suite.getTagName()).isEqualTo("testsuite");
        assertThat(suite.getAttribute("name")).isEqualTo("reliable-push-e2e");
        assertThat(suite.getAttribute("tests")).isEqualTo("3");
        assertThat(suite.getAttribute("failures")).isEqualTo("1");
        assertThat(suite.getAttribute("skipped")).isEqualTo("1");
        NodeList cases = suite.getElementsByTagName("testcase");
        assertThat(cases.getLength()).isEqualTo(3);
        Element failed = (Element) cases.item(1);
        assertThat(failed.getAttribute("name")).isEqualTo("receiver-final-failure");
        assertThat(failed.getAttribute("time")).isEqualTo("30.001");
        Element failure = (Element) failed.getElementsByTagName("failure").item(0);
        assertThat(failure.getAttribute("message")).contains("state FALLBACK_FAILED").contains("state FALLBACK_TRIGGERED");
        assertThat(failure.getTextContent()).contains("expected: state FALLBACK_FAILED")
                .contains("actual: state FALLBACK_TRIGGERED").contains("hint: check the receiver");
        assertThat(((Element) cases.item(0)).getElementsByTagName("failure").getLength()).isZero();
        assertThat(((Element) cases.item(2)).getElementsByTagName("skipped").getLength()).isEqualTo(1);
    }

    @Test
    void escapesXml() throws Exception {
        String nasty = "a<b>&\"c\"'d' \u0001 end";
        List<ScenarioResult> results = List.of(new ScenarioResult("x<y", "FAILED", 1,
                new ScenarioResult.Failure(nasty, nasty, nasty), List.of(nasty)));

        String xml = ScenarioReport.junitXml(results);

        assertThat(xml).doesNotContain("\u0001").doesNotContain("a<b>");
        Element testcase = (Element) parse(xml).getElementsByTagName("testcase").item(0);
        assertThat(testcase.getAttribute("name")).isEqualTo("x<y");
        Element failure = (Element) testcase.getElementsByTagName("failure").item(0);
        assertThat(failure.getAttribute("message")).contains("a<b>&\"c\"'d'");
        assertThat(failure.getTextContent()).contains("a<b>&\"c\"'d'");
    }
}
