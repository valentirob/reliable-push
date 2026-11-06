package io.reliablepush.loadtest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.util.Map;
import org.junit.jupiter.api.Test;

class BurstDrainProbeTest {

    private static final String SAMPLE = """
            # HELP rp_fallback_total Fallback outcomes
            # TYPE rp_fallback_total counter
            rp_fallback_total{application="reliable-push",outcome="triggered"} 10000.0
            rp_fallback_total{outcome="failed",application="reliable-push"} 2.0
            rp_fallback_total{application="reliable-push",outcome="expired",channel="sms"} 1.0E1
            rp_fallback_total{application="reliable-push",outcome="expired",channel="push"} 5.0
            # HELP rp_fallback_seconds_count something else
            rp_fallback_seconds_count{outcome="triggered"} 99.0
            # HELP rp_due_backlog Due rows
            # TYPE rp_due_backlog gauge
            rp_due_backlog{application="reliable-push"} 1.0E4
            jvm_memory_used_bytes{area="heap"} 123456.0
            """;

    private static Map<String, Double> totals(double triggered, double failed, double expired, double backlog) {
        return Map.of("triggered", triggered, "failed", failed, "expired", expired, "backlog", backlog);
    }

    @Test
    void parsesRealisticPrometheusText() {
        Map<String, Double> m = BurstDrainProbe.parse(SAMPLE);
        assertThat(m.get("triggered")).isEqualTo(10000.0);
        assertThat(m.get("failed")).isEqualTo(2.0);
        assertThat(m.get("expired")).isEqualTo(15.0);
        assertThat(m.get("backlog")).isEqualTo(10000.0);
    }

    @Test
    void absentSeriesAreZero() {
        Map<String, Double> m = BurstDrainProbe.parse("# only a comment\n");
        assertThat(m).containsEntry("triggered", 0.0).containsEntry("failed", 0.0)
                .containsEntry("expired", 0.0).containsEntry("backlog", 0.0);
    }

    @Test
    void scriptedSamplesGiveDrainTimeAndRates() {
        BurstDrainProbe.Tracker t = new BurstDrainProbe.Tracker();
        // counters start at 500 (earlier runs): everything is a delta from the first sample
        t.sample(0, totals(500, 0, 0, 0));
        t.sample(1, totals(500, 0, 0, 30000));
        t.sample(2, totals(500, 0, 0, 30000)); // no increase yet
        t.sample(3, totals(1500, 0, 0, 29000)); // first increase at t=3
        for (int s = 4; s <= 32; s++) {
            t.sample(s, totals(500 + 1000 * (s - 2), 0, 0, 30000 - 1000 * (s - 2)));
        }
        BurstDrainProbe.Result r = t.result(30000, false);
        assertThat(r.triggered()).isEqualTo(30000);
        assertThat(r.drainSeconds()).isEqualTo(29.0); // 3 -> 32
        assertThat(r.rowsPerSecondOverall()).isCloseTo(30000.0 / 29, within(0.001));
        assertThat(r.rowsPerSecondPeak10s()).isCloseTo(1000.0, within(0.001));
        assertThat(r.maxBacklog()).isEqualTo(30000);
        assertThat(r.status()).isEqualTo("PASSED");
        assertThat(r.toJson()).contains("\"status\":\"PASSED\"").contains("\"expected\":30000");
    }

    @Test
    void expiredIncreaseFails() {
        BurstDrainProbe.Tracker t = new BurstDrainProbe.Tracker();
        t.sample(0, totals(0, 0, 0, 0));
        t.sample(1, totals(100, 0, 1, 0));
        BurstDrainProbe.Result r = t.result(100, false);
        assertThat(r.expired()).isEqualTo(1);
        assertThat(r.status()).isEqualTo("FAILED");
    }

    @Test
    void failedIncreaseAndTimeoutFail() {
        BurstDrainProbe.Tracker t = new BurstDrainProbe.Tracker();
        t.sample(0, totals(0, 0, 0, 0));
        t.sample(1, totals(100, 3, 0, 0));
        assertThat(t.result(100, false).status()).isEqualTo("FAILED");

        BurstDrainProbe.Tracker t2 = new BurstDrainProbe.Tracker();
        t2.sample(0, totals(0, 0, 0, 0));
        t2.sample(1, totals(50, 0, 0, 5));
        BurstDrainProbe.Result r = t2.result(100, true);
        assertThat(r.status()).isEqualTo("FAILED");
        assertThat(r.triggered()).isEqualTo(50);
    }

    @Test
    void reachedReportsExpectedAndNoIncreaseMeansZeroDrain() {
        BurstDrainProbe.Tracker t = new BurstDrainProbe.Tracker();
        t.sample(0, totals(7, 0, 0, 0));
        assertThat(t.reached(1)).isFalse();
        BurstDrainProbe.Result r = t.result(1, true);
        assertThat(r.drainSeconds()).isEqualTo(0.0);
        assertThat(r.rowsPerSecondOverall()).isEqualTo(0.0);
    }
}
