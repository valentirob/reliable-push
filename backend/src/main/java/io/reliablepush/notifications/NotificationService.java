package io.reliablepush.notifications;

import io.reliablepush.apps.AppConfig;
import io.reliablepush.apps.AppRegistry;
import io.reliablepush.devices.DeviceService;
import io.reliablepush.shared.logging.EventLog;
import io.reliablepush.shared.logging.UserHash;
import io.reliablepush.shared.metrics.RpMetrics;
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
 * Sender-side use cases (spec 4.1): register (idempotent per sender {@code trackingId}), read, list and cancel. Every method
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
    private final RpMetrics metrics;
    private final DeviceService devices;

    public NotificationService(
            NotificationRepository repository,
            AppRegistry apps,
            TimeScale timeScale,
            Clock clock,
            RpMetrics metrics,
            DeviceService devices) {
        this.repository = repository;
        this.devices = devices;
        this.apps = apps;
        this.timeScale = timeScale;
        this.clock = clock;
        this.metrics = metrics;
    }

    /**
     * Registers a notification under the sender's {@code trackingId} (sidecar revision spec 2.1), or returns the
     * existing one when the app already registered that {@code trackingId}: the first registration wins, other fields
     * are not compared. Race-safe: the insert is a single {@code ON CONFLICT DO NOTHING}, and the loser reads the
     * winner. A user without an active device gets a row stored closed as {@code NO_DEVICE_REGISTERED} (spec 2.2).
     *
     * @throws ConflictException when the {@code trackingId} belongs to another app, or when the {@code externalId}
     *     belongs to a row with another {@code trackingId}
     */
    public NotificationView register(String appId, UUID trackingId, RegisterNotificationRequest request) {
        AppConfig app = apps.byId(appId).orElseThrow(() -> new IllegalStateException("authenticated app is not configured"));
        // microsecond precision is what PostgreSQL keeps, so the response equals what a later GET returns
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        boolean tracked = devices.hasActiveDevice(appId, request.userId());
        Notification notification = newNotification(trackingId, app, request, now, timeScale, tracked);

        if (repository.insert(notification)) {
            if (tracked) {
                metrics.notificationRegistered("pending");
                logEvent("notification.registered", notification);
            } else {
                metrics.notificationRegistered("no_device_registered");
                logEvent("notification.no_device_registered", notification);
            }
            return NotificationView.of(notification);
        }
        Notification existing = existing(appId, trackingId, request.externalId());
        logEvent("notification.duplicate", existing);
        return NotificationView.of(existing);
    }

    /** After a skipped insert: the app's row with this {@code trackingId}, provided nothing else conflicts. */
    private Notification existing(String appId, UUID trackingId, String externalId) {
        Optional<Notification> byId = repository.findById(trackingId);
        Optional<Notification> byExternalId =
                externalId == null ? Optional.empty() : repository.findByExternalId(appId, externalId);
        if (byId.isPresent() && !byId.get().appId().equals(appId)) {
            throw new ConflictException("The trackingId is already in use.");
        }
        if (byExternalId.isPresent() && !byExternalId.get().id().equals(trackingId)) {
            throw new ConflictException("The externalId is already registered with another trackingId.");
        }
        // the insert only skips a row when one of its two unique keys is taken, and both checks above passed
        return byId.orElseThrow(() -> new IllegalStateException("notification vanished after a conflict"));
    }

    /**
     * Builds the new row: app defaults for policy and timeout. Tracked: {@code PENDING}, {@code dueAt = now + timeout}
     * on the (fast) clock. Not tracked (no active device): {@code NO_DEVICE_REGISTERED}, closed at creation, never due.
     */
    static Notification newNotification(
            UUID id, AppConfig app, RegisterNotificationRequest request, Instant now, TimeScale timeScale, boolean tracked) {
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
                tracked ? NotificationState.PENDING : NotificationState.NO_DEVICE_REGISTERED,
                now,
                tracked ? now.plus(timeScale.minutes(timeoutMinutes)) : null,
                null,
                null,
                tracked ? null : now);
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
     * Cancels a {@code PENDING} or {@code DELIVERED} notification that is not closed yet. A missing notification, or one of another app, is
     * {@link NotFoundException}; any other state (also {@code NO_DEVICE_REGISTERED}, final from the start) is
     * {@link ConflictException} and leaves the row unchanged.
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
