package io.reliablepush.notifications;

import io.reliablepush.apps.AppConfig;
import io.reliablepush.apps.AppRegistry;
import io.reliablepush.shared.logging.EventLog;
import io.reliablepush.shared.logging.UserHash;
import io.reliablepush.shared.time.TimeScale;
import io.reliablepush.shared.web.ConflictException;
import io.reliablepush.shared.web.InvalidRequestException;
import io.reliablepush.shared.web.NotFoundException;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Sender-side use cases (spec 4.1): register (idempotent per {@code externalId}), read, list and cancel. Every method
 * is scoped to the calling app; a notification of another app is indistinguishable from a missing one.
 */
@Service
public class NotificationService {

    static final int MAX_LIMIT = 100;
    static final int MAX_USER_ID = 256;
    private static final String NOT_FOUND = "Notification not found.";

    private final NotificationRepository repository;
    private final AppRegistry apps;
    private final TimeScale timeScale;
    private final Clock clock;

    public NotificationService(NotificationRepository repository, AppRegistry apps, TimeScale timeScale, Clock clock) {
        this.repository = repository;
        this.apps = apps;
        this.timeScale = timeScale;
        this.clock = clock;
    }

    /**
     * Registers a notification, or returns the existing one when the app already registered the same
     * {@code externalId} (also under concurrent requests: the insert is a single {@code ON CONFLICT DO NOTHING}).
     */
    public NotificationView register(String appId, RegisterNotificationRequest request) {
        AppConfig app = apps.byId(appId).orElseThrow(() -> new IllegalStateException("authenticated app is not configured"));
        // microsecond precision is what PostgreSQL keeps, so the response equals what a later GET returns
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        Notification notification = newNotification(UUID.randomUUID(), app, request, now, timeScale);

        if (repository.insert(notification)) {
            logEvent("notification.registered", notification);
            return NotificationView.of(notification);
        }
        // not inserted: the app already has this externalId (insert never skips a row without one)
        Notification existing = repository
                .findByExternalId(appId, request.externalId())
                .orElseThrow(() -> new IllegalStateException("notification vanished after an externalId conflict"));
        logEvent("notification.duplicate", existing);
        return NotificationView.of(existing);
    }

    /** Builds the new row: app defaults for policy and timeout, {@code dueAt = now + timeout} on the (fast) clock. */
    static Notification newNotification(
            UUID id, AppConfig app, RegisterNotificationRequest request, Instant now, TimeScale timeScale) {
        FallbackPolicy policy = request.policy() != null ? request.policy() : FallbackPolicy.valueOf(app.defaultPolicy());
        int timeoutMinutes = request.timeoutMinutes() != null ? request.timeoutMinutes() : app.defaultTimeoutMinutes();
        return new Notification(
                id,
                app.appId(),
                request.userId(),
                request.externalId(),
                policy,
                timeoutMinutes,
                request.fallback().toString(),
                NotificationState.PENDING,
                now,
                now.plus(timeScale.minutes(timeoutMinutes)),
                null,
                null,
                null);
    }

    public Optional<NotificationView> get(String appId, UUID id) {
        return repository.findById(id).filter(n -> n.appId().equals(appId)).map(NotificationView::of);
    }

    /** Newest first; {@code userId}, {@code state} and {@code since} (inclusive) are optional filters. */
    public NotificationPage list(String appId, String userId, NotificationState state, Instant since, int limit, String cursor) {
        if (limit < 1 || limit > MAX_LIMIT) {
            throw new InvalidRequestException("limit", "must be between 1 and " + MAX_LIMIT);
        }
        if (userId != null && (userId.isEmpty() || userId.length() > MAX_USER_ID)) {
            throw new InvalidRequestException("userId", "must be between 1 and " + MAX_USER_ID + " characters");
        }
        PageCursor after = cursor == null ? null : PageCursor.decode(cursor);

        List<Notification> rows = repository.page(appId, userId, state, since, after, limit + 1);
        boolean more = rows.size() > limit;
        List<Notification> pageRows = more ? rows.subList(0, limit) : rows;
        String nextCursor = null;
        if (more) {
            Notification last = pageRows.get(pageRows.size() - 1);
            nextCursor = new PageCursor(last.createdAt(), last.id()).encode();
        }
        return new NotificationPage(pageRows.stream().map(NotificationView::of).toList(), nextCursor);
    }

    /**
     * Cancels a {@code PENDING} or {@code DELIVERED} notification. A missing notification, or one of another app, is
     * {@link NotFoundException}; any other state is {@link ConflictException}.
     */
    public void cancel(String appId, UUID id) {
        if (repository.cancel(id, appId, clock.instant().truncatedTo(ChronoUnit.MICROS))) {
            EventLog.info("notification.cancelled", Map.of(
                    "event.outcome", "success", "rp.app_id", appId, "rp.tracking_id", id.toString()));
            return;
        }
        Notification current = repository
                .findById(id)
                .filter(n -> n.appId().equals(appId))
                .orElseThrow(() -> new NotFoundException(NOT_FOUND));
        throw new ConflictException("Notification is " + current.state().external() + " and can no longer be cancelled.");
    }

    private static void logEvent(String action, Notification n) {
        EventLog.info(action, Map.of(
                "event.outcome", "success",
                "rp.app_id", n.appId(),
                "rp.tracking_id", n.id().toString(),
                "rp.user_hash", UserHash.of(n.userId())));
    }
}
