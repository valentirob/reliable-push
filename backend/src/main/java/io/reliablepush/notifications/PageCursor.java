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
            return new PageCursor(Instant.parse(text.substring(0, separator)), UUID.fromString(text.substring(separator + 1)));
        } catch (IllegalArgumentException | DateTimeParseException e) {
            throw invalid();
        }
    }

    private static InvalidRequestException invalid() {
        return new InvalidRequestException("cursor", "is not a valid cursor");
    }
}
