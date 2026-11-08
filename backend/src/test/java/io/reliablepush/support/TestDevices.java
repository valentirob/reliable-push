package io.reliablepush.support;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Device rows for integration tests. A notification is tracked (state {@code PENDING}) only for a user with an
 * active device (sidecar revision spec 2.3), so every test that expects tracking or a fallback inserts one first.
 */
public final class TestDevices {

    private TestDevices() {}

    /** An Android device of {@code appId} and {@code userId}, token version 0, its token valid for 90 more days. */
    public static UUID insertActive(JdbcClient jdbc, String appId, String userId) {
        return insert(jdbc, appId, userId, Instant.now().plus(Duration.ofDays(90)), null);
    }

    /** A device row with the given token expiry and revocation instant ({@code null}: not revoked). */
    public static UUID insert(JdbcClient jdbc, String appId, String userId, Instant tokenExpiresAt, Instant revokedAt) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                        insert into device (id, app_id, user_id, platform, token_version, created_at, last_seen_at,
                                            revoked_at, token_expires_at)
                        values (:id, :app, :user, 'ANDROID', 0, now(), now(), :revoked, :expires)
                        """)
                .param("id", id)
                .param("app", appId)
                .param("user", userId)
                .param("revoked", revokedAt == null ? null : revokedAt.atOffset(ZoneOffset.UTC))
                .param("expires", tokenExpiresAt.atOffset(ZoneOffset.UTC))
                .update();
        return id;
    }
}
