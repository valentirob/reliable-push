package io.reliablepush.apps;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.springframework.mock.env.MockEnvironment;

/** Builds {@link ReliablePushProperties} the way Spring does (relaxed binding), without a context. */
public final class AppFixtures {

    static final String HOOK_SECRET_ENV = "RP_TEST_HOOK_SECRET";

    private AppFixtures() {}

    static String sha256Hex(String raw) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(raw.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Minimal valid config of one app: only the mandatory keys, everything else defaulted. */
    public static Map<String, Object> minimalApp(String appId, String rawSenderKey) {
        Map<String, Object> p = new HashMap<>();
        String base = "reliable-push.apps." + appId + ".";
        p.put(base + "sender-api-key-sha256", sha256Hex(rawSenderKey));
        p.put(base + "identity.type", "oidc");
        p.put(base + "identity.issuer", "https://idp.test.example");
        p.put(base + "identity.audience", appId + "-mobile");
        p.put(base + "fallback.url", "https://fallback.test.example/hook");
        p.put(base + "fallback.hmac-secret-ref", "env:" + HOOK_SECRET_ENV);
        return p;
    }

    public static MockEnvironment environment() {
        return new MockEnvironment().withProperty(HOOK_SECRET_ENV, "generated-test-hook-secret");
    }

    public static ReliablePushProperties bind(Map<String, Object> properties) {
        return new Binder(new MapConfigurationPropertySource(properties))
                .bind("reliable-push", ReliablePushProperties.class)
                .get();
    }
}
