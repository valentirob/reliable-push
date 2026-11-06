package io.reliablepush.devices;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.util.UUID;

/**
 * A freshly issued device token. Registration answers {@code {deviceId, deviceToken, expiresAt}}; a refresh leaves
 * {@code deviceId} out ({@code {deviceToken, expiresAt}}). {@code toString} never prints the token.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record DeviceTokenResponse(UUID deviceId, String deviceToken, Instant expiresAt) {

    @Override
    public String toString() {
        return "DeviceTokenResponse[deviceId=" + deviceId + ", expiresAt=" + expiresAt + "]";
    }
}
