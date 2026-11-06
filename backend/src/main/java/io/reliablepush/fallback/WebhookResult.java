package io.reliablepush.fallback;

/**
 * Outcome of one webhook attempt (spec 5): only {@code 2xx} is {@link Success}; {@link Final} for a {@code 4xx} other
 * than {@code 408/429}; {@link Retryable} for everything else (timeouts, I/O errors, {@code 408/429/5xx}, redirects).
 * {@code reason} is a short code for logs (e.g. {@code http_503}, {@code timeout}); never a response body.
 */
public sealed interface WebhookResult {

    record Success(int status) implements WebhookResult {}

    record Retryable(String reason) implements WebhookResult {}

    record Final(String reason) implements WebhookResult {}
}
