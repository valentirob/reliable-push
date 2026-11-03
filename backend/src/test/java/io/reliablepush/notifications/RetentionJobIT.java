package io.reliablepush.notifications;

import static org.assertj.core.api.Assertions.assertThat;

import io.reliablepush.shared.logging.EventCapture;
import io.reliablepush.support.MutableClock;
import io.reliablepush.support.PostgresTestBase;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.apache.logging.log4j.core.LogEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

/**
 * The retention job against a real PostgreSQL (spec 3): closed notifications older than {@code retention-days} go,
 * their events with them (ON DELETE CASCADE); open rows and younger closed rows stay.
 *
 * <p>The database is shared with every other IT and the purge is global, so the clock is fixed in the year 2020
 * (before any row another test creates, which are 2026 or 2100): only this class's rows can be older than the cutoff.
 * The assertions are on this test's own rows, and since nothing else can be that old the logged count is exact.
 * The scheduled fallback poll is pushed out to one day, and the open rows are due in the year 2200, so no worker
 * ever claims them.
 */
@ActiveProfiles({"test", "worker"})
@TestPropertySource(properties = "reliable-push.worker.poll-interval=1d")
@Import(RetentionJobIT.Fakes.class)
class RetentionJobIT extends PostgresTestBase {

    private static final Instant NOW = Instant.parse("2020-06-15T12:00:00Z");
    private static final Instant FAR_FUTURE = Instant.parse("2200-01-01T00:00:00Z");
    private static final String APP = "retention-it-";

    @TestConfiguration(proxyBeanMethods = false)
    static class Fakes {

        @Bean
        @Primary
        MutableClock testClock() {
            return new MutableClock(NOW);
        }
    }

    @Autowired
    RetentionJob job;

    @Autowired
    JdbcClient jdbc;

    private EventCapture capture;

    @BeforeEach
    void startCapture() {
        capture = new EventCapture();
    }

    @AfterEach
    void stopCapture() {
        capture.close();
    }

    private UUID openRow(String app, Instant createdAt, String state) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                insert into notification (id, app_id, user_id, policy, timeout_minutes, fallback_payload, state,
                                          created_at, due_at)
                values (?, ?, 'user-1', 'NOT_DELIVERED', 10, '{}'::jsonb, ?, ?, ?)
                """)
                .params(id, app, state, at(createdAt), at(FAR_FUTURE))
                .update();
        return id;
    }

    private UUID closedRow(String app, Instant closedAt) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                insert into notification (id, app_id, user_id, policy, timeout_minutes, fallback_payload, state,
                                          created_at, closed_at)
                values (?, ?, 'user-1', 'NOT_DELIVERED', 10, '{}'::jsonb, 'DELIVERED', ?, ?)
                """)
                .params(id, app, at(closedAt.minus(Duration.ofHours(1))), at(closedAt))
                .update();
        jdbc.sql("""
                insert into notification_event (notification_id, type, occurred_at, received_at)
                values (?, 'DELIVERED', ?, ?)
                """)
                .params(id, at(closedAt), at(closedAt))
                .update();
        return id;
    }

    private static OffsetDateTime at(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }

    private boolean exists(UUID id) {
        return jdbc.sql("select count(*) from notification where id = ?").param(id).query(Integer.class).single() == 1;
    }

    private int events(UUID id) {
        return jdbc.sql("select count(*) from notification_event where notification_id = ?")
                .param(id)
                .query(Integer.class)
                .single();
    }

    private List<LogEvent> purgedEvents() {
        return List.copyOf(capture.events()).stream()
                .filter(e -> "retention.purged".equals(EventCapture.data(e).get("event.action")))
                .toList();
    }

    @Test
    void purgesOnlyClosedOlderThanRetention() {
        String app = APP + UUID.randomUUID();
        UUID old = closedRow(app, NOW.minus(Duration.ofDays(31)));
        UUID recent = closedRow(app, NOW.minus(Duration.ofDays(29)));
        UUID pending = openRow(app, NOW.minus(Duration.ofDays(60)), "PENDING");
        UUID dispatching = openRow(app, NOW.minus(Duration.ofDays(60)), "DISPATCHING");
        assertThat(events(old)).isEqualTo(1);

        job.purge();

        assertThat(exists(old)).isFalse();
        assertThat(events(old)).isZero();
        assertThat(exists(recent)).isTrue();
        assertThat(events(recent)).isEqualTo(1);
        assertThat(exists(pending)).isTrue();
        assertThat(exists(dispatching)).isTrue();
        assertThat(purgedEvents()).hasSize(1);
        assertThat(EventCapture.data(purgedEvents().get(0))).containsEntry("count", 1);
    }

    @Test
    void purgesInChunksUntilNothingOldIsLeft() {
        String app = APP + UUID.randomUUID();
        int total = 2_300; // more than two chunks of 1,000
        jdbc.sql("""
                insert into notification (id, app_id, user_id, policy, timeout_minutes, fallback_payload, state,
                                          created_at, closed_at)
                select gen_random_uuid(), ?, 'user-1', 'NOT_DELIVERED', 10, '{}'::jsonb, 'DELIVERED', ?, ?
                  from generate_series(1, ?)
                """)
                .params(app, at(NOW.minus(Duration.ofDays(40))), at(NOW.minus(Duration.ofDays(40))), total)
                .update();
        UUID recent = closedRow(app, NOW.minus(Duration.ofDays(1)));

        job.purge();

        assertThat(jdbc.sql("select count(*) from notification where app_id = ? and closed_at < ?")
                        .params(app, at(NOW.minus(Duration.ofDays(30))))
                        .query(Integer.class)
                        .single())
                .isZero();
        assertThat(exists(recent)).isTrue();
        assertThat(EventCapture.data(purgedEvents().get(0))).containsEntry("count", total);
    }

    @Test
    void logsCountEvenWhenNothingToPurge() {
        job.purge(); // drains whatever earlier tests of this class left
        job.purge();

        List<LogEvent> purged = purgedEvents();
        assertThat(purged).hasSize(2);
        assertThat(EventCapture.data(purged.get(1))).containsEntry("count", 0);
    }
}
