package io.reliablepush.apps;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;

class AppRegistryTest {

    private AppRegistry registryWith(Map<String, Object> properties) {
        return new AppRegistry(AppFixtures.bind(properties), AppFixtures.environment());
    }

    @Test
    void findsAppBySenderKey() {
        AppRegistry registry = registryWith(AppFixtures.minimalApp("shop", "k-123"));

        assertThat(registry.bySenderApiKey("k-123")).hasValueSatisfying(app -> assertThat(app.appId()).isEqualTo("shop"));
        assertThat(registry.bySenderApiKey("wrong")).isEmpty();
        assertThat(registry.bySenderApiKey("")).isEmpty();
        assertThat(registry.bySenderApiKey(null)).isEmpty();
    }

    @Test
    void picksTheRightAppAmongSeveral() {
        Map<String, Object> properties = AppFixtures.minimalApp("shop", "k-shop");
        properties.putAll(AppFixtures.minimalApp("bank", "k-bank"));
        AppRegistry registry = registryWith(properties);

        assertThat(registry.bySenderApiKey("k-bank")).hasValueSatisfying(app -> assertThat(app.appId()).isEqualTo("bank"));
        assertThat(registry.bySenderApiKey("k-shop")).hasValueSatisfying(app -> assertThat(app.appId()).isEqualTo("shop"));
        assertThat(registry.all()).extracting(AppConfig::appId).containsExactlyInAnyOrder("shop", "bank");
    }

    @Test
    void findsAppById() {
        AppRegistry registry = registryWith(AppFixtures.minimalApp("shop", "k-123"));

        assertThat(registry.byId("shop")).isPresent();
        assertThat(registry.byId("nope")).isEmpty();
        assertThat(registry.byId(null)).isEmpty();
    }

    @Test
    void emptyWithoutConfiguredApps() {
        AppRegistry registry = registryWith(Map.of("reliable-push.retention-days", "30"));

        assertThat(registry.all()).isEmpty();
        assertThat(registry.bySenderApiKey("anything")).isEmpty();
    }

    @Test
    void resolvedSecretsNeverShowUpInToString() {
        AppRegistry registry = registryWith(AppFixtures.minimalApp("shop", "k-123"));

        AppConfig app = registry.byId("shop").orElseThrow();

        assertThat(app.fallback().hmacSecret().value()).isEqualTo("generated-test-hook-secret");
        assertThat(app.toString()).doesNotContain("generated-test-hook-secret");
    }
}
