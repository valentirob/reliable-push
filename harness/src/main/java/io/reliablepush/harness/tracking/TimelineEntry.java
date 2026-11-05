package io.reliablepush.harness.tracking;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One line of a notification's history. {@code kind}: registered | push_simulated | event_sent | state_changed |
 * webhook_in_flight | webhook_received | cancelled. {@code details} never carries secrets, API keys or device tokens.
 */
public record TimelineEntry(Instant at, String kind, Map<String, Object> details) {

    public TimelineEntry {
        details = details == null ? Map.of() : java.util.Collections.unmodifiableMap(new LinkedHashMap<>(details));
    }

    /** JSON-ready view (ISO-8601 instant). */
    Map<String, Object> toJson() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("at", at.toString());
        m.put("kind", kind);
        m.put("details", details);
        return m;
    }
}
