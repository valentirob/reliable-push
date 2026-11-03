package io.reliablepush.devices;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.ECDSASigner;
import com.nimbusds.jose.jwk.Curve;
import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jose.jwk.gen.ECKeyGenerator;
import com.nimbusds.jose.util.Base64URL;
import io.reliablepush.devices.DeviceTokenService.IssuedToken;
import io.reliablepush.support.TestKeys;
import java.nio.charset.StandardCharsets;
import java.security.interfaces.ECPrivateKey;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class DeviceTokenServiceTest {

    private static final Instant T0 = Instant.parse("2026-11-03T10:00:00Z");
    private static final UUID DEVICE = UUID.fromString("0b0f5c1e-6a4e-4a43-9d7d-2f1d6c2d9a10");

    private final String pem1 = TestKeys.newEcPem();
    private final String pem2 = TestKeys.newEcPem();
    private MutableClock clock;
    private DeviceTokenService service;

    @BeforeEach
    void setUp() {
        clock = new MutableClock(T0);
        service = serviceWith("k1", Map.of("k1", pem1));
    }

    private DeviceTokenService serviceWith(String current, Map<String, String> keys) {
        return new DeviceTokenService(SigningKeys.fromPem(current, keys), clock);
    }

    private DeviceClaims claims() {
        return new DeviceClaims("shop", "user-42", DEVICE, 3, T0, T0.plus(Duration.ofDays(90)));
    }

    // ---- brief tests ----

    @Test
    void issueThenVerifyRoundTrip() {
        IssuedToken issued = service.issue(claims());

        assertThat(issued.expiresAt()).isEqualTo(T0.plus(Duration.ofDays(90)));
        assertThat(service.verify(issued.token())).isEqualTo(claims());
    }

    @Test
    void expiredRejected() {
        String token = service.issue(claims()).token();
        clock.set(T0.plus(Duration.ofDays(91)));

        assertReason(token, "expired");
    }

    @Test
    void tamperedRejected() {
        String token = service.issue(claims()).token();
        String[] parts = token.split("\\.");
        String forgedPayload = new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8)
                .replace("user-42", "user-43");
        String forged = parts[0] + "." + b64(forgedPayload) + "." + parts[2];

        assertReason(forged, "bad_signature");
    }

    @Test
    void oldKidStillValidAfterRotation() {
        String oldToken = service.issue(claims()).token();

        DeviceTokenService rotated = serviceWith("k2", Map.of("k1", pem1, "k2", pem2));
        String newToken = rotated.issue(claims()).token();

        assertThat(rotated.verify(oldToken)).isEqualTo(claims());
        assertThat(rotated.verify(newToken)).isEqualTo(claims());
        assertThat(header(oldToken)).contains("\"kid\":\"k1\"");
        assertThat(header(newToken)).contains("\"kid\":\"k2\"").contains("\"alg\":\"ES256\"");
    }

    @Test
    void removedKidRejected() {
        String oldToken = service.issue(claims()).token();

        DeviceTokenService withoutK1 = serviceWith("k2", Map.of("k2", pem2));

        assertThatThrownBy(() -> withoutK1.verify(oldToken))
                .isInstanceOfSatisfying(InvalidDeviceTokenException.class, e -> assertThat(e.reason()).isEqualTo("unknown_kid"));
    }

    // ---- token shape ----

    @Test
    void tokenCarriesTheSpecClaims() {
        String payload = payload(service.issue(claims()).token());

        assertThat(payload)
                .contains("\"iss\":\"reliable-push\"")
                .contains("\"aud\":\"shop\"")
                .contains("\"sub\":\"user-42\"")
                .contains("\"did\":\"" + DEVICE + "\"")
                .contains("\"ver\":3")
                .contains("\"iat\":" + T0.getEpochSecond())
                .contains("\"exp\":" + T0.plus(Duration.ofDays(90)).getEpochSecond());
    }

    // ---- exceptions never carry the token ----

    @Test
    void exceptionMessageIsTheReasonOnly() {
        String token = service.issue(claims()).token();
        clock.set(T0.plus(Duration.ofDays(91)));

        assertThatThrownBy(() -> service.verify(token))
                .isInstanceOf(InvalidDeviceTokenException.class)
                .hasMessage("expired")
                .satisfies(e -> assertThat(e.getCause()).isNull());
    }

    @Test
    void issuedTokenToStringDoesNotPrintTheToken() {
        IssuedToken issued = service.issue(claims());

        assertThat(issued.toString()).doesNotContain(issued.token()).doesNotContain("eyJ");
    }

    // ---- strict verification: malformed ----

    @ParameterizedTest
    @ValueSource(strings = {"", "   ", "abc", "a.b", "a.b.c", "a.b.c.d", "....", "e30.e30.e30"})
    void garbageIsMalformed(String token) {
        assertReason(token, "malformed");
    }

    @Test
    void nullIsMalformed() {
        assertReason(null, "malformed");
    }

    @Test
    void algNoneRejected() {
        String token = b64("{\"alg\":\"none\",\"kid\":\"k1\"}") + "." + b64(validClaims()) + ".";

        assertReason(token, "malformed");
    }

    @Test
    void algNoneWithBogusSignatureRejected() {
        String token = b64("{\"alg\":\"none\",\"kid\":\"k1\"}") + "." + b64(validClaims()) + ".AAAA";

        assertReason(token, "malformed");
    }

    @ParameterizedTest
    @ValueSource(strings = {"HS256", "HS384", "HS512", "RS256", "PS256", "ES384", "ES512", "EdDSA", "ES256K"})
    void otherAlgorithmsRejected(String alg) {
        // signature bytes are irrelevant: the algorithm check comes first
        String token = b64("{\"alg\":\"" + alg + "\",\"kid\":\"k1\"}") + "." + b64(validClaims()) + "." + b64("sig");

        assertReason(token, "malformed");
    }

    @Test
    void hmacTokenKeyedWithThePublicKeyRejected() throws Exception {
        // classic algorithm-confusion attack: HS256 using the (public) key bytes as the HMAC secret
        byte[] publicKeyBytes = SigningKeys.fromPem("k1", Map.of("k1", pem1)).current().publicKey().getEncoded();
        String input = b64("{\"alg\":\"HS256\",\"kid\":\"k1\"}") + "." + b64(validClaims());
        javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA256");
        mac.init(new javax.crypto.spec.SecretKeySpec(publicKeyBytes, "HmacSHA256"));
        String token = input + "." + Base64URL.encode(mac.doFinal(input.getBytes(StandardCharsets.US_ASCII)));

        assertReason(token, "malformed");
    }

    @Test
    void headerProvidedJwkNeverTrusted() throws Exception {
        ECKey attacker = new ECKeyGenerator(Curve.P_256).keyID("k1").generate();
        JWSHeader header = new JWSHeader.Builder(JWSAlgorithm.ES256).keyID("k1").jwk(attacker.toPublicJWK()).build();

        assertReason(sign(header, validClaims(), attacker), "malformed");
    }

    @Test
    void headerProvidedJwkNeverTrustedEvenWithUnknownKid() throws Exception {
        ECKey attacker = new ECKeyGenerator(Curve.P_256).generate();
        JWSHeader header = new JWSHeader.Builder(JWSAlgorithm.ES256).keyID("attacker").jwk(attacker.toPublicJWK()).build();

        assertReason(sign(header, validClaims(), attacker), "malformed");
    }

    @Test
    void headerProvidedJkuAndX5uRejected() throws Exception {
        ECKey attacker = new ECKeyGenerator(Curve.P_256).generate();
        JWSHeader jku = new JWSHeader.Builder(JWSAlgorithm.ES256).keyID("k1")
                .jwkURL(java.net.URI.create("https://attacker.example/jwks.json")).build();
        JWSHeader x5u = new JWSHeader.Builder(JWSAlgorithm.ES256).keyID("k1")
                .x509CertURL(java.net.URI.create("https://attacker.example/cert.pem")).build();

        assertReason(sign(jku, validClaims(), attacker), "malformed");
        assertReason(sign(x5u, validClaims(), attacker), "malformed");
    }

    @Test
    void critHeaderRejected() throws Exception {
        ECPrivateKey signer = TestKeys.privateKey(pem1);
        String header = "{\"alg\":\"ES256\",\"kid\":\"k1\",\"crit\":[\"exp\"],\"exp\":1}";

        assertReason(signRaw(header, validClaims(), signer), "malformed");
    }

    @Test
    void missingKidIsMalformed() throws Exception {
        ECPrivateKey signer = TestKeys.privateKey(pem1);

        assertReason(signRaw("{\"alg\":\"ES256\"}", validClaims(), signer), "malformed");
    }

    @Test
    void unknownKidRejected() throws Exception {
        ECPrivateKey signer = TestKeys.privateKey(pem1);

        assertReason(signRaw("{\"alg\":\"ES256\",\"kid\":\"nope\"}", validClaims(), signer), "unknown_kid");
    }

    @Test
    void signatureFromAnotherKeyUnderKnownKidRejected() throws Exception {
        ECKey attacker = new ECKeyGenerator(Curve.P_256).generate();

        assertReason(signRaw("{\"alg\":\"ES256\",\"kid\":\"k1\"}", validClaims(), attacker.toECPrivateKey()), "bad_signature");
    }

    @Test
    void truncatedSignatureRejected() {
        String token = service.issue(claims()).token();
        String[] parts = token.split("\\.");
        String shortSig = parts[0] + "." + parts[1] + "." + parts[2].substring(0, 20);

        assertReason(shortSig, "bad_signature");
    }

    @Test
    void wrongIssuerRejected() throws Exception {
        String claims = validClaims().replace("\"iss\":\"reliable-push\"", "\"iss\":\"someone-else\"");

        assertReason(signWithK1(claims), "wrong_issuer");
    }

    @Test
    void missingIssuerRejected() throws Exception {
        String claims = validClaims().replace("\"iss\":\"reliable-push\",", "");

        assertReason(signWithK1(claims), "wrong_issuer");
    }

    @Test
    void validHandCraftedTokenAccepted() throws Exception {
        // guards the helper itself: the negative claim tests below mutate exactly this token
        assertThat(service.verify(signWithK1(validClaims())).appId()).isEqualTo("shop");
    }

    @Test
    void audienceMustBeExactlyOneValue() throws Exception {
        assertReason(signWithK1(validClaims().replace("\"aud\":\"shop\"", "\"aud\":[\"shop\",\"other\"]")), "malformed");
        assertReason(signWithK1(validClaims().replace("\"aud\":\"shop\",", "")), "malformed");
        assertReason(signWithK1(validClaims().replace("\"aud\":\"shop\"", "\"aud\":[]")), "malformed");
        assertReason(signWithK1(validClaims().replace("\"aud\":\"shop\"", "\"aud\":\"\"")), "malformed");
        assertThat(service.verify(signWithK1(validClaims().replace("\"aud\":\"shop\"", "\"aud\":[\"shop\"]"))).appId())
                .isEqualTo("shop");
    }

    @Test
    void requiredClaimsMustBePresentAndWellTyped() throws Exception {
        String[] broken = {
            validClaims().replace("\"sub\":\"user-42\",", ""),
            validClaims().replace("\"sub\":\"user-42\"", "\"sub\":\"\""),
            validClaims().replace("\"sub\":\"user-42\"", "\"sub\":42"),
            validClaims().replace("\"did\":\"" + DEVICE + "\",", ""),
            validClaims().replace(DEVICE.toString(), "not-a-uuid"),
            validClaims().replace("\"did\":\"" + DEVICE + "\"", "\"did\":7"),
            validClaims().replace("\"ver\":3,", ""),
            validClaims().replace("\"ver\":3", "\"ver\":-1"),
            validClaims().replace("\"ver\":3", "\"ver\":1.5"),
            validClaims().replace("\"ver\":3", "\"ver\":\"3\""),
            validClaims().replace("\"ver\":3", "\"ver\":3000000000"),
            validClaims().replace("\"iat\":" + T0.getEpochSecond() + ",", ""),
            validClaims().replace("\"iat\":" + T0.getEpochSecond(), "\"iat\":\"now\""),
            validClaims().replace(",\"exp\":" + T0.plusSeconds(3600).getEpochSecond(), ""),
            validClaims().replace("\"exp\":" + T0.plusSeconds(3600).getEpochSecond(), "\"exp\":\"later\""),
            "[]",
            "\"just a string\"",
            "not json",
        };
        for (String claims : broken) {
            String token = signWithK1(claims);
            String reason;
            try {
                service.verify(token);
                reason = "ACCEPTED";
            } catch (InvalidDeviceTokenException e) {
                reason = e.reason();
            }
            assertThat(reason).as(claims).isEqualTo("malformed");
        }
    }

    // ---- clock skew ----

    @Test
    void expirySkewOf60SecondsIsTolerated() {
        String token = service.issue(claims()).token();
        Instant exp = T0.plus(Duration.ofDays(90));

        clock.set(exp.plusSeconds(60));
        assertThat(service.verify(token).appId()).isEqualTo("shop");

        clock.set(exp.plusSeconds(61));
        assertReason(token, "expired");
    }

    @Test
    void futureIssuedAtBeyondSkewRejected() throws Exception {
        long iat = T0.getEpochSecond();
        assertThat(service.verify(signWithK1(validClaims().replace("\"iat\":" + iat, "\"iat\":" + (iat + 60)))).appId())
                .isEqualTo("shop");
        assertReason(signWithK1(validClaims().replace("\"iat\":" + iat, "\"iat\":" + (iat + 61))), "malformed");
    }

    @Test
    void notBeforeInTheFutureBeyondSkewRejected() throws Exception {
        long nbf = T0.getEpochSecond();
        String ok = validClaims().replace("\"iss\"", "\"nbf\":" + (nbf + 60) + ",\"iss\"");
        String future = validClaims().replace("\"iss\"", "\"nbf\":" + (nbf + 61) + ",\"iss\"");
        String broken = validClaims().replace("\"iss\"", "\"nbf\":\"soon\",\"iss\"");

        assertThat(service.verify(signWithK1(ok)).appId()).isEqualTo("shop");
        assertReason(signWithK1(future), "malformed");
        assertReason(signWithK1(broken), "malformed");
    }

    // ---- issue guards ----

    @Test
    void issueWithoutConfiguredKeysFails() {
        DeviceTokenService unconfigured = new DeviceTokenService(SigningKeys.fromPem(null, Map.of()), clock);

        assertThatThrownBy(() -> unconfigured.issue(claims()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("device-token signing keys are not configured");
        assertThatThrownBy(() -> unconfigured.verify(service.issue(claims()).token()))
                .isInstanceOfSatisfying(InvalidDeviceTokenException.class, e -> assertThat(e.reason()).isEqualTo("unknown_kid"));
    }

    @Test
    void issueRejectsInvalidClaims() {
        assertThatThrownBy(() -> service.issue(new DeviceClaims("shop", "u", DEVICE, -1, T0, T0.plusSeconds(1))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.issue(new DeviceClaims(" ", "u", DEVICE, 0, T0, T0.plusSeconds(1))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.issue(new DeviceClaims("shop", "", DEVICE, 0, T0, T0.plusSeconds(1))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.issue(new DeviceClaims("shop", "u", DEVICE, 0, T0, T0)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ---- helpers ----

    private void assertReason(String token, String reason) {
        assertThatThrownBy(() -> service.verify(token))
                .isInstanceOfSatisfying(InvalidDeviceTokenException.class, e -> {
                    assertThat(e.reason()).isEqualTo(reason);
                    assertThat(e).hasMessage(reason);
                });
    }

    private String validClaims() {
        return "{\"iss\":\"reliable-push\",\"aud\":\"shop\",\"sub\":\"user-42\",\"did\":\"" + DEVICE + "\",\"ver\":3,\"iat\":"
                + T0.getEpochSecond() + ",\"exp\":" + T0.plusSeconds(3600).getEpochSecond() + "}";
    }

    private String signWithK1(String claimsJson) throws Exception {
        return signRaw("{\"alg\":\"ES256\",\"kid\":\"k1\"}", claimsJson, TestKeys.privateKey(pem1));
    }

    private static String sign(JWSHeader header, String claimsJson, ECKey key) throws Exception {
        return signRaw(header.toString(), claimsJson, key.toECPrivateKey());
    }

    /** Signs exactly the given header and payload text with ES256, so tests control every byte of the token. */
    private static String signRaw(String headerJson, String claimsJson, ECPrivateKey key) throws Exception {
        String input = b64(headerJson) + "." + b64(claimsJson);
        Base64URL signature = new ECDSASigner(key).sign(new JWSHeader(JWSAlgorithm.ES256), input.getBytes(StandardCharsets.US_ASCII));
        return input + "." + signature;
    }

    private static String b64(String text) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(text.getBytes(StandardCharsets.UTF_8));
    }

    private static String header(String token) {
        return new String(Base64.getUrlDecoder().decode(token.split("\\.")[0]), StandardCharsets.UTF_8);
    }

    private static String payload(String token) {
        return new String(Base64.getUrlDecoder().decode(token.split("\\.")[1]), StandardCharsets.UTF_8);
    }

    /** Settable clock: tests move time forward without sleeping. */
    private static final class MutableClock extends Clock {
        private Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        void set(Instant instant) {
            this.now = instant;
        }

        @Override
        public java.time.ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
