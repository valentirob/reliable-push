package io.reliablepush.notifications;

import io.reliablepush.shared.web.InvalidRequestException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Base64;
import java.util.UUID;

/** Keyset position of the list: base64url (no padding) of {@code <created_at ISO instant>|<id>}. Opaque to clients. */
record PageCursor(Instant createdAt, UUID id) {

    String encode() {
        return Base64.getUrlEncoder().withoutPadding().encodeToString((createdAt + "|" + id).getBytes(StandardCharsets.UTF_8));
    }

    static PageCursor decode(String cursor) {
        try {
            String text = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
            int separator = text.indexOf('|');
            if (separator < 0) {
                throw invalid();
            }
            Instant createdAt = Instant.parse(text.substring(0, separator));
            if (!inSaneRange(createdAt)) {
                throw invalid();
            }
            return new PageCursor(createdAt, UUID.fromString(text.substring(separator + 1)));
        } catch (IllegalArgumentException | DateTimeParseException e) {
            throw invalid();
        }
    }

    /** Parsers accept years up to +-999999999, which PostgreSQL's timestamptz rejects (a 500): only 1970..9999 is queried. */
    static final Instant MIN_INSTANT = Instant.parse("1970-01-01T00:00:00Z");

    static final Instant MAX_INSTANT = Instant.parse("9999-12-31T23:59:59Z");

    static boolean inSaneRange(Instant instant) {
        return !instant.isBefore(MIN_INSTANT) && !instant.isAfter(MAX_INSTANT);
    }

    private static InvalidRequestException invalid() {
        return new InvalidRequestException("cursor", "is not a valid cursor");
    }
}
