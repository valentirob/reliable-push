package io.reliablepush.tracking;

import java.util.List;
import java.util.UUID;

/**
 * Result of a batch. {@code accepted} counts the items whose event row was stored ({@code ACCEPTED} and
 * {@code LATE}); {@code ignored} counts {@code DUPLICATE}, {@code NOT_FOUND} and {@code FORBIDDEN}.
 * {@code items} has one entry per submitted event, in request order.
 */
public record EventBatchResult(int accepted, int ignored, List<ItemResult> items) {

    /**
     * {@code ACCEPTED}: stored and the state moved. {@code LATE}: stored, but no transition applied any more
     * (fallback claimed, cancelled, already read, ...). {@code DUPLICATE}: same trackingId and type seen before.
     */
    public enum Outcome {
        ACCEPTED,
        DUPLICATE,
        LATE,
        NOT_FOUND,
        FORBIDDEN
    }

    public record ItemResult(UUID trackingId, Outcome outcome) {}

    static EventBatchResult of(List<ItemResult> items) {
        int accepted = 0;
        for (ItemResult item : items) {
            if (item.outcome() == Outcome.ACCEPTED || item.outcome() == Outcome.LATE) {
                accepted++;
            }
        }
        return new EventBatchResult(accepted, items.size() - accepted, List.copyOf(items));
    }
}
