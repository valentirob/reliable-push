package io.reliablepush.loadtest;

import static org.assertj.core.api.Assertions.assertThat;

import io.gatling.javaapi.core.Assertion;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The burst's assertions cover only the measured requests: the warm-up before them (cold JIT on a fresh stack) has its
 * own request name and is never asserted. Loading the class needs no environment (the settings are read per instance).
 */
class FallbackBurstSimulationTest {

    @Test
    void warmUpAndMeasuredRequestsHaveDifferentNames() {
        assertThat(FallbackBurstSimulation.MEASURED).isEqualTo("notify");
        assertThat(FallbackBurstSimulation.WARM_UP).isNotEqualTo(FallbackBurstSimulation.MEASURED);
    }

    @Test
    void assertionsAreOnTheMeasuredRequestOnly() {
        List<Assertion> assertions = FallbackBurstSimulation.assertions();

        // load.sh expects exactly this many assertion result lines for the burst
        assertThat(assertions).hasSize(2);
        assertThat(assertions).allSatisfy(a -> assertThat(a.asScala().toString())
                .contains("Details(List(" + FallbackBurstSimulation.MEASURED + "))")
                .doesNotContain("Global")
                .doesNotContain(FallbackBurstSimulation.WARM_UP));
        assertThat(assertions.stream().map(a -> a.asScala().toString()))
                .anySatisfy(s -> assertThat(s).contains("Percentile(99.0)").contains("Lte(250.0)"))
                .anySatisfy(s -> assertThat(s).contains("FailedRequests").contains("Lte(0.1)"));
    }
}
