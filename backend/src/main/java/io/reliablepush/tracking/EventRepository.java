package io.reliablepush.tracking;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Persistence of {@code notification_event} (plain SQL through {@link JdbcClient}); used by {@link TrackingService} only. */
@Repository
class EventRepository {

    private final JdbcClient jdbc;

    EventRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Stores the event unless one of the same type already exists for the notification. Atomic under concurrency:
     * the unique {@code (notification_id, type)} constraint lets exactly one of any number of concurrent inserts win;
     * the others wait for it and do nothing.
     *
     * @return {@code true} if the row was inserted, {@code false} for a duplicate
     */
    boolean insert(UUID notificationId, UUID deviceId, EventType type, Instant occurredAt, Instant receivedAt) {
        return jdbc.sql("""
                        insert into notification_event (notification_id, device_id, type, occurred_at, received_at, late)
                        values (:notificationId, :deviceId, :type, :occurredAt, :receivedAt, false)
                        on conflict (notification_id, type) do nothing
                        """)
                        .param("notificationId", notificationId)
                        .param("deviceId", deviceId)
                        .param("type", type.name())
                        .param("occurredAt", timestamp(occurredAt))
                        .param("receivedAt", timestamp(receivedAt))
                        .update()
                == 1;
    }

    void markLate(UUID notificationId, EventType type) {
        jdbc.sql("update notification_event set late = true where notification_id = :id and type = :type")
                .param("id", notificationId)
                .param("type", type.name())
                .update();
    }

    private static OffsetDateTime timestamp(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }
}
