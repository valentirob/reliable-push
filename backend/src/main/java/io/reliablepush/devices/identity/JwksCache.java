package io.reliablepush.devices.identity;

import static io.reliablepush.devices.identity.IdentityRejectedException.JWKS_UNAVAILABLE;
import static io.reliablepush.devices.identity.IdentityRejectedException.MALFORMED;
import static io.reliablepush.devices.identity.IdentityRejectedException.UNKNOWN_KID;

import com.nimbusds.jose.jwk.Curve;
import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.KeyUse;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.util.JSONObjectUtils;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.text.ParseException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.locks.ReentrantLock;

/**
 * The signing keys of one identity provider, fetched from its JWKS and cached.
 *
 * <ul>
 *   <li>The JWKS URL is the configured {@code jwks-uri}, or else {@code jwks_uri} from the provider's discovery
 *       document ({@code <issuer>/.well-known/openid-configuration}, whose {@code issuer} must equal the configured one).
 *       Both follow {@link JwksUrls}. Redirects are never followed.
 *   <li>Keys are cached for 15 minutes. A token with a {@code kid} that is not cached may refresh the JWKS, at most once
 *       per 30 seconds, so a flood of random kids costs at most one fetch per 30 s.
 *   <li>Outage tolerance: when a refresh fails, the cached keys keep being served and the next attempt is at the
 *       earliest 30 seconds later (a failing provider is not hammered). With nothing cached the request is rejected as
 *       {@code jwks_unavailable}.
 *   <li>Every fetch has a 5 s overall deadline and a 64 KB size limit.
 * </ul>
 *
 * Hand-rolled instead of nimbus' {@code JWKSourceBuilder}: it needs the discovery step, and its rate limiter works on
 * system time and lets the initial load plus one refresh through per window rather than "one refresh per 30 s", which
 * could not be tested with the injected {@link Clock}.
 */
final class JwksCache {

    static final Duration TTL = Duration.ofMinutes(15);
    static final Duration MIN_REFRESH_INTERVAL = Duration.ofSeconds(30);
    static final Duration TIMEOUT = Duration.ofSeconds(5);
    static final int MAX_RESPONSE_BYTES = 64 * 1024;
    private static final int MIN_RSA_BITS = 2048;

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(TIMEOUT)
            .followRedirects(HttpClient.Redirect.NEVER)
            .version(HttpClient.Version.HTTP_1_1)
            .build();

    private record Snapshot(List<JWK> keys, Instant fetchedAt) {}

    private final String issuer;
    private final URI issuerUri;
    private final URI configuredJwksUri;
    private final Clock clock;
    private final ReentrantLock lock = new ReentrantLock();

    private volatile Snapshot snapshot;
    // guarded by lock
    private URI discoveredJwksUri;
    private Instant discoveredAt;
    private Instant nextAttemptAt = Instant.MIN;
    private Instant nextKidRefreshAt = Instant.MIN;

    /** URLs must already have been checked with {@link JwksUrls}. */
    JwksCache(String issuer, URI issuerUri, URI configuredJwksUri, Clock clock) {
        this.issuer = issuer;
        this.issuerUri = issuerUri;
        this.configuredJwksUri = configuredJwksUri;
        this.clock = clock;
    }

    /**
     * The public key for {@code kid}. A token without {@code kid} is accepted only when the JWKS has exactly one key.
     *
     * @throws IdentityRejectedException {@code malformed} (no kid, several keys), {@code unknown_kid}, {@code jwks_unavailable}
     */
    JWK keyFor(String kid) {
        Snapshot current = snapshot;
        if (current != null && clock.instant().isBefore(current.fetchedAt().plus(TTL))) {
            JWK key = select(current, kid);
            if (key != null) {
                return key;
            }
        }
        return keyForSlow(kid);
    }

    private JWK keyForSlow(String kid) {
        lock.lock();
        try {
            Instant now = clock.instant();
            Snapshot current = snapshot;
            boolean fetchedNow = false;
            if (current == null || !now.isBefore(current.fetchedAt().plus(TTL))) {
                if (!now.isBefore(nextAttemptAt)) {
                    fetchedNow = refresh(now);
                    current = snapshot;
                }
                if (current == null) {
                    throw new IdentityRejectedException(JWKS_UNAVAILABLE);
                }
            }
            JWK key = select(current, kid);
            if (key != null) {
                return key;
            }
            if (fetchedNow || now.isBefore(nextKidRefreshAt) || now.isBefore(nextAttemptAt)) {
                throw new IdentityRejectedException(UNKNOWN_KID);
            }
            // the kid may belong to a key published after the last fetch (rotation): refresh, at most every 30 s
            nextKidRefreshAt = now.plus(MIN_REFRESH_INTERVAL);
            boolean refreshed = refresh(now);
            key = select(snapshot, kid);
            if (key != null) {
                return key;
            }
            throw new IdentityRejectedException(refreshed ? UNKNOWN_KID : JWKS_UNAVAILABLE);
        } finally {
            lock.unlock();
        }
    }

    /** The key for {@code kid}, or the only key when there is no {@code kid}; {@code null} if there is none. */
    private static JWK select(Snapshot snapshot, String kid) {
        List<JWK> keys = snapshot.keys();
        if (kid == null) {
            if (keys.size() > 1) {
                throw new IdentityRejectedException(MALFORMED);
            }
            return keys.size() == 1 ? keys.get(0) : null;
        }
        for (JWK key : keys) {
            if (kid.equals(key.getKeyID())) {
                return key;
            }
        }
        return null;
    }

