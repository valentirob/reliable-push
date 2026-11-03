package io.reliablepush.shared.config;

import java.nio.charset.StandardCharsets;

/** A resolved secret value (HMAC secret, key material). {@code toString} never reveals it, so it cannot leak into logs. */
public record Secret(String value) {

    public Secret {
        if (value == null || value.isEmpty()) {
            throw new IllegalArgumentException("secret must not be empty");
        }
    }

    public byte[] bytes() {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    @Override
    public String toString() {
        return "Secret[REDACTED]";
    }
}
