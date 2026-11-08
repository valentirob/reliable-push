package io.reliablepush.fallback;

import io.reliablepush.apps.AppConfig;
import io.reliablepush.apps.AppRegistry;
import io.reliablepush.fallback.FallbackDecision.Outcome;
import io.reliablepush.notifications.ClaimedNotification;
import io.reliablepush.notifications.NotificationRepository;
import io.reliablepush.notifications.NotificationState;
import io.reliablepush.shared.logging.EventLog;
import io.reliablepush.shared.logging.UserHash;
import io.reliablepush.shared.metrics.RpMetrics;
import io.reliablepush.shared.time.TimeScale;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.ThreadContext;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Handles one claimed row (spec 2): trigger-window check, webhook call, outcome. Runs outside any transaction, so no
 * row lock is held during the HTTP call; the outcome is persisted with an update fenced on this worker's lease
 * ({@code lease_until} it claimed with, plus {@code DISPATCHING} and the attempts count). If another worker re-claimed
 * the row after this worker's lease ran out, this worker's outcome is dropped (logged, not counted) whether it finishes
 * before or after the re-claimer: only the current lease holder writes. The row keeps exactly one final state, though
 * the webhook may have been called twice (receivers de-duplicate on {@code Idempotency-Key}).
 *
 * <p>Every row gets its own {@code trace.id} and {@code rp.tracking_id} in the {@link ThreadContext}, removed when done.
 * Never logged: the fallback payload and the raw user id.
 */
final class FallbackDispatcher {

    private static final Logger LOG = LogManager.getLogger(FallbackDispatcher.class);

    /**
     * Grace period for rows of an app this replica does not know (no app config, so no app window): the default
     * {@code fallback.trigger-window-minutes}, in product minutes.
     */
    static final int UNCONFIGURED_APP_WINDOW_MINUTES = 20;

    static final String TRACE_ID = "trace.id";
    static final String TRACKING_ID = "rp.tracking_id";

    private final NotificationRepository notifications;
    private final AppRegistry apps;
    private final TimeScale timeScale;
    private final Clock clock;
    private final WebhookClient webhook;
    private final JsonMapper json;
    private final RpMetrics metrics;
    private final Duration lease;
    private final FallbackAttemptRepository attempts;
    private final TransactionTemplate transactions;

    FallbackDispatcher(
            NotificationRepository notifications,
            AppRegistry apps,
            TimeScale timeScale,
            Clock clock,
            WebhookClient webhook,
            JsonMapper json,
            RpMetrics metrics,
            Duration lease,
            FallbackAttemptRepository attempts,
            TransactionTemplate transactions) {
        this.notifications = notifications;
        this.apps = apps;
        this.timeScale = timeScale;
        this.clock = clock;
        this.webhook = webhook;
        this.json = json;
        this.metrics = metrics;
        this.lease = lease;
        this.attempts = attempts;
        this.transactions = transactions;
    }

    /** Never throws: an unexpected failure is logged and the row is picked up again when its lease ends. */
    void process(ClaimedNotification row) {
        ThreadContext.put(TRACE_ID, UUID.randomUUID().toString().replace("-", ""));
        ThreadContext.put(TRACKING_ID, row.id().toString());
        try {
            handle(row);
        } catch (RuntimeException e) {
            LOG.error("Fallback processing failed; the row is retried when its lease ends", e);
        } finally {
            ThreadContext.remove(TRACE_ID);
            ThreadContext.remove(TRACKING_ID);
        }
    }

