package io.reliablepush.loadtest;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;

class GrantSignerTest {

    private static final Instant NOW = Instant.parse("2026-11-05T10:00:00Z");

    private static String decode(String part) {
        return new String(Base64.getUrlDecoder().decode(part), StandardCharsets.UTF_8);
    }

    @Test
    void signsHs256WithTheHarnessClaimShape() throws Exception {
        GrantSigner signer = new GrantSigner("s3cret-for-test", "shop-backend", "shop");
        String jwt = signer.sign("load-user-7", NOW);

        String[] parts = jwt.split("\\.");
        assertThat(parts).hasSize(3);
        assertThat(jwt).doesNotContain("=").doesNotContain("+").doesNotContain("/");

        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec("s3cret-for-test".getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        String expected = Base64.getUrlEncoder().withoutPadding().encodeToString(
                mac.doFinal((parts[0] + "." + parts[1]).getBytes(StandardCharsets.US_ASCII)));
        assertThat(parts[2]).isEqualTo(expected);

        assertThat(decode(parts[0])).contains("\"alg\":\"HS256\"").contains("\"typ\":\"JWT\"");
        String claims = decode(parts[1]);
        long iat = NOW.getEpochSecond();
        assertThat(claims)
                .contains("\"iss\":\"shop-backend\"")
                .contains("\"aud\":\"shop\"")
                .contains("\"sub\":\"load-user-7\"")
                .contains("\"iat\":" + iat)
                .contains("\"exp\":" + (iat + 120));
    }

    @Test
    void escapesJsonSpecialCharacters() {
        String jwt = new GrantSigner("k", "iss", "shop").sign("we\"ird\\user", NOW);
        assertThat(decode(jwt.split("\\.")[1])).contains("\"sub\":\"we\\\"ird\\\\user\"");
    }
}
