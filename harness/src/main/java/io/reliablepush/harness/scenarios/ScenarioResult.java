package io.reliablepush.harness.scenarios;

import java.util.List;

/**
 * Outcome of one scenario. {@code status}: PASSED | FAILED | SKIPPED; {@code failure} is set only for FAILED;
 * {@code logs} are the scenario's own progress lines (never secrets).
 */
public record ScenarioResult(String scenario, String status, long durationMs, Failure failure, List<String> logs) {

    public static final String PASSED = "PASSED";
    public static final String FAILED = "FAILED";
    public static final String SKIPPED = "SKIPPED";

    public ScenarioResult {
        logs = logs == null ? List.of() : List.copyOf(logs);
    }

    /** What the scenario expected, what it saw, and where to look next. */
    public record Failure(String expected, String actual, String hint) {}
}
