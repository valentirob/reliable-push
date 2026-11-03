package io.reliablepush.devices.identity;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import io.reliablepush.apps.AppConfig;
import io.reliablepush.apps.ReliablePushProperties.IdentityType;
import io.reliablepush.support.FakeOidc;
import io.reliablepush.support.MutableClock;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Runs against a WireMock-backed provider ({@link FakeOidc}); no database or Spring context needed. */
class OidcIdTokenVerifierIT {

    private static final String AUDIENCE = "shop-mobile";
    private static final Instant T0 = Instant.parse("2026-11-03T10:00:00Z");

    private MutableClock clock;
    private FakeOidc idp;
    private OidcIdTokenVerifier verifier;

    @BeforeEach
    void setUp() {
        clock = new MutableClock(T0);
        idp = new FakeOidc(clock);
        verifier = verifier("sub", null);
    }

    @AfterEach
    void tearDown() {
        idp.close();
    }

    private OidcIdTokenVerifier verifier(String userClaim, String jwksUri) {
        return new OidcIdTokenVerifier(identity(idp.issuer(), AUDIENCE, userClaim, jwksUri), clock);
    }

    private static AppConfig.Identity identity(String issuer, String audience, String userClaim, String jwksUri) {
        return new AppConfig.Identity(IdentityType.OIDC, issuer, audience, userClaim, jwksUri, null);
    }

    private String reasonOf(String token) {
        return reasonOf(verifier, token);
    }

    private static String reasonOf(IdentityVerifier verifier, String token) {
        return assertThatThrownBy(() -> verifier.verify(token))
                .isInstanceOf(IdentityRejectedException.class)
                .actual()
                .getMessage();
    }

    // ---- brief tests ----

    @Test
    void validTokenYieldsSub() {
        VerifiedIdentity identity = verifier.verify(idp.idToken("user-42", AUDIENCE, Duration.ofMinutes(10)));

        assertThat(identity.userId()).isEqualTo("user-42");
    }

    @Test
    void wrongAudienceRejected() {
        assertThat(reasonOf(idp.idToken("user-42", "someone-elses-app", Duration.ofMinutes(10))))
                .isEqualTo("wrong_audience");
    }

    @Test
    void expiredRejected() {
        assertThat(reasonOf(idp.idToken("user-42", AUDIENCE, Duration.ofMinutes(-5)))).isEqualTo("expired");
    }

    @Test
    void unknownKidTriggersOneJwksRefresh() {
        verifier.verify(idp.idToken("u1", AUDIENCE, Duration.ofMinutes(10)));
        assertThat(idp.jwksRequests()).isEqualTo(1);

        idp.rotate();
        VerifiedIdentity afterRotation = verifier.verify(idp.idToken("u2", AUDIENCE, Duration.ofMinutes(10)));

        assertThat(afterRotation.userId()).isEqualTo("u2");
        assertThat(idp.jwksRequests()).isEqualTo(2);
    }

    @Test
    void customUserClaim() {
        OidcIdTokenVerifier byEmail = verifier("email", null);
        String token = idp.sign(idp.claims("opaque-sub", AUDIENCE, Duration.ofMinutes(10))
                .claim("email", "ana@example.org")
                .build());

        assertThat(byEmail.verify(token).userId()).isEqualTo("ana@example.org");
    }

    // ---- JWKS fetching, caching and refresh limits ----

    @Test
    void randomKidsCannotCauseAJwksFetchPerRequest() {
        verifier.verify(idp.idToken("u1", AUDIENCE, Duration.ofMinutes(10)));
        idp.resetRequests();

        for (int i = 0; i < 20; i++) {
            String forged = FakeOidc.sign(idp.newRsaKey(), JWSAlgorithm.RS256,
                    idp.claims("attacker", AUDIENCE, Duration.ofMinutes(10)).build());
            assertThat(reasonOf(forged)).isEqualTo("unknown_kid");
        }

        // the first unknown kid may refresh once; the other 19 are served from the rate limit
        assertThat(idp.jwksRequests()).isEqualTo(1);

        clock.advance(Duration.ofSeconds(29));
        reasonOf(FakeOidc.sign(idp.newRsaKey(), JWSAlgorithm.RS256, idp.claims("a", AUDIENCE, Duration.ofMinutes(10)).build()));
        assertThat(idp.jwksRequests()).isEqualTo(1);

        clock.advance(Duration.ofSeconds(2));
        reasonOf(FakeOidc.sign(idp.newRsaKey(), JWSAlgorithm.RS256, idp.claims("a", AUDIENCE, Duration.ofMinutes(10)).build()));
        assertThat(idp.jwksRequests()).isEqualTo(2);
    }

