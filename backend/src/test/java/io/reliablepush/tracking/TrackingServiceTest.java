package io.reliablepush.tracking;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/** Pure unit checks of {@link TrackingService}; the state transitions are covered against PostgreSQL in {@link TrackingServiceIT}. */
class TrackingServiceTest {

    private static final Instant RECEIVED = Instant.parse("2026-11-03T10:00:00.123456789Z");

    @Test
    void futureOccurredAtIsClampedToReceivedAt() {
        assertThat(TrackingService.clamp(RECEIVED.plus(Duration.ofDays(2)), RECEIVED))
                .isEqualTo(Instant.parse("2026-11-03T10:00:00.123456Z"));
    }

    @Test
    void occurredAtOlderThanSevenDaysIsClampedToTheOldestAllowed() {
        assertThat(TrackingService.clamp(RECEIVED.minus(Duration.ofDays(30)), RECEIVED))
                .isEqualTo(Instant.parse("2026-10-27T10:00:00.123456Z"));
    }

    @Test
    void occurredAtInsideTheWindowIsKeptAtMicrosecondPrecision() {
        Instant oneHourAgo = RECEIVED.minus(Duration.ofHours(1));

        assertThat(TrackingService.clamp(oneHourAgo, RECEIVED)).isEqualTo(Instant.parse("2026-11-03T09:00:00.123456Z"));
        assertThat(TrackingService.clamp(RECEIVED.minus(TrackingService.MAX_EVENT_AGE), RECEIVED))
                .isEqualTo(Instant.parse("2026-10-27T10:00:00.123456Z"));
    }
}
