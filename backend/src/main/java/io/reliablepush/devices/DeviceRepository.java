package io.reliablepush.devices;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Persistence of devices (plain SQL through {@link JdbcClient}). Used through {@link DeviceService} only. Every state
 * change is one conditional statement scoped by {@code app_id} and {@code user_id}, so a device id of another user or
 * app can never be touched.
 */
@Repository
class DeviceRepository {

    private final JdbcClient jdbc;

    DeviceRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    void insert(Device d) {
        jdbc.sql("""
                        insert into device (id, app_id, user_id, platform, token_version, created_at, last_seen_at)
                        values (:id, :appId, :userId, :platform, :version, :createdAt, :lastSeenAt)
                        """)
                .param("id", d.id())
                .param("appId", d.appId())
                .param("userId", d.userId())
                .param("platform", d.platform().name())
                .param("version", d.tokenVersion())
                .param("createdAt", timestamp(d.createdAt()))
                .param("lastSeenAt", timestamp(d.lastSeenAt()))
                .update();
    }

    Optional<Device> findById(UUID id) {
        return jdbc.sql("select id, app_id, user_id, platform, token_version, created_at, last_seen_at, revoked_at"
                        + " from device where id = :id")
                .param("id", id)
                .query(DeviceRepository::map)
                .optional();
    }

    /**
     * Re-registration of an existing device of the same app and user: bumps {@code token_version} (all older tokens
     * stop working), clears {@code revoked_at} and updates platform and last-seen.
     *
     * @return the new token version, empty when no such device belongs to this app and user
     */
    Optional<Integer> reRegister(UUID id, String appId, String userId, Device.Platform platform, Instant now) {
        return jdbc.sql("""
                        update device set token_version = token_version + 1, revoked_at = null,
                                          platform = :platform, last_seen_at = :now
                        where id = :id and app_id = :appId and user_id = :userId
                        returning token_version
                        """)
                .param("platform", platform.name())
                .param("now", timestamp(now))
                .param("id", id)
                .param("appId", appId)
                .param("userId", userId)
                .query(Integer.class)
                .optional();
    }

    /**
     * Marks the device as seen, provided it is not revoked and its version is still {@code tokenVersion}.
     *
     * @return {@code true} if the device is active with this version
     */
    boolean touch(UUID id, String appId, String userId, int tokenVersion, Instant now) {
        return jdbc.sql("""
                                update device set last_seen_at = :now
                                where id = :id and app_id = :appId and user_id = :userId
                                  and token_version = :version and revoked_at is null
                                """)
                        .param("now", timestamp(now))
                        .param("id", id)
                        .param("appId", appId)
                        .param("userId", userId)
                        .param("version", tokenVersion)
                        .update()
                == 1;
    }

    /**
     * Sets {@code revoked_at} and bumps the version, provided the device still has {@code tokenVersion} and is not
     * revoked. @return {@code false} otherwise
     */
    boolean revoke(UUID id, String appId, String userId, int tokenVersion, Instant now) {
        return jdbc.sql("""
                                update device set revoked_at = :now, token_version = token_version + 1
                                where id = :id and app_id = :appId and user_id = :userId
                                  and token_version = :version and revoked_at is null
                                """)
                        .param("now", timestamp(now))
                        .param("id", id)
                        .param("appId", appId)
                        .param("userId", userId)
                        .param("version", tokenVersion)
                        .update()
                == 1;
    }

    /** True if a device with exactly this id, app, user and version exists and is not revoked. */
    boolean isActive(UUID id, String appId, String userId, int tokenVersion) {
        return jdbc.sql("""
                                select exists (select 1 from device
                                               where id = :id and app_id = :appId and user_id = :userId
                                                 and token_version = :version and revoked_at is null)
                                """)
                .param("id", id)
                .param("appId", appId)
                .param("userId", userId)
                .param("version", tokenVersion)
                .query(Boolean.class)
                .single();
    }

    private static OffsetDateTime timestamp(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }

    private static Device map(ResultSet rs, int row) throws SQLException {
        OffsetDateTime revoked = rs.getObject("revoked_at", OffsetDateTime.class);
        return new Device(
                rs.getObject("id", UUID.class),
                rs.getString("app_id"),
                rs.getString("user_id"),
                Device.Platform.valueOf(rs.getString("platform")),
                rs.getInt("token_version"),
                rs.getObject("created_at", OffsetDateTime.class).toInstant(),
                rs.getObject("last_seen_at", OffsetDateTime.class).toInstant(),
                revoked == null ? null : revoked.toInstant());
    }
}
