package io.reliablepush.harness.apns;

import static org.assertj.core.api.Assertions.assertThat;

import io.reliablepush.harness.apns.ApnsTestSupport.MutableClock;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.Signature;
import java.time.Instant;
import java.util.Base64;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ApnsProviderTokenTest {

    static final Instant T0 = Instant.parse("2026-11-05T10:00:00Z");

    @TempDir
    Path dir;

    ApnsCredentials creds(KeyPair key) throws Exception {
        ApnsTestSupport.writeCredentials(dir, key);
        return ApnsCredentials.load(dir).orElseThrow();
    }

    private static String decode(String part) {
        return new String(Base64.getUrlDecoder().decode(part), StandardCharsets.UTF_8);
    }

    @Test
    void providerTokenIsEs256WithRawSignature() throws Exception {
        KeyPair key = ApnsTestSupport.newKey();
        String jwt = new ApnsProviderToken(creds(key), new MutableClock(T0)).token();

        String[] parts = jwt.split("\\.");
        assertThat(parts).hasSize(3);
        assertThat(decode(parts[0])).contains("\"alg\":\"ES256\"").contains("\"kid\":\"" + ApnsTestSupport.KEY_ID + "\"");
        assertThat(decode(parts[1])).contains("\"iss\":\"" + ApnsTestSupport.TEAM_ID + "\"")
                .contains("\"iat\":" + T0.getEpochSecond());
        byte[] raw = Base64.getUrlDecoder().decode(parts[2]);
        assertThat(raw).hasSize(64); // JOSE: R || S, not DER

        // back to DER for the JDK verifier (DER is what SHA256withECDSA expects)
        Signature verifier = Signature.getInstance("SHA256withECDSA");
        verifier.initVerify(key.getPublic());
        verifier.update((parts[0] + "." + parts[1]).getBytes(StandardCharsets.US_ASCII));
        assertThat(verifier.verify(toDer(raw))).isTrue();
    }

    @Test
    void providerTokenIsCachedFor50Minutes() throws Exception {
        MutableClock clock = new MutableClock(T0);
        ApnsProviderToken tokens = new ApnsProviderToken(creds(ApnsTestSupport.newKey()), clock);

        String first = tokens.token();
        clock.advanceSeconds(49 * 60);
        assertThat(tokens.token()).isEqualTo(first);
        clock.advanceSeconds(2 * 60); // 51 min
        String second = tokens.token();
        assertThat(second).isNotEqualTo(first);
        assertThat(decode(second.split("\\.")[1])).contains("\"iat\":" + (T0.getEpochSecond() + 51 * 60));
        tokens.invalidate();
        clock.advanceSeconds(1);
        assertThat(tokens.token()).isNotEqualTo(second);
    }

    static byte[] toDer(byte[] raw) {
        byte[] r = integer(java.util.Arrays.copyOfRange(raw, 0, 32));
        byte[] s = integer(java.util.Arrays.copyOfRange(raw, 32, 64));
        byte[] der = new byte[6 + r.length + s.length - 2 + 2 - 2 + 2];
        int i = 0;
        der[i++] = 0x30;
        der[i++] = (byte) (2 + r.length + 2 + s.length);
        der[i++] = 0x02;
        der[i++] = (byte) r.length;
        System.arraycopy(r, 0, der, i, r.length);
        i += r.length;
        der[i++] = 0x02;
        der[i++] = (byte) s.length;
        System.arraycopy(s, 0, der, i, s.length);
        i += s.length;
        return java.util.Arrays.copyOf(der, i);
    }

    private static byte[] integer(byte[] v) {
        int start = 0;
        while (start < v.length - 1 && v[start] == 0) {
            start++;
        }
        boolean pad = (v[start] & 0x80) != 0;
        byte[] out = new byte[v.length - start + (pad ? 1 : 0)];
        System.arraycopy(v, start, out, pad ? 1 : 0, v.length - start);
        return out;
    }
}
