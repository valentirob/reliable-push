package io.reliablepush.devices.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import io.reliablepush.shared.config.Secret;
import io.reliablepush.support.MutableClock;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class SignedGrantVerifierTest {

    private static final Instant T0 = Instant.parse("2026-11-03T10:00:00Z");
    private static final String APP = "shop";

    /** Generated per run; never a real secret. */
    private final String secret = UUID.randomUUID() + "-" + UUID.randomUUID();
    private MutableClock clock;
    private SignedGrantVerifier verifier;

    @BeforeEach
    void setUp() {
        clock = new MutableClock(T0);
        verifier = new SignedGrantVerifier(APP, new Secret(secret), clock);
    }

    private JWTClaimsSet.Builder claims() {
        return new JWTClaimsSet.Builder()
                .audience(APP)
                .subject("user-42")
                .issueTime(Date.from(T0))
                .expirationTime(Date.from(T0.plus(Duration.ofMinutes(2))));
    }

    private String sign(JWTClaimsSet claims) {
        return sign(secret, JWSAlgorithm.HS256, claims);
    }

    private static String sign(String key, JWSAlgorithm alg, JWTClaimsSet claims) {
        try {
            SignedJWT jwt = new SignedJWT(new JWSHeader(alg), claims);
            jwt.sign(new MACSigner(key.getBytes(StandardCharsets.UTF_8)));
            return jwt.serialize();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** Signs a raw JSON payload (nimbus' claims builder normalises odd claim shapes away). */
    private String signRaw(String json) {
        try {
            JWSHeader header = new JWSHeader(JWSAlgorithm.HS256);
            com.nimbusds.jose.JWSObject jws = new com.nimbusds.jose.JWSObject(header, new com.nimbusds.jose.Payload(json));
            jws.sign(new MACSigner(secret.getBytes(StandardCharsets.UTF_8)));
            return jws.serialize();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private String reasonOf(String token) {
        return assertThatThrownBy(() -> verifier.verify(token))
                .isInstanceOf(IdentityRejectedException.class)
                .actual()
                .getMessage();
    }

    // ---- brief tests ----

    @Test
    void validGrant() {
        assertThat(verifier.verify(sign(claims().build())).userId()).isEqualTo("user-42");
    }

    @Test
    void lifetimeOver5MinRejected() {
        String token = sign(claims().expirationTime(Date.from(T0.plusSeconds(301))).build());

        assertThat(reasonOf(token)).isEqualTo("lifetime_too_long");
    }

    @Test
    void wrongSecretRejected() {
        String token = sign(UUID.randomUUID() + "-" + UUID.randomUUID(), JWSAlgorithm.HS256, claims().build());

        assertThat(reasonOf(token)).isEqualTo("bad_signature");
    }

    // ---- more ----

    @Test
    void exactlyFiveMinutesLifetimeAccepted() {
        String token = sign(claims().expirationTime(Date.from(T0.plusSeconds(300))).build());

        assertThat(verifier.verify(token).userId()).isEqualTo("user-42");
    }

    @Test
    void lifetimeMustBePositive() {
        assertThat(reasonOf(sign(claims().expirationTime(Date.from(T0)).build()))).isEqualTo("malformed");
        assertThat(reasonOf(sign(claims().expirationTime(Date.from(T0.minusSeconds(5))).build())))
                .isEqualTo("malformed");
    }

    @Test
    void expiredGrantRejectedAfterSkew() {
        String token = sign(claims().build()); // exp = T0 + 2 min

        clock.set(T0.plusSeconds(120 + 60));
        assertThat(verifier.verify(token).userId()).isEqualTo("user-42");

        clock.set(T0.plusSeconds(120 + 61));
        assertThat(reasonOf(token)).isEqualTo("expired");
    }

    @Test
    void grantIssuedInTheFutureRejected() {
        String soon = sign(claims()
                .issueTime(Date.from(T0.plusSeconds(60)))
                .expirationTime(Date.from(T0.plusSeconds(120)))
                .build());
        String later = sign(claims()
                .issueTime(Date.from(T0.plusSeconds(61)))
                .expirationTime(Date.from(T0.plusSeconds(120)))
                .build());

        assertThat(verifier.verify(soon).userId()).isEqualTo("user-42");
        assertThat(reasonOf(later)).isEqualTo("not_yet_valid");
    }

    @Test
    void iatAndExpAreRequiredNumbers() {
        assertThat(reasonOf(sign(new JWTClaimsSet.Builder()
                        .audience(APP)
                        .subject("u")
                        .expirationTime(Date.from(T0.plusSeconds(60)))
                        .build())))
                .isEqualTo("malformed");
        assertThat(reasonOf(sign(new JWTClaimsSet.Builder()
                        .audience(APP)
                        .subject("u")
                        .issueTime(Date.from(T0))
                        .build())))
                .isEqualTo("malformed");
        assertThat(reasonOf(sign(claims().claim("iat", "now").build()))).isEqualTo("malformed");
        assertThat(reasonOf(sign(claims().claim("exp", "later").build()))).isEqualTo("malformed");
    }

    @Test
    void audienceMustBeExactlyTheAppId() {
        assertThat(reasonOf(sign(claims().audience("other-app").build()))).isEqualTo("wrong_audience");
        assertThat(reasonOf(sign(claims().audience("SHOP").build()))).isEqualTo("wrong_audience");
        assertThat(reasonOf(sign(claims().audience(List.of(APP, "other")).build()))).isEqualTo("wrong_audience");
        assertThat(reasonOf(sign(claims().audience((String) null).build()))).isEqualTo("wrong_audience");
        assertThat(reasonOf(signRaw("{\"aud\":7,\"sub\":\"u\",\"iat\":" + T0.getEpochSecond() + ",\"exp\":"
                        + T0.plusSeconds(60).getEpochSecond() + "}")))
                .isEqualTo("malformed");
        assertThat(verifier.verify(sign(claims().audience(List.of(APP)).build())).userId()).isEqualTo("user-42");
    }

    @Test
    void subjectMustBeANonBlankStringOfAtMost256Chars() {
        for (Object bad : new Object[] {42, true, List.of("a"), Map.of("a", "b"), "", "  ", "x".repeat(257)}) {
            assertThat(reasonOf(sign(claims().claim("sub", bad).build()))).isEqualTo("missing_user_claim");
        }
        assertThat(reasonOf(sign(new JWTClaimsSet.Builder()
                        .audience(APP)
                        .issueTime(Date.from(T0))
                        .expirationTime(Date.from(T0.plusSeconds(60)))
                        .build())))
                .isEqualTo("missing_user_claim");
        String max = "x".repeat(256);
        assertThat(verifier.verify(sign(claims().subject(max).build())).userId()).isEqualTo(max);
    }

    @ParameterizedTest
    @ValueSource(strings = {"HS384", "HS512"})
    void onlyHs256Accepted(String alg) {
        String token = sign(secret, JWSAlgorithm.parse(alg), claims().build());

        assertThat(reasonOf(token)).isEqualTo("malformed");
    }

    @Test
    void algNoneAndAsymmetricAlgorithmsRejected() {
        String payload = b64("{\"aud\":\"shop\",\"sub\":\"admin\",\"iat\":" + T0.getEpochSecond() + ",\"exp\":"
                + T0.plusSeconds(60).getEpochSecond() + "}");

        assertThat(reasonOf(b64("{\"alg\":\"none\"}") + "." + payload + ".")).isEqualTo("malformed");
        assertThat(reasonOf(b64("{\"alg\":\"RS256\"}") + "." + payload + "." + b64("sig"))).isEqualTo("malformed");
        assertThat(reasonOf(b64("{\"alg\":\"ES256\"}") + "." + payload + "." + b64("sig"))).isEqualTo("malformed");
    }

    @Test
    void tamperedPayloadRejected() {
        String[] parts = sign(claims().build()).split("\\.");
        String tampered = b64(new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8)
                .replace("user-42", "admin"));

        assertThat(reasonOf(parts[0] + "." + tampered + "." + parts[2])).isEqualTo("bad_signature");
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   ", "abc", "a.b", "a.b.c.d", "e30.e30.e30"})
    void malformedRejected(String token) {
        assertThat(reasonOf(token)).isEqualTo("malformed");
    }

    @Test
    void nullAndOversizedRejected() {
        assertThat(reasonOf(null)).isEqualTo("malformed");
        assertThat(reasonOf("x".repeat(10_000))).isEqualTo("malformed");
    }

    @Test
    void secretShorterThan32BytesFailsStartupWithoutLeakingIt() {
        String shortSecret = "short-secret-0123456789012345"; // 29 bytes

        assertThatThrownBy(() -> new SignedGrantVerifier(APP, new Secret(shortSecret), clock))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("32")
                .hasMessageContaining(APP)
                .satisfies(e -> assertThat(e.getMessage()).doesNotContain(shortSecret));
        new SignedGrantVerifier(APP, new Secret("x".repeat(32)), clock);
    }

    @Test
    void multiByteSecretLengthCountsBytesNotChars() {
        // 16 two-byte characters = 32 bytes
        new SignedGrantVerifier(APP, new Secret("é".repeat(16)), clock);
        assertThatThrownBy(() -> new SignedGrantVerifier(APP, new Secret("é".repeat(15)), clock))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void rejectionNeverCarriesTokenOrClaims() {
        String token = sign(claims().subject("secret-user-id").audience("other").build());

        assertThatThrownBy(() -> verifier.verify(token))
                .isInstanceOf(IdentityRejectedException.class)
                .hasMessage("wrong_audience")
                .hasNoCause()
                .satisfies(e -> assertThat(e.toString()).doesNotContain("secret-user-id").doesNotContain(token));
    }

    private static String b64(String text) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(text.getBytes(StandardCharsets.UTF_8));
    }
}
