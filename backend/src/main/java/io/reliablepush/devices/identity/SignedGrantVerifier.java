package io.reliablepush.devices.identity;

import static io.reliablepush.devices.identity.IdentityRejectedException.BAD_SIGNATURE;
import static io.reliablepush.devices.identity.IdentityRejectedException.LIFETIME_TOO_LONG;
import static io.reliablepush.devices.identity.IdentityRejectedException.MALFORMED;
import static io.reliablepush.devices.identity.IdentityRejectedException.WRONG_AUDIENCE;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.crypto.MACVerifier;
import com.nimbusds.jwt.SignedJWT;
import io.reliablepush.shared.config.Secret;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Verifies a short-lived grant minted by the customer's backend and signed (HS256) with a secret shared with this
 * service. Required claims: {@code aud} = the app id, {@code sub} = the user id, {@code iat} and {@code exp} with
 * {@code exp - iat} at most 5 minutes. Only HS256 is accepted. Time checks use the injected {@link Clock} with 60 s
 * skew. Grants carry no {@code jti} store: within their (at most 5 minute) lifetime a grant can be replayed by whoever
 * holds it, which is why the lifetime is capped.
 */
public final class SignedGrantVerifier implements IdentityVerifier {

    static final Duration MAX_LIFETIME = Duration.ofMinutes(5);
    static final int MIN_SECRET_BYTES = 32;

    private final String appId;
    private final byte[] secret;
    private final Clock clock;

    /** @throws IllegalStateException if the secret is shorter than 32 bytes (the message never contains the secret) */
    public SignedGrantVerifier(String appId, Secret grantSecret, Clock clock) {
        byte[] bytes = grantSecret.bytes();
        if (bytes.length < MIN_SECRET_BYTES) {
            throw new IllegalStateException(
                    "identity.grant-secret-ref of app " + appId + " must resolve to at least " + MIN_SECRET_BYTES + " bytes");
        }
        this.appId = appId;
        this.secret = bytes;
        this.clock = clock;
    }

    @Override
    public VerifiedIdentity verify(String credential) {
        SignedJWT jwt = TokenClaims.parse(credential);
        if (!JWSAlgorithm.HS256.equals(jwt.getHeader().getAlgorithm())) {
            throw new IdentityRejectedException(MALFORMED);
        }
        try {
            // MACVerifier compares in constant time
            if (!jwt.verify(new MACVerifier(secret))) {
                throw new IdentityRejectedException(BAD_SIGNATURE);
            }
        } catch (JOSEException | RuntimeException e) {
            if (e instanceof IdentityRejectedException rejected) {
                throw rejected;
            }
            throw new IdentityRejectedException(BAD_SIGNATURE);
        }

        Map<String, Object> claims = TokenClaims.payload(jwt);
        requireAudience(claims.get("aud"));
        Instant issuedAt = claims.containsKey("iat") ? TokenClaims.time(claims.get("iat")) : null;
        Instant expiresAt = claims.containsKey("exp") ? TokenClaims.time(claims.get("exp")) : null;
        if (issuedAt == null || expiresAt == null || !expiresAt.isAfter(issuedAt)) {
            throw new IdentityRejectedException(MALFORMED);
        }
        if (Duration.between(issuedAt, expiresAt).compareTo(MAX_LIFETIME) > 0) {
            throw new IdentityRejectedException(LIFETIME_TOO_LONG);
        }
        TokenClaims.requireStarted(issuedAt, clock);
        TokenClaims.requireStarted(TokenClaims.optionalTime(claims, "nbf"), clock);
        TokenClaims.requireNotExpired(expiresAt, clock);
        return new VerifiedIdentity(TokenClaims.userId(claims.get("sub")));
    }

    /** {@code aud} must be exactly the app id, bare or as a one-element array. */
    private void requireAudience(Object aud) {
        if (aud == null) {
            throw new IdentityRejectedException(WRONG_AUDIENCE);
        }
        List<?> values = aud instanceof List<?> list ? list : List.of(aud);
        if (!values.stream().allMatch(String.class::isInstance)) {
            throw new IdentityRejectedException(MALFORMED);
        }
        if (values.size() != 1 || !appId.equals(values.get(0))) {
            throw new IdentityRejectedException(WRONG_AUDIENCE);
        }
    }
}
