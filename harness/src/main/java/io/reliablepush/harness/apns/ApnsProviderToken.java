package io.reliablepush.harness.apns;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.Signature;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;

/**
 * The APNs provider token: an ES256 JWT ({@code kid} = key id; {@code iss} = team id, {@code iat}) signed with the
 * auth key, cached for 50 minutes (Apple rejects tokens older than one hour and throttles refreshes more often than
 * every 20 minutes). Thread-safe. Neither the key nor the JWT is logged or printed.
 */
public final class ApnsProviderToken {

    static final Duration CACHE = Duration.ofMinutes(50);
    private static final Base64.Encoder B64 = Base64.getUrlEncoder().withoutPadding();

    private final ApnsCredentials credentials;
    private final Clock clock;

    private String cached;
    private Instant cachedUntil = Instant.MIN;

    public ApnsProviderToken(ApnsCredentials credentials) {
        this(credentials, Clock.systemUTC());
    }

    ApnsProviderToken(ApnsCredentials credentials, Clock clock) {
        this.credentials = credentials;
        this.clock = clock;
    }

    public synchronized String token() {
        Instant now = clock.instant();
        if (cached != null && now.isBefore(cachedUntil)) {
            return cached;
        }
        cached = sign(now);
        cachedUntil = now.plus(CACHE);
        return cached;
    }

    /** Forgets the cached token (Apple answered 403 InvalidProviderToken / ExpiredProviderToken). */
    public synchronized void invalidate() {
        cached = null;
        cachedUntil = Instant.MIN;
    }

    private String sign(Instant now) {
        String header = B64.encodeToString(("{\"alg\":\"ES256\",\"kid\":\"" + credentials.keyId() + "\"}")
                .getBytes(StandardCharsets.UTF_8));
        String claims = B64.encodeToString(("{\"iss\":\"" + credentials.teamId() + "\",\"iat\":" + now.getEpochSecond() + "}")
                .getBytes(StandardCharsets.UTF_8));
        String input = header + "." + claims;
        try {
            Signature s = Signature.getInstance("SHA256withECDSA");
            s.initSign(credentials.privateKey());
            s.update(input.getBytes(StandardCharsets.US_ASCII));
            return input + "." + B64.encodeToString(derToRaw(s.sign()));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("cannot sign the APNs provider token");
        }
    }

    /** DER {@code SEQUENCE { INTEGER r, INTEGER s }} to the JOSE form: r then s, each left-padded to 32 bytes. */
    static byte[] derToRaw(byte[] der) {
        int i = 2; // 0x30, one length byte (a P-256 signature is at most 72 bytes)
        if (der.length < 8 || der[0] != 0x30 || (der[1] & 0x80) != 0) {
            throw new IllegalStateException("unexpected ECDSA signature encoding");
        }
        byte[] raw = new byte[64];
        for (int part = 0; part < 2; part++) {
            if (der[i] != 0x02) {
                throw new IllegalStateException("unexpected ECDSA signature encoding");
            }
            int len = der[i + 1] & 0xff;
            int from = i + 2;
            while (len > 32 && der[from] == 0) { // the sign byte
                from++;
                len--;
            }
            if (len > 32) {
                throw new IllegalStateException("unexpected ECDSA signature encoding");
            }
            System.arraycopy(der, from, raw, part * 32 + (32 - len), len);
            i = from + len;
        }
        return raw;
    }

    @Override
    public String toString() {
        return "ApnsProviderToken[keyId=" + credentials.keyId() + "]";
    }
}
