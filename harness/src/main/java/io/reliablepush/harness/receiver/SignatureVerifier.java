package io.reliablepush.harness.receiver;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Independent implementation of the documented webhook signature (docs/integration-guide.md):
 * {@code X-RP-Signature = "v1=" + lowercase hex(HMAC-SHA256(secret, X-RP-Timestamp + "." + rawBody))}, timestamp skew
 * at most 300 s. Deliberately does not use backend code, so the harness validates the documented algorithm.
 */
public final class SignatureVerifier {

    static final Duration MAX_SKEW = Duration.ofSeconds(300);
    private static final String PREFIX = "v1=";
    private static final String ALGORITHM = "HmacSHA256";

    private SignatureVerifier() {}

    /** @param body the exact bytes received, before any parsing */
    public static boolean verify(byte[] secret, String timestampHeader, String signatureHeader, byte[] body, Instant now) {
        if (timestampHeader == null || signatureHeader == null || !signatureHeader.startsWith(PREFIX)) {
            return false;
        }
        long timestamp;
        try {
            timestamp = Long.parseLong(timestampHeader.trim());
        } catch (NumberFormatException e) {
            return false;
        }
        if (Math.abs(now.getEpochSecond() - timestamp) > MAX_SKEW.getSeconds()) {
            return false;
        }
        byte[] provided;
        try {
            provided = HexFormat.of().parseHex(signatureHeader.substring(PREFIX.length()));
        } catch (IllegalArgumentException e) {
            return false;
        }
        // HexFormat.parseHex accepts upper case; the documented format is lower case only
        if (!signatureHeader.equals(signatureHeader.toLowerCase(java.util.Locale.ROOT))) {
            return false;
        }
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(new SecretKeySpec(secret, ALGORITHM));
            mac.update((timestamp + ".").getBytes(StandardCharsets.US_ASCII));
            return MessageDigest.isEqual(mac.doFinal(body), provided);
        } catch (GeneralSecurityException e) {
            return false;
        }
    }
}
