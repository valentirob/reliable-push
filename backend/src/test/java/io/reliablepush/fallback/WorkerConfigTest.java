package io.reliablepush.fallback;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.reliablepush.apps.AppConfig;
import io.reliablepush.apps.AppFixtures;
import io.reliablepush.apps.AppRegistry;
import java.time.Duration;
import java.util.Collection;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Startup check: the claim lease must outlast one webhook call, otherwise a slow call is re-sent by another worker. */
class WorkerConfigTest {

    private static Collection<AppConfig> apps(String httpTimeout) {
        Map<String, Object> properties = new java.util.HashMap<>(AppFixtures.minimalApp("shop", "k-shop"));
        properties.put("reliable-push.apps.shop.fallback.http-timeout", httpTimeout);
        return new AppRegistry(AppFixtures.bind(properties), AppFixtures.environment()).all();
    }

    @Test
    void leaseLongerThanHttpTimeoutIsFine() {
        assertThat(WorkerConfig.checkLease(Duration.ofMinutes(2), false, apps("10s"))).isEmpty();
    }

    @Test
    void leaseNotLongerThanHttpTimeoutFailsStartup() {
        assertThatThrownBy(() -> WorkerConfig.checkLease(Duration.ofSeconds(10), false, apps("10s")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("reliable-push.worker.lease")
                .hasMessageContaining("shop");
    }

    @Test
    void fastClockOnlyWarns() {
        assertThat(WorkerConfig.checkLease(Duration.ofSeconds(2), true, apps("10s")))
                .hasValueSatisfying(warning -> assertThat(warning).contains("shop").contains("PT2S"));
    }

    @Test
    void shutdownTimeoutDefaultsToLargestHttpTimeoutPlusFiveSeconds() {
        assertThat(WorkerConfig.resolveShutdownTimeout(null, apps("20s"))).isEqualTo(Duration.ofSeconds(25));
    }

    @Test
    void configuredShutdownTimeoutWins() {
        assertThat(WorkerConfig.resolveShutdownTimeout(Duration.ofSeconds(7), apps("20s")))
                .isEqualTo(Duration.ofSeconds(7));
    }

    @Test
    void shutdownTimeoutLongerThanShutdownPhaseFailsStartup() {
        assertThatThrownBy(() -> WorkerConfig.checkShutdownTimeout(Duration.ofSeconds(31), Duration.ofSeconds(30)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("reliable-push.worker.shutdown-timeout")
                .hasMessageContaining("spring.lifecycle.timeout-per-shutdown-phase");
    }

    @Test
    void shutdownTimeoutWithinShutdownPhaseIsFine() {
        WorkerConfig.checkShutdownTimeout(Duration.ofSeconds(30), Duration.ofSeconds(30));
    }

    @Test
    void nonPositiveShutdownTimeoutFailsStartup() {
        assertThatThrownBy(() -> new io.reliablepush.apps.ReliablePushProperties.Worker(
                        Duration.ofSeconds(5), 100, Duration.ofMinutes(2), null, Duration.ZERO))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("reliable-push.worker.shutdown-timeout");
    }
}
