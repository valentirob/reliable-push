package io.reliablepush.devices.identity;

import static io.reliablepush.devices.identity.IdentityRejectedException.EXPIRED;
import static io.reliablepush.devices.identity.IdentityRejectedException.MALFORMED;
import static io.reliablepush.devices.identity.IdentityRejectedException.MISSING_USER_CLAIM;
import static io.reliablepush.devices.identity.IdentityRejectedException.NOT_YET_VALID;

import com.nimbusds.jwt.SignedJWT;
import java.text.ParseException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;

/**
 * Strict readers shared by the verifiers. Claims are read from the raw JSON values: nimbus' typed getters coerce (a
 * numeric {@code sub} becomes a string, {@code aud} always becomes a list), which verification must not accept.
 */
final class TokenClaims {

    static final Duration SKEW = Duration.ofSeconds(60);
    static final int MAX_USER_ID_LENGTH = 256;
    /** Real tokens are a few hundred bytes to a few KB; anything this large is rejected before parsing. */
    private static final int MAX_TOKEN_LENGTH = 8192;
    /** Year 9999: keeps {@code Instant} arithmetic far from overflow for absurd timestamps. */
    private static final long MAX_EPOCH_SECONDS = 253_402_300_799L;

    private TokenClaims() {}

    /** Parses a compact JWS. Anything else (null, oversized, unsecured JWT, JWE, garbage) is {@code malformed}. */
    static SignedJWT parse(String token) {
        if (token == null || token.isBlank() || token.length() > MAX_TOKEN_LENGTH) {
            throw new IdentityRejectedException(MALFORMED);
        }
        try {
            return SignedJWT.parse(token);
        } catch (ParseException | RuntimeException e) {
            throw new IdentityRejectedException(MALFORMED);
        }
    }

    /** The payload as a JSON object; {@code malformed} if it is anything else. */
    static Map<String, Object> payload(SignedJWT jwt) {
        try {
            // toJSONObject() maps some non-objects (e.g. an empty array) to an empty map: insist on a JSON object
            if (!jwt.getPayload().toString().stripLeading().startsWith("{")) {
                throw new IdentityRejectedException(MALFORMED);
            }
            Map<String, Object> claims = jwt.getPayload().toJSONObject();
            if (claims == null) {
                throw new IdentityRejectedException(MALFORMED);
            }
            return claims;
        } catch (RuntimeException e) {
            if (e instanceof IdentityRejectedException rejected) {
                throw rejected;
            }
            throw new IdentityRejectedException(MALFORMED);
        }
    }

    /** A JWT NumericDate: a JSON number of seconds since the epoch (fractions are dropped); anything else is {@code malformed}. */
    static Instant time(Object claim) {
        if (claim instanceof Number number && !(claim instanceof Double d && (d.isNaN() || d.isInfinite()))) {
            long seconds = number.longValue();
            if (seconds >= 0 && seconds <= MAX_EPOCH_SECONDS) {
                return Instant.ofEpochSecond(seconds);
            }
        }
        throw new IdentityRejectedException(MALFORMED);
    }

    /** Like {@link #time} but absent is {@code null}. */
    static Instant optionalTime(Map<String, Object> claims, String name) {
        return claims.containsKey(name) ? time(claims.get(name)) : null;
    }

    /** {@code iat}/{@code nbf} more than the skew in the future are {@code not_yet_valid}. */
    static void requireStarted(Instant issuedAtOrNotBefore, Clock clock) {
        if (issuedAtOrNotBefore != null && issuedAtOrNotBefore.isAfter(clock.instant().plus(SKEW))) {
            throw new IdentityRejectedException(NOT_YET_VALID);
        }
    }

    /** Past {@code exp} plus the skew is {@code expired}. */
    static void requireNotExpired(Instant expiresAt, Clock clock) {
        if (clock.instant().isAfter(expiresAt.plus(SKEW))) {
            throw new IdentityRejectedException(EXPIRED);
        }
    }

    /** The user id must be a non-blank JSON string of at most 256 characters; otherwise {@code missing_user_claim}. */
    static String userId(Object claim) {
        if (claim instanceof String text && !text.isBlank() && text.length() <= MAX_USER_ID_LENGTH) {
            return text;
        }
        throw new IdentityRejectedException(MISSING_USER_CLAIM);
    }
}
