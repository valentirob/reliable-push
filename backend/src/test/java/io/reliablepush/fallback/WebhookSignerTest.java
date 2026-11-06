package io.reliablepush.fallback;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;

class WebhookSignerTest {

    /** Reference implementation inside the test: HMAC-SHA256 over "<timestamp>.<body>", computed with the JDK. */
    private static String expected(String secret, long timestamp, String body) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        byte[] digest = mac.doFinal((timestamp + "." + body).getBytes(StandardCharsets.UTF_8));
        return "v1=" + HexFormat.of().formatHex(digest);
    }

    @Test
    void knownVector() throws Exception {
        String signature = WebhookSigner.sign(
                "s3cr3t".getBytes(StandardCharsets.UTF_8), 1700000000L, "{\"a\":1}".getBytes(StandardCharsets.UTF_8));

        assertThat(signature).isEqualTo(expected("s3cr3t", 1700000000L, "{\"a\":1}"));
        assertThat(signature).matches("v1=[0-9a-f]{64}");
    }

    @Test
    void signsTheExactBytesIncludingNonAscii() throws Exception {
        String body = "{\"name\":\"Zoë ✓\"}";

        assertThat(WebhookSigner.sign("k".getBytes(StandardCharsets.UTF_8), 42L, body.getBytes(StandardCharsets.UTF_8)))
                .isEqualTo(expected("k", 42L, body));
    }

    @Test
    void differsWhenTimestampSecretOrBodyDiffers() {
        byte[] secret = "s3cr3t".getBytes(StandardCharsets.UTF_8);
        byte[] body = "{}".getBytes(StandardCharsets.UTF_8);
        String base = WebhookSigner.sign(secret, 1L, body);

        assertThat(WebhookSigner.sign(secret, 2L, body)).isNotEqualTo(base);
        assertThat(WebhookSigner.sign("other".getBytes(StandardCharsets.UTF_8), 1L, body)).isNotEqualTo(base);
        assertThat(WebhookSigner.sign(secret, 1L, "{ }".getBytes(StandardCharsets.UTF_8))).isNotEqualTo(base);
    }
}
