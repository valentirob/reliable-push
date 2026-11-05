package io.reliablepush.loadtest;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.time.Instant;
import java.util.Base64;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Plays the customer's backend: HS256 signed grant, same shape as the harness {@code GrantMinter}
 * ({@code iss}, {@code aud} = app id, {@code sub}, {@code iat}, {@code exp = iat + 120}). JDK only.
 */
public final class GrantSigner {

    static final long LIFETIME_SECONDS = 120;
    private static final Base64.Encoder B64 = Base64.getUrlEncoder().withoutPadding();

    private final String secret;
    private final String issuer;
    private final String appId;

    public GrantSigner(String secret, String issuer, String appId) {
        this.secret = secret;
        this.issuer = issuer;
        this.appId = appId;
    }

    public static GrantSigner of(LoadConfig c) {
        return new GrantSigner(c.grantSecret(), c.grantIssuer(), c.appId());
    }

    public String sign(String userId, Instant now) {
        long iat = now.getEpochSecond();
        String header = "{\"alg\":\"HS256\",\"typ\":\"JWT\"}";
        String claims = "{\"iss\":" + quote(issuer) + ",\"aud\":" + quote(appId) + ",\"sub\":" + quote(userId)
                + ",\"iat\":" + iat + ",\"exp\":" + (iat + LIFETIME_SECONDS) + "}";
        String input = b64(header) + "." + b64(claims);
        return input + "." + B64.encodeToString(hmac(input));
    }

    @Override
    public String toString() {
        return "GrantSigner[issuer=" + issuer + ", appId=" + appId + "]";
    }

    private static String b64(String json) {
        return B64.encodeToString(json.getBytes(StandardCharsets.UTF_8));
    }

    static String quote(String s) {
        StringBuilder sb = new StringBuilder("\"");
        for (char ch : s.toCharArray()) {
            switch (ch) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (ch < 0x20) {
                        sb.append(String.format("\\u%04x", (int) ch));
                    } else {
                        sb.append(ch);
                    }
                }
            }
        }
        return sb.append('"').toString();
    }

    private byte[] hmac(String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return mac.doFinal(data.getBytes(StandardCharsets.US_ASCII));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256 unavailable", e);
        }
    }
}
