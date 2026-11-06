package io.reliablepush.loadtest;

import java.net.URI;
import java.time.Duration;
import java.util.Map;

/** Load-test settings read from the environment. {@link #toString()} never includes the secrets. */
public record LoadConfig(
        URI apiUrl,
        String senderApiKey,
        String grantSecret,
        String grantIssuer,
        String appId,
        int devices,
        int notificationsPerSecond,
        Duration ramp,
        Duration steady,
        int burstSize,
        int burstPerSecond,
        Duration burstWarmUp) {

    public static LoadConfig fromEnv(Map<String, String> env) {
        LoadConfig c = parse(env);
        if (c.burstSize() % c.burstPerSecond() != 0) {
            throw new IllegalStateException(
                    "LOAD_BURST_SIZE must be a multiple of LOAD_BURST_PER_SECOND (the burst injects exactly that many requests)");
        }
        return c;
    }

    private static LoadConfig parse(Map<String, String> env) {
        return new LoadConfig(
                URI.create(text(env, "LOAD_API_URL", "http://api:8080")),
                required(env, "HARNESS_APPS_SHOP_SENDERAPIKEY"),
                required(env, "HARNESS_APPS_SHOP_GRANTSECRET"),
                required(env, "HARNESS_APPS_SHOP_GRANTISSUER"),
                text(env, "LOAD_APP_ID", "shop"),
                positiveInt(env, "LOAD_DEVICES", 2000),
                positiveInt(env, "LOAD_NOTIFICATIONS_PER_SECOND", 500),
                duration(env, "LOAD_RAMP", Duration.ofSeconds(60)),
                duration(env, "LOAD_STEADY", Duration.ofSeconds(300)),
                positiveInt(env, "LOAD_BURST_SIZE", 30000),
                positiveInt(env, "LOAD_BURST_PER_SECOND", 1500),
                duration(env, "LOAD_BURST_WARMUP", Duration.ofSeconds(30)));
    }

    private static String required(Map<String, String> env, String name) {
        String v = env.get(name);
        if (v == null || v.isBlank()) {
            throw new IllegalStateException("Missing required environment variable " + name);
        }
        return v;
    }

    private static String text(Map<String, String> env, String name, String def) {
        String v = env.get(name);
        return v == null || v.isBlank() ? def : v.trim();
    }

    private static int positiveInt(Map<String, String> env, String name, int def) {
        String v = env.get(name);
        if (v == null || v.isBlank()) {
            return def;
        }
        try {
            int n = Integer.parseInt(v.trim());
            if (n > 0) {
                return n;
            }
        } catch (NumberFormatException ignored) {
            // fall through: the message names the variable only
        }
        throw new IllegalStateException(name + " must be a positive integer");
    }

    private static Duration duration(Map<String, String> env, String name, Duration def) {
        String v = env.get(name);
        if (v == null || v.isBlank()) {
            return def;
        }
        try {
            return parseDuration(v.trim());
        } catch (RuntimeException e) {
            throw new IllegalStateException(name + " must be a positive duration such as 90s, 5m or PT90S");
        }
    }

    /** Accepts {@code 500ms}, {@code 90s}, {@code 5m}, {@code 1h}, a bare number of seconds or ISO-8601. */
    static Duration parseDuration(String v) {
        Duration d;
        if (v.startsWith("P") || v.startsWith("p")) {
            d = Duration.parse(v);
        } else if (v.endsWith("ms")) {
            d = Duration.ofMillis(Long.parseLong(v.substring(0, v.length() - 2)));
        } else if (v.endsWith("s")) {
            d = Duration.ofSeconds(Long.parseLong(v.substring(0, v.length() - 1)));
        } else if (v.endsWith("m")) {
            d = Duration.ofMinutes(Long.parseLong(v.substring(0, v.length() - 1)));
        } else if (v.endsWith("h")) {
            d = Duration.ofHours(Long.parseLong(v.substring(0, v.length() - 1)));
        } else {
            d = Duration.ofSeconds(Long.parseLong(v));
        }
        if (d.isZero() || d.isNegative()) {
            throw new IllegalArgumentException("not positive");
        }
        return d;
    }

    @Override
    public String toString() {
        return "LoadConfig[apiUrl=" + apiUrl + ", appId=" + appId + ", devices=" + devices
                + ", notificationsPerSecond=" + notificationsPerSecond + ", ramp=" + ramp + ", steady=" + steady
                + ", burstSize=" + burstSize + ", burstPerSecond=" + burstPerSecond + ", burstWarmUp=" + burstWarmUp
                + ", secrets=<redacted>]";
    }
}
