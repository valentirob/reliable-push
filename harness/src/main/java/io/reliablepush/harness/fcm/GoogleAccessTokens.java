package io.reliablepush.harness.fcm;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.Signature;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * OAuth 2.0 JWT bearer grant for a service account, JDK only: an RS256 JWT is posted to Google's token endpoint and
 * the access token is cached until 60 s before it expires. Neither the key, the JWT nor the access token is logged
 * or printed.
 */
public final class GoogleAccessTokens {

    static final URI TOKEN_ENDPOINT = URI.create("https://oauth2.googleapis.com/token");
    static final String SCOPE = "https://www.googleapis.com/auth/firebase.messaging";
    private static final long EXPIRY_MARGIN_SECONDS = 60;
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final FcmCredentials credentials;
    private final Clock clock;
    private final URI endpoint;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    private String cached;
    private Instant cachedUntil = Instant.MIN;

    public GoogleAccessTokens(FcmCredentials credentials) {
        this(credentials, Clock.systemUTC(), TOKEN_ENDPOINT);
    }

    GoogleAccessTokens(FcmCredentials credentials, Clock clock) {
        this(credentials, clock, TOKEN_ENDPOINT);
    }

    /** Test seam: another token endpoint. */
    GoogleAccessTokens(FcmCredentials credentials, Clock clock, URI endpoint) {
        this.credentials = credentials;
        this.clock = clock;
        this.endpoint = endpoint;
    }

    /** A valid access token, requested from Google when none is cached or the cached one expires within 60 s. */
    public synchronized String accessToken() throws IOException {
        Instant now = clock.instant();
        if (cached != null && now.isBefore(cachedUntil)) {
            return cached;
        }
        String form = "grant_type=" + enc("urn:ietf:params:oauth:grant-type:jwt-bearer")
                + "&assertion=" + enc(jwtAssertion());
        HttpRequest req = HttpRequest.newBuilder(endpoint).timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form)).build();
        HttpResponse<String> res;
        try {
            res = http.send(req, HttpResponse.BodyHandlers.ofString());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted while requesting an access token");
        }
        if (res.statusCode() != 200) {
            throw new IOException("the token endpoint answered " + res.statusCode());
        }
        JsonNode json;
        try {
            json = JSON.readTree(res.body());
        } catch (RuntimeException e) {
            throw new IOException("the token endpoint answered something that is not JSON");
        }
        JsonNode token = json.get("access_token");
        JsonNode expires = json.get("expires_in");
        if (token == null || !token.isString() || token.asString().isBlank() || expires == null || !expires.isNumber()) {
            throw new IOException("the token endpoint answered without access_token / expires_in");
        }
        cached = token.asString();
        cachedUntil = now.plusSeconds(Math.max(0, expires.asLong() - EXPIRY_MARGIN_SECONDS));
        return cached;
    }

    /** Forgets the cached token (after the API refused it). */
    public synchronized void invalidate() {
        cached = null;
        cachedUntil = Instant.MIN;
    }

    /** The signed assertion: header {@code RS256/JWT}, claims iss, scope, aud, iat, exp = iat + 3600. */
    String jwtAssertion() throws IOException {
        long iat = clock.instant().getEpochSecond();
        Map<String, Object> header = new LinkedHashMap<>();
        header.put("alg", "RS256");
        header.put("typ", "JWT");
        Map<String, Object> claims = new LinkedHashMap<>();
        claims.put("iss", credentials.clientEmail());
        claims.put("scope", SCOPE);
        claims.put("aud", credentials.tokenUri().toString());
        claims.put("iat", iat);
        claims.put("exp", iat + 3600);
        String signingInput = b64(JSON.writeValueAsString(header)) + "." + b64(JSON.writeValueAsString(claims));
        try {
            Signature sig = Signature.getInstance("SHA256withRSA");
            sig.initSign(credentials.privateKey());
            sig.update(signingInput.getBytes(StandardCharsets.US_ASCII));
            return signingInput + "." + Base64.getUrlEncoder().withoutPadding().encodeToString(sig.sign());
        } catch (GeneralSecurityException e) {
            throw new IOException("could not sign the token request");
        }
    }

    private static String b64(String s) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(s.getBytes(StandardCharsets.UTF_8));
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    @Override
    public String toString() {
        return "GoogleAccessTokens[project=" + credentials.projectId() + ", token=<redacted>]";
    }
}
