package io.reliablepush.fallback;

import io.micrometer.core.instrument.MeterRegistry;
import io.reliablepush.apps.AppConfig;
import io.reliablepush.apps.AppRegistry;
import io.reliablepush.fallback.FallbackDecision.Outcome;
import io.reliablepush.notifications.ClaimedNotification;
import io.reliablepush.notifications.NotificationRepository;
import io.reliablepush.notifications.NotificationState;
import io.reliablepush.shared.logging.EventLog;
import io.reliablepush.shared.logging.UserHash;
import io.reliablepush.shared.time.TimeScale;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.ThreadContext;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Handles one claimed row (spec 2): trigger-window check, webhook call, outcome. Runs outside any transaction, so no
 * row lock is held during the HTTP call; the outcome is persisted with a conditional update ({@code DISPATCHING} and
 * the attempts count seen at claim time). If another worker re-claimed the row after this worker's lease ran out and
 * persisted first, the outcome is dropped (logged, not counted): the row keeps exactly one final state, though the
 * webhook may have been called twice (receivers de-duplicate on {@code Idempotency-Key}).
 *
 * <p>Every row gets its own {@code trace.id} and {@code rp.tracking_id} in the {@link ThreadContext}, removed when done.
 * Never logged: the fallback payload and the raw user id.
 */
final class FallbackDispatcher {

    private static final Logger LOG = LogManager.getLogger(FallbackDispatcher.class);

    static final String TRACE_ID = "trace.id";
    static final String TRACKING_ID = "rp.tracking_id";

    private final NotificationRepository notifications;
    private final AppRegistry apps;
    private final TimeScale timeScale;
    private final Clock clock;
    private final WebhookClient webhook;
    private final JsonMapper json;
    private final MeterRegistry meters;
    private final Duration lease;

    FallbackDispatcher(
            NotificationRepository notifications,
            AppRegistry apps,
            TimeScale timeScale,
            Clock clock,
            WebhookClient webhook,
            JsonMapper json,
            MeterRegistry meters,
            Duration lease) {
        this.notifications = notifications;
        this.apps = apps;
        this.timeScale = timeScale;
        this.clock = clock;
        this.webhook = webhook;
        this.json = json;
        this.meters = meters;
        this.lease = lease;
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
        if (row.previousState() == NotificationState.DISPATCHING) {
            // a due retry or a lease that ran out (previous worker crashed or too slow): the row cannot tell
            EventLog.warn("fallback.reclaimed", claimFields);
        } else {
            EventLog.info("fallback.claimed", claimFields);
        }

        AppConfig app = apps.byId(row.appId()).orElse(null);
        if (app == null) {
            // e.g. a rolling deploy where this replica's configuration does not know the app yet: leave it for later
            Instant next = now().plus(lease);
            if (notifications.reschedule(row.id(), row.fallbackAttempts(), row.fallbackAttempts(), next)) {
                Map<String, Object> f = fields(row, "unknown");
                f.put("reason", "app_not_configured");
                f.put("rp.next_attempt_at", next.toString());
                EventLog.warn("fallback.retry_scheduled", f);
            } else {
                dropped("retry_scheduled");
            }
            return;
        }

        AppConfig.Fallback config = app.fallback();
        Duration window = timeScale.minutes(config.triggerWindowMinutes());
        List<Duration> backoff = config.backoff().stream().map(timeScale::scale).toList();

        if (FallbackDecision.decide(now(), row.dueAt(), window, row.fallbackAttempts(), config.maxAttempts(), backoff, null)
                instanceof Outcome.Expire) {
            expire(row, row.fallbackAttempts(), "trigger_window_passed");
            return;
        }

        FallbackCall call = new FallbackCall(row.id(), row.userId(), payload(row), row.policy().name(), attempt);
        long started = System.nanoTime();
        WebhookResult result;
        try {
            result = webhook.send(app, call);
        } catch (RuntimeException e) {
            LOG.error("Webhook client failed unexpectedly; treated as a retryable attempt", e);
            result = new WebhookResult.Retryable("client_error");
        }
        long duration = System.nanoTime() - started;

        Outcome outcome = FallbackDecision.decide(
                now(), row.dueAt(), window, attempt, config.maxAttempts(), backoff, result);
        switch (outcome) {
            case Outcome.Done done -> {
                if (close(row, attempt, NotificationState.FALLBACK_TRIGGERED, "dispatched")) {
                    Map<String, Object> f = fields(row, "success");
                    f.put("rp.attempt", attempt);
                    f.put("event.duration", duration);
                    if (result instanceof WebhookResult.Success success) {
                        f.put("http.response.status_code", success.status());
                    }
                    EventLog.info("fallback.dispatched", f);
                    count("triggered");
                }
            }
            case Outcome.Fail fail -> {
                if (close(row, attempt, NotificationState.FALLBACK_FAILED, "failed")) {
                    Map<String, Object> f = fields(row, "failure");
                    f.put("rp.attempt", attempt);
                    f.put("event.duration", duration);
                    f.put("reason", reason(result));
                    EventLog.warn("fallback.failed", f);
                    count("failed");
                }
            }
            case Outcome.RetryAt retry -> {
                if (notifications.reschedule(row.id(), row.fallbackAttempts(), attempt, retry.at())) {
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
            case Outcome.Expire expire -> expire(row, attempt, "next_retry_after_trigger_window");
            case Outcome.Dispatch dispatch -> throw new IllegalStateException("decision after a call cannot be DISPATCH");
        }
    }

    private void expire(ClaimedNotification row, int attempts, String reason) {
        if (close(row, attempts, NotificationState.FALLBACK_EXPIRED, "expired")) {
            Map<String, Object> f = fields(row, "failure");
            f.put("rp.attempt", attempts);
            f.put("reason", reason);
            EventLog.warn("fallback.expired", f);
            count("expired");
        }
    }

    private boolean close(ClaimedNotification row, int attempts, NotificationState state, String outcome) {
        if (notifications.close(row.id(), row.fallbackAttempts(), attempts, state, now())) {
            return true;
        }
        dropped(outcome);
        return false;
    }

    private static void dropped(String outcome) {
        LOG.warn("Fallback outcome {} dropped: the row was re-claimed by another worker after this worker's lease ran out",
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

    private void count(String outcome) {
        // Task 15 moves this into RpMetrics
        meters.counter("rp.fallback", "outcome", outcome).increment();
    }

    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }
}
