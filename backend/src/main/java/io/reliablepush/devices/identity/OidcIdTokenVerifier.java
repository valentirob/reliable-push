package io.reliablepush.devices.identity;

import static io.reliablepush.devices.identity.IdentityRejectedException.BAD_SIGNATURE;
import static io.reliablepush.devices.identity.IdentityRejectedException.MALFORMED;
import static io.reliablepush.devices.identity.IdentityRejectedException.WRONG_AUDIENCE;
import static io.reliablepush.devices.identity.IdentityRejectedException.WRONG_ISSUER;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.JWSVerifier;
import com.nimbusds.jose.crypto.ECDSAVerifier;
import com.nimbusds.jose.crypto.RSASSAVerifier;
import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jwt.SignedJWT;
import io.reliablepush.apps.AppConfig;
import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Verifies an OIDC ID token against the app's identity provider (spec 5): signature (RS256 or ES256 only) with keys
 * from the provider's JWKS, {@code iss} (exact), {@code aud} (contains the configured audience), {@code exp}, and
 * {@code iat}/{@code nbf} (60 s skew via the injected {@link Clock}); the user id is the configured claim
 * (default {@code sub}), which must be a non-blank string of at most 256 characters.
 *
 * <p>Keys come from the configured JWKS only: {@code jwk}, {@code jku}, {@code x5u} and {@code x5c} token headers are
 * ignored. {@code iat} is not required (it is validated when present); {@code exp} is.
 */
public final class OidcIdTokenVerifier implements IdentityVerifier {

    private final String issuer;
    private final String audience;
    private final String userClaim;
    private final Clock clock;
    private final JwksCache jwks;

    /**
     * @throws IllegalStateException if the issuer or {@code jwks-uri} is not an https URL (http is allowed only when the
     *     issuer host is {@code localhost} or {@code 127.0.0.1}); nothing is fetched here
     */
    public OidcIdTokenVerifier(AppConfig.Identity identity, Clock clock) {
        URI issuerUri = JwksUrls.parse(identity.issuer());
        if (issuerUri == null || !JwksUrls.allowed(issuerUri, issuerUri)) {
            throw new IllegalStateException("identity.issuer must be an https URL (http only for localhost)");
        }
        URI jwksUri = null;
        if (identity.jwksUri() != null && !identity.jwksUri().isBlank()) {
            jwksUri = JwksUrls.parse(identity.jwksUri());
            if (jwksUri == null || !JwksUrls.allowed(jwksUri, issuerUri)) {
                throw new IllegalStateException("identity.jwks-uri must be an https URL (http only when the issuer is localhost)");
            }
        }
        this.issuer = identity.issuer();
        this.audience = identity.audience();
        this.userClaim = identity.userClaim();
        this.clock = clock;
        this.jwks = new JwksCache(identity.issuer(), issuerUri, jwksUri, clock);
    }

    @Override
    public VerifiedIdentity verify(String credential) {
        SignedJWT jwt = TokenClaims.parse(credential);
        JWSHeader header = jwt.getHeader();
        JWSAlgorithm alg = header.getAlgorithm();
        if (!JWSAlgorithm.RS256.equals(alg) && !JWSAlgorithm.ES256.equals(alg)) {
            throw new IdentityRejectedException(MALFORMED);
        }
        String kid = header.getKeyID() == null || header.getKeyID().isBlank() ? null : header.getKeyID();
        verifySignature(jwt, alg, jwks.keyFor(kid));

        Map<String, Object> claims = TokenClaims.payload(jwt);
        if (!issuer.equals(claims.get("iss"))) {
            throw new IdentityRejectedException(WRONG_ISSUER);
        }
        requireAudience(claims.get("aud"));
        Instant expiresAt = TokenClaims.time(claims.get("exp"));
        TokenClaims.requireStarted(TokenClaims.optionalTime(claims, "iat"), clock);
        TokenClaims.requireStarted(TokenClaims.optionalTime(claims, "nbf"), clock);
        TokenClaims.requireNotExpired(expiresAt, clock);
        return new VerifiedIdentity(TokenClaims.userId(claims.get(userClaim)));
    }

    private static void verifySignature(SignedJWT jwt, JWSAlgorithm alg, JWK key) {
        try {
            if (key.getAlgorithm() != null && !alg.getName().equals(key.getAlgorithm().getName())) {
                throw new IdentityRejectedException(BAD_SIGNATURE);
            }
            JWSVerifier verifier;
            if (JWSAlgorithm.RS256.equals(alg) && key instanceof RSAKey rsa) {
                verifier = new RSASSAVerifier(rsa.toRSAPublicKey());
            } else if (JWSAlgorithm.ES256.equals(alg) && key instanceof ECKey ec) {
                verifier = new ECDSAVerifier(ec.toECPublicKey());
            } else {
                throw new IdentityRejectedException(BAD_SIGNATURE);
            }
            if (!jwt.verify(verifier)) {
                throw new IdentityRejectedException(BAD_SIGNATURE);
            }
        } catch (JOSEException | RuntimeException e) {
            if (e instanceof IdentityRejectedException rejected) {
                throw rejected;
            }
            throw new IdentityRejectedException(BAD_SIGNATURE);
        }
    }

    /** {@code aud} is a string or an array of strings; the configured audience must be one of them. */
    private void requireAudience(Object aud) {
        if (aud == null) {
            throw new IdentityRejectedException(WRONG_AUDIENCE);
        }
        List<?> values = aud instanceof List<?> list ? list : List.of(aud);
        if (!values.stream().allMatch(String.class::isInstance)) {
            throw new IdentityRejectedException(MALFORMED);
        }
        if (!values.contains(audience)) {
            throw new IdentityRejectedException(WRONG_AUDIENCE);
        }
    }
}
