package io.reliablepush.tracking;

import static org.assertj.core.api.Assertions.assertThat;

import io.reliablepush.notifications.FallbackPolicy;
import io.reliablepush.notifications.Notification;
import io.reliablepush.notifications.NotificationRepository;
import io.reliablepush.notifications.NotificationState;
import io.reliablepush.shared.security.DevicePrincipal;
import io.reliablepush.support.PostgresTestBase;
import io.reliablepush.tracking.EventBatchResult.Outcome;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CyclicBarrier;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;

/**
 * State transitions and clamping of {@link TrackingService} against a real PostgreSQL (the conditional updates are the
 * point, so the database is never mocked). The database is shared with other tests: every test uses fresh ids.
 */
@ActiveProfiles("test")
class TrackingServiceIT extends PostgresTestBase {

    private static final String APP = "shop";

    @Autowired
    TrackingService service;

    @Autowired
    NotificationRepository notifications;

    @Autowired
    JdbcClient jdbc;

    private record Fixture(DevicePrincipal device, UUID trackingId) {}

    private Fixture fixture(FallbackPolicy policy) {
        String userId = "user-" + UUID.randomUUID();
        UUID deviceId = UUID.randomUUID();
        jdbc.sql("""
                insert into device (id, app_id, user_id, platform, token_version, created_at, last_seen_at)
                values (?, ?, ?, 'ANDROID', 0, now(), now())
                """).params(deviceId, APP, userId).update();
        UUID trackingId = UUID.randomUUID();
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        notifications.insert(new Notification(trackingId, APP, userId, null, policy, 10, "{}",
                NotificationState.PENDING, now, now.plus(Duration.ofMinutes(10)), null, null, null));
        return new Fixture(new DevicePrincipal(APP, userId, deviceId, 0), trackingId);
    }

    private Outcome report(Fixture f, EventType type, Instant occurredAt) {
        EventBatchResult result = service.process(f.device(), List.of(new DeviceEvent(f.trackingId(), type, occurredAt)));
        return result.items().get(0).outcome();
    }

    private Notification row(Fixture f) {
        return notifications.findById(f.trackingId()).orElseThrow();
    }

    @Test
    void deliveredSatisfiesNotDeliveredPolicy() {
        Fixture f = fixture(FallbackPolicy.NOT_DELIVERED);

        assertThat(report(f, EventType.DELIVERED, Instant.now())).isEqualTo(Outcome.ACCEPTED);

        Notification n = row(f);
        assertThat(n.state()).isEqualTo(NotificationState.DELIVERED);
        assertThat(n.dueAt()).isNull();
        assertThat(n.deliveredAt()).isNotNull();
        assertThat(n.closedAt()).isEqualTo(n.deliveredAt());
        assertThat(n.readAt()).isNull();
    }

    @Test
    void deliveredKeepsNotReadPolicyDue() {
        Fixture f = fixture(FallbackPolicy.NOT_READ);

        assertThat(report(f, EventType.DELIVERED, Instant.now())).isEqualTo(Outcome.ACCEPTED);

        Notification n = row(f);
        assertThat(n.state()).isEqualTo(NotificationState.DELIVERED);
        assertThat(n.dueAt()).isNotNull();
        assertThat(n.closedAt()).isNull();
    }

    @Test
    void readImpliesDelivered() {
        Fixture f = fixture(FallbackPolicy.NOT_READ);

        assertThat(report(f, EventType.READ, Instant.now())).isEqualTo(Outcome.ACCEPTED);

        Notification n = row(f);
        assertThat(n.state()).isEqualTo(NotificationState.READ);
        assertThat(n.readAt()).isNotNull();
        assertThat(n.deliveredAt()).isEqualTo(n.readAt());
        assertThat(n.dueAt()).isNull();
        assertThat(n.closedAt()).isEqualTo(n.readAt());
    }

    @Test
    void readAfterDeliveredKeepsDeliveredAtAndEarlierClose() {
        Fixture f = fixture(FallbackPolicy.NOT_DELIVERED);
        report(f, EventType.DELIVERED, Instant.now());
        Notification delivered = row(f);

        assertThat(report(f, EventType.READ, Instant.now())).isEqualTo(Outcome.ACCEPTED);

        Notification n = row(f);
        assertThat(n.state()).isEqualTo(NotificationState.READ);
        assertThat(n.deliveredAt()).isEqualTo(delivered.deliveredAt());
        assertThat(n.closedAt()).isEqualTo(delivered.closedAt());
        assertThat(n.dueAt()).isNull();
    }

