package io.reliablepush.harness.devices;

import io.reliablepush.harness.config.HarnessApp;
import io.reliablepush.harness.config.HarnessProperties;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * Plays the customer's backend: mints the short-lived signed grant (HS256) the backend's {@code signed-grant}
 * identity type accepts: {@code iss}, {@code aud} = app id, {@code sub} = user id, {@code iat}, {@code exp = iat + 120 s}.
 */
@Component
public class GrantMinter {

    static final long LIFETIME_SECONDS = 120;
    private static final Base64.Encoder B64 = Base64.getUrlEncoder().withoutPadding();

    private final HarnessProperties properties;
    private final Clock clock;
    private final JsonMapper json = JsonMapper.builder().build();

    @Autowired
    public GrantMinter(HarnessProperties properties) {
        this(properties, Clock.systemUTC());
    }

    GrantMinter(HarnessProperties properties, Clock clock) {
        this.properties = properties;
        this.clock = clock;
    }

    public String mint(String appId, String userId) {
        HarnessApp app = properties.app(appId);
        Instant now = clock.instant();
        Map<String, Object> header = new LinkedHashMap<>();
        header.put("alg", "HS256");
        header.put("typ", "JWT");
        Map<String, Object> claims = new LinkedHashMap<>();
        claims.put("iss", app.grantIssuer());
        claims.put("aud", appId);
        claims.put("sub", userId);
        claims.put("iat", now.getEpochSecond());
        claims.put("exp", now.getEpochSecond() + LIFETIME_SECONDS);
        String signingInput = part(header) + "." + part(claims);
        return signingInput + "." + B64.encodeToString(hmac(app.grantSecret(), signingInput));
    }

    private String part(Map<String, Object> value) {
        return B64.encodeToString(json.writeValueAsBytes(value));
    }

    private static byte[] hmac(String secret, String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return mac.doFinal(data.getBytes(StandardCharsets.US_ASCII));
        } catch (java.security.GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256 unavailable", e);
        }
    }
}