    @Test
    void knownKeysAreCachedForFifteenMinutes() {
        String token = idp.idToken("u1", AUDIENCE, Duration.ofHours(2));
        verifier.verify(token);

        clock.advance(Duration.ofMinutes(14));
        verifier.verify(token);
        assertThat(idp.jwksRequests()).isEqualTo(1);

        clock.advance(Duration.ofMinutes(1));
        verifier.verify(token);
        assertThat(idp.jwksRequests()).isEqualTo(2);
    }

    @Test
    void servesCachedKeysWhenRefreshFails() {
        String token = idp.idToken("u1", AUDIENCE, Duration.ofHours(2));
        verifier.verify(token);
        idp.respondToJwksWith(aResponse().withStatus(503));

        clock.advance(Duration.ofMinutes(20));

        assertThat(verifier.verify(token).userId()).isEqualTo("u1");
        // a failed refresh is not retried on every request
        verifier.verify(token);
        verifier.verify(token);
        assertThat(idp.jwksRequests()).isEqualTo(2);
    }

    @Test
    void recoversWhenTheProviderComesBack() {
        String token = idp.idToken("u1", AUDIENCE, Duration.ofHours(2));
        idp.respondToJwksWith(aResponse().withStatus(500));
        assertThat(reasonOf(token)).isEqualTo("jwks_unavailable");

        idp.restoreJwks();
        clock.advance(Duration.ofSeconds(31));

        assertThat(verifier.verify(token).userId()).isEqualTo("u1");
    }

    @Test
    void unavailableJwksIsNotRetriedPerRequest() {
        String token = idp.idToken("u1", AUDIENCE, Duration.ofHours(2));
        idp.respondToJwksWith(aResponse().withStatus(500));

        for (int i = 0; i < 5; i++) {
            assertThat(reasonOf(token)).isEqualTo("jwks_unavailable");
        }

        assertThat(idp.jwksRequests()).isEqualTo(1);
    }

    @Test
    void jwksUriSkipsDiscovery() {
        OidcIdTokenVerifier direct = verifier("sub", idp.jwksUri());

        assertThat(direct.verify(idp.idToken("u1", AUDIENCE, Duration.ofMinutes(10))).userId()).isEqualTo("u1");
        assertThat(idp.discoveryRequests()).isZero();
    }

    @Test
    void discoveryIsUsedWhenNoJwksUri() {
        verifier.verify(idp.idToken("u1", AUDIENCE, Duration.ofMinutes(10)));

        assertThat(idp.discoveryRequests()).isEqualTo(1);
    }

    @Test
    void discoveryDocumentOfAnotherIssuerIsRejected() {
        idp.respondToDiscoveryWith(aResponse()
                .withHeader("Content-Type", "application/json")
                .withBody("{\"issuer\":\"https://evil.example\",\"jwks_uri\":\"" + idp.jwksUri() + "\"}"));

        assertThat(reasonOf(idp.idToken("u1", AUDIENCE, Duration.ofMinutes(10)))).isEqualTo("jwks_unavailable");
    }

    @Test
    void discoveredJwksUriWithAnotherSchemeIsRejected() {
        idp.respondToDiscoveryWith(aResponse()
                .withHeader("Content-Type", "application/json")
                .withBody("{\"issuer\":\"" + idp.issuer() + "\",\"jwks_uri\":\"file:///etc/passwd\"}"));

        assertThat(reasonOf(idp.idToken("u1", AUDIENCE, Duration.ofMinutes(10)))).isEqualTo("jwks_unavailable");
    }

