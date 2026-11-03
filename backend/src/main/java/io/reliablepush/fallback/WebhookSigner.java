package io.reliablepush.fallback;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** Webhook signature of spec 5: {@code v1=<hex HMAC-SHA256(secret, timestamp + "." + body)>}. */
final class WebhookSigner {

    static final String VERSION_PREFIX = "v1=";
    private static final String ALGORITHM = "HmacSHA256";

    private WebhookSigner() {}

    /** @param body the exact bytes that are sent */
    static String sign(byte[] secret, long epochSeconds, byte[] body) {
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(new SecretKeySpec(secret, ALGORITHM));
            mac.update((epochSeconds + ".").getBytes(StandardCharsets.US_ASCII));
            return VERSION_PREFIX + HexFormat.of().formatHex(mac.doFinal(body));
        } catch (GeneralSecurityException e) {
            // HmacSHA256 is mandatory in every JDK; the key is never empty (Secret rejects that). No message: key material
            throw new IllegalStateException("HMAC-SHA256 signing failed");
        }
    }
}
