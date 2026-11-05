package io.reliablepush.loadtest;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Samples the Prometheus endpoints of all worker replicas every second and measures how fast the fallback
 * backlog drains. Run before the burst starts, as a plain JDK main:
 * {@code java -cp loadtest/target/classes io.reliablepush.loadtest.BurstDrainProbe --metrics <url>... --expected <n>
 * --timeout <duration> --out <file>}. JDK only.
 */
public final class BurstDrainProbe {

    private static final Pattern LINE = Pattern.compile("^([A-Za-z_:][A-Za-z0-9_:]*)(?:\\{(.*)\\})?\\s+(\\S+)(?:\\s+\\S+)?$");
    private static final Pattern OUTCOME = Pattern.compile("(?:^|,)\\s*outcome=\"([^\"]*)\"");

    private BurstDrainProbe() {}

    /** Sums {@code rp_fallback_total} per outcome ({@code triggered|failed|expired}) and {@code rp_due_backlog}. */
    public static Map<String, Double> parse(String prometheusText) {
        Map<String, Double> out = new HashMap<>();
        for (String k : new String[] {"triggered", "failed", "expired", "backlog"}) {
            out.put(k, 0.0);
        }
        for (String raw : prometheusText.split("\\R")) {
            String line = raw.strip();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            Matcher m = LINE.matcher(line);
            if (!m.matches()) {
                continue;
            }
            String name = m.group(1);
            double value;
            try {
                value = Double.parseDouble(m.group(3));
            } catch (NumberFormatException e) {
                continue;
            }
            if (name.equals("rp_due_backlog")) {
                out.merge("backlog", value, Double::sum);
            } else if (name.equals("rp_fallback_total") && m.group(2) != null) {
                Matcher o = OUTCOME.matcher(m.group(2));
                if (o.find() && out.containsKey(o.group(1)) && !o.group(1).equals("backlog")) {
                    out.merge(o.group(1), value, Double::sum);
                }
            }
        }
        return out;
    }

    /** Final figures; counters are deltas from the first sample. */
    public record Result(long expected, long triggered, long failed, long expired, double drainSeconds,
            double rowsPerSecondOverall, double rowsPerSecondPeak10s, long maxBacklog, String status) {

        public String toJson() {
            return String.format(Locale.ROOT,
                    "{\"expected\":%d,\"triggered\":%d,\"failed\":%d,\"expired\":%d,\"drainSeconds\":%.3f,"
                            + "\"rowsPerSecondOverall\":%.3f,\"rowsPerSecondPeak10s\":%.3f,\"maxBacklog\":%d,"
                            + "\"status\":\"%s\"}",
                    expected, triggered, failed, expired, drainSeconds, rowsPerSecondOverall, rowsPerSecondPeak10s,
                    maxBacklog, status);
        }
    }

    /** Accumulates samples (time in seconds, summed counters over all replicas). */
    public static final class Tracker {
        private final List<double[]> samples = new ArrayList<>(); // t, triggeredDelta
        private double[] first;
        private double lastFailed;
        private double lastExpired;
        private double lastTriggered;
        private double maxBacklog;
        private double firstIncreaseAt = Double.NaN;
        private double lastIncreaseAt = Double.NaN;

        public void sample(double tSeconds, Map<String, Double> totals) {
            double[] cur = {totals.getOrDefault("triggered", 0.0), totals.getOrDefault("failed", 0.0),
                totals.getOrDefault("expired", 0.0)};
            if (first == null) {
                first = cur;
            }
            double trig = cur[0] - first[0];
            if (trig > lastTriggered) {
                if (Double.isNaN(firstIncreaseAt)) {
                    firstIncreaseAt = tSeconds;
                }
                lastIncreaseAt = tSeconds;
            }
            lastTriggered = trig;
            lastFailed = cur[1] - first[1];
            lastExpired = cur[2] - first[2];
            maxBacklog = Math.max(maxBacklog, totals.getOrDefault("backlog", 0.0));
            samples.add(new double[] {tSeconds, trig});
        }

        public boolean reached(long expected) {
            return lastTriggered >= expected;
        }

        public Result result(long expected, boolean timedOut) {
            double drain = Double.isNaN(firstIncreaseAt) ? 0.0 : lastIncreaseAt - firstIncreaseAt;
            double overall = drain > 0 ? lastTriggered / drain : 0.0;
            double peak = peak10s(overall);
            boolean ok = !timedOut && lastTriggered >= expected && lastFailed <= 0 && lastExpired <= 0;
            return new Result(expected, Math.round(lastTriggered), Math.round(lastFailed), Math.round(lastExpired),
                    drain, overall, peak, Math.round(maxBacklog), ok ? "PASSED" : "FAILED");
        }

        /** Best rate over any window of at least 10 s (ending at a sample); the overall rate for shorter runs. */
        private double peak10s(double overall) {
            double best = -1;
            for (int i = 0; i < samples.size(); i++) {
                double[] si = samples.get(i);
                for (int j = i - 1; j >= 0; j--) {
                    double[] sj = samples.get(j);
                    if (si[0] - sj[0] >= 10.0) {
                        best = Math.max(best, (si[1] - sj[1]) / (si[0] - sj[0]));
                        break;
                    }
                }
            }
            return best < 0 ? overall : best;
        }
    }

    public static void main(String[] args) throws Exception {
        List<URI> metrics = new ArrayList<>();
        long expected = -1;
        Duration timeout = Duration.ofMinutes(10);
        Path out = null;
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--metrics" -> {
                    while (i + 1 < args.length && !args[i + 1].startsWith("--")) {
                        metrics.add(URI.create(args[++i]));
                    }
                }
                case "--expected" -> expected = Long.parseLong(args[++i]);
                case "--timeout" -> timeout = LoadConfig.parseDuration(args[++i]);
                case "--out" -> out = Path.of(args[++i]);
                default -> throw new IllegalArgumentException("Unknown argument " + args[i]);
            }
        }
        if (metrics.isEmpty() || expected < 0 || out == null) {
            System.err.println("usage: --metrics <url>... --expected <n> [--timeout <duration>] --out <file>");
            System.exit(2);
        }
        Result r = run(metrics, expected, timeout);
        Files.writeString(out, r.toJson() + "\n", StandardCharsets.UTF_8);
        System.out.println(r.toJson());
        System.exit("PASSED".equals(r.status()) ? 0 : 1);
    }

    static Result run(List<URI> metrics, long expected, Duration timeout) throws InterruptedException {
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        Tracker tracker = new Tracker();
        long start = System.nanoTime();
        long deadline = start + timeout.toNanos();
        boolean timedOut = false;
        while (true) {
            Map<String, Double> total = new HashMap<>();
            boolean complete = true;
            for (URI u : metrics) {
                try {
                    HttpResponse<String> res = client.send(
                            HttpRequest.newBuilder(u).timeout(Duration.ofSeconds(5)).GET().build(),
                            HttpResponse.BodyHandlers.ofString());
                    if (res.statusCode() != 200) {
                        complete = false;
                        continue;
                    }
                    parse(res.body()).forEach((k, v) -> total.merge(k, v, Double::sum));
                } catch (java.io.IOException e) {
                    complete = false; // a replica that cannot be scraped must not skew the sum (counter would look reset)
                }
            }
            if (complete) {
                tracker.sample((System.nanoTime() - start) / 1e9, total);
                if (tracker.reached(expected)) {
                    break;
                }
            }
            if (System.nanoTime() >= deadline) {
                timedOut = true;
                break;
            }
            Thread.sleep(1000);
        }
        return tracker.result(expected, timedOut);
    }
}
