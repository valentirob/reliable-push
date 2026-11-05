package io.reliablepush.harness.receiver;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;

class SignatureVerifierTest {

    private static final byte[] SECRET = "unit-test-hmac-secret-91ab".getBytes(StandardCharsets.UTF_8);
    private static final Instant NOW = Instant.parse("2026-11-04T10:00:00Z");

    private static String sign(byte[] secret, long ts, byte[] body) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret, "HmacSHA256"));
        mac.update((ts + ".").getBytes(StandardCharsets.US_ASCII));
        return "v1=" + HexFormat.of().formatHex(mac.doFinal(body));
    }

    @Test
    void knownVector() throws Exception {
        byte[] body = "{\"trackingId\":\"x\"}".getBytes(StandardCharsets.UTF_8);
        long ts = NOW.getEpochSecond();

        assertThat(SignatureVerifier.verify(SECRET, Long.toString(ts), sign(SECRET, ts, body), body, NOW)).isTrue();
    }

    @Test
    void rejectsTamperedBody() throws Exception {
        byte[] body = "{\"a\":1}".getBytes(StandardCharsets.UTF_8);
        long ts = NOW.getEpochSecond();
        String signature = sign(SECRET, ts, body);

        assertThat(SignatureVerifier.verify(SECRET, Long.toString(ts), signature,
                "{\"a\":2}".getBytes(StandardCharsets.UTF_8), NOW)).isFalse();
    }

    @Test
    void rejectsStaleTimestamp() throws Exception {
        byte[] body = "{}".getBytes(StandardCharsets.UTF_8);
        long stale = NOW.getEpochSecond() - 301;
        long edge = NOW.getEpochSecond() - 300;

        assertThat(SignatureVerifier.verify(SECRET, Long.toString(stale), sign(SECRET, stale, body), body, NOW)).isFalse();
        assertThat(SignatureVerifier.verify(SECRET, Long.toString(edge), sign(SECRET, edge, body), body, NOW)).isTrue();
    }

    @Test
    void rejectsMissingMalformedAndWrongSecret() throws Exception {
        byte[] body = "{}".getBytes(StandardCharsets.UTF_8);
        long ts = NOW.getEpochSecond();
        String good = sign(SECRET, ts, body);

        assertThat(SignatureVerifier.verify(SECRET, null, good, body, NOW)).isFalse();
        assertThat(SignatureVerifier.verify(SECRET, Long.toString(ts), null, body, NOW)).isFalse();
        assertThat(SignatureVerifier.verify(SECRET, "abc", good, body, NOW)).isFalse();
        assertThat(SignatureVerifier.verify(SECRET, Long.toString(ts), good.substring(3), body, NOW)).isFalse();
        assertThat(SignatureVerifier.verify(SECRET, Long.toString(ts), "v1=zz", body, NOW)).isFalse();
        assertThat(SignatureVerifier.verify(SECRET, Long.toString(ts), good.toUpperCase(), body, NOW)).isFalse();
        assertThat(SignatureVerifier.verify("other".getBytes(StandardCharsets.UTF_8), Long.toString(ts), good, body, NOW))
                .isFalse();
    }
}
