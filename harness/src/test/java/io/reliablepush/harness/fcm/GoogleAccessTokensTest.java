package io.reliablepush.harness.fcm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.reliablepush.harness.fcm.FcmTestSupport.MutableClock;
import io.reliablepush.harness.fcm.FcmTestSupport.Stub;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.Signature;
import java.time.Instant;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class GoogleAccessTokensTest {

    static final JsonMapper JSON = JsonMapper.builder().build();
    static final Instant T0 = Instant.parse("2026-11-05T10:00:00Z");

    @TempDir
    Path dir;

    FcmCredentials credentials(KeyPair key) throws Exception {
        return FcmCredentials.load(FcmTestSupport.writeCredentials(dir, key)).orElseThrow();
    }

    static Map<String, String> form(String body) {
        Map<String, String> m = new HashMap<>();
        for (String kv : body.split("&")) {
            String[] p = kv.split("=", 2);
            m.put(URLDecoder.decode(p[0], StandardCharsets.UTF_8), URLDecoder.decode(p[1], StandardCharsets.UTF_8));
        }
        return m;
    }

    @Test
    void jwtAssertionIsSignedAndHasClaims() throws Exception {
        KeyPair key = FcmTestSupport.newKey();
        GoogleAccessTokens tokens = new GoogleAccessTokens(credentials(key), new MutableClock(T0));

        String jwt = tokens.jwtAssertion();

        String[] parts = jwt.split("\\.");
        assertThat(parts).hasSize(3);
        Signature verify = Signature.getInstance("SHA256withRSA");
        verify.initVerify(key.getPublic());
        verify.update((parts[0] + "." + parts[1]).getBytes(StandardCharsets.US_ASCII));
        assertThat(verify.verify(Base64.getUrlDecoder().decode(parts[2]))).as("RS256 signature").isTrue();
        JsonNode header = JSON.readTree(Base64.getUrlDecoder().decode(parts[0]));
        assertThat(header.get("alg").asString()).isEqualTo("RS256");
        assertThat(header.get("typ").asString()).isEqualTo("JWT");
        JsonNode claims = JSON.readTree(Base64.getUrlDecoder().decode(parts[1]));
        assertThat(claims.get("iss").asString()).isEqualTo(FcmTestSupport.CLIENT_EMAIL);
        assertThat(claims.get("scope").asString()).isEqualTo("https://www.googleapis.com/auth/firebase.messaging");
        assertThat(claims.get("aud").asString()).isEqualTo(FcmTestSupport.TOKEN_URI);
        assertThat(claims.get("iat").asLong()).isEqualTo(T0.getEpochSecond());
        assertThat(claims.get("exp").asLong()).isEqualTo(T0.getEpochSecond() + 3600);
    }

    @Test
    void postsTheJwtBearerGrantAndReturnsTheAccessToken() throws Exception {
        KeyPair key = FcmTestSupport.newKey();
        try (Stub stub = new Stub(s -> new Object[] {200, "{\"access_token\":\"at-1\",\"expires_in\":3600}"})) {
            GoogleAccessTokens tokens = new GoogleAccessTokens(credentials(key), new MutableClock(T0),
                    URI.create(stub.url() + "/token"));

            assertThat(tokens.accessToken()).isEqualTo("at-1");

            FcmTestSupport.Seen seen = stub.seen.get(0);
            assertThat(seen.method()).isEqualTo("POST");
            assertThat(seen.path()).isEqualTo("/token");
            assertThat(seen.contentType()).startsWith("application/x-www-form-urlencoded");
            Map<String, String> f = form(seen.body());
            assertThat(f.get("grant_type")).isEqualTo("urn:ietf:params:oauth:grant-type:jwt-bearer");
            assertThat(f.get("assertion").split("\\.")).hasSize(3);
        }
    }

    @Test
    void accessTokenIsCachedUntilNearExpiry() throws Exception {
        KeyPair key = FcmTestSupport.newKey();
        MutableClock clock = new MutableClock(T0);
        int[] n = {0};
        try (Stub stub = new Stub(s -> new Object[] {200,
                "{\"access_token\":\"at-" + (++n[0] + "\",\"expires_in\":3600}")})) {
            GoogleAccessTokens tokens = new GoogleAccessTokens(credentials(key), clock,
                    URI.create(stub.url() + "/token"));

            assertThat(tokens.accessToken()).isEqualTo("at-1");
            clock.advanceSeconds(3539); // 61 s before expiry: still cached
            assertThat(tokens.accessToken()).isEqualTo("at-1");
            assertThat(stub.seen).hasSize(1);
            clock.advanceSeconds(2); // 59 s before expiry: renewed
            assertThat(tokens.accessToken()).isEqualTo("at-2");
            assertThat(stub.seen).hasSize(2);
        }
    }

    @Test
    void refusedGrantFailsWithoutLeakingTheResponse() throws Exception {
        KeyPair key = FcmTestSupport.newKey();
        try (Stub stub = new Stub(s -> new Object[] {400, "{\"error\":\"invalid_grant\",\"detail\":\"SECRET-DETAIL\"}"})) {
            GoogleAccessTokens tokens = new GoogleAccessTokens(credentials(key), new MutableClock(T0),
                    URI.create(stub.url() + "/token"));

            assertThatThrownBy(tokens::accessToken).isInstanceOf(java.io.IOException.class)
                    .hasMessageContaining("400").hasMessageNotContaining("SECRET-DETAIL");
        }
    }

    @Test
    void malformedGrantResponseFails() throws Exception {
        KeyPair key = FcmTestSupport.newKey();
        try (Stub stub = new Stub(s -> new Object[] {200, "{\"expires_in\":3600}"})) {
            GoogleAccessTokens tokens = new GoogleAccessTokens(credentials(key), new MutableClock(T0),
                    URI.create(stub.url() + "/token"));

            assertThatThrownBy(tokens::accessToken).isInstanceOf(java.io.IOException.class);
        }
    }
}
