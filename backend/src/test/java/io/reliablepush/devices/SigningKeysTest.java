package io.reliablepush.devices;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.reliablepush.apps.ReliablePushProperties;
import io.reliablepush.support.TestKeys;
import java.security.KeyPair;
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
    void loadsKeyPairsFromEnvRefsAndSelectsCurrent() {
        SigningKeys.PemPair p1 = TestKeys.newEcPemPair();
        SigningKeys.PemPair p2 = TestKeys.newEcPemPair();
        runner.withPropertyValues(
                        "reliable-push.device-token.signing-keys[0].kid=k1",
                        "reliable-push.device-token.signing-keys[0].private-key-pem-ref=env:RP_TEST_K1",
                        "reliable-push.device-token.signing-keys[0].public-key-pem-ref=env:RP_TEST_P1",
                        "reliable-push.device-token.signing-keys[1].kid=k2",
                        "reliable-push.device-token.signing-keys[1].private-key-pem-ref=env:RP_TEST_K2",
                        "reliable-push.device-token.signing-keys[1].public-key-pem-ref=env:RP_TEST_P2",
                        "reliable-push.device-token.current-kid=k2",
                        "RP_TEST_K1=" + p1.privateKeyPem(),
                        "RP_TEST_P1=" + p1.publicKeyPem(),
                        "RP_TEST_K2=" + p2.privateKeyPem(),
                        "RP_TEST_P2=" + p2.publicKeyPem())
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
    void missingPublicKeyEnvVarFailsStartup() {
        SigningKeys.PemPair p1 = TestKeys.newEcPemPair();
        runner.withPropertyValues(
                        "reliable-push.device-token.signing-keys[0].kid=k1",
                        "reliable-push.device-token.signing-keys[0].private-key-pem-ref=env:RP_TEST_K1",
                        "reliable-push.device-token.signing-keys[0].public-key-pem-ref=env:RP_TEST_P1_UNSET",
                        "reliable-push.device-token.current-kid=k1",
                        "RP_TEST_K1=" + p1.privateKeyPem())
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(rootMessage(context.getStartupFailure())).contains("RP_TEST_P1_UNSET").doesNotContain("BEGIN");
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
        SigningKeys.PemPair p1 = TestKeys.newEcPemPair();
        runner.withPropertyValues(
                        "reliable-push.device-token.signing-keys[0].kid=k1",
                        "reliable-push.device-token.signing-keys[0].private-key-pem-ref=env:RP_TEST_K1",
                        "reliable-push.device-token.signing-keys[0].public-key-pem-ref=env:RP_TEST_P1",
                        "reliable-push.device-token.current-kid=k9",
                        "RP_TEST_K1=" + p1.privateKeyPem(),
                        "RP_TEST_P1=" + p1.publicKeyPem())
                .run(context -> assertThat(context).hasFailed());
        assertThatThrownBy(() -> SigningKeys.fromPem("k9", Map.of("k1", p1)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("current-kid");
    }

    @Test
    void publicKeyIsTheConfiguredOne() {
        for (int i = 0; i < 3; i++) {
            KeyPair pair = TestKeys.newEcKeyPair();
            SigningKeys keys = SigningKeys.fromPem("k1", Map.of("k1", TestKeys.pemPair(pair)));

            assertThat(keys.current().publicKey().getEncoded()).isEqualTo(pair.getPublic().getEncoded());
            assertThat(keys.current().privateKey().getEncoded()).isEqualTo(pair.getPrivate().getEncoded());
        }
    }

    @Test
    void rejectsNonP256KeyPairWithoutLeakingMaterial() {
        SigningKeys.PemPair p384 = TestKeys.newP384PemPair();
        String privateBody = body(p384.privateKeyPem());
        String publicBody = body(p384.publicKeyPem());

        assertThatThrownBy(() -> SigningKeys.fromPem("k1", Map.of("k1", p384)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("k1")
                .hasMessageContaining("P-256")
                .satisfies(e -> assertThat(e.getMessage()).doesNotContain(privateBody).doesNotContain(publicBody));
    }

    @Test
    void rejectsP256PrivateKeyWithP384PublicKey() {
        SigningKeys.PemPair good = TestKeys.newEcPemPair();
        SigningKeys.PemPair p384 = TestKeys.newP384PemPair();

        assertThatThrownBy(() -> SigningKeys.fromPem("k1", Map.of("k1", new SigningKeys.PemPair(good.privateKeyPem(), p384.publicKeyPem()))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("P-256");
    }

    @Test
    void rejectsMismatchedPublicKeyWithoutLeakingMaterial() {
        SigningKeys.PemPair a = TestKeys.newEcPemPair();
        SigningKeys.PemPair b = TestKeys.newEcPemPair();

        assertThatThrownBy(() -> SigningKeys.fromPem("k1", Map.of("k1", new SigningKeys.PemPair(a.privateKeyPem(), b.publicKeyPem()))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("k1")
                .hasMessageContaining("does not match")
                .satisfies(e -> {
                    assertThat(e.getMessage()).doesNotContain(body(a.privateKeyPem())).doesNotContain(body(b.publicKeyPem()));
                    assertThat(e.getCause()).isNull();
                });
    }

    @Test
    void rejectsGarbageWithoutLeakingContent() {
        SigningKeys.PemPair good = TestKeys.newEcPemPair();
        String garbage = "-----BEGIN PRIVATE KEY-----\nc2VjcmV0LW1hdGVyaWFs\n-----END PRIVATE KEY-----\n";
        String garbagePublic = "-----BEGIN PUBLIC KEY-----\nc2VjcmV0LW1hdGVyaWFs\n-----END PUBLIC KEY-----\n";

        assertThatThrownBy(() -> SigningKeys.fromPem("k1", Map.of("k1", new SigningKeys.PemPair(garbage, good.publicKeyPem()))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("k1")
                .satisfies(e -> {
                    assertThat(e.getMessage()).doesNotContain("c2VjcmV0");
                    assertThat(e.getCause()).isNull();
                });
        assertThatThrownBy(() -> SigningKeys.fromPem("k1", Map.of("k1", new SigningKeys.PemPair(good.privateKeyPem(), garbagePublic))))
                .isInstanceOf(IllegalStateException.class)
                .satisfies(e -> {
                    assertThat(e.getMessage()).contains("public-key-pem-ref").doesNotContain("c2VjcmV0");
                    assertThat(e.getCause()).isNull();
                });
        assertThatThrownBy(() -> SigningKeys.fromPem("k1", Map.of("k1", new SigningKeys.PemPair("not a pem at all", good.publicKeyPem()))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageNotContaining("not a pem at all");
    }

    @Test
    void rejectsSwappedAndSec1Pem() {
        SigningKeys.PemPair good = TestKeys.newEcPemPair();
        String sec1 = "-----BEGIN EC PRIVATE KEY-----\nMHcCAQEEIA==\n-----END EC PRIVATE KEY-----\n";

        // public PEM where the private key belongs, and vice versa
        assertThatThrownBy(() -> SigningKeys.fromPem("k1", Map.of("k1", new SigningKeys.PemPair(good.publicKeyPem(), good.privateKeyPem()))))
                .isInstanceOf(IllegalStateException.class);
        // SEC1 is not supported any more: PKCS#8 only
        assertThatThrownBy(() -> SigningKeys.fromPem("k1", Map.of("k1", new SigningKeys.PemPair(sec1, good.publicKeyPem()))))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void toStringShowsKidsOnly() {
        SigningKeys.PemPair pair = TestKeys.newEcPemPair();
        SigningKeys keys = SigningKeys.fromPem("k1", Map.of("k1", pair));

        assertThat(keys.toString()).contains("k1").doesNotContain("PRIVATE").doesNotContain(body(pair.privateKeyPem()));
        assertThat(pair.toString()).doesNotContain(body(pair.privateKeyPem())).doesNotContain(body(pair.publicKeyPem()));
        assertThat(keys.current().toString()).doesNotContain(body(pair.privateKeyPem()));
    }

    private static String body(String pem) {
        return pem.lines().filter(l -> !l.startsWith("-----")).findFirst().orElseThrow();
    }

    private static String rootMessage(Throwable failure) {
        Throwable t = failure;
        StringBuilder all = new StringBuilder();
        while (t != null) {
            all.append(t.getMessage()).append('\n');
            t = t.getCause();
        }
        return all.toString();
    }
}
