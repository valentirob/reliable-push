package io.reliablepush.devices;

import static io.reliablepush.devices.InvalidDeviceTokenException.BAD_SIGNATURE;
import static io.reliablepush.devices.InvalidDeviceTokenException.EXPIRED;
import static io.reliablepush.devices.InvalidDeviceTokenException.MALFORMED;
import static io.reliablepush.devices.InvalidDeviceTokenException.UNKNOWN_KID;
import static io.reliablepush.devices.InvalidDeviceTokenException.WRONG_ISSUER;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.ECDSASigner;
import com.nimbusds.jose.crypto.ECDSAVerifier;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.text.ParseException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import org.springframework.stereotype.Service;

/**
 * Issues and verifies device tokens (spec 5): ES256 JWTs signed by the backend with {@code kid}-identified keys.
 * This is the pure token layer: it checks signature and claims only. Revocation ({@code token_version}, {@code
 * revoked_at}) is checked against the database by the caller.
 *
 * <p>Verification is strict: the header {@code alg} must be exactly ES256, the verification key is always looked up by
 * {@code kid} among the configured keys (never taken from {@code jwk}/{@code jku}/{@code x5u}/{@code x5c} headers, which
 * are rejected), {@code iss} must be {@value #ISSUER}, {@code aud} must be exactly one value, and {@code sub}, {@code
 * did}, {@code ver}, {@code iat}, {@code exp} are required. Time checks use the injected {@link Clock} with 60 s skew.
 */
@Service
public class DeviceTokenService {

    static final String ISSUER = "reliable-push";
    /**
     * Clock skew allowed on {@code iat}/{@code nbf}/{@code exp}. Also bounds the active-device window
     * ({@link DeviceRepository#hasActiveDevice}); the harness {@code TokenGrace} scenario and
     * {@code harness/compose/backend.env.template} (the short grace it runs with) rely on it: change them together.
     */
    static final Duration SKEW = Duration.ofSeconds(60);
    /** Real tokens are a few hundred bytes; anything this large is rejected before parsing. */
    private static final int MAX_TOKEN_LENGTH = 8192;
    /** Year 9999: keeps {@code Instant} arithmetic far from overflow for absurd {@code exp} values. */
    private static final long MAX_EPOCH_SECONDS = 253_402_300_799L;

    /** A freshly signed token. {@code toString} never prints the token. */
    public record IssuedToken(String token, Instant expiresAt) {
        @Override
        public String toString() {
            return "IssuedToken[expiresAt=" + expiresAt + "]";
        }
    }

    private final SigningKeys keys;
    private final Clock clock;

    public DeviceTokenService(SigningKeys keys, Clock clock) {
        this.keys = keys;
        this.clock = clock;
    }

    /**
     * Signs a token for the given claims with the current key. Timestamps are truncated to whole seconds.
     *
     * @throws IllegalStateException if no signing keys are configured
     * @throws IllegalArgumentException if the claims cannot form a valid token
     */
    public IssuedToken issue(DeviceClaims c) {
        if (c.appId() == null || c.appId().isBlank() || c.userId() == null || c.userId().isBlank()
                || c.deviceId() == null || c.tokenVersion() < 0 || c.issuedAt() == null || c.expiresAt() == null) {
            throw new IllegalArgumentException("invalid device token claims");
        }
        Instant issuedAt = c.issuedAt().truncatedTo(ChronoUnit.SECONDS);
        Instant expiresAt = c.expiresAt().truncatedTo(ChronoUnit.SECONDS);
        if (!expiresAt.isAfter(issuedAt)) {
            throw new IllegalArgumentException("invalid device token claims");
        }
        SigningKeys.Key key = keys.current();
        JWSHeader header =
                new JWSHeader.Builder(JWSAlgorithm.ES256).keyID(key.kid()).type(JOSEObjectType.JWT).build();
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .issuer(ISSUER)
                .audience(c.appId())
                .subject(c.userId())
                .claim("did", c.deviceId().toString())
                .claim("ver", c.tokenVersion())
                .issueTime(Date.from(issuedAt))
                .expirationTime(Date.from(expiresAt))
                .build();
        try {
            SignedJWT jwt = new SignedJWT(header, claims);
            jwt.sign(new ECDSASigner(key.privateKey()));
            return new IssuedToken(jwt.serialize(), expiresAt);
        } catch (JOSEException e) {
            // never chain: the cause text is not under our control
            throw new IllegalStateException("device token could not be signed");
        }
    }

    /**
     * Verifies signature and claims.
     *
     * @throws InvalidDeviceTokenException with reason {@code malformed} (unparseable, wrong algorithm, header-provided
     *     keys, missing/ill-typed claims, {@code iat}/{@code nbf} in the future), {@code unknown_kid}, {@code
     *     bad_signature}, {@code wrong_issuer} or {@code expired}
     */
    public DeviceClaims verify(String token) {
        return verify(token, appId -> Duration.ZERO);
    }

