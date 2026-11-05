package io.reliablepush.harness.tracking;

import static org.assertj.core.api.Assertions.assertThat;

import io.reliablepush.client.FallbackPolicy;
import io.reliablepush.client.Notification;
import io.reliablepush.client.NotificationState;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class TimelineTest {

    static final Instant T0 = Instant.parse("2026-11-04T10:00:00Z");
    final Clock clock = Clock.fixed(T0.plusSeconds(60), ZoneOffset.UTC);

    static Notification n(UUID id, NotificationState s, Instant createdAt) {
        return new Notification(id, s, "u1", null, FallbackPolicy.NOT_DELIVERED, 10, createdAt,
                createdAt.plusSeconds(600), null, null, null);
    }

    @Test
    void ordersEntriesAndDetectsTransitions() {
        Timeline t = new Timeline(10, clock);
        UUID id = UUID.randomUUID();

        var first = t.observe("shop", n(id, NotificationState.PENDING, T0));
        var same = t.observe("shop", n(id, NotificationState.PENDING, T0));
        var moved = t.observe("shop", n(id, NotificationState.DELIVERED, T0));
        t.append(id, "shop", new TimelineEntry(T0.plusSeconds(30), "event_sent", Map.of("outcome", "OK")));

        assertThat(first).extracting(TimelineEntry::kind).containsExactly("registered");
        assertThat(first.get(0).details()).containsEntry("source", "backend");
        assertThat(same).isEmpty();
        assertThat(moved).extracting(TimelineEntry::kind).containsExactly("state_changed");
        assertThat(moved.get(0).details()).containsEntry("from", "PENDING").containsEntry("to", "DELIVERED");
        // event_sent (T0+30s) sorts between registered (T0) and state_changed (clock = T0+60s)
        assertThat(t.entries(id)).extracting(TimelineEntry::kind)
                .containsExactly("registered", "event_sent", "state_changed");
        assertThat(t.current(id)).get().extracting(Notification::state).isEqualTo(NotificationState.DELIVERED);
    }

    @Test
    void cancellationAddsCancelledEntry() {
        Timeline t = new Timeline(10, clock);
        UUID id = UUID.randomUUID();
        t.observe("shop", n(id, NotificationState.PENDING, T0));

        var out = t.observe("shop", n(id, NotificationState.CANCELLED, T0));

        assertThat(out).extracting(TimelineEntry::kind).containsExactly("state_changed", "cancelled");
    }

    @Test
    void senderRegistrationIsNotDuplicatedByTheBackendObservation() {
        Timeline t = new Timeline(10, clock);
        UUID id = UUID.randomUUID();

        assertThat(t.registered("shop", n(id, NotificationState.PENDING, T0))).isNotNull();
        assertThat(t.observe("shop", n(id, NotificationState.PENDING, T0))).isEmpty();
        assertThat(t.registered("shop", n(id, NotificationState.PENDING, T0))).isNull();

        assertThat(t.entries(id)).extracting(TimelineEntry::kind).containsExactly("registered");
    }

    @Test
    void eventsBeforeTheBackendSawTheNotificationStillGetRegisteredEntry() {
        Timeline t = new Timeline(10, clock);
        UUID id = UUID.randomUUID();
        t.append(id, null, new TimelineEntry(T0.plusSeconds(5), "event_sent", Map.of()));

        t.observe("shop", n(id, NotificationState.PENDING, T0));

        assertThat(t.entries(id)).extracting(TimelineEntry::kind).containsExactly("registered", "event_sent");
        assertThat(t.recent("shop", 5)).hasSize(1);
    }

    @Test
    void keepsOnlyTheMostRecentlyUpdatedNotifications() {
        Timeline t = new Timeline(3, clock);
        UUID[] ids = {UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID()};
        for (int i = 0; i < 3; i++) {
            t.observe("shop", n(ids[i], NotificationState.PENDING, T0.plusSeconds(i)));
        }
        t.observe("shop", n(ids[0], NotificationState.DELIVERED, T0)); // refreshes ids[0]
        t.observe("shop", n(ids[3], NotificationState.PENDING, T0.plusSeconds(3)));

        assertThat(t.size()).isEqualTo(3);
        assertThat(t.current(ids[1])).isEmpty(); // least recently updated was evicted
        assertThat(t.current(ids[0])).isPresent();
    }

    @Test
    void recentIsPerAppNewestFirstAndLimited() {
        Timeline t = new Timeline(10, clock);
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        UUID other = UUID.randomUUID();
        t.observe("shop", n(a, NotificationState.PENDING, T0));
        t.observe("shop", n(b, NotificationState.PENDING, T0.plusSeconds(10)));
        t.observe("other", n(other, NotificationState.PENDING, T0.plusSeconds(20)));

        assertThat(t.recent("shop", 10)).extracting(Notification::trackingId).containsExactly(b, a);
        assertThat(t.recent("shop", 1)).extracting(Notification::trackingId).containsExactly(b);
    }

    @Test
    void lateSenderRegistrationDoesNotRegressCurrentState() {
        Timeline t = new Timeline(10, clock);
        UUID id = UUID.randomUUID();
        t.observe("shop", n(id, NotificationState.DELIVERED, T0));

        assertThat(t.registered("shop", n(id, NotificationState.PENDING, T0))).isNull();

        assertThat(t.current(id)).get().extracting(Notification::state).isEqualTo(NotificationState.DELIVERED);
        assertThat(t.observe("shop", n(id, NotificationState.DELIVERED, T0))).isEmpty();
    }
}
