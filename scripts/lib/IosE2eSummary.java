// Builds harness/build/ios-e2e/summary.json and junit/ios-e2e.xml for scripts/ios-e2e.sh from the test results of each
// phase's .xcresult bundle (the JSON of `xcrun xcresulttool get test-results tests --path <bundle>`) and the entries the
// script measured itself. Single-file Java program (JDK 21, no dependencies):
//
//   java scripts/lib/IosE2eSummary.java <summary.json> <junit.xml> [--phase <name>=<tests.json>]...
//        [--expect <scenario>]... [--entry <fields>]...
//
// Scenario names: one entry per test method, the method name without "test" and "()" in kebab case
// (testDeliveredIsReported() -> delivered-is-reported). A phase whose JSON file is missing or empty (the build or the
// test run failed before any result) contributes nothing; its expected scenarios then become FAILED ("did not run").
// --entry: "scenario|STATUS|durationMs|expected|actual|hint|log" (expected/actual/hint only matter for FAILED; for
// SKIPPED the hint says what is missing and is printed and kept in summary.json as "hint").
// STATUS is PASSED, FAILED or SKIPPED. SKIPPED counts in neither <passed> nor <total>.
// --expect: a scenario that must be there; a missing one becomes FAILED ("did not run"), and so does one that is
// SKIPPED, except the optional apns-real-delivery (Task 7: SKIPPED stays SKIPPED).
// Same entry shape as harness/build/e2e/summary.json (SKIPPED entries add "hint"). Prints one line per entry, then
// "<passed>/<total> <skipped>" (for example "8/8 0"), on stdout.
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public class IosE2eSummary {

    static final String OUT = "harness/build/ios-e2e";

    static final Map<String, String> EXPECTED = Map.of(
            "delivered-is-reported", "DELIVERED before the 5-minute (fast clock) timeout, no webhook afterwards",
            "read-is-reported-on-tap", "tapping the notification on Springboard reports READ",
            "offline-delivery-is-sent-on-next-launch",
            "PENDING while the device proxy is offline, DELIVERED after the next launch, before the timeout",
            "notifications-blocked-means-fallback", "authorization denied: no DELIVERED, FALLBACK_TRIGGERED with one webhook",
            "token-refresh-near-expiry", "DELIVERED after a token refresh (backend log device.token_refreshed)",
            "logout-revokes-device", "no DELIVERED after logout, FALLBACK_TRIGGERED with one webhook",
            "keychain-access-group-and-app-group-are-usable",
            "Keychain access group = App Group id and the App Group defaults and container work in the signed app",
            "keychain-round-trip-without-access-group", "Keychain round trip in the default access group of the signed app");

    /** Scenarios allowed to be SKIPPED even when expected (they need files only the maintainer can place). */
    static final Set<String> OPTIONAL = Set.of("apns-real-delivery");

    static final class Entry {
        String scenario;
        String suite = "ios-e2e";
        String status = "PASSED";
        long durationMs;
        String expected;
        String actual;
        String hint;
        final List<String> logs = new ArrayList<>();
    }

    public static void main(String[] args) throws Exception {
        Path out = Path.of(args[0]);
        Path junit = Path.of(args[1]);
        List<String> expect = new ArrayList<>();
        List<String> extra = new ArrayList<>();
        Map<String, Entry> entries = new LinkedHashMap<>();
        for (int i = 2; i < args.length; i++) {
            switch (args[i]) {
                case "--expect" -> expect.add(args[++i]);
                case "--entry" -> extra.add(args[++i]);
                case "--phase" -> {
                    String[] kv = args[++i].split("=", 2);
                    readPhase(kv[0], Path.of(kv[1]), entries);
                }
                default -> throw new IllegalArgumentException("unknown argument: " + args[i]);
            }
        }
        for (String e : extra) {
            String[] f = e.split("\\|", -1);
            Entry en = new Entry();
            en.scenario = f[0];
            en.status = f[1];
            en.durationMs = Long.parseLong(f[2]);
            en.expected = f[3];
            en.actual = f[4];
            en.hint = f[5];
            if (!f[6].isEmpty()) {
                en.logs.add(f[6]);
            }
            entries.put(en.scenario, en);
        }
        for (String name : expect) {
            Entry en = entries.get(name);
            if (en == null) {
                en = new Entry();
                en.scenario = name;
                en.status = "FAILED";
                en.expected = EXPECTED.getOrDefault(name, "the test runs and passes");
                en.actual = "did not run (no result in the phase's .xcresult)";
                en.hint = OUT + "/logs/xcodebuild-*.log (build, install or test-runner problem)";
                entries.put(name, en);
            } else if (en.status.equals("SKIPPED") && !OPTIONAL.contains(name)) {
                en.status = "FAILED"; // an expected scenario that did not run is a failure, not a pass-by-omission
                en.expected = EXPECTED.getOrDefault(name, "the test runs and passes");
                en.actual = "skipped (" + (en.actual == null ? "XCTSkip" : en.actual) + ")";
                en.hint = "the phase's .xcresult in " + OUT + "/ (open it in Xcode)";
            }
        }
        StringBuilder sb = new StringBuilder("[ ");
        int passed = 0;
        int skipped = 0;
        int failed = 0;
        long totalMs = 0;
        int n = 0;
        for (Entry en : entries.values()) {
            if (n++ > 0) {
                sb.append(", ");
            }
            switch (en.status) {
                case "PASSED" -> passed++;
                case "SKIPPED" -> skipped++;
                default -> failed++;
            }
            totalMs += en.durationMs;
            System.out.println("  " + en.status + "  " + en.scenario + " (" + en.durationMs + " ms)"
                    + (en.status.equals("SKIPPED") && en.hint != null && !en.hint.isEmpty() ? ": " + en.hint : "")
                    + (en.status.equals("FAILED") && en.actual != null ? ": " + en.actual : ""));
            sb.append("{\n  \"scenario\" : ").append(str(en.scenario))
                    .append(",\n  \"status\" : ").append(str(en.status))
                    .append(",\n  \"durationMs\" : ").append(en.durationMs)
                    .append(",\n  \"failure\" : ");
            if (en.status.equals("FAILED")) {
                sb.append("{\n    \"expected\" : ").append(str(en.expected))
                        .append(",\n    \"actual\" : ").append(str(en.actual))
                        .append(",\n    \"hint\" : ").append(str(en.hint)).append("\n  }");
            } else {
                sb.append("null");
            }
            if (en.status.equals("SKIPPED") && en.hint != null && !en.hint.isEmpty()) {
                sb.append(",\n  \"hint\" : ").append(str(en.hint));
            }
            sb.append(",\n  \"logs\" : [ ");
            for (int i = 0; i < en.logs.size(); i++) {
                sb.append(i > 0 ? ", " : "").append(str(en.logs.get(i)));
            }
            sb.append(en.logs.isEmpty() ? "]" : " ]").append("\n}");
        }
        sb.append(entries.isEmpty() ? "]\n" : " ]\n");
        Files.writeString(out, sb.toString(), StandardCharsets.UTF_8);
        Files.writeString(junit, junitXml(entries, failed, skipped, totalMs), StandardCharsets.UTF_8);
        System.out.println(passed + "/" + (entries.size() - skipped) + " " + skipped);
    }

    /** Test cases of one phase's `xcresulttool get test-results tests` JSON (a tree of testNodes). */
    static void readPhase(String phase, Path json, Map<String, Entry> entries) throws Exception {
        if (!Files.isRegularFile(json) || Files.size(json) == 0) {
            return;
        }
        Object root = new Json(Files.readString(json, StandardCharsets.UTF_8)).parse();
        if (root instanceof Map<?, ?> m && m.get("testNodes") instanceof List<?> nodes) {
            for (Object node : nodes) {
                walk(phase, node, null, entries);
            }
        }
    }

    static void walk(String phase, Object node, String suite, Map<String, Entry> entries) {
        if (!(node instanceof Map<?, ?> m)) {
            return;
        }
        String type = String.valueOf(m.get("nodeType"));
        String name = String.valueOf(m.get("name"));
        if (type.equals("Test Case")) {
            String method = name.replaceAll("\\(\\)$", "");
            String scenario = kebab(method.replaceFirst("^test", ""));
            Entry en = entries.computeIfAbsent(scenario, k -> new Entry());
            en.scenario = scenario;
            en.suite = suite == null ? "ios-e2e" : suite;
            if (m.get("durationInSeconds") instanceof Number d) {
                en.durationMs += Math.round(d.doubleValue() * 1000);
            }
            en.logs.add((suite == null ? "" : suite + ".") + name);
            en.logs.add(OUT + "/" + phase + ".xcresult");
            List<String> messages = new ArrayList<>();
            if (m.get("children") instanceof List<?> children) {
                for (Object c : children) {
                    if (c instanceof Map<?, ?> cm && "Failure Message".equals(cm.get("nodeType"))) {
                        messages.add(String.valueOf(cm.get("name")));
                    }
                }
            }
            String result = String.valueOf(m.get("result"));
            switch (result) {
                case "Passed", "Expected Failure" -> en.status = "PASSED";
                case "Skipped" -> {
                    en.status = "SKIPPED";
                    en.actual = messages.isEmpty() ? null : String.join("; ", messages);
                    en.hint = en.actual;
                }
                default -> {
                    en.status = "FAILED";
                    String actual = messages.isEmpty() ? "result " + result : String.join("; ", messages);
                    en.actual = actual.length() > 600 ? actual.substring(0, 600) + "…" : actual;
                    en.expected = EXPECTED.getOrDefault(scenario, "the test passes");
                    en.hint = "open " + OUT + "/" + phase + ".xcresult in Xcode (screenshots, device log); "
                            + OUT + "/logs/xcodebuild-" + phase + ".log; backend and harness logs in " + OUT + "/logs/";
                }
            }
            return;
        }
        // suite = "<bundle>.<suite>" (the JUnit classname)
        String next = suite;
        if (type.endsWith("test bundle")) {
            next = name;
        } else if (type.equals("Test Suite")) {
            next = suite == null ? name : suite + "." + name;
        }
        if (m.get("children") instanceof List<?> children) {
            for (Object c : children) {
                walk(phase, c, next, entries);
            }
        }
    }

    static String junitXml(Map<String, Entry> entries, int failed, int skipped, long totalMs) {
        StringBuilder x = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        x.append("<testsuite name=\"ios-e2e\" tests=\"").append(entries.size()).append("\" failures=\"").append(failed)
                .append("\" errors=\"0\" skipped=\"").append(skipped).append("\" time=\"").append(seconds(totalMs))
                .append("\">\n");
        for (Entry en : entries.values()) {
            x.append("  <testcase classname=\"").append(xml(en.suite)).append("\" name=\"").append(xml(en.scenario))
                    .append("\" time=\"").append(seconds(en.durationMs)).append("\"");
            switch (en.status) {
                case "FAILED" -> x.append(">\n    <failure message=\"").append(xml(en.actual)).append("\">")
                        .append(xml("expected: " + en.expected + "\nactual: " + en.actual + "\nhint: " + en.hint))
                        .append("</failure>\n  </testcase>\n");
                case "SKIPPED" -> x.append(">\n    <skipped message=\"").append(xml(en.hint)).append("\"/>\n  </testcase>\n");
                default -> x.append("/>\n");
            }
        }
        return x.append("</testsuite>\n").toString();
    }

    static String seconds(long ms) {
        return String.format(Locale.ROOT, "%.3f", ms / 1000.0);
    }

    static String kebab(String camel) {
        String s = camel.replaceAll("([a-z0-9])([A-Z])", "$1-$2").toLowerCase(Locale.ROOT);
        return s.startsWith("-") ? s.substring(1) : s;
    }

    static String xml(String s) {
        if (s == null) {
            return "";
        }
        StringBuilder b = new StringBuilder();
        for (char c : s.toCharArray()) {
            switch (c) {
                case '<' -> b.append("&lt;");
                case '>' -> b.append("&gt;");
                case '&' -> b.append("&amp;");
                case '"' -> b.append("&quot;");
                default -> b.append(c < 0x20 && c != '\n' ? ' ' : c);
            }
        }
        return b.toString();
    }

    static String str(String s) {
        if (s == null) {
            return "null";
        }
        StringBuilder b = new StringBuilder("\"");
        for (char c : s.toCharArray()) {
            switch (c) {
                case '"' -> b.append("\\\"");
                case '\\' -> b.append("\\\\");
                default -> b.append(c < 0x20 ? ' ' : c);
            }
        }
        return b.append('"').toString();
    }

    /** Minimal JSON reader: objects to LinkedHashMap, arrays to ArrayList, numbers to Double, plus String/Boolean/null. */
    static final class Json {
        private final String s;
        private int i;

        Json(String s) {
            this.s = s;
        }

        Object parse() {
            Object v = value();
            ws();
            if (i != s.length()) {
                throw error("trailing characters");
            }
            return v;
        }

        private Object value() {
            ws();
            if (i >= s.length()) {
                throw error("unexpected end");
            }
            char c = s.charAt(i);
            switch (c) {
                case '{' -> {
                    i++;
                    Map<String, Object> m = new LinkedHashMap<>();
                    ws();
                    if (peek('}')) {
                        return m;
                    }
                    do {
                        ws();
                        String k = string();
                        ws();
                        expect(':');
                        m.put(k, value());
                        ws();
                    } while (peek(','));
                    expect('}');
                    return m;
                }
                case '[' -> {
                    i++;
                    List<Object> l = new ArrayList<>();
                    ws();
                    if (peek(']')) {
                        return l;
                    }
                    do {
                        l.add(value());
                        ws();
                    } while (peek(','));
                    expect(']');
                    return l;
                }
                case '"' -> {
                    return string();
                }
                default -> {
                    if (s.startsWith("true", i)) {
                        i += 4;
                        return Boolean.TRUE;
                    }
                    if (s.startsWith("false", i)) {
                        i += 5;
                        return Boolean.FALSE;
                    }
                    if (s.startsWith("null", i)) {
                        i += 4;
                        return null;
                    }
                    int start = i;
                    while (i < s.length() && "+-0123456789.eE".indexOf(s.charAt(i)) >= 0) {
                        i++;
                    }
                    if (start == i) {
                        throw error("unexpected character '" + c + "'");
                    }
                    return Double.parseDouble(s.substring(start, i));
                }
            }
        }

        private String string() {
            expect('"');
            StringBuilder b = new StringBuilder();
            while (true) {
                if (i >= s.length()) {
                    throw error("unterminated string");
                }
                char c = s.charAt(i++);
                if (c == '"') {
                    return b.toString();
                }
                if (c != '\\') {
                    b.append(c);
                    continue;
                }
                char e = s.charAt(i++);
                switch (e) {
                    case 'n' -> b.append('\n');
                    case 't' -> b.append('\t');
                    case 'r' -> b.append('\r');
                    case 'b' -> b.append('\b');
                    case 'f' -> b.append('\f');
                    case 'u' -> {
                        b.append((char) Integer.parseInt(s.substring(i, i + 4), 16));
                        i += 4;
                    }
                    default -> b.append(e); // \" \\ \/
                }
            }
        }

        private void ws() {
            while (i < s.length() && Character.isWhitespace(s.charAt(i))) {
                i++;
            }
        }

        private boolean peek(char c) {
            if (i < s.length() && s.charAt(i) == c) {
                i++;
                return true;
            }
            return false;
        }

        private void expect(char c) {
            if (!peek(c)) {
                throw error("expected '" + c + "'");
            }
        }

        private IllegalArgumentException error(String what) {
            return new IllegalArgumentException("JSON: " + what + " at offset " + i);
        }
    }
}