    private void handle(ClaimedNotification row) {
        int attempt = row.fallbackAttempts() + 1;
        Map<String, Object> claimFields = fields(row, "success");
        claimFields.put("rp.attempt", attempt);
        if (row.reclaimed()) {
            // the previous holder never persisted an outcome: it crashed or its call outlasted the lease
            EventLog.warn("fallback.reclaimed", claimFields);
        } else {
            EventLog.info("fallback.claimed", claimFields);
        }

        AppConfig app = apps.byId(row.appId()).orElse(null);
        if (app == null) {
            unconfiguredApp(row);
            return;
        }

        AppConfig.Fallback config = app.fallback();
        Duration window = timeScale.minutes(config.triggerWindowMinutes());
        List<Duration> backoff = config.backoff().stream().map(timeScale::scale).toList();

        if (FallbackDecision.decide(now(), row.dueAt(), window, row.fallbackAttempts(), config.maxAttempts(), backoff, null)
                instanceof Outcome.Expire) {
            expire(row, row.fallbackAttempts(), "trigger_window_passed", null);
            return;
        }

        FallbackCall call = new FallbackCall(row.id(), row.userId(), payload(row), row.policy().name(), attempt);
        Instant startedAt = now();
        long started = System.nanoTime();
        WebhookResult result;
        try {
            result = webhook.send(app, call);
        } catch (RuntimeException e) {
            LOG.error("Webhook client failed unexpectedly; treated as a retryable attempt", e);
            result = new WebhookResult.Retryable("client_error");
        }
        long duration = System.nanoTime() - started;
        FallbackAttempt record = attemptOf(row, attempt, startedAt, duration, result);

        Outcome outcome = FallbackDecision.decide(
                now(), row.dueAt(), window, attempt, config.maxAttempts(), backoff, result);
        switch (outcome) {
            case Outcome.Done done -> {
                if (closeRecording(row, attempt, NotificationState.FALLBACK_TRIGGERED, "dispatched", record)) {
                    Map<String, Object> f = fields(row, "success");
                    f.put("rp.attempt", attempt);
                    f.put("event.duration", duration);
                    if (result instanceof WebhookResult.Success success) {
                        f.put("http.response.status_code", success.status());
                    }
                    EventLog.info("fallback.dispatched", f);
                    metrics.fallback("triggered");
                }
            }
            case Outcome.Fail fail -> {
                if (closeRecording(row, attempt, NotificationState.FALLBACK_FAILED, "failed", record)) {
                    Map<String, Object> f = fields(row, "failure");
                    f.put("rp.attempt", attempt);
                    f.put("event.duration", duration);
                    f.put("reason", reason(result));
                    EventLog.warn("fallback.failed", f);
                    metrics.fallback("failed");
                }
            }
            case Outcome.RetryAt retry -> {
                if (recording(record, () -> notifications.reschedule(
                        row.id(), row.leaseUntil(), row.fallbackAttempts(), attempt, retry.at()))) {
                    Map<String, Object> f = fields(row, "failure");
                    f.put("rp.attempt", attempt);
                    f.put("event.duration", duration);
                    f.put("reason", reason(result));
                    f.put("rp.next_attempt_at", retry.at().toString());
                    EventLog.info("fallback.retry_scheduled", f);
                } else {
                    dropped("retry_scheduled");
                }
            }
            case Outcome.Expire expire -> expire(row, attempt, "next_retry_after_trigger_window", record);
            case Outcome.Dispatch dispatch -> throw new IllegalStateException("decision after a call cannot be DISPATCH");
        }
    }

    /**
     * A row of an app this replica does not know, e.g. during a rolling deploy where the new app is only configured on
     * newer replicas: left for later (re-claimable after one lease, attempts unchanged) while
     * {@code now <= dueAt + 20 product minutes}, then closed as {@code FALLBACK_EXPIRED} so it neither stays due forever
     * nor keeps competing for batch slots.
     */
    private void unconfiguredApp(ClaimedNotification row) {
        Instant now = now();
        if (now.isAfter(row.dueAt().plus(timeScale.minutes(UNCONFIGURED_APP_WINDOW_MINUTES)))) {
            expire(row, row.fallbackAttempts(), "app_not_configured", null);
            return;
        }
        Instant next = now.plus(lease);
        if (notifications.reschedule(row.id(), row.leaseUntil(), row.fallbackAttempts(), row.fallbackAttempts(), next)) {
            Map<String, Object> f = fields(row, "unknown");
            f.put("reason", "app_not_configured");
            f.put("rp.next_attempt_at", next.toString());
            EventLog.warn("fallback.retry_scheduled", f);
        } else {
            dropped("retry_scheduled");
        }
    }

