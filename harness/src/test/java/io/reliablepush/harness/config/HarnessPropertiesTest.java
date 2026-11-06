package io.reliablepush.harness.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import java.net.URI;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;

class HarnessPropertiesTest {

    private static final String KEY = "test-sender-key-7f3a91c2d4e8";
    private static final String GRANT = "test-grant-secret-0b5d6e1a9c37f284";
    private static final String HMAC = "test-hmac-secret-4c8e2a7d1f6b9035";

    private static HarnessApp app() {
        return new HarnessApp(KEY, GRANT, HMAC, "https://shop.test.invalid");
    }

    private static HarnessProperties props(Map<String, HarnessApp> apps) {
        return new HarnessProperties(URI.create("http://localhost:8080"), Path.of("/tmp/rp-logs"), apps);
    }

    @Test
    void secretsRedactedInToString() {
        HarnessProperties props = props(Map.of("shop", app()));

        assertThat(app().toString()).doesNotContain(KEY, GRANT, HMAC).contains("***");
        assertThat(props.toString()).doesNotContain(KEY, GRANT, HMAC).contains("***", "shop");
    }

    @Test
    void unknownAppThrows() {
        HarnessProperties props = props(Map.of("shop", app()));

        assertThat(props.app("shop")).isEqualTo(app());
        assertThatThrownBy(() -> props.app("nope")).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("nope");
    }

    @Test
    void deviceProxyBindsLoopbackUnlessConfigured() {
        assertThat(props(Map.of("shop", app())).proxyBindAddress()).isEqualTo("127.0.0.1");
        HarnessProperties blank = new HarnessProperties(URI.create("http://localhost:8080"), Path.of("/tmp/rp-logs"),
                Map.of("shop", app()), null, null, null, " ");
        assertThat(blank.proxyBindAddress()).isEqualTo("127.0.0.1");
        HarnessProperties all = new HarnessProperties(URI.create("http://localhost:8080"), Path.of("/tmp/rp-logs"),
                Map.of("shop", app()), null, null, null, "0.0.0.0");
        assertThat(all.proxyBindAddress()).isEqualTo("0.0.0.0");
    }

    @Test
    void validationRejectsBlankValuesAndMissingApps() {
        Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

        assertThat(validator.validate(props(Map.of("shop", app())))).isEmpty();
        assertThat(validator.validate(props(Map.of()))).isNotEmpty();
        assertThat(validator.validate(props(Map.of("shop", new HarnessApp(" ", GRANT, HMAC, "iss"))))).isNotEmpty();
        assertThat(validator.validate(props(Map.of("shop", new HarnessApp(KEY, GRANT, HMAC, ""))))).isNotEmpty();
        assertThat(validator.validate(new HarnessProperties(null, Path.of("/tmp"), Map.of("shop", app())))).isNotEmpty();
    }
}
