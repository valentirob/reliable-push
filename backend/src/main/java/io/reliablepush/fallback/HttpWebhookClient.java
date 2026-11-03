package io.reliablepush.fallback;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.reliablepush.apps.AppConfig;
import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Production {@link WebhookClient} (spec 5): one signed JSON POST per attempt.
 *
 * <p>Body {@code {trackingId, userId, fallback, reason, attempt}} with {@code fallback} embedded as the original JSON
 * object; the signature covers the exact bytes sent. Redirects are never followed (a {@code 3xx} is a failed attempt),
 * the response body is discarded unread, and every delivery problem becomes a {@link WebhookResult}: only
 * {@code 2xx} is a success, {@code 4xx} other than {@code 408/429} is final, everything else (5xx, 3xx, timeout, I/O
 * error) is retryable. Never logged: secret, signature, request body, user id, response body.
 *
 * <p>{@code http-timeout} bounds the whole exchange (connect, headers and body): the call is sent asynchronously and
 * cancelled when the time is up, because the JDK request timeout alone does not cover reading the response body.
 * The JDK {@link HttpClient} is shared by all attempts; its connect timeout is the {@code http-timeout}, which is
 * configured per app, so there is one client per distinct timeout value (normally exactly one).
 */
public class HttpWebhookClient implements WebhookClient, AutoCloseable {

    private static final Logger LOG = LogManager.getLogger(HttpWebhookClient.class);

    static final String LATENCY_TIMER = "rp.webhook.latency";

    private final Clock clock;
    private final JsonMapper json;
    private final MeterRegistry meters;
    private final String userAgent;
    private final Map<Duration, HttpClient> clients = new ConcurrentHashMap<>();

    public HttpWebhookClient(Clock clock, JsonMapper json, MeterRegistry meters, String version) {
        this.clock = clock;
        this.json = json;
        this.meters = meters;
        this.userAgent = "reliable-push/" + version;
    }

    @Override
    public WebhookResult send(AppConfig app, FallbackCall call) {
        AppConfig.Fallback config = app.fallback();
        long started = System.nanoTime();
        try {
            byte[] body = body(call);
            long timestamp = clock.instant().getEpochSecond();
            HttpRequest request = HttpRequest.newBuilder(config.url())
                    .timeout(config.httpTimeout())
                    .header("Content-Type", "application/json")
                    .header("X-RP-Timestamp", Long.toString(timestamp))
                    .header("X-RP-Signature", WebhookSigner.sign(config.hmacSecret().bytes(), timestamp, body))
                    .header("Idempotency-Key", call.trackingId().toString())
                    .header("User-Agent", userAgent)
                    .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                    .build();
            HttpResponse<Void> response = exchange(client(config.httpTimeout()), request, config.httpTimeout());
            WebhookResult result = classify(response.statusCode());
            LOG.debug("Webhook answered with status {} in {} ms", response.statusCode(), millis(started));
            return result;
        } catch (TimeoutException | HttpTimeoutException e) {
            // no message, no cause: exception texts can quote the request URL
            LOG.debug("Webhook timed out after {} ms", millis(started));
            return new WebhookResult.Retryable("timeout");
        } catch (IOException e) {
            LOG.debug("Webhook call failed with an I/O error after {} ms", millis(started));
            return new WebhookResult.Retryable("io_error");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new WebhookResult.Retryable("interrupted");
        } finally {
            Timer.builder(LATENCY_TIMER).register(meters).record(Duration.ofNanos(System.nanoTime() - started));
        }
    }

    /**
     * Runs the exchange asynchronously and waits at most {@code timeout} for all of it: connect, response headers and
     * body. The JDK request timeout covers only the wait for the response headers, so a receiver that sends headers
     * and then stalls would block a plain {@code send} until it closes the connection, and the fallback tick (which
     * joins all rows) with it. On timeout or interrupt the exchange is cancelled, which closes the connection.
     */
    private static HttpResponse<Void> exchange(HttpClient client, HttpRequest request, Duration timeout)
            throws IOException, InterruptedException, TimeoutException {
        CompletableFuture<HttpResponse<Void>> pending = client.sendAsync(request, HttpResponse.BodyHandlers.discarding());
        try {
            return pending.get(timeout.toNanos(), TimeUnit.NANOSECONDS);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof IOException io) {
                throw io;
            }
            if (cause instanceof RuntimeException re) {
                throw re;
            }
            if (cause instanceof Error err) {
                throw err;
            }
            throw new IOException("webhook exchange failed");
        } catch (TimeoutException | InterruptedException e) {
            pending.cancel(true);
            throw e;
        }
    }

    static WebhookResult classify(int status) {
        if (status >= 200 && status < 300) {
            return new WebhookResult.Success(status);
        }
        boolean finalClientError = status >= 400 && status < 500 && status != 408 && status != 429;
        String reason = "http_" + status;
        return finalClientError ? new WebhookResult.Final(reason) : new WebhookResult.Retryable(reason);
    }

    private byte[] body(FallbackCall call) {
        ObjectNode body = json.createObjectNode();
        body.put("trackingId", call.trackingId().toString());
        body.put("userId", call.userId());
        body.set("fallback", call.fallback());
        body.put("reason", call.reason());
        body.put("attempt", call.attempt());
        return json.writeValueAsBytes(body);
    }

    private HttpClient client(Duration timeout) {
        return clients.computeIfAbsent(timeout, t -> HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(t)
                .build());
    }

    private static long millis(long startedNanos) {
        return Duration.ofNanos(System.nanoTime() - startedNanos).toMillis();
    }

    @Override
    public void close() {
        clients.values().forEach(HttpClient::close);
        clients.clear();
    }
}
