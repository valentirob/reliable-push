package io.reliablepush.harness.scenarios;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * Machine-readable reports of a run (spec 10.1): {@code summary.json} — an array of
 * {@code {scenario, status, durationMs, failure: {expected, actual, hint} | null, logs}} in run order — and one JUnit
 * XML {@code <testsuite name="reliable-push-e2e">}.
 */
public final class ScenarioReport {

    static final String SUITE = "reliable-push-e2e";
    private static final JsonMapper JSON = JsonMapper.builder().enable(SerializationFeature.INDENT_OUTPUT).build();

    private ScenarioReport() {
    }

    /** One result as an ordered JSON-ready map (the summary.json element shape). */
    public static Map<String, Object> toJson(ScenarioResult r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("scenario", r.scenario());
        m.put("status", r.status());
        m.put("durationMs", r.durationMs());
        if (r.failure() == null) {
            m.put("failure", null);
        } else {
            Map<String, Object> f = new LinkedHashMap<>();
            f.put("expected", r.failure().expected());
            f.put("actual", r.failure().actual());
            f.put("hint", r.failure().hint());
            m.put("failure", f);
        }
        m.put("logs", r.logs());
        return m;
    }

    public static String summaryJson(List<ScenarioResult> results) {
        return JSON.writeValueAsString(results.stream().map(ScenarioReport::toJson).toList());
    }

    public static String junitXml(List<ScenarioResult> results) {
        long failures = results.stream().filter(r -> ScenarioResult.FAILED.equals(r.status())).count();
        long skipped = results.stream().filter(r -> ScenarioResult.SKIPPED.equals(r.status())).count();
        long totalMs = results.stream().mapToLong(ScenarioResult::durationMs).sum();
        StringBuilder sb = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        sb.append("<testsuite name=\"").append(SUITE).append("\" tests=\"").append(results.size())
                .append("\" failures=\"").append(failures).append("\" errors=\"0\" skipped=\"").append(skipped)
                .append("\" time=\"").append(seconds(totalMs)).append("\">\n");
        for (ScenarioResult r : results) {
            sb.append("  <testcase classname=\"").append(SUITE).append("\" name=\"").append(attr(r.scenario()))
                    .append("\" time=\"").append(seconds(r.durationMs())).append("\">\n");
            if (ScenarioResult.FAILED.equals(r.status())) {
                ScenarioResult.Failure f = r.failure() != null ? r.failure()
                        : new ScenarioResult.Failure("", "failed", "");
                sb.append("    <failure message=\"").append(attr("expected: " + f.expected() + "; actual: "
                                + f.actual())).append("\">")
                        .append(text("expected: " + f.expected() + "\nactual: " + f.actual() + "\nhint: " + f.hint()))
                        .append("</failure>\n");
            } else if (ScenarioResult.SKIPPED.equals(r.status())) {
                sb.append("    <skipped message=\"").append(attr(r.logs().isEmpty() ? "" : r.logs().get(0)))
                        .append("\"/>\n");
            }
            if (!r.logs().isEmpty()) {
                sb.append("    <system-out>").append(text(String.join("\n", r.logs()))).append("</system-out>\n");
            }
            sb.append("  </testcase>\n");
        }
        return sb.append("</testsuite>\n").toString();
    }

    private static String seconds(long ms) {
        return String.format(Locale.ROOT, "%.3f", ms / 1000.0);
    }

    private static String text(String s) {
        return escape(s, false);
    }

    private static String attr(String s) {
        return escape(s, true);
    }

    /** XML 1.0 escaping; characters XML cannot carry (control chars, lone surrogates) become U+FFFD. */
    private static String escape(String s, boolean attribute) {
        if (s == null) {
            return "";
        }
        StringBuilder out = new StringBuilder(s.length() + 16);
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            switch (ch) {
                case '&' -> out.append("&amp;");
                case '<' -> out.append("&lt;");
                case '>' -> out.append("&gt;");
                case '"' -> out.append(attribute ? "&quot;" : "\"");
                case '\'' -> out.append(attribute ? "&apos;" : "'");
                case '\n' -> out.append(attribute ? "&#10;" : "\n");
                case '\r' -> out.append(attribute ? "&#13;" : "\r");
                case '\t' -> out.append(attribute ? "&#9;" : "\t");
                default -> {
                    if (Character.isHighSurrogate(ch) && i + 1 < s.length() && Character.isLowSurrogate(s.charAt(i + 1))) {
                        out.append(ch).append(s.charAt(++i));
                    } else if (ch < 0x20 || Character.isSurrogate(ch) || ch == 0xFFFE || ch == 0xFFFF) {
                        out.append('�');
                    } else {
                        out.append(ch);
                    }
                }
            }
        }
        return out.toString();
    }
}