    @Test
    void deliveredAfterReadIsLateAndChangesNothing() {
        Fixture f = fixture(FallbackPolicy.NOT_READ);
        report(f, EventType.READ, Instant.now());
        Notification read = row(f);

        assertThat(report(f, EventType.DELIVERED, Instant.now())).isEqualTo(Outcome.LATE);

        assertThat(row(f)).isEqualTo(read);
    }

    @Test
    void occurredAtClamped() {
        Fixture future = fixture(FallbackPolicy.NOT_READ);
        Fixture old = fixture(FallbackPolicy.NOT_READ);
        Fixture recent = fixture(FallbackPolicy.NOT_READ);
        Instant recentTime = Instant.now().minus(Duration.ofHours(1)).truncatedTo(ChronoUnit.MICROS);

        report(future, EventType.DELIVERED, Instant.now().plus(Duration.ofDays(2)));
        report(old, EventType.DELIVERED, Instant.now().minus(Duration.ofDays(30)));
        report(recent, EventType.DELIVERED, recentTime);

        Instant[] f = times(future);
        assertThat(f[0]).isEqualTo(f[1]);
        Instant[] o = times(old);
        assertThat(o[0]).isEqualTo(o[1].minus(Duration.ofDays(7)));
        assertThat(times(recent)[0]).isEqualTo(recentTime);
    }

    @Test
    void deliveredRacingWithFallbackClaimHasExactlyOneWinner() {
        for (int i = 0; i < 25; i++) {
            Fixture f = fixture(FallbackPolicy.NOT_DELIVERED);
            CyclicBarrier start = new CyclicBarrier(2);

            CompletableFuture<Outcome> event = CompletableFuture.supplyAsync(() -> {
                await(start);
                return report(f, EventType.DELIVERED, Instant.now());
            });
            // the worker's claim (task 12) is a conditional update on the same states
            CompletableFuture<Integer> claim = CompletableFuture.supplyAsync(() -> {
                await(start);
                return jdbc.sql("update notification set state = 'DISPATCHING' where id = ? and state in ('PENDING', 'DELIVERED')"
                                + " and due_at is not null")
                        .param(f.trackingId()).update();
            });

            Outcome outcome = event.join();
            int claimed = claim.join();

            if (claimed == 1) {
                assertThat(outcome).isEqualTo(Outcome.LATE);
                assertThat(row(f).state()).isEqualTo(NotificationState.DISPATCHING);
                assertThat(row(f).deliveredAt()).isNull();
            } else {
                assertThat(outcome).isEqualTo(Outcome.ACCEPTED);
                assertThat(row(f).state()).isEqualTo(NotificationState.DELIVERED);
                assertThat(row(f).dueAt()).isNull();
            }
        }
    }

    @Test
    void concurrentDuplicateEventsStoreOneRow() {
        Fixture f = fixture(FallbackPolicy.NOT_READ);
        CyclicBarrier start = new CyclicBarrier(4);

        List<CompletableFuture<Outcome>> calls = java.util.stream.IntStream.range(0, 4)
                .mapToObj(n -> CompletableFuture.supplyAsync(() -> {
                    await(start);
                    return report(f, EventType.READ, Instant.now());
                }))
                .toList();
        List<Outcome> outcomes = calls.stream().map(CompletableFuture::join).toList();

        assertThat(outcomes).containsOnlyOnce(Outcome.ACCEPTED).filteredOn(o -> o != Outcome.ACCEPTED)
                .containsOnly(Outcome.DUPLICATE);
        assertThat(jdbc.sql("select count(*) from notification_event where notification_id = ?")
                .param(f.trackingId()).query(Integer.class).single()).isEqualTo(1);
    }

    private static void await(CyclicBarrier barrier) {
        try {
            barrier.await();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** {occurred_at, received_at} of the single event of the notification. */
    private Instant[] times(Fixture f) {
        return jdbc.sql("select occurred_at, received_at from notification_event where notification_id = ?")
                .param(f.trackingId())
                .query((rs, row) -> new Instant[] {
                    rs.getObject(1, OffsetDateTime.class).toInstant(), rs.getObject(2, OffsetDateTime.class).toInstant()
                })
                .single();
    }
}
