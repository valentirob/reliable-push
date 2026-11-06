package io.reliablepush.shared.time;

import static org.assertj.core.api.Assertions.assertThat;

import io.reliablepush.apps.ReliablePushProperties;
import java.time.Clock;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

class TimeScaleTest {

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(ReliablePushProperties.class)
    @Import({TimeScale.class, ClockConfig.class})
    static class Config {}

    private ApplicationContextRunner runnerWithProfiles(String... profiles) {
        return new ApplicationContextRunner()
                .withUserConfiguration(Config.class)
                .withInitializer(ctx -> ctx.getEnvironment().setActiveProfiles(profiles));
    }

    @Test
    void fastClockOnlyInE2eOrDemo() {
        runnerWithProfiles("api").withPropertyValues("reliable-push.testing.minute-duration=1s").run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).hasStackTraceContaining("minute-duration is only allowed in profiles e2e or demo");
        });

        runnerWithProfiles("e2e").withPropertyValues("reliable-push.testing.minute-duration=1s").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(TimeScale.class).minutes(10)).isEqualTo(Duration.ofSeconds(10));
        });
    }

    @Test
    void demoProfileAlsoAllowsFastClock() {
        runnerWithProfiles("api", "demo").withPropertyValues("reliable-push.testing.minute-duration=2s").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(TimeScale.class).minutes(3)).isEqualTo(Duration.ofSeconds(6));
        });
    }

    @Test
    void defaultsToRealMinutes() {
        runnerWithProfiles("api", "worker").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(TimeScale.class).minutes(10)).isEqualTo(Duration.ofMinutes(10));
        });
    }

    @Test
    void rejectsNonPositiveMinuteDuration() {
        runnerWithProfiles("e2e").withPropertyValues("reliable-push.testing.minute-duration=0s").run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).hasStackTraceContaining("minute-duration");
        });
    }

    @Test
    void scalesDurationsLikeMinutes() {
        runnerWithProfiles("e2e").withPropertyValues("reliable-push.testing.minute-duration=1s").run(context -> {
            TimeScale scale = context.getBean(TimeScale.class);
            assertThat(scale.scale(Duration.ofMinutes(1))).isEqualTo(Duration.ofSeconds(1));
            assertThat(scale.scale(Duration.ofMinutes(8))).isEqualTo(Duration.ofSeconds(8));
            assertThat(scale.scale(Duration.ofSeconds(30))).isEqualTo(Duration.ofMillis(500));
        });
        runnerWithProfiles("api").run(context -> {
            TimeScale scale = context.getBean(TimeScale.class);
            assertThat(scale.scale(Duration.ofMinutes(8))).isEqualTo(Duration.ofMinutes(8));
            assertThat(scale.scale(Duration.ofSeconds(10))).isEqualTo(Duration.ofSeconds(10));
        });
    }

    @Test
    void clockBeanIsSystemUtc() {
        runnerWithProfiles("api").run(context -> assertThat(context.getBean(Clock.class).getZone()).isEqualTo(java.time.ZoneOffset.UTC));
    }
}
