package io.reliablepush.tracking;

import io.reliablepush.devices.DeviceService;
import io.reliablepush.notifications.Notification;
import io.reliablepush.notifications.NotificationRepository;
import io.reliablepush.shared.logging.EventLog;
import io.reliablepush.shared.logging.UserHash;
import io.reliablepush.shared.security.DevicePrincipal;
import io.reliablepush.tracking.EventBatchResult.ItemResult;
import io.reliablepush.tracking.EventBatchResult.Outcome;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Applies the events a device reports (spec 2, 4.2). Every item is processed in its own transaction, in request
 * order: authorize (same app and user as the device), store the event ({@code ON CONFLICT DO NOTHING}: a duplicate
 * changes nothing), then attempt the state transition as a conditional update. Exactly one of "policy satisfied"
 * and "fallback claimed / cancelled" wins; when the transition does not apply any more the event is kept with
 * {@code late = true} and reported as {@code LATE} (never an error). State decisions use the server receive time;
 * the device's {@code occurredAt} is only stored, clamped to {@code [received - 7d, received]}.
 */
@Service
public class TrackingService {

    static final int MAX_BATCH = 50;
    static final Duration MAX_EVENT_AGE = Duration.ofDays(7);

    private final NotificationRepository notifications;
    private final EventRepository events;
    private final DeviceService devices;
    private final TransactionTemplate transactions;
    private final Clock clock;

    TrackingService(
            NotificationRepository notifications,
            EventRepository events,
            DeviceService devices,
            TransactionTemplate transactions,
            Clock clock) {
        this.notifications = notifications;
        this.events = events;
        this.devices = devices;
        this.transactions = transactions;
        this.clock = clock;
    }

    /**
     * Processes the batch; one item never fails another. The device's {@code last_seen_at} is updated once per call.
     * An unexpected failure (database down) aborts the call: items already processed stay stored, and a retry of the
     * whole batch reports them as {@code DUPLICATE}.
     */
    public EventBatchResult process(DevicePrincipal principal, List<DeviceEvent> batch) {
        Instant receivedAt = clock.instant().truncatedTo(ChronoUnit.MICROS);
        devices.markSeen(principal);

        List<ItemResult> results = new ArrayList<>(batch.size());
        for (DeviceEvent event : batch) {
            Outcome outcome = transactions.execute(status -> processOne(principal, event, receivedAt));
            log(principal, event, outcome);
            results.add(new ItemResult(event.trackingId(), outcome));
        }
        return EventBatchResult.of(results);
    }

    private Outcome processOne(DevicePrincipal principal, DeviceEvent event, Instant receivedAt) {
        Notification notification = notifications.findById(event.trackingId()).orElse(null);
        if (notification == null || !notification.appId().equals(principal.appId())) {
            // another app's notification is indistinguishable from a missing one
            return Outcome.NOT_FOUND;
        }
        if (!notification.userId().equals(principal.userId())) {
            return Outcome.FORBIDDEN;
        }
        if (!events.insert(
                notification.id(), principal.deviceId(), event.type(), clamp(event.occurredAt(), receivedAt), receivedAt)) {
            return Outcome.DUPLICATE;
        }
        boolean moved = switch (event.type()) {
            case DELIVERED -> notifications.markDelivered(notification.id(), receivedAt);
            case READ -> notifications.markRead(notification.id(), receivedAt);
        };
        if (moved) {
            return Outcome.ACCEPTED;
        }
        events.markLate(notification.id(), event.type());
        return Outcome.LATE;
    }

    /** Device clocks are not trusted: never in the future, never older than 7 days. */
    static Instant clamp(Instant occurredAt, Instant receivedAt) {
        Instant oldest = receivedAt.minus(MAX_EVENT_AGE);
        Instant clamped = occurredAt.isAfter(receivedAt) ? receivedAt : occurredAt.isBefore(oldest) ? oldest : occurredAt;
        return clamped.truncatedTo(ChronoUnit.MICROS);
    }

    private static void log(DevicePrincipal principal, DeviceEvent event, Outcome outcome) {
        String tracking = event.trackingId().toString();
        switch (outcome) {
            case ACCEPTED, LATE -> EventLog.info("tracking.event_accepted", Map.of(
                    "event.outcome", "success",
                    "rp.app_id", principal.appId(),
                    "rp.device_id", principal.deviceId().toString(),
                    "rp.user_hash", UserHash.of(principal.userId()),
                    "rp.tracking_id", tracking,
                    "rp.event_type", event.type().name(),
                    "rp.late", outcome == Outcome.LATE));
            case DUPLICATE -> EventLog.info("tracking.event_ignored", Map.of(
                    "event.outcome", "success",
                    "reason", "duplicate",
                    "rp.app_id", principal.appId(),
                    "rp.device_id", principal.deviceId().toString(),
                    "rp.user_hash", UserHash.of(principal.userId()),
                    "rp.tracking_id", tracking,
                    "rp.event_type", event.type().name()));
            case FORBIDDEN -> EventLog.warn("authz.denied", Map.of(
                    "event.outcome", "failure",
                    "reason", "tracking_id_of_another_user",
                    "rp.app_id", principal.appId(),
                    "rp.device_id", principal.deviceId().toString(),
                    "rp.user_hash", UserHash.of(principal.userId()),
                    "rp.tracking_id", tracking));
            case NOT_FOUND -> {
                // not logged: unknown ids are not part of the event catalogue and would only add noise
            }
        }
    }
}
