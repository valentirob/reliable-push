package io.reliablepush.harness.logs;

/** Filters for {@link BackendLogTail#query}: exact match on each non-null field; {@code limit} is capped at 500. */
public record LogQuery(String traceId, String trackingId, String eventAction, int limit) {

    public static final int MAX_LIMIT = 500;

    int effectiveLimit() {
        return limit <= 0 ? MAX_LIMIT : Math.min(limit, MAX_LIMIT);
    }
}
