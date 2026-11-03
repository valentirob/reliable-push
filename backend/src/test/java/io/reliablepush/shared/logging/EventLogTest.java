package io.reliablepush.shared.logging;

import static io.reliablepush.shared.logging.EventCapture.data;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.ThreadContext;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.message.MapMessage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class EventLogTest {

    private EventCapture capture;

    @BeforeEach
    void startCapture() {
        capture = new EventCapture();
    }

    @AfterEach
    void stopCapture() {
        ThreadContext.clearMap();
        capture.close();
    }

    @Test
    void emitsActionAndFields() {
        EventLog.info("notification.registered", Map.of("rp.tracking_id", "t1", "event.outcome", "success"));

        LogEvent event = capture.only();
        assertThat(event.getLevel()).isEqualTo(Level.INFO);
        assertThat(event.getMessage()).isInstanceOf(MapMessage.class);
        assertThat(data(event))
                .containsEntry("event.action", "notification.registered")
                .containsEntry("rp.tracking_id", "t1")
                .containsEntry("event.outcome", "success");
        // message is the human-readable action, not the key=value dump
        assertThat(event.getMessage().getFormattedMessage()).isEqualTo("notification.registered");
    }

    @Test
    void warnEmitsAtWarnLevel() {
        EventLog.warn("auth.failed", Map.of("rp.reason", "invalid_key"));

        LogEvent event = capture.only();
        assertThat(event.getLevel()).isEqualTo(Level.WARN);
        assertThat(data(event)).containsEntry("event.action", "auth.failed").containsEntry("rp.reason", "invalid_key");
    }

    @Test
    void keepsNumericValuesTyped() {
        EventLog.info("api.request", Map.of("event.duration", 1500L, "http.response.status_code", 200));

        assertThat(data(capture.only()))
                .containsEntry("event.duration", 1500L)
                .containsEntry("http.response.status_code", 200);
    }

    @Test
    void skipsNullValuesAndCannotOverrideAction() {
        Map<String, Object> fields = new HashMap<>();
        fields.put("rp.app_id", null);
        fields.put("rp.tracking_id", "t1");
        fields.put("event.action", "spoofed");

        EventLog.info("x.y", fields);

        assertThat(data(capture.only()))
                .containsEntry("rp.tracking_id", "t1")
                .containsEntry("event.action", "x.y")
                .doesNotContainKey("rp.app_id");
    }

    @Test
    void rejectsSensitiveKeys() {
        for (String key : List.of("token", "idToken", "apiKey", "secret", "signature", "message", "body", "userId")) {
            assertThatThrownBy(() -> EventLog.info("x", Map.of(key, "abc")))
                    .as(key)
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> EventLog.warn("x", Map.of(key, "abc")))
                    .as(key)
                    .isInstanceOf(IllegalArgumentException.class);
        }
        assertThat(capture.events()).isEmpty();
    }

    @Test
    void rejectsKeysFixedByTheTemplate() {
        for (String key : List.of("@timestamp", "ecs.version", "log.level", "log.logger", "process.thread.name",
                "service.name", "service.version", "trace.id", "tags", "error.type", "error.message",
                "error.stack_trace", "labels.other")) {
            assertThatThrownBy(() -> EventLog.info("x", Map.of(key, "abc")))
                    .as(key)
                    .isInstanceOf(IllegalArgumentException.class);
        }
        assertThat(capture.events()).isEmpty();
    }

    @Test
    void doesNotRepeatContextFieldWithSameValue() {
        ThreadContext.put("rp.tracking_id", "t1");

        EventLog.info("fallback.claimed", Map.of("rp.tracking_id", "t1", "rp.device_id", "d1"));

        assertThat(data(capture.only()))
                .doesNotContainKey("rp.tracking_id")
                .containsEntry("rp.device_id", "d1");
    }

    @Test
    void rejectsContextFieldWithDifferentValue() {
        ThreadContext.put("rp.tracking_id", "t1");

        assertThatThrownBy(() -> EventLog.info("fallback.claimed", Map.of("rp.tracking_id", "t2")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(capture.events()).isEmpty();
    }
}
