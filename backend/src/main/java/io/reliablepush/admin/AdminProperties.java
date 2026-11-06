package io.reliablepush.admin;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * Typed {@code reliable-push.admin.*} configuration of the read-only ops dashboard. Per-key checks (id pattern, hash
 * format, uniqueness, app ids, permissions) run in {@link AdminKeys} at startup. An empty {@code keys} list leaves
 * the admin surface disabled. Keys are held as SHA-256 hashes only.
 */
@Validated
@ConfigurationProperties("reliable-push.admin")
public record AdminProperties(
        @DefaultValue("600") @Min(1) int rateLimitPerMinute,
        @DefaultValue("400") @Min(1) int rollupRetentionDays,
        @Valid @DefaultValue Rollup rollup,
        @DefaultValue List<@Valid Key> keys) {

    public AdminProperties {
        keys = keys == null ? List.of() : List.copyOf(keys);
    }

    /** {@code cron} is a UTC Spring cron expression; {@code -} disables the job. */
    public record Rollup(@DefaultValue("0 7 3 * * *") @NotBlank String cron) {}

    public record Key(String id, String keySha256, List<String> apps, @DefaultValue List<String> permissions) {

        public Key {
            apps = apps == null ? List.of() : List.copyOf(apps);
            permissions = permissions == null ? List.of() : List.copyOf(permissions);
        }
    }
}
