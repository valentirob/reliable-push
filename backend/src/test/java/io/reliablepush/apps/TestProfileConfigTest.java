package io.reliablepush.apps;

import static org.assertj.core.api.Assertions.assertThat;

import io.reliablepush.devices.SigningKeys;
import io.reliablepush.support.TestKeys;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/** Guards the shared test configuration (application-test.yml) that later integration tests rely on. */
class TestProfileConfigTest {

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(ReliablePushProperties.class)
    @Import({AppRegistry.class, SigningKeys.class})
    static class Config {}

    @Test
    void shopAppIsAvailableUnderTestProfile() {
        new ApplicationContextRunner()
                .withUserConfiguration(Config.class)
                .withInitializer(new ConfigDataApplicationContextInitializer())
                .withPropertyValues("spring.profiles.active=test", "RP_TEST_DEVICE_KEY_K1=" + TestKeys.newEcPem())
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    AppConfig shop = context.getBean(AppRegistry.class).bySenderApiKey("test-sender-key-shop").orElseThrow();
                    assertThat(shop.appId()).isEqualTo("shop");
                    assertThat(shop.fallback().hmacSecret().value()).isEqualTo("test-only-hook-secret-shop");
                    assertThat(context.getBean(SigningKeys.class).current().kid()).isEqualTo("test-k1");
                });
    }
}
