// Builds harness/build/android-e2e/summary.json for scripts/android-e2e.sh from the connected-test JUnit XML files and
// the entries the script measured itself (cold start). Single-file Java program (JDK 21, no dependencies):
//
//   java scripts/lib/AndroidE2eSummary.java <summary.json> [--expect <scenario>]... [--entry <fields>]... <junit.xml>...
//
// Scenario names: a test of the sample app (package io.reliablepush.sample) is one entry per method, its name in
// kebab case (deliveredIsReported -> delivered-is-reported); any other test class (the SDK's instrumented tests) is one
// entry per class (KeystoreTokenCipherTest -> keystore-token-cipher), FAILED when one of its methods failed.
// --entry: "scenario|STATUS|durationMs|expected|actual|hint|log" (expected/actual/hint only matter for FAILED; for
// SKIPPED the hint says what is missing and is printed and kept in summary.json as "hint").
// STATUS is PASSED, FAILED or SKIPPED. SKIPPED (optional real-FCM entry, a skipped JUnit test) counts in neither
// <passed> nor <total>.
// --expect: a scenario that must be there; a missing one becomes FAILED ("did not run").
// Same entry shape as harness/build/e2e/summary.json (SKIPPED entries add "hint"). Prints one line per entry, then
// "<passed>/<total> <skipped>" (for example "8/8 1"), on stdout.
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

public class AndroidE2eSummary {

    static final Map<String, String> EXPECTED = Map.of(
            "delivered-is-reported", "DELIVERED before the 5-minute (fast clock) timeout, no webhook afterwards",
            "read-is-reported-on-tap", "tapping the notification in the shade reports READ",
            "offline-delivery-is-sent-when-network-returns",
            "PENDING while offline, DELIVERED by the retry job once the network is back, before the timeout",
            "notifications-blocked-means-fallback", "nothing posted, no DELIVERED, FALLBACK_TRIGGERED with one webhook",
            "token-refresh-near-expiry", "DELIVERED after a token refresh (backend log device.token_refreshed)",
            "logout-revokes-device", "notification shown, no DELIVERED after logout, FALLBACK_TRIGGERED with one webhook",
            "keystore-token-cipher", "AndroidKeyStore AES-GCM round trip, tamper detection and key reset");

    static final class Entry {
        String scenario;
        String status = "PASSED";
        long durationMs;
        String expected;
        String actual;
        String hint;
        final List<String> logs = new ArrayList<>();
    }

    public static void main(String[] args) throws Exception {
        Path out = Path.of(args[0]);
        List<String> expect = new ArrayList<>();
        Map<String, Entry> entries = new LinkedHashMap<>();
        List<String> xmls = new ArrayList<>();
        List<String> extra = new ArrayList<>();
        for (int i = 1; i < args.length; i++) {
            switch (args[i]) {
                case "--expect" -> expect.add(args[++i]);
                case "--entry" -> extra.add(args[++i]);
                default -> xmls.add(args[i]);
            }
        }
        for (String xml : xmls) {
            readJunit(new File(xml), entries);
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
            if (!entries.containsKey(name)) {
                Entry en = new Entry();
                en.scenario = name;
                en.status = "FAILED";
                en.expected = EXPECTED.getOrDefault(name, "the test runs and passes");
                en.actual = "did not run (no result in the connected-test JUnit XML)";
                en.hint = "harness/build/android-e2e/logs/gradle.log (build, install or instrumentation problem)";
                entries.put(name, en);
            }
        }
        StringBuilder sb = new StringBuilder("[ ");
        int passed = 0;
        int skipped = 0;
        int n = 0;
        for (Entry en : entries.values()) {
            if (n++ > 0) {
                sb.append(", ");
            }
            if (en.status.equals("PASSED")) {
                passed++;
            } else if (en.status.equals("SKIPPED")) {
                skipped++;
            }
            System.out.println("  " + en.status + "  " + en.scenario + " (" + en.durationMs + " ms)"
                    + (en.status.equals("SKIPPED") && en.hint != null && !en.hint.isEmpty() ? ": " + en.hint : ""));
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
        System.out.println(passed + "/" + (entries.size() - skipped) + " " + skipped);
    }

    static void readJunit(File xml, Map<String, Entry> entries) throws Exception {
        var factory = DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        NodeList cases = factory.newDocumentBuilder().parse(xml).getElementsByTagName("testcase");
        for (int i = 0; i < cases.getLength(); i++) {
            Element tc = (Element) cases.item(i);
            String cls = tc.getAttribute("classname");
            String method = tc.getAttribute("name");
            String simple = cls.substring(cls.lastIndexOf('.') + 1);
            boolean perMethod = cls.startsWith("io.reliablepush.sample.");
            String name = perMethod ? kebab(method) : kebab(simple.replaceAll("Test$", ""));
            Entry en = entries.computeIfAbsent(name, k -> {
                Entry e = new Entry();
                e.scenario = k;
                return e;
            });
            en.durationMs += Math.round(parseTime(tc.getAttribute("time")) * 1000);
            en.logs.add(simple + "." + method);
            String problem = firstChild(tc, "failure", "error");
            if (problem != null) {
                en.status = "FAILED";
                String line = problem.strip().lines().findFirst().orElse("failed without a message");
                String actual = (perMethod ? "" : method + ": ") + line;
                en.actual = en.actual == null ? actual : en.actual + "; " + actual;
                en.actual = en.actual.length() > 600 ? en.actual.substring(0, 600) + "…" : en.actual;
                en.expected = EXPECTED.getOrDefault(name, "the test passes");
                en.hint = "stack trace in harness/build/android-e2e/junit/" + xml.getName()
                        + "; logcat in harness/build/android-e2e/logs/logcat.txt; backend logs in harness/build/android-e2e/logs/";
            } else if (firstChild(tc, "skipped") != null && !en.status.equals("FAILED")) {
                en.status = "SKIPPED";
            }
        }
    }

    static String firstChild(Element tc, String... names) {
        for (String n : names) {
            NodeList l = tc.getElementsByTagName(n);
            if (l.getLength() > 0) {
                Element e = (Element) l.item(0);
                String msg = e.getAttribute("message");
                String text = e.getTextContent();
                return !msg.isBlank() ? msg : (text == null ? "" : text);
            }
        }
        return null;
    }

    static double parseTime(String s) {
        try {
            return s.isBlank() ? 0 : Double.parseDouble(s.replace(",", ""));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    static String kebab(String camel) {
        return camel.replaceAll("([a-z0-9])([A-Z])", "$1-$2").toLowerCase(java.util.Locale.ROOT);
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
                default -> {
                    if (c < 0x20) {
                        b.append(' ');
                    } else {
                        b.append(c);
                    }
                }
            }
        }
        return b.append('"').toString();
    }
}
