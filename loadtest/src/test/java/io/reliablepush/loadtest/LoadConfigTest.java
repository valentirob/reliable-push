package io.reliablepush.loadtest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class LoadConfigTest {

    private static Map<String, String> secrets() {
        Map<String, String> env = new HashMap<>();
        env.put("HARNESS_APPS_SHOP_SENDERAPIKEY", "sender-key-VALUE-1");
        env.put("HARNESS_APPS_SHOP_GRANTSECRET", "grant-secret-VALUE-2");
        env.put("HARNESS_APPS_SHOP_GRANTISSUER", "shop-backend");
        return env;
    }

    @Test
    void defaults() {
        LoadConfig c = LoadConfig.fromEnv(secrets());
        assertThat(c.apiUrl()).isEqualTo(URI.create("http://api:8080"));
        assertThat(c.appId()).isEqualTo("shop");
        assertThat(c.devices()).isEqualTo(2000);
        assertThat(c.notificationsPerSecond()).isEqualTo(500);
        assertThat(c.ramp()).isEqualTo(Duration.ofSeconds(60));
        assertThat(c.steady()).isEqualTo(Duration.ofSeconds(300));
        assertThat(c.burstSize()).isEqualTo(30000);
        assertThat(c.burstPerSecond()).isEqualTo(1500);
        assertThat(c.burstWarmUp()).isEqualTo(Duration.ofSeconds(30));
        assertThat(c.senderApiKey()).isEqualTo("sender-key-VALUE-1");
        assertThat(c.grantSecret()).isEqualTo("grant-secret-VALUE-2");
        assertThat(c.grantIssuer()).isEqualTo("shop-backend");
    }

    @Test
    void overrides() {
        Map<String, String> env = secrets();
        env.put("LOAD_API_URL", "http://localhost:9000");
        env.put("LOAD_DEVICES", "10");
        env.put("LOAD_RAMP", "5s");
        env.put("LOAD_STEADY", "2m");
        env.put("LOAD_APP_ID", "other");
        env.put("LOAD_BURST_WARMUP", "1m");
        LoadConfig c = LoadConfig.fromEnv(env);
        assertThat(c.apiUrl()).isEqualTo(URI.create("http://localhost:9000"));
        assertThat(c.devices()).isEqualTo(10);
        assertThat(c.ramp()).isEqualTo(Duration.ofSeconds(5));
        assertThat(c.steady()).isEqualTo(Duration.ofMinutes(2));
        assertThat(c.appId()).isEqualTo("other");
        assertThat(c.burstWarmUp()).isEqualTo(Duration.ofMinutes(1));
    }

    @Test
    void missingSecretNamesTheVariableNotAValue() {
        for (String name : new String[] {
            "HARNESS_APPS_SHOP_SENDERAPIKEY", "HARNESS_APPS_SHOP_GRANTSECRET", "HARNESS_APPS_SHOP_GRANTISSUER"}) {
            Map<String, String> env = secrets();
            env.remove(name);
            assertThatThrownBy(() -> LoadConfig.fromEnv(env))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining(name)
                    .message()
                    .doesNotContain("VALUE");
        }
    }

    @Test
    void invalidNumberMessageHasNoValue() {
        Map<String, String> env = secrets();
        env.put("LOAD_DEVICES", "abc-secret-ish");
        assertThatThrownBy(() -> LoadConfig.fromEnv(env))
                .hasMessageContaining("LOAD_DEVICES")
                .message()
                .doesNotContain("abc-secret-ish")
                .doesNotContain("VALUE");
    }

    @Test
    void toStringOmitsSecrets() {
        String s = LoadConfig.fromEnv(secrets()).toString();
        assertThat(s).doesNotContain("VALUE").contains("http://api:8080").contains("shop");
    }

    @Test
    void burstSizeMustBeAMultipleOfBurstRate() {
        Map<String, String> env = secrets();
        env.put("LOAD_BURST_SIZE", "30000");
        env.put("LOAD_BURST_PER_SECOND", "1400");
        assertThatThrownBy(() -> LoadConfig.fromEnv(env))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("LOAD_BURST_SIZE")
                .hasMessageContaining("LOAD_BURST_PER_SECOND");
        env.put("LOAD_BURST_PER_SECOND", "1500");
        assertThat(LoadConfig.fromEnv(env).burstSize()).isEqualTo(30000);
    }
}
