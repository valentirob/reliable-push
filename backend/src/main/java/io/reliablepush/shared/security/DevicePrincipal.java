package io.reliablepush.shared.security;

import java.util.UUID;

/**
 * The authenticated device of a request; set by {@link DeviceAuthFilter} under {@link #ATTR} after the token was
 * verified and the device row confirmed active. {@code tokenVersion} is the {@code ver} the presented token carries
 * (equal to the device's current version at the time of the check). {@code toString} never prints the user id.
 */
public record DevicePrincipal(String appId, String userId, UUID deviceId, int tokenVersion) {

    /** Request attribute holding the {@link DevicePrincipal}. */
    public static final String ATTR = "rp.device_principal";

    @Override
    public String toString() {
        return "DevicePrincipal[appId=" + appId + ", deviceId=" + deviceId + "]";
    }
}