    /** Fetches and stores the keys; on failure keeps the old ones and delays the next attempt. Caller holds the lock. */
    private boolean refresh(Instant now) {
        try {
            URI jwksUri = configuredJwksUri != null ? configuredJwksUri : discover(now);
            snapshot = new Snapshot(parseKeys(get(jwksUri)), now);
            return true;
        } catch (IOException | RuntimeException e) {
            // deliberately no logging of the cause here: it may echo provider-controlled text
            discoveredJwksUri = null;
            nextAttemptAt = now.plus(MIN_REFRESH_INTERVAL);
            return false;
        }
    }

    private URI discover(Instant now) throws IOException {
        if (discoveredJwksUri != null && now.isBefore(discoveredAt.plus(TTL))) {
            return discoveredJwksUri;
        }
        String base = issuer.endsWith("/") ? issuer.substring(0, issuer.length() - 1) : issuer;
        Map<String, Object> document;
        try {
            document = JSONObjectUtils.parse(get(URI.create(base + "/.well-known/openid-configuration")));
        } catch (ParseException e) {
            throw new IOException("discovery document is not JSON");
        }
        if (!issuer.equals(document.get("issuer"))) {
            throw new IOException("discovery document names another issuer");
        }
        URI jwksUri = document.get("jwks_uri") instanceof String text ? JwksUrls.parse(text) : null;
        if (jwksUri == null || !JwksUrls.allowed(jwksUri, issuerUri)) {
            throw new IOException("discovery jwks_uri is not acceptable");
        }
        discoveredJwksUri = jwksUri;
        discoveredAt = now;
        return jwksUri;
    }

    private static String get(URI uri) throws IOException {
        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(TIMEOUT)
                .header("Accept", "application/json")
                .GET()
                .build();
        CompletableFuture<HttpResponse<byte[]>> future = HTTP.sendAsync(request, info -> new LimitedBody());
        try {
            // the request timeout covers the response headers only; this bounds the whole exchange, body included
            HttpResponse<byte[]> response = future.get(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
            if (response.statusCode() != 200) {
                throw new IOException("unacceptable response");
            }
            return new String(response.body(), StandardCharsets.UTF_8);
        } catch (TimeoutException e) {
            future.cancel(true);
            throw new IOException("timeout");
        } catch (InterruptedException e) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            throw new IOException("interrupted");
        } catch (ExecutionException e) {
            throw new IOException("request failed");
        }
    }

    /** Collects a response body, aborting the download as soon as it exceeds {@link #MAX_RESPONSE_BYTES}. */
    private static final class LimitedBody implements HttpResponse.BodySubscriber<byte[]> {

        private final CompletableFuture<byte[]> result = new CompletableFuture<>();
        private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        private Flow.Subscription subscription;

        @Override
        public CompletionStage<byte[]> getBody() {
            return result;
        }

        @Override
        public void onSubscribe(Flow.Subscription subscription) {
            this.subscription = subscription;
            subscription.request(Long.MAX_VALUE);
        }

        @Override
        public void onNext(List<ByteBuffer> items) {
            for (ByteBuffer item : items) {
                if (buffer.size() + item.remaining() > MAX_RESPONSE_BYTES) {
                    subscription.cancel();
                    result.completeExceptionally(new IOException("response too large"));
                    return;
                }
                byte[] chunk = new byte[item.remaining()];
                item.get(chunk);
                buffer.writeBytes(chunk);
            }
        }

        @Override
        public void onError(Throwable throwable) {
            result.completeExceptionally(throwable);
        }

        @Override
        public void onComplete() {
            result.complete(buffer.toByteArray());
        }
    }

    /** Usable verification keys only; anything unparsable, private-only, too weak or not for signatures is skipped. */
    private static List<JWK> parseKeys(String body) throws IOException {
        Map<String, Object> jwks;
        try {
            jwks = JSONObjectUtils.parse(body);
        } catch (ParseException e) {
            throw new IOException("jwks is not JSON");
        }
        if (!(jwks.get("keys") instanceof List<?> entries)) {
            throw new IOException("jwks has no keys");
        }
        List<JWK> keys = new ArrayList<>();
        for (Object entry : entries) {
            if (!(entry instanceof Map<?, ?> raw)) {
                continue;
            }
            try {
                Map<String, Object> json = new java.util.HashMap<>();
                raw.forEach((k, v) -> json.put(String.valueOf(k), v));
                JWK key = JWK.parse(json).toPublicJWK();
                if (key != null && usable(key)) {
                    keys.add(key);
                }
            } catch (ParseException | RuntimeException e) {
                // skip this key; the others may be fine
            }
        }
        return List.copyOf(keys);
    }

    private static boolean usable(JWK key) {
        if (key.getKeyUse() != null && !KeyUse.SIGNATURE.equals(key.getKeyUse())) {
            return false;
        }
        if (key instanceof RSAKey rsa) {
            return rsa.size() >= MIN_RSA_BITS;
        }
        return key instanceof ECKey ec && Curve.P_256.equals(ec.getCurve());
    }
}
