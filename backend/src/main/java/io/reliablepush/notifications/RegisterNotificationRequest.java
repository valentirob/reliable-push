package io.reliablepush.notifications;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import tools.jackson.databind.JsonNode;

/** Body of {@code POST /v1/notifications}. Unknown JSON fields are ignored; {@code policy} and {@code timeoutMinutes} default per app. */
public record RegisterNotificationRequest(
        @NotNull @Size(min = 1, max = 256) String userId,
        @Size(min = 1, max = 128) String externalId,
        FallbackPolicy policy,
        @Min(1) @Max(10080) Integer timeoutMinutes,
        @FallbackPayload JsonNode fallback) {}
