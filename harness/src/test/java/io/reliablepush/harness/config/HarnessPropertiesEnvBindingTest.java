package io.reliablepush.harness.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;

/** Pins the environment-variable names documented in application.yml (and used by the compose/GenSecrets tasks). */
class HarnessPropertiesEnvBindingTest {

    @Test
    void documentedEnvNamesBind() {
        assertBinds("SENDERAPIKEY", "GRANTSECRET", "HMACSECRET", "GRANTISSUER");
    }

    /** Underscored spellings bind too (verified empirically); the dash-less form stays the documented one. */
    @Test
    void underscoredEnvNamesBindToo() {
        assertBinds("SENDER_API_KEY", "GRANT_SECRET", "HMAC_SECRET", "GRANT_ISSUER");
    }

    private static void assertBinds(String key, String grant, String hmac, String issuer) {
        new ApplicationContextRunner()
                .withInitializer(ctx -> ctx.getEnvironment().getPropertySources().addFirst(
                        new SystemEnvironmentPropertySource(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
                                Map.of("HARNESS_BACKENDURL", "http://backend:8080",
                                        "HARNESS_DEVICEPROXYTARGET", "http://api:8080",
                                        "HARNESS_DEVICEPROXYPORT", "8191",
                                        "HARNESS_DEVICEPROXYBIND", "0.0.0.0",
                                        "HARNESS_BACKENDLOGDIR", "/var/rp-logs",
                                        "HARNESS_APPS_SHOP_" + key, "k",
                                        "HARNESS_APPS_SHOP_" + grant, "g",
                                        "HARNESS_APPS_SHOP_" + hmac, "h",
                                        "HARNESS_APPS_SHOP_" + issuer, "https://shop.invalid"))))
                .withUserConfiguration(Config.class)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    HarnessProperties props = context.getBean(HarnessProperties.class);
                    assertThat(props.backendUrl()).isEqualTo(URI.create("http://backend:8080"));
                    assertThat(props.deviceProxyTarget()).isEqualTo(URI.create("http://api:8080"));
                    assertThat(props.proxyListenPort()).isEqualTo(8191);
                    assertThat(props.proxyBindAddress()).isEqualTo("0.0.0.0");
                    assertThat(props.backendLogDir()).isEqualTo(Path.of("/var/rp-logs"));
                    assertThat(props.app("shop")).isEqualTo(new HarnessApp("k", "g", "h", "https://shop.invalid"));
                });
    }

    @EnableConfigurationProperties(HarnessProperties.class)
    static class Config {
    }
}
