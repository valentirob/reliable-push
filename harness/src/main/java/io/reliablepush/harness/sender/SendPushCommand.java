package io.reliablepush.harness.sender;

import io.reliablepush.client.FallbackPolicy;
import io.reliablepush.harness.devices.DeviceBehaviour;
import java.util.Map;
import java.util.UUID;

/**
 * One simulated send. {@code policy}, {@code timeoutMinutes}, {@code fallback} and {@code perDevice} may be null:
 * the backend then applies the app defaults, {@code fallback} defaults to {@code {"channel":"sms","text":<body>}}
 * and every device uses its default behaviour.
 */
public record SendPushCommand(String appId, String userId, String title, String body, FallbackPolicy policy,
                              Integer timeoutMinutes, Map<String, ?> fallback,
                              Map<UUID, DeviceBehaviour> perDevice) {
}
