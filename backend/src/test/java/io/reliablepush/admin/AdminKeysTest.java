package io.reliablepush.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.reliablepush.apps.AppFixtures;
import io.reliablepush.apps.AppRegistry;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class AdminKeysTest {

    private static final String SHOP_SENDER_KEY = "generated-shop-sender-key";
    private static final String RAW = "generated-admin-key-one";
    private static final String RAW_2 = "generated-admin-key-two";

    private static final AppRegistry REGISTRY = registry();

    private static AppRegistry registry() {
        Map<String, Object> props = new HashMap<>(AppFixtures.minimalApp("shop", SHOP_SENDER_KEY));
        props.putAll(AppFixtures.minimalApp("other", "generated-other-sender-key"));
        return new AppRegistry(AppFixtures.bind(props), AppFixtures.environment());
    }

    private static String sha(String raw) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(raw.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static AdminProperties.Key key(String id, String hash, List<String> apps, List<String> permissions) {
        return new AdminProperties.Key(id, hash, apps, permissions);
    }

    private static AdminKeys keys(AdminProperties.Key... keys) {
        return new AdminKeys(new AdminProperties(600, 400, new AdminProperties.Rollup("0 7 3 * * *"), List.of(keys)), REGISTRY);
    }

    @Test
    void noKeysMeansDisabled() {
        AdminKeys keys = keys();
        assertThat(keys.enabled()).isFalse();
        assertThat(keys.byRawKey("x")).isEmpty();
    }

    @Test
    void matchesByHashAndExpandsStar() {
        AdminKeys keys = keys(
                key("k1", sha(RAW), List.of("*"), List.of()),
                key("k2", sha(RAW_2), List.of("*"), List.of("payload")));
        assertThat(keys.enabled()).isTrue();
        AdminPrincipal p1 = keys.byRawKey(RAW).orElseThrow();
        assertThat(p1.keyId()).isEqualTo("k1");
        assertThat(p1.apps()).isEqualTo(Set.of("shop", "other"));
        assertThat(p1.payload()).isFalse();
        assertThat(keys.byRawKey(RAW_2).orElseThrow().payload()).isTrue();
    }

    @Test
    void scopedKeySeesOnlyItsApps() {
        AdminPrincipal p = keys(key("k1", sha(RAW), List.of("shop"), List.of())).byRawKey(RAW).orElseThrow();
        assertThat(p.canSee("shop")).isTrue();
        assertThat(p.canSee("other")).isFalse();
    }

    @Test
    void unknownOrBlankKeyIsEmpty() {
        AdminKeys keys = keys(key("k1", sha(RAW), List.of("*"), List.of()));
        assertThat(keys.byRawKey("")).isEmpty();
        assertThat(keys.byRawKey(null)).isEmpty();
        assertThat(keys.byRawKey("nope")).isEmpty();
    }

    @Test
    void exposesRateLimit() {
        assertThat(keys().ratePerMinute()).isEqualTo(600);
    }

    static Stream<Arguments> startupFailures() {
        String h1 = sha(RAW);
        String h2 = sha(RAW_2);
        List<String> all = List.of("*");
        return Stream.of(
                Arguments.of("duplicate id", List.of(key("dup-id", h1, all, List.of()), key("dup-id", h2, all, List.of())), "dup-id"),
                Arguments.of("bad id", List.of(key("Bad_Id", h1, all, List.of())), "Bad_Id"),
                // @ConditionalOnProperty would read the id "false" as off while AdminKeys is enabled
                Arguments.of("id false", List.of(key("false", h1, all, List.of())), "'false' is reserved"),
                Arguments.of("id false not first", List.of(key("first", h1, all, List.of()), key("false", h2, all, List.of())),
                        "'false' is reserved"),
                Arguments.of("short hash", List.of(key("short-hash", h1.substring(1), all, List.of())), "short-hash"),
                Arguments.of("upper-case hash", List.of(key("upper-hash", h1.toUpperCase(), all, List.of())), "upper-hash"),
                Arguments.of("same hash twice", List.of(key("same-a", h1, all, List.of()), key("same-b", h1, all, List.of())), "same-b"),
                Arguments.of("sender key hash", List.of(key("sender-hash", sha(SHOP_SENDER_KEY), all, List.of())), "sender-hash"),
                Arguments.of("empty apps", List.of(key("no-apps", h1, List.of(), List.of())), "no-apps"),
                Arguments.of("star mixed", List.of(key("mixed", h1, List.of("*", "shop"), List.of())), "mixed"),
                Arguments.of("unknown app", List.of(key("bad-app", h1, List.of("nope"), List.of())), "bad-app"),
                Arguments.of("unknown permission", List.of(key("bad-perm", h1, all, List.of("write"))), "bad-perm"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("startupFailures")
    void startupFailures(String name, List<AdminProperties.Key> configured, String id) {
        AdminProperties props = new AdminProperties(600, 400, new AdminProperties.Rollup("0 7 3 * * *"), configured);
        assertThatThrownBy(() -> new AdminKeys(props, REGISTRY))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(id);
    }
}
