package io.reliablepush.shared.time;

import io.reliablepush.apps.ReliablePushProperties;
import java.time.Duration;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;

/**
 * Converts product "minutes" (notification timeouts, trigger windows) to real durations. One minute is 60 s unless
 * {@code reliable-push.testing.minute-duration} is set, which is only allowed in the {@code e2e} and {@code demo}
 * profiles (spec 10.1), so the fast clock cannot leak into production.
 */
@Component
public class TimeScale {

    private final Duration minuteDuration;

    public TimeScale(ReliablePushProperties properties, Environment environment) {
        Duration configured = properties.testing().minuteDuration();
        if (configured != null) {
            if (!environment.acceptsProfiles(Profiles.of("e2e", "demo"))) {
                throw new IllegalStateException(
                        "reliable-push.testing.minute-duration is only allowed in profiles e2e or demo");
            }
            if (configured.isZero() || configured.isNegative()) {
                throw new IllegalStateException("reliable-push.testing.minute-duration must be positive");
            }
        }
        this.minuteDuration = configured != null ? configured : Duration.ofMinutes(1);
    }

    /** {@code n} product minutes as a real duration. */
    public Duration minutes(int n) {
        return minuteDuration.multipliedBy(n);
    }

    public Duration minuteDuration() {
        return minuteDuration;
    }
}
