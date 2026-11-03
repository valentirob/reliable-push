package io.reliablepush.devices;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.UUID;

/**
 * Body of {@code POST /v1/devices}. {@code idToken} is the app's identity credential (OIDC ID token or signed grant);
 * {@code toString} never prints it.
 */
public record RegisterDeviceRequest(
        @NotBlank @Size(max = 64) String appId,
        @NotBlank @Size(max = 16384) String idToken,
        @NotNull Device.Platform platform,
        UUID deviceId) {

    @Override
    public String toString() {
        return "RegisterDeviceRequest[appId=" + appId + ", platform=" + platform + "]";
    }
}
