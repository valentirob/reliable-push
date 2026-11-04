package io.reliablepush.harness.receiver;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * One call to the fallback receiver. {@code trackingId} is null when neither the Idempotency-Key header nor the
 * body carries a valid UUID; {@code body} is empty when the body is not a JSON object.
 */
public record ReceivedWebhook(
        String appId,
        UUID trackingId,
        String idempotencyKey,
        int attempt,
        String reason,
        boolean signatureValid,
        Instant receivedAt,
        int respondedStatus,
        Map<String, Object> body) {}
