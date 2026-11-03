package io.reliablepush.devices.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import io.reliablepush.apps.AppConfig;
import io.reliablepush.apps.AppFixtures;
import io.reliablepush.apps.AppRegistry;
import io.reliablepush.shared.config.Secret;
import io.reliablepush.support.MutableClock;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class IdentityVerifiersTest {

    private static final Instant T0 = Instant.parse("2026-11-03T10:00:00Z");
    private static final String GRANT_ENV = "RP_TEST_GRANT_SECRET";
    private final MutableClock clock = new MutableClock(T0);
    private final String grantSecret = UUID.randomUUID() + "-" + UUID.randomUUID();

    private AppRegistry registry(String grantSecretValue) {
        Map<String, Object> props = new HashMap<>(AppFixtures.minimalApp("shop", "sender-key-shop"));
        props.putAll(AppFixtures.minimalApp("partner", "sender-key-partner"));
        props.put("reliable-push.apps.partner.identity.type", "signed-grant");
        props.put("reliable-push.apps.partner.identity.grant-secret-ref", "env:" + GRANT_ENV);
        MockEnvironment env = AppFixtures.environment().withProperty(GRANT_ENV, grantSecretValue);
        return new AppRegistry(AppFixtures.bind(props), env);
    }

    @Test
    void oneVerifierPerAppOfTheConfiguredType() {
        AppRegistry registry = registry(grantSecret);
        IdentityVerifiers verifiers = new IdentityVerifiers(registry, clock);
        AppConfig shop = registry.byId("shop").orElseThrow();
        AppConfig partner = registry.byId("partner").orElseThrow();

        IdentityVerifier shopVerifier = verifiers.forApp(shop);
        IdentityVerifier partnerVerifier = verifiers.forApp(partner);

        assertThat(shopVerifier).isInstanceOf(OidcIdTokenVerifier.class).isSameAs(verifiers.forApp(shop));
        assertThat(partnerVerifier).isInstanceOf(SignedGrantVerifier.class).isSameAs(verifiers.forApp(partner));
    }

    @Test
    void signedGrantAppVerifiesGrantsEndToEnd() throws Exception {
        AppRegistry registry = registry(grantSecret);
        IdentityVerifiers verifiers = new IdentityVerifiers(registry, clock);
        SignedJWT jwt = new SignedJWT(
                new JWSHeader(JWSAlgorithm.HS256),
                new JWTClaimsSet.Builder()
                        .audience("partner")
                        .subject("user-7")
                        .issueTime(Date.from(T0))
                        .expirationTime(Date.from(T0.plusSeconds(60)))
                        .build());
        jwt.sign(new MACSigner(grantSecret.getBytes(StandardCharsets.UTF_8)));

        assertThat(verifiers.forApp(registry.byId("partner").orElseThrow()).verify(jwt.serialize()).userId())
                .isEqualTo("user-7");
    }

    @Test
    void shortGrantSecretFailsStartup() {
        AppRegistry registry = registry("too-short");

        assertThatThrownBy(() -> new IdentityVerifiers(registry, clock))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("partner")
                .satisfies(e -> assertThat(e.getMessage()).doesNotContain("too-short"));
    }

    @Test
    void insecureRemoteIssuerFailsStartup() {
        Map<String, Object> props = new HashMap<>(AppFixtures.minimalApp("shop", "sender-key-shop"));
        props.put("reliable-push.apps.shop.identity.issuer", "http://idp.example.org");
        AppRegistry registry = new AppRegistry(AppFixtures.bind(props), AppFixtures.environment());

        assertThatThrownBy(() -> new IdentityVerifiers(registry, clock))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("shop");
    }

    @Test
    void appNotInTheRegistryGetsALazilyCachedVerifier() {
        IdentityVerifiers verifiers = new IdentityVerifiers(registry(grantSecret), clock);
        AppConfig adHoc = new AppConfig(
                "adhoc", "0".repeat(64),
                new AppConfig.Identity(
                        io.reliablepush.apps.ReliablePushProperties.IdentityType.SIGNED_GRANT,
                        "https://idp.test.example", "adhoc-mobile", "sub", null, new Secret("y".repeat(40))),
                null, "NOT_DELIVERED", 10, null, null);

        assertThat(verifiers.forApp(adHoc)).isSameAs(verifiers.forApp(adHoc));
    }

    @Test
    void concurrentCallersShareOneVerifier() throws Exception {
        IdentityVerifiers verifiers = new IdentityVerifiers(registry(grantSecret), clock);
        AppConfig shop = registry(grantSecret).byId("shop").orElseThrow();
        try (ExecutorService pool = Executors.newFixedThreadPool(16)) {
            List<Callable<IdentityVerifier>> calls = java.util.stream.IntStream.range(0, 64)
                    .<Callable<IdentityVerifier>>mapToObj(i -> () -> verifiers.forApp(shop))
                    .toList();
            Set<IdentityVerifier> distinct = new java.util.HashSet<>();
            for (Future<IdentityVerifier> f : pool.invokeAll(calls)) {
                distinct.add(f.get());
            }
            assertThat(distinct).hasSize(1);
        }
    }
}
