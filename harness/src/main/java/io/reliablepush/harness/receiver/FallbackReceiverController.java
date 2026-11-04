package io.reliablepush.harness.receiver;

import io.reliablepush.harness.config.HarnessApp;
import io.reliablepush.harness.config.HarnessProperties;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/** The endpoint the backend's fallback webhook calls during local E2E and demos. */
@RestController
@RequestMapping("/hooks")
public class FallbackReceiverController {

    static final Duration MAX_HANG = Duration.ofSeconds(120);
    private static final Logger LOG = LogManager.getLogger(FallbackReceiverController.class);
    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {};

    private final HarnessProperties properties;
    private final WebhookInbox inbox;
    private final Clock clock = Clock.systemUTC();
    private final JsonMapper json = JsonMapper.builder().build();

    public FallbackReceiverController(HarnessProperties properties, WebhookInbox inbox) {
        this.properties = properties;
        this.inbox = inbox;
    }

    @PostMapping("/{appId}")
    public ResponseEntity<Void> receive(
            @PathVariable String appId,
            @RequestBody(required = false) byte[] rawBody,
            @RequestHeader(value = "X-RP-Timestamp", required = false) String timestamp,
            @RequestHeader(value = "X-RP-Signature", required = false) String signature,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        HarnessApp app;
        try {
            app = properties.app(appId);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        }
        byte[] bytes = rawBody == null ? new byte[0] : rawBody;
        Instant now = clock.instant();
        boolean valid = SignatureVerifier.verify(
                app.hmacSecret().getBytes(StandardCharsets.UTF_8), timestamp, signature, bytes, now);
        Map<String, Object> body = parse(bytes);

        UUID trackingId = uuid(idempotencyKey);
        if (trackingId == null) {
            trackingId = uuid(body.get("trackingId"));
        }
        int attempt = body.get("attempt") instanceof Number n ? n.intValue() : 0;
        String reason = body.get("reason") instanceof String s ? s : null;

        UUID callId = UUID.randomUUID();
        inbox.begin(new ReceivedWebhook(appId, trackingId, idempotencyKey, attempt, reason, valid, now, 0, body,
                callId, null));
        int status = HttpStatus.SERVICE_UNAVAILABLE.value();
        boolean interrupted = false;
        try {
            if (!valid) {
                status = HttpStatus.UNAUTHORIZED.value();
            } else {
                try {
                    status = answer(appId);
                } catch (InterruptedException e) {
                    interrupted = true;
                }
            }
        } catch (RuntimeException e) {
            // status stays 503
        } finally {
            // Clear the flag so recording and listeners do not run interrupted; restore it afterwards.
            interrupted |= Thread.interrupted();
            inbox.complete(callId, status, clock.instant());
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
        LOG.info("fallback webhook received appId={} trackingId={} attempt={} signatureValid={} respondedStatus={}",
                appId, trackingId, attempt, valid, status);
        return ResponseEntity.status(status).build();
    }

    private int answer(String appId) throws InterruptedException {
        WebhookInbox.Mode mode = inbox.mode(appId);
        return switch (mode.mode()) {
            case OK -> 200;
            case FAIL_500 -> 500;
            case FAIL_400 -> 400;
            case HANG -> {
                Duration hang = mode.hang().compareTo(MAX_HANG) > 0 ? MAX_HANG : mode.hang();
                Thread.sleep(hang);
                yield 200;
            }
        };
    }

    /** Tolerant: anything that is not a JSON object yields an empty map. Never logged. */
    private Map<String, Object> parse(byte[] bytes) {
        try {
            Map<String, Object> parsed = json.readValue(bytes, MAP);
            return parsed == null ? Map.of() : parsed;
        } catch (RuntimeException e) {
            return Map.of();
        }
    }

    private static UUID uuid(Object value) {
        if (!(value instanceof String s)) {
            return null;
        }
        try {
            return UUID.fromString(s);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
