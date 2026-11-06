package io.reliablepush.apps;

import io.reliablepush.apps.ReliablePushProperties.AppProperties;
import io.reliablepush.apps.ReliablePushProperties.IdentityType;
import io.reliablepush.shared.config.Secret;
import io.reliablepush.shared.config.SecretRefs;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;
import org.springframework.boot.convert.DurationStyle;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * Read-only registry of the configured apps. Everything is resolved and validated once, at startup: secret refs
 * ({@code env:NAME}) are read from the {@link Environment} immediately, so a missing variable or an invalid
 * combination fails the application start with an {@link IllegalStateException} instead of the first request. So
 * does a configuration without any app: the service would only answer 401 to everyone.
 */
@Component
public class AppRegistry {

    private static final Pattern APP_ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9_-]{0,63}");

    private record Entry(byte[] senderKeyHash, AppConfig app) {}

    private final Map<String, AppConfig> byId;
    private final List<Entry> entries;

    public AppRegistry(ReliablePushProperties properties, Environment environment) {
        if (properties.apps().isEmpty()) {
            // the usual cause: variables named RELIABLE_PUSH_APPS_... bind nothing under reliable-push.*
            throw new IllegalStateException("no apps configured under reliable-push.apps"
                    + " (environment variables must use the RELIABLEPUSH_APPS_<ID>_... form)");
        }
        Map<String, AppConfig> apps = new LinkedHashMap<>();
        List<Entry> list = new ArrayList<>();
        for (Map.Entry<String, AppProperties> configured : properties.apps().entrySet()) {
            AppConfig app = resolve(configured.getKey(), configured.getValue(), environment);
            for (Entry other : list) {
                if (MessageDigest.isEqual(other.senderKeyHash(), HexFormat.of().parseHex(app.senderApiKeySha256()))) {
                    throw new IllegalStateException("reliable-push.apps." + app.appId() + ".sender-api-key-sha256 equals the one of app "
                            + other.app().appId() + "; every app needs its own sender key");
                }
            }
            apps.put(app.appId(), app);
            list.add(new Entry(HexFormat.of().parseHex(app.senderApiKeySha256()), app));
        }
        this.byId = Map.copyOf(apps);
        this.entries = List.copyOf(list);
    }

    public Optional<AppConfig> byId(String appId) {
        return Optional.ofNullable(appId).map(byId::get);
    }

    /**
     * Looks an app up by its raw sender API key: SHA-256 of the key is compared with {@link MessageDigest#isEqual}
     * against every app (no early exit), so timing does not reveal which app or how much of a hash matched.
     */
    public Optional<AppConfig> bySenderApiKey(String rawKey) {
        if (rawKey == null || rawKey.isEmpty()) {
            return Optional.empty();
        }
        byte[] hash = sha256(rawKey);
        AppConfig found = null;
        for (Entry entry : entries) {
            if (MessageDigest.isEqual(hash, entry.senderKeyHash())) {
                found = entry.app();
            }
        }
        return Optional.ofNullable(found);
    }

    /** Whether the SHA-256 is the sender key hash of any app (no early exit, like {@link #bySenderApiKey}). */
    public boolean isSenderKeyHash(byte[] sha256) {
        boolean found = false;
        for (Entry entry : entries) {
            found |= MessageDigest.isEqual(sha256, entry.senderKeyHash());
        }
        return found;
    }

    public Collection<AppConfig> all() {
        return entries.stream().map(Entry::app).toList();
    }

    private static AppConfig resolve(String appId, AppProperties p, Environment env) {
        String base = "reliable-push.apps." + appId + ".";
        if (!APP_ID.matcher(appId).matches()) {
            throw new IllegalStateException("reliable-push.apps." + appId + ": app id must match " + APP_ID.pattern());
        }

        ReliablePushProperties.Identity id = p.identity();
        Secret grantSecret = null;
        if (id.type() == IdentityType.SIGNED_GRANT) {
            grantSecret = SecretRefs.resolve(env, base + "identity.grant-secret-ref", id.grantSecretRef());
        } else if (id.grantSecretRef() != null) {
            throw new IllegalStateException(base + "identity.grant-secret-ref is only valid for identity.type signed-grant");
        }

        ReliablePushProperties.Fallback f = p.fallback();
        URI url = parseWebhookUrl(base + "fallback.url", f.url());
        Secret hmac = SecretRefs.resolve(env, base + "fallback.hmac-secret-ref", f.hmacSecretRef());
        List<Duration> backoff = parseBackoff(base + "fallback.backoff", f.backoff());
        if (backoff.isEmpty() || backoff.stream().anyMatch(d -> d.isZero() || d.isNegative())) {
            throw new IllegalStateException(base + "fallback.backoff must list at least one positive duration");
        }

        AppConfig.Fallback fallback = new AppConfig.Fallback(
                url, hmac, f.maxAttempts(), backoff, f.triggerWindowMinutes(), f.httpTimeout());

        // The trigger window must cover every webhook attempt, otherwise retries would run past the notification's window.
        // Both sides are in configured (unscaled) units: in fast-clock profiles TimeScale scales window and backoff alike.
        Duration window = Duration.ofMinutes(fallback.triggerWindowMinutes());
        Duration cumulative = fallback.cumulativeBackoff();
        if (window.compareTo(cumulative) < 0) {
            throw new IllegalStateException(base + "fallback.trigger-window-minutes (" + fallback.triggerWindowMinutes()
                    + " min = " + window + ") must be at least the cumulative backoff " + cumulative
                    + " of " + fallback.maxAttempts() + " attempts");
        }
        if (p.deviceTokenValidity().isZero() || p.deviceTokenValidity().isNegative()) {
            throw new IllegalStateException(base + "device-token-validity must be positive");
        }
        if (f.httpTimeout().isZero() || f.httpTimeout().isNegative()) {
            throw new IllegalStateException(base + "fallback.http-timeout must be positive");
        }

        return new AppConfig(
                appId,
                p.senderApiKeySha256(),
                new AppConfig.Identity(id.type(), id.issuer(), id.audience(), id.userClaim(), id.jwksUri(), grantSecret),
                p.deviceTokenValidity(),
                p.defaultPolicy(),
                p.defaultTimeoutMinutes(),
                new AppConfig.RateLimits(p.rateLimits().devicePerMinute(), p.rateLimits().senderPerMinute()),
                fallback);
    }

    /** Each entry is a Spring duration ({@code 90s}, {@code 2m}, ...); a bare number means minutes. */
    private static List<Duration> parseBackoff(String property, List<String> values) {
        List<Duration> parsed = new ArrayList<>(values.size());
        for (String value : values) {
            try {
                parsed.add(DurationStyle.detectAndParse(value.trim(), ChronoUnit.MINUTES));
            } catch (IllegalArgumentException e) {
                throw new IllegalStateException(property + " entry '" + value + "' is not a duration (e.g. 2m, 90s or 2)");
            }
        }
        return List.copyOf(parsed);
    }

    private static URI parseWebhookUrl(String property, String value) {
        try {
            URI uri = new URI(value);
            if (!("https".equalsIgnoreCase(uri.getScheme()) || "http".equalsIgnoreCase(uri.getScheme())) || uri.getHost() == null) {
                throw new IllegalStateException(property + " must be an absolute http(s) URL");
            }
            return uri;
        } catch (URISyntaxException e) {
            throw new IllegalStateException(property + " is not a valid URL");
        }
    }

    private static byte[] sha256(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
