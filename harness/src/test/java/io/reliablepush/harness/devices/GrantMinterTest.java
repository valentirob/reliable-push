package io.reliablepush.harness.devices;

import static org.assertj.core.api.Assertions.assertThat;

import io.reliablepush.harness.config.HarnessApp;
import io.reliablepush.harness.config.HarnessProperties;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.Map;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class GrantMinterTest {

    static final String SECRET = "test-grant-secret-0b5d6e1a9c37f284";
    static final Instant NOW = Instant.parse("2026-11-04T10:00:00Z");

    static HarnessProperties props(URI backend) {
        return new HarnessProperties(backend, Path.of("target"),
                Map.of("shop", new HarnessApp("k", SECRET, "h", "https://shop.test.invalid")));
    }

    @Test
    void validHs256Claims() throws Exception {
        GrantMinter minter = new GrantMinter(props(URI.create("http://localhost:1")), Clock.fixed(NOW, ZoneOffset.UTC));

        String jwt = minter.mint("shop", "user-7");

        String[] parts = jwt.split("\\.");
        assertThat(parts).hasSize(3);
        JsonMapper json = JsonMapper.builder().build();
        JsonNode header = json.readTree(Base64.getUrlDecoder().decode(parts[0]));
        JsonNode claims = json.readTree(Base64.getUrlDecoder().decode(parts[1]));
        assertThat(header.get("alg").asString()).isEqualTo("HS256");
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        byte[] expected = mac.doFinal((parts[0] + "." + parts[1]).getBytes(StandardCharsets.US_ASCII));
        assertThat(MessageDigest.isEqual(expected, Base64.getUrlDecoder().decode(parts[2]))).isTrue();
        assertThat(claims.get("iss").asString()).isEqualTo("https://shop.test.invalid");
        assertThat(claims.get("aud").asString()).isEqualTo("shop");
        assertThat(claims.get("sub").asString()).isEqualTo("user-7");
        assertThat(claims.get("iat").asLong()).isEqualTo(NOW.getEpochSecond());
        assertThat(claims.get("exp").asLong() - claims.get("iat").asLong()).isEqualTo(120);
        assertThat(jwt).doesNotContain("=");
    }

    @Test
    void hygieneScanSeesMintedGrantsBounded() {
        GrantMinter minter = new GrantMinter(props(URI.create("http://localhost:1")), Clock.systemUTC());
        String first = minter.mint("shop", "u1");

        assertThat(minter.issuedGrantsForLogHygieneScan()).containsExactly(first);
        for (int i = 0; i < GrantMinter.GRANT_HISTORY + 1; i++) {
            minter.mint("shop", "other-" + i);
        }
        assertThat(minter.issuedGrantsForLogHygieneScan()).hasSize(GrantMinter.GRANT_HISTORY).doesNotContain(first);
        assertThat(minter.toString()).doesNotContain(first);
    }

    @Test
    void unknownAppIsRejected() {
        GrantMinter minter = new GrantMinter(props(URI.create("http://localhost:1")), Clock.systemUTC());
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> minter.mint("nope", "u"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
