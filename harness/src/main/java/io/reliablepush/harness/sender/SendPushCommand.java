package io.reliablepush.harness.sender;

import io.reliablepush.client.FallbackPolicy;
import io.reliablepush.harness.devices.DeviceBehaviour;
import java.util.Map;
import java.util.UUID;

/**
 * One simulated send. {@code policy}, {@code timeoutMinutes}, {@code fallback} and {@code perDevice} may be null:
 * the backend then applies the app defaults, {@code fallback} defaults to {@code {"channel":"sms","text":<body>}}
 * and every device uses its default behaviour.
 *
 * <p>{@code trackingId} (nullable): the sender's own id, for example a retry of an earlier registration; null means a
 * new one from {@code TrackingIds.newId()}. {@code externalId} (nullable) is passed to the backend as is.
 * {@code skipRegistration}: the push is sent without calling the sender API at all, as when the registration timed out
 * before the backend stored the row (spec 1, "row not stored").
 */
public record SendPushCommand(String appId, String userId, String title, String body, FallbackPolicy policy,
                              Integer timeoutMinutes, Map<String, ?> fallback,
                              Map<UUID, DeviceBehaviour> perDevice, UUID trackingId, String externalId,
                              boolean skipRegistration) {

    /** A new tracking id, registered normally. */
    public SendPushCommand(String appId, String userId, String title, String body, FallbackPolicy policy,
                           Integer timeoutMinutes, Map<String, ?> fallback, Map<UUID, DeviceBehaviour> perDevice) {
        this(appId, userId, title, body, policy, timeoutMinutes, fallback, perDevice, null, null, false);
    }
}
