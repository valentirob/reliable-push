package io.reliablepush.support;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;

import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.JWSSigner;
import com.nimbusds.jose.crypto.ECDSASigner;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.Curve;
import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.KeyUse;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.ECKeyGenerator;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.UUID;

/**
 * A WireMock-backed OpenID provider on {@code http://localhost:<port>} for identity tests: serves the discovery
 * document and a JWKS, and mints RS256 ID tokens (and, via {@link #sign}, any other shape a test needs). Keys are
 * generated per instance (nothing is committed) and can be rotated to exercise JWKS refresh.
 */
public final class FakeOidc implements AutoCloseable {

    private final WireMockServer server;
    private final Clock clock;
    private final List<JWK> published = new ArrayList<>();
    private RSAKey active;
    private ResponseDefinitionBuilder jwksOverride;
    private ResponseDefinitionBuilder discoveryOverride;

    public FakeOidc() {
        this(Clock.systemUTC());
    }

    /** {@code clock} stamps {@code iat}/{@code exp} of minted tokens. */
    public FakeOidc(Clock clock) {
        this.clock = clock;
        this.server = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        this.server.start();
        this.active = newRsaKey();
        this.published.add(active.toPublicJWK());
        applyStubs();
    }

    public String issuer() {
        return "http://localhost:" + server.port();
    }

    public String jwksUri() {
        return issuer() + "/jwks";
    }

    /** Mints an RS256 ID token signed by the active key; {@code exp = now + ttl} (a negative ttl gives an expired token). */
    public String idToken(String sub, String aud, Duration ttl) {
        return sign(claims(sub, aud, ttl).build());
    }

    /** Claims of a well-formed ID token; tests adjust them before {@link #sign}. */
    public JWTClaimsSet.Builder claims(String sub, String aud, Duration ttl) {
        Instant now = clock.instant();
        return new JWTClaimsSet.Builder()
                .issuer(issuer())
                .subject(sub)
                .audience(aud)
                .issueTime(Date.from(now))
                .expirationTime(Date.from(now.plus(ttl)));
    }

    public String sign(JWTClaimsSet claims) {
        return sign(active, JWSAlgorithm.RS256, claims);
    }

    /** Signs with any key (published or not); the header carries that key's {@code kid}. */
    public static String sign(JWK key, JWSAlgorithm alg, JWTClaimsSet claims) {
        return sign(key, new JWSHeader.Builder(alg).keyID(key.getKeyID()).build(), claims);
    }

    /** Signs with any key and a caller-built header (to omit {@code kid}, add {@code jwk}, ...). */
    public static String sign(JWK key, JWSHeader header, JWTClaimsSet claims) {
        try {
            JWSSigner signer = key instanceof RSAKey rsa ? new RSASSASigner(rsa) : new ECDSASigner((ECKey) key);
            SignedJWT jwt = new SignedJWT(header, claims);
            jwt.sign(signer);
            return jwt.serialize();
        } catch (JOSEException e) {
            throw new IllegalStateException(e);
        }
    }

    public RSAKey activeKey() {
        return active;
    }

    /** Replaces the signing key: the new key becomes active and the JWKS contains only it. */
    public RSAKey rotate() {
        active = newRsaKey();
        published.clear();
        published.add(active.toPublicJWK());
        applyStubs();
        return active;
    }

    /** Publishes one more key (the active key does not change). */
    public void publishAlso(JWK key) {
        published.add(key.toPublicJWK());
        applyStubs();
    }

    public RSAKey newRsaKey() {
        try {
            return new RSAKeyGenerator(2048)
                    .keyID(UUID.randomUUID().toString())
                    .keyUse(KeyUse.SIGNATURE)
                    .generate();
        } catch (JOSEException e) {
            throw new IllegalStateException(e);
        }
    }

    public ECKey newEcKey() {
        try {
            return new ECKeyGenerator(Curve.P_256)
                    .keyID(UUID.randomUUID().toString())
                    .keyUse(KeyUse.SIGNATURE)
                    .generate();
        } catch (JOSEException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Makes the JWKS endpoint answer with this response (status, body, delay, redirect) until {@link #restoreJwks()}. */
    public void respondToJwksWith(ResponseDefinitionBuilder response) {
        jwksOverride = response;
        applyStubs();
    }

    public void restoreJwks() {
        jwksOverride = null;
        applyStubs();
    }

    public void respondToDiscoveryWith(ResponseDefinitionBuilder response) {
        discoveryOverride = response;
        applyStubs();
    }

    public int jwksRequests() {
        return server.findAll(getRequestedFor(urlPathEqualTo("/jwks"))).size();
    }

    public int discoveryRequests() {
        return server.findAll(getRequestedFor(urlPathEqualTo("/.well-known/openid-configuration"))).size();
    }

    public void resetRequests() {
        server.resetRequests();
    }

    private void applyStubs() {
        server.resetMappings();
        server.stubFor(get(urlPathEqualTo("/.well-known/openid-configuration"))
                .willReturn(discoveryOverride != null
                        ? discoveryOverride
                        : aResponse()
                                .withHeader("Content-Type", "application/json")
                                .withBody("{\"issuer\":\"" + issuer() + "\",\"jwks_uri\":\"" + jwksUri() + "\"}")));
        server.stubFor(get(urlPathEqualTo("/jwks"))
                .willReturn(jwksOverride != null
                        ? jwksOverride
                        : aResponse()
                                .withHeader("Content-Type", "application/json")
                                .withBody(new JWKSet(new ArrayList<>(published)).toString())));
    }

    @Override
    public void close() {
        server.stop();
    }
}
