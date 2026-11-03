package io.reliablepush.devices;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.reliablepush.apps.ReliablePushProperties;
import io.reliablepush.support.TestKeys;
import java.time.Clock;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

class SigningKeysTest {

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(ReliablePushProperties.class)
    @Import({SigningKeys.class, DeviceTokenService.class})
    static class Config {
        @org.springframework.context.annotation.Bean
        Clock clock() {
            return Clock.systemUTC();
        }
    }

    private final ApplicationContextRunner runner = new ApplicationContextRunner().withUserConfiguration(Config.class);

    @Test
    void loadsKeysFromEnvRefsAndSelectsCurrent() {
        runner.withPropertyValues(
                        "reliable-push.device-token.signing-keys[0].kid=k1",
                        "reliable-push.device-token.signing-keys[0].private-key-pem-ref=env:RP_TEST_K1",
                        "reliable-push.device-token.signing-keys[1].kid=k2",
                        "reliable-push.device-token.signing-keys[1].private-key-pem-ref=env:RP_TEST_K2",
                        "reliable-push.device-token.current-kid=k2",
                        "RP_TEST_K1=" + TestKeys.newEcPem(),
                        "RP_TEST_K2=" + TestKeys.newEcPem())
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    SigningKeys keys = context.getBean(SigningKeys.class);
                    assertThat(keys.current().kid()).isEqualTo("k2");
                    assertThat(keys.byKid("k1")).isPresent();
                    assertThat(keys.byKid("k2")).isPresent();
                    assertThat(keys.byKid("k3")).isEmpty();
                    assertThat(keys.byKid(null)).isEmpty();
                });
    }

    @Test
    void noKeysConfiguredStartsButCannotIssue() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(SigningKeys.class).isConfigured()).isFalse();
            assertThatThrownBy(() -> context.getBean(SigningKeys.class).current())
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("device-token signing keys are not configured");
        });
    }

    @Test
    void currentKidMustBeConfigured() {
        runner.withPropertyValues(
                        "reliable-push.device-token.signing-keys[0].kid=k1",
                        "reliable-push.device-token.signing-keys[0].private-key-pem-ref=env:RP_TEST_K1",
                        "reliable-push.device-token.current-kid=k9",
                        "RP_TEST_K1=" + TestKeys.newEcPem())
                .run(context -> assertThat(context).hasFailed());
        assertThatThrownBy(() -> SigningKeys.fromPem("k9", Map.of("k1", TestKeys.newEcPem())))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("current-kid");
    }

    @Test
    void acceptsSec1Pem() {
        SigningKeys keys = SigningKeys.fromPem("k1", Map.of("k1", TestKeys.newSec1Pem()));

        DeviceTokenService service = new DeviceTokenService(keys, Clock.systemUTC());
        java.time.Instant now = Clock.systemUTC().instant();
        DeviceClaims claims = new DeviceClaims("shop", "u", java.util.UUID.randomUUID(), 0, now.truncatedTo(java.time.temporal.ChronoUnit.SECONDS),
                now.plusSeconds(60).truncatedTo(java.time.temporal.ChronoUnit.SECONDS));
        assertThat(service.verify(service.issue(claims).token())).isEqualTo(claims);
    }

    @Test
    void rejectsNonP256KeyWithoutLeakingMaterial() {
        String p384 = TestKeys.newP384Pem();
        String body = p384.lines().filter(l -> !l.startsWith("-----")).findFirst().orElseThrow();

        assertThatThrownBy(() -> SigningKeys.fromPem("k1", Map.of("k1", p384)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("k1")
                .hasMessageContaining("P-256")
                .satisfies(e -> assertThat(e.getMessage()).doesNotContain(body));
    }

    @Test
    void rejectsGarbageWithoutLeakingContent() {
        String garbage = "-----BEGIN PRIVATE KEY-----\nc2VjcmV0LW1hdGVyaWFs\n-----END PRIVATE KEY-----\n";

        assertThatThrownBy(() -> SigningKeys.fromPem("k1", Map.of("k1", garbage)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("k1")
                .satisfies(e -> {
                    assertThat(e.getMessage()).doesNotContain("c2VjcmV0");
                    assertThat(e.getCause()).isNull();
                });
        assertThatThrownBy(() -> SigningKeys.fromPem("k1", Map.of("k1", "not a pem at all")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageNotContaining("not a pem at all");
    }

    @Test
    void derivedPublicKeyMatchesTheGeneratedOne() {
        for (int i = 0; i < 5; i++) {
            java.security.KeyPair pair = TestKeys.newEcKeyPair();
            SigningKeys keys = SigningKeys.fromPem("k1", Map.of("k1", TestKeys.pkcs8Pem(pair)));

            assertThat(keys.current().publicKey().getEncoded()).isEqualTo(pair.getPublic().getEncoded());
        }
    }

    @Test
    void rejectsPublicKeyPem() {
        String pub = "-----BEGIN PUBLIC KEY-----\nMFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAE\n-----END PUBLIC KEY-----\n";

        assertThatThrownBy(() -> SigningKeys.fromPem("k1", Map.of("k1", pub))).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void toStringShowsKidsOnly() {
        String pem = TestKeys.newEcPem();
        SigningKeys keys = SigningKeys.fromPem("k1", Map.of("k1", pem));

        assertThat(keys.toString()).contains("k1").doesNotContain("PRIVATE").doesNotContain(pem.lines().skip(1).findFirst().orElseThrow());
    }
}
