package io.reliablepush.client;

import java.net.URI;
import java.time.Duration;
import java.util.Objects;

/** Builder of {@link SenderClient}. {@code baseUrl} and {@code apiKey} are required; timeout defaults to 10 s. */
public final class SenderClientBuilder {

    private URI baseUrl;
    private String apiKey;
    private Duration timeout = Duration.ofSeconds(10);

    SenderClientBuilder() {
    }

    public SenderClientBuilder baseUrl(URI baseUrl) {
        this.baseUrl = baseUrl;
        return this;
    }

    public SenderClientBuilder apiKey(String apiKey) {
        this.apiKey = apiKey;
        return this;
    }

    /** Per-request timeout (also used as connect timeout). Must be positive. */
    public SenderClientBuilder timeout(Duration timeout) {
        this.timeout = timeout;
        return this;
    }

    public SenderClient build() {
        Objects.requireNonNull(baseUrl, "baseUrl");
        Objects.requireNonNull(apiKey, "apiKey");
        Objects.requireNonNull(timeout, "timeout");
        String scheme = baseUrl.getScheme();
        if (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme) || baseUrl.getHost() == null) {
            throw new IllegalArgumentException("baseUrl must be an absolute http(s) URL");
        }
        if (apiKey.isBlank() || apiKey.chars().anyMatch(c -> c < 0x21 || c > 0x7e)) {
            throw new IllegalArgumentException("apiKey must be non-empty printable ASCII");
        }
        if (timeout.isNegative() || timeout.isZero()) {
            throw new IllegalArgumentException("timeout must be positive");
        }
        return new SenderClient(baseUrl, apiKey, timeout);
    }
}
