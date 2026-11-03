package io.reliablepush.devices;

import java.time.Instant;
import java.util.UUID;

/**
 * A device row (spec 3). {@code tokenVersion} is the {@code ver} claim that device tokens must carry; it is bumped by
 * re-registration and revocation. {@code toString} never prints the user id.
 */
public record Device(
        UUID id,
        String appId,
        String userId,
        Platform platform,
        int tokenVersion,
        Instant createdAt,
        Instant lastSeenAt,
        Instant revokedAt) {

    public enum Platform {
        IOS,
        ANDROID
    }

    @Override
    public String toString() {
        return "Device[id=" + id + ", appId=" + appId + ", platform=" + platform + ", tokenVersion=" + tokenVersion
                + ", revoked=" + (revokedAt != null) + "]";
    }
}