    @Test
    void oversizedJwksIsRejected() {
        idp.respondToJwksWith(aResponse().withBody("{\"keys\":[],\"pad\":\"" + "x".repeat(70_000) + "\"}"));

        assertThat(reasonOf(idp.idToken("u1", AUDIENCE, Duration.ofMinutes(10)))).isEqualTo("jwks_unavailable");
    }

    @Test
    void redirectsAreNotFollowed() {
        idp.respondToJwksWith(aResponse().withStatus(302).withHeader("Location", "http://localhost:1/jwks"));

        assertThat(reasonOf(idp.idToken("u1", AUDIENCE, Duration.ofMinutes(10)))).isEqualTo("jwks_unavailable");
    }

    @Test
    void slowJwksTimesOutAfterFiveSeconds() {
        idp.respondToJwksWith(aResponse().withFixedDelay(7_000).withBody("{\"keys\":[]}"));
        long start = System.nanoTime();

        assertThat(reasonOf(idp.idToken("u1", AUDIENCE, Duration.ofMinutes(10)))).isEqualTo("jwks_unavailable");

        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(6));
    }

    @Test
    void garbageJwksIsUnavailable() {
        idp.respondToJwksWith(aResponse().withBody("<html>not json</html>"));

        assertThat(reasonOf(idp.idToken("u1", AUDIENCE, Duration.ofMinutes(10)))).isEqualTo("jwks_unavailable");
    }

    // ---- algorithms, keys and kid ----

    @Test
    void es256TokensAreAccepted() {
        ECKey ec = idp.newEcKey();
        idp.publishAlso(ec);
        String token = FakeOidc.sign(ec, JWSAlgorithm.ES256, idp.claims("ana", AUDIENCE, Duration.ofMinutes(10)).build());

        assertThat(verifier.verify(token).userId()).isEqualTo("ana");
    }

    @Test
    void algNoneRejected() {
        String header = b64("{\"alg\":\"none\"}");
        String payload = b64("{\"iss\":\"" + idp.issuer() + "\",\"sub\":\"admin\",\"aud\":\"" + AUDIENCE + "\",\"exp\":"
                + T0.plusSeconds(600).getEpochSecond() + "}");

        assertThat(reasonOf(header + "." + payload + ".")).isEqualTo("malformed");
        assertThat(reasonOf(header + "." + payload)).isEqualTo("malformed");
    }

    @Test
    void hmacSignedWithThePublicKeyIsRejected() throws Exception {
        // classic algorithm confusion: HS256 keyed with the (public) RSA modulus bytes
        RSAKey active = idp.activeKey();
        byte[] secret = active.toRSAPublicKey().getEncoded();
        JWTClaimsSet claims = idp.claims("admin", AUDIENCE, Duration.ofMinutes(10)).build();
        SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.HS256).keyID(active.getKeyID()).build(), claims);
        jwt.sign(new MACSigner(secret));

        assertThat(reasonOf(jwt.serialize())).isEqualTo("malformed");
    }

    @ParameterizedTest
    @ValueSource(strings = {"HS384", "HS512", "PS256", "RS384", "RS512", "ES384", "ES512"})
    void otherAlgorithmsRejected(String alg) {
        // signature content is irrelevant: the algorithm is rejected before any key lookup
        String header = b64("{\"alg\":\"" + alg + "\",\"kid\":\"" + idp.activeKey().getKeyID() + "\"}");
        String payload = b64("{\"iss\":\"" + idp.issuer() + "\",\"sub\":\"x\",\"aud\":\"" + AUDIENCE + "\",\"exp\":"
                + T0.plusSeconds(600).getEpochSecond() + "}");

        assertThat(reasonOf(header + "." + payload + "." + b64("sig"))).isEqualTo("malformed");
        assertThat(idp.jwksRequests()).isZero();
    }

    @Test
    void embeddedJwkHeaderIsIgnored() {
        // attacker signs with their own key and ships the public key in the header; only the configured JWKS counts
        RSAKey attacker = idp.newRsaKey();
        JWSHeader header = new JWSHeader.Builder(JWSAlgorithm.RS256)
                .keyID(idp.activeKey().getKeyID())
                .jwk(attacker.toPublicJWK())
                .build();
        String token = FakeOidc.sign(attacker, header, idp.claims("admin", AUDIENCE, Duration.ofMinutes(10)).build());

        assertThat(reasonOf(token)).isEqualTo("bad_signature");
    }

    @Test
    void jkuHeaderIsNeverFetched() throws Exception {
        RSAKey attacker = idp.newRsaKey();
        JWSHeader header = new JWSHeader.Builder(JWSAlgorithm.RS256)
                .keyID(attacker.getKeyID())
                .jwkURL(new java.net.URI(idp.jwksUri()))
                .build();
        String token = FakeOidc.sign(attacker, header, idp.claims("admin", AUDIENCE, Duration.ofMinutes(10)).build());

        assertThat(reasonOf(token)).isEqualTo("unknown_kid");
    }

    @Test
    void tamperedSignatureRejected() {
        String token = idp.idToken("user-42", AUDIENCE, Duration.ofMinutes(10));
        String[] parts = token.split("\\.");
        String tamperedPayload = b64(new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8)
                .replace("user-42", "admin"));

        assertThat(reasonOf(parts[0] + "." + tamperedPayload + "." + parts[2])).isEqualTo("bad_signature");
    }

    @Test
    void signatureOfAnotherKeyWithTheSameKidIsRejected() throws Exception {
        RSAKey forged = new RSAKey.Builder(idp.newRsaKey()).keyID(idp.activeKey().getKeyID()).build();

        String token = FakeOidc.sign(forged, JWSAlgorithm.RS256, idp.claims("admin", AUDIENCE, Duration.ofMinutes(10)).build());

        assertThat(reasonOf(token)).isEqualTo("bad_signature");
    }

    @Test
    void ecKeyCannotVerifyAnRs256Token() throws Exception {
        // header says RS256 but the published key under that kid is an EC key
        ECKey ec = idp.newEcKey();
        idp.publishAlso(ec);
        String ecToken = FakeOidc.sign(ec, JWSAlgorithm.ES256, idp.claims("u", AUDIENCE, Duration.ofMinutes(10)).build());
        String[] parts = ecToken.split("\\.");
        String swappedHeader = b64("{\"alg\":\"RS256\",\"kid\":\"" + ec.getKeyID() + "\"}");

        assertThat(reasonOf(swappedHeader + "." + parts[1] + "." + parts[2])).isEqualTo("bad_signature");
    }

    @Test
    void missingKidAcceptedOnlyWithASingleKeyJwks() {
        JWTClaimsSet claims = idp.claims("u1", AUDIENCE, Duration.ofMinutes(10)).build();
        String noKid = FakeOidc.sign(idp.activeKey(), new JWSHeader.Builder(JWSAlgorithm.RS256).build(), claims);

        assertThat(verifier.verify(noKid).userId()).isEqualTo("u1");

        idp.publishAlso(idp.newRsaKey());
        clock.advance(Duration.ofMinutes(16));

        assertThat(reasonOf(noKid)).isEqualTo("malformed");
    }

    @Test
    void encryptionOnlyJwksKeyIsIgnored() throws Exception {
        RSAKey encOnly = new RSAKey.Builder(idp.newRsaKey()).keyUse(com.nimbusds.jose.jwk.KeyUse.ENCRYPTION).build();
        idp.publishAlso(encOnly);
        String token = FakeOidc.sign(encOnly, JWSAlgorithm.RS256, idp.claims("u", AUDIENCE, Duration.ofMinutes(10)).build());

        assertThat(reasonOf(token)).isEqualTo("unknown_kid");
    }

    // ---- claims ----

    @Test
    void wrongIssuerRejected() {
        String token = idp.sign(idp.claims("u", AUDIENCE, Duration.ofMinutes(10)).issuer(idp.issuer() + "/").build());

        assertThat(reasonOf(token)).isEqualTo("wrong_issuer");
    }

    @Test
    void missingIssuerRejected() {
        String token = idp.sign(new JWTClaimsSet.Builder()
                .subject("u")
                .audience(AUDIENCE)
                .expirationTime(Date.from(T0.plusSeconds(600)))
                .build());

        assertThat(reasonOf(token)).isEqualTo("wrong_issuer");
    }

    @Test
    void audienceArrayContainingTheConfiguredOneIsAccepted() {
        String token = idp.sign(idp.claims("u", AUDIENCE, Duration.ofMinutes(10))
                .audience(List.of("other", AUDIENCE))
                .build());

        assertThat(verifier.verify(token).userId()).isEqualTo("u");
    }

    @Test
    void audienceArrayWithoutTheConfiguredOneIsRejected() {
        String token = idp.sign(idp.claims("u", AUDIENCE, Duration.ofMinutes(10))
                .audience(List.of("other", "another"))
                .build());

        assertThat(reasonOf(token)).isEqualTo("wrong_audience");
    }

    @Test
    void audienceMustBeTypedStrings() throws Exception {
        // raw JSON: nimbus' claims builder would normalise these shapes away
        String exp = String.valueOf(T0.plusSeconds(600).getEpochSecond());
        String base = "\"iss\":\"" + idp.issuer() + "\",\"sub\":\"u\",\"exp\":" + exp;
        assertThat(reasonOf(signedRawPayload("{" + base + ",\"aud\":42}"))).isEqualTo("malformed");
        assertThat(reasonOf(signedRawPayload("{" + base + ",\"aud\":[\"" + AUDIENCE + "\",42]}"))).isEqualTo("malformed");
        assertThat(reasonOf(signedRawPayload("{" + base + ",\"aud\":{\"x\":\"" + AUDIENCE + "\"}}"))).isEqualTo("malformed");
        assertThat(reasonOf(idp.sign(new JWTClaimsSet.Builder()
                        .issuer(idp.issuer())
                        .subject("u")
                        .expirationTime(Date.from(T0.plusSeconds(600)))
                        .build())))
                .isEqualTo("wrong_audience");
    }

    @Test
    void expiryHonoursSixtySecondSkew() {
        String atSkew = idp.idToken("u", AUDIENCE, Duration.ofSeconds(-60));
        String pastSkew = idp.idToken("u", AUDIENCE, Duration.ofSeconds(-61));

        assertThat(verifier.verify(atSkew).userId()).isEqualTo("u");
        assertThat(reasonOf(pastSkew)).isEqualTo("expired");
    }

    @Test
    void expMustBeANumber() {
        assertThat(reasonOf(idp.sign(idp.claims("u", AUDIENCE, Duration.ofMinutes(10)).claim("exp", "tomorrow").build())))
                .isEqualTo("malformed");
        assertThat(reasonOf(idp.sign(new JWTClaimsSet.Builder()
                        .issuer(idp.issuer())
                        .subject("u")
                        .audience(AUDIENCE)
                        .build())))
                .isEqualTo("malformed");
    }

    @Test
    void iatAndNbfInTheFutureBeyondSkewRejected() {
        long soon = T0.plusSeconds(60).getEpochSecond();
        long later = T0.plusSeconds(61).getEpochSecond();

        assertThat(verifier.verify(idp.sign(idp.claims("u", AUDIENCE, Duration.ofMinutes(10))
                        .claim("iat", soon)
                        .claim("nbf", soon)
                        .build()))
                .userId()).isEqualTo("u");
        assertThat(reasonOf(idp.sign(idp.claims("u", AUDIENCE, Duration.ofMinutes(10)).claim("iat", later).build())))
                .isEqualTo("not_yet_valid");
        assertThat(reasonOf(idp.sign(idp.claims("u", AUDIENCE, Duration.ofMinutes(10)).claim("nbf", later).build())))
                .isEqualTo("not_yet_valid");
        assertThat(reasonOf(idp.sign(idp.claims("u", AUDIENCE, Duration.ofMinutes(10)).claim("iat", "now").build())))
                .isEqualTo("malformed");
    }

    @Test
    void missingUserClaimRejected() {
        String token = idp.sign(new JWTClaimsSet.Builder()
                .issuer(idp.issuer())
                .audience(AUDIENCE)
                .expirationTime(Date.from(T0.plusSeconds(600)))
                .build());

        assertThat(reasonOf(token)).isEqualTo("missing_user_claim");
        assertThat(reasonOf(verifier("email", null), idp.idToken("u", AUDIENCE, Duration.ofMinutes(10))))
                .isEqualTo("missing_user_claim");
    }

    @Test
    void userClaimMustBeANonBlankStringOfAtMost256Chars() {
        for (Object bad : new Object[] {42, true, List.of("a"), Map.of("a", "b"), "", "   ", "x".repeat(257)}) {
            String token = idp.sign(idp.claims("u", AUDIENCE, Duration.ofMinutes(10)).claim("sub", bad).build());
            assertThat(reasonOf(token)).as("sub=%s", bad.toString().length() > 20 ? "long" : bad).isEqualTo("missing_user_claim");
        }
        String max = "x".repeat(256);
        assertThat(verifier.verify(idp.idToken(max, AUDIENCE, Duration.ofMinutes(10))).userId()).isEqualTo(max);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   ", "abc", "a.b", "a.b.c.d", "....", "e30.e30.e30"})
    void malformedTokensRejected(String token) {
        assertThat(reasonOf(token)).isEqualTo("malformed");
    }

    @Test
    void nullAndOversizedTokensRejected() {
        assertThat(reasonOf((String) null)).isEqualTo("malformed");
        assertThat(reasonOf("x".repeat(10_000))).isEqualTo("malformed");
        assertThat(idp.jwksRequests()).isZero();
    }

    @Test
    void payloadThatIsNotAnObjectRejected() throws Exception {
        String token = signedRawPayload("[]");

        assertThat(reasonOf(token)).isEqualTo("malformed");
    }

    /** A correctly signed token whose payload is the given raw JSON text. */
    private String signedRawPayload(String payload) throws Exception {
        RSAKey key = idp.activeKey();
        String header = b64("{\"alg\":\"RS256\",\"kid\":\"" + key.getKeyID() + "\"}");
        String signingInput = header + "." + b64(payload);
        com.nimbusds.jose.util.Base64URL sig = new com.nimbusds.jose.crypto.RSASSASigner(key).sign(
                new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(key.getKeyID()).build(),
                signingInput.getBytes(StandardCharsets.US_ASCII));
        return signingInput + "." + sig;
    }

    // ---- exception hygiene ----

    @Test
    void rejectionNeverCarriesTokenOrClaims() {
        String token = idp.idToken("secret-user-id", "wrong-aud", Duration.ofMinutes(10));

        assertThatThrownBy(() -> verifier.verify(token))
                .isInstanceOf(IdentityRejectedException.class)
                .hasMessage("wrong_audience")
                .hasNoCause()
                .satisfies(e -> {
                    assertThat(e.toString()).doesNotContain("secret-user-id").doesNotContain(token);
                    assertThat(((IdentityRejectedException) e).reason()).isEqualTo("wrong_audience");
                });
    }

    @Test
    void verifiedIdentityToStringHidesTheUserId() {
        assertThat(new VerifiedIdentity("secret-user-id").toString()).doesNotContain("secret-user-id");
    }

    // ---- configuration ----

    @Test
    void remoteIssuerMustUseHttps() {
        assertThatThrownBy(() -> new OidcIdTokenVerifier(identity("http://idp.example.org", AUDIENCE, "sub", null), clock))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("https");
        assertThatThrownBy(() -> new OidcIdTokenVerifier(
                        identity("https://idp.example.org", AUDIENCE, "sub", "http://idp.example.org/jwks"), clock))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("jwks-uri");
    }

    @Test
    void localhostIssuersMayUseHttp() {
        new OidcIdTokenVerifier(identity("http://localhost:8080", AUDIENCE, "sub", null), clock);
        new OidcIdTokenVerifier(identity("http://127.0.0.1:8080/realm", AUDIENCE, "sub", "http://127.0.0.1:8080/jwks"), clock);
    }

    @Test
    void invalidUrlsFailAtConstruction() {
        assertThatThrownBy(() -> new OidcIdTokenVerifier(identity("not a url", AUDIENCE, "sub", null), clock))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new OidcIdTokenVerifier(identity("https://idp.example.org", AUDIENCE, "sub", "ftp://x/jwks"), clock))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new OidcIdTokenVerifier(identity("file:///etc/passwd", AUDIENCE, "sub", null), clock))
                .isInstanceOf(IllegalStateException.class);
    }

    // ---- helpers ----

    private static String b64(String text) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(text.getBytes(StandardCharsets.UTF_8));
    }
}