    /** {@code record} is the attempt of the call that preceded the expiry; null when the expiry made no call. */
    private void expire(ClaimedNotification row, int attempts, String reason, FallbackAttempt record) {
        if (closeRecording(row, attempts, NotificationState.FALLBACK_EXPIRED, "expired", record)) {
            Map<String, Object> f = fields(row, "failure");
            f.put("rp.attempt", attempts);
            f.put("reason", reason);
            EventLog.warn("fallback.expired", f);
            metrics.fallback("expired");
        }
    }

    private boolean closeRecording(
            ClaimedNotification row, int attempts, NotificationState state, String outcome, FallbackAttempt record) {
        if (recording(record, () -> notifications.close(
                row.id(), row.leaseUntil(), row.fallbackAttempts(), attempts, state, now()))) {
            return true;
        }
        dropped(outcome);
        return false;
    }

    /**
     * Runs the fenced update and, only if it took effect, inserts the attempt in the same transaction: a worker that
     * lost its lease records nothing, and a failed insert rolls the update back. Without a call ({@code record} null)
     * there is nothing to insert and no transaction.
     */
    private boolean recording(FallbackAttempt record, BooleanSupplier fencedUpdate) {
        if (record == null) {
            return fencedUpdate.getAsBoolean();
        }
        return Boolean.TRUE.equals(transactions.execute(status -> {
            boolean applied = fencedUpdate.getAsBoolean();
            if (applied) {
                attempts.insert(record);
            }
            return applied;
        }));
    }

    private static FallbackAttempt attemptOf(
            ClaimedNotification row, int attempt, Instant startedAt, long durationNanos, WebhookResult result) {
        long durationMs = durationNanos / 1_000_000;
        return switch (result) {
            case WebhookResult.Success s -> new FallbackAttempt(row.id(), attempt, startedAt, durationMs, "SUCCESS", s.status(), null);
            case WebhookResult.Retryable r -> new FallbackAttempt(
                    row.id(), attempt, startedAt, durationMs, "RETRYABLE", statusOf(r.reason()), r.reason());
            case WebhookResult.Final f -> new FallbackAttempt(
                    row.id(), attempt, startedAt, durationMs, "FINAL", statusOf(f.reason()), f.reason());
        };
    }

    /** The status of a reason code {@code http_<nnn>}; null for {@code timeout}, {@code io_error}, ... */
    private static Integer statusOf(String reason) {
        if (reason != null && reason.matches("http_\\d{3}")) {
            return Integer.valueOf(reason.substring(5));
        }
        return null;
    }

    private static void dropped(String outcome) {
        LOG.warn("Fallback outcome {} dropped: this worker's lease ran out and another worker re-claimed the row",
                outcome);
    }

    private JsonNode payload(ClaimedNotification row) {
        try {
            return json.readTree(row.fallbackPayload());
        } catch (JacksonException e) {
            // no cause and no message from the parser: it could quote the payload
            throw new IllegalStateException("stored fallback payload is not valid JSON");
        }
    }

    private static String reason(WebhookResult result) {
        return switch (result) {
            case WebhookResult.Retryable r -> r.reason();
            case WebhookResult.Final f -> f.reason();
            case WebhookResult.Success s -> "http_" + s.status();
        };
    }

    private static Map<String, Object> fields(ClaimedNotification row, String outcome) {
        Map<String, Object> f = new LinkedHashMap<>();
        f.put("event.outcome", outcome);
        f.put("rp.app_id", row.appId());
        f.put("rp.user_hash", UserHash.of(row.userId()));
        return f;
    }

    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }
}
