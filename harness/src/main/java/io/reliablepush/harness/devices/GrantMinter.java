package io.reliablepush.harness.devices;

import io.reliablepush.harness.config.HarnessApp;
import io.reliablepush.harness.config.HarnessProperties;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Base64;
import java.util.List;
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
    static final int GRANT_HISTORY = 10_000;
    private static final Base64.Encoder B64 = Base64.getUrlEncoder().withoutPadding();

    private final HarnessProperties properties;
    private final Clock clock;
    private final JsonMapper json = JsonMapper.builder().build();
    /** Every grant minted (last {@value #GRANT_HISTORY}); only for {@link #issuedGrantsForLogHygieneScan}. */
    private final ArrayDeque<String> issued = new ArrayDeque<>();

    @Autowired
    public GrantMinter(HarnessProperties properties) {
        this(properties, Clock.systemUTC());
    }

    GrantMinter(HarnessProperties properties, Clock clock) {
        this.properties = properties;
        this.clock = clock;
    }

    /** The {@code exp} of a grant this minter produced (no signature check). */
    public Instant expiresAt(String grant) {
        byte[] payload = Base64.getUrlDecoder().decode(grant.split("\\.")[1]);
        return Instant.ofEpochSecond(json.readTree(payload).get("exp").asLong());
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
        String grant = signingInput + "." + B64.encodeToString(hmac(app.grantSecret(), signingInput));
        synchronized (issued) {
            issued.addLast(grant);
            while (issued.size() > GRANT_HISTORY) {
                issued.removeFirst();
            }
        }
        return grant;
    }

    /**
     * INTERNAL — for the log-hygiene scan only ({@code scenarios} package): the grants minted during this harness's
     * lifetime (bounded). Never return it through any endpoint and never log it.
     */
    public List<String> issuedGrantsForLogHygieneScan() {
        synchronized (issued) {
            return List.copyOf(issued);
        }
    }

    @Override
    public String toString() {
        return "GrantMinter[apps=" + properties.apps().keySet() + "]";
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