    /**
     * Like {@link #verify(String)}, but a token whose {@code exp} (plus the 60 s skew) is in the past by at most the
     * app's grace is still accepted. {@code graceByApp} maps the token's (signed) {@code aud} to that grace; zero
     * means no grace. Only the expiry check is relaxed. Callers use it for the grace routes only; use {@link
     * #isInGrace} to tell whether the accepted token is past its normal expiry.
     */
    public DeviceClaims verify(String token, Function<String, Duration> graceByApp) {
        if (token == null || token.isBlank() || token.length() > MAX_TOKEN_LENGTH) {
            throw new InvalidDeviceTokenException(MALFORMED);
        }
        SignedJWT jwt;
        try {
            jwt = SignedJWT.parse(token);
        } catch (ParseException | RuntimeException e) {
            throw new InvalidDeviceTokenException(MALFORMED);
        }
        JWSHeader header = jwt.getHeader();
        if (!JWSAlgorithm.ES256.equals(header.getAlgorithm())) {
            throw new InvalidDeviceTokenException(MALFORMED);
        }
        // keys come from our configuration only; a token that tries to bring its own is malformed outright
        if (header.getJWK() != null
                || header.getJWKURL() != null
                || header.getX509CertURL() != null
                || header.getX509CertChain() != null
                || (header.getCriticalParams() != null && !header.getCriticalParams().isEmpty())) {
            throw new InvalidDeviceTokenException(MALFORMED);
        }
        String kid = header.getKeyID();
        if (kid == null || kid.isBlank()) {
            throw new InvalidDeviceTokenException(MALFORMED);
        }
        SigningKeys.Key key = keys.byKid(kid).orElseThrow(() -> new InvalidDeviceTokenException(UNKNOWN_KID));
        try {
            if (!jwt.verify(new ECDSAVerifier(key.publicKey()))) {
                throw new InvalidDeviceTokenException(BAD_SIGNATURE);
            }
        } catch (JOSEException | RuntimeException e) {
            if (e instanceof InvalidDeviceTokenException invalid) {
                throw invalid;
            }
            throw new InvalidDeviceTokenException(BAD_SIGNATURE);
        }
        return checkClaims(jwt, graceByApp);
    }

    /** True if the (verified) claims are past their normal expiry (exp + skew), so only a grace route accepts them. */
    public boolean isInGrace(DeviceClaims claims) {
        return clock.instant().isAfter(claims.expiresAt().plus(SKEW));
    }

    private DeviceClaims checkClaims(SignedJWT jwt, Function<String, Duration> graceByApp) {
        // raw JSON values, not JWTClaimsSet: its typed getters coerce (a numeric "sub" becomes a string, "aud" becomes a
        // list), which strict verification must not accept
        Map<String, Object> claims;
        try {
            // toJSONObject() maps some non-objects (e.g. an empty array) to an empty map: insist on a JSON object
            claims = jwt.getPayload().toString().stripLeading().startsWith("{") ? jwt.getPayload().toJSONObject() : null;
        } catch (RuntimeException e) {
            throw new InvalidDeviceTokenException(MALFORMED);
        }
        if (claims == null) {
            throw new InvalidDeviceTokenException(MALFORMED);
        }
        if (!ISSUER.equals(claims.get("iss"))) {
            throw new InvalidDeviceTokenException(WRONG_ISSUER);
        }
        String appId = singleAudience(claims.get("aud"));
        if (!(claims.get("sub") instanceof String userId) || userId.isBlank()) {
            throw new InvalidDeviceTokenException(MALFORMED);
        }
        UUID deviceId = parseUuid(claims.get("did"));
        int version = parseVersion(claims.get("ver"));
        Instant issuedAt = requireTime(claims.get("iat"));
        Instant expiresAt = requireTime(claims.get("exp"));
        Instant notBefore = claims.containsKey("nbf") ? requireTime(claims.get("nbf")) : null;
        Instant now = clock.instant();
        Instant latestAllowedStart = now.plus(SKEW);
        if (issuedAt.isAfter(latestAllowedStart) || (notBefore != null && notBefore.isAfter(latestAllowedStart))) {
            throw new InvalidDeviceTokenException(MALFORMED);
        }
        Duration grace = graceByApp.apply(appId);
        if (now.isAfter(expiresAt.plus(SKEW).plus(grace == null ? Duration.ZERO : grace))) {
            throw new InvalidDeviceTokenException(EXPIRED);
        }
        return new DeviceClaims(appId, userId, deviceId, version, issuedAt, expiresAt);
    }

    /** A JWT NumericDate: a JSON number of seconds since the epoch (fractions are dropped). */
    private static Instant requireTime(Object claim) {
        if (claim instanceof Number number && !(claim instanceof Double d && (d.isNaN() || d.isInfinite()))) {
            long seconds = number.longValue();
            if (seconds >= 0 && seconds <= MAX_EPOCH_SECONDS) {
                return Instant.ofEpochSecond(seconds);
            }
        }
        throw new InvalidDeviceTokenException(MALFORMED);
    }

    /** {@code aud} must be exactly one non-blank string, either bare or as a one-element array. */
    private static String singleAudience(Object claim) {
        Object value = claim instanceof List<?> list && list.size() == 1 ? list.get(0) : claim;
        if (value instanceof String text && !text.isBlank()) {
            return text;
        }
        throw new InvalidDeviceTokenException(MALFORMED);
    }

    private static UUID parseUuid(Object claim) {
        if (!(claim instanceof String text)) {
            throw new InvalidDeviceTokenException(MALFORMED);
        }
        try {
            UUID uuid = UUID.fromString(text);
            if (!uuid.toString().equalsIgnoreCase(text)) {
                throw new InvalidDeviceTokenException(MALFORMED);
            }
            return uuid;
        } catch (IllegalArgumentException e) {
            throw new InvalidDeviceTokenException(MALFORMED);
        }
    }

    private static int parseVersion(Object claim) {
        if (claim instanceof Integer || claim instanceof Long) {
            long value = ((Number) claim).longValue();
            if (value >= 0 && value <= Integer.MAX_VALUE) {
                return (int) value;
            }
        }
        throw new InvalidDeviceTokenException(MALFORMED);
    }
}
