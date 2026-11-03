package io.reliablepush.shared.logging;

import java.util.Map;
import java.util.Set;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.message.MapMessage;

/**
 * Emits the catalogue events of spec 9.1. Every event is a Log4j {@link MapMessage} whose entries
 * ({@code event.action}, {@code event.outcome}, {@code rp.*}, ...) become top-level ECS fields in the
 * JSON output (see {@code log4j2/ecs-reliable-push.json}); the {@code message} field is the action.
 */
public final class EventLog {

    /** Keys that can carry secrets, personal data or message content: never loggable. */
    private static final Set<String> DENIED_KEYS =
            Set.of("token", "idToken", "apiKey", "secret", "signature", "message", "body", "userId");

    private static final String ACTION_KEY = "event.action";

    private static final Logger LOGGER = LogManager.getLogger("io.reliablepush.event");

    private EventLog() {}

    public static void info(String action, Map<String, ?> fields) {
        log(Level.INFO, action, fields);
    }

    public static void warn(String action, Map<String, ?> fields) {
        log(Level.WARN, action, fields);
    }

    private static void log(Level level, String action, Map<String, ?> fields) {
        for (String key : fields.keySet()) {
            if (DENIED_KEYS.contains(key)) {
                throw new IllegalArgumentException("Field is never logged: " + key);
            }
        }
        if (!LOGGER.isEnabled(level)) {
            return;
        }
        EventMessage message = new EventMessage(action);
        fields.forEach((key, value) -> {
            if (value != null && !ACTION_KEY.equals(key)) {
                message.with(key, value);
            }
        });
        LOGGER.log(level, message);
    }

    /** MapMessage whose human-readable form is just the action; typed values stay typed in JSON. */
    private static final class EventMessage extends MapMessage<EventMessage, Object> {
        private static final long serialVersionUID = 1L;
        private final String action;

        EventMessage(String action) {
            this.action = action;
            with(ACTION_KEY, action);
        }

        @Override
        public String getFormattedMessage() {
            return action;
        }

        @Override
        public String getFormattedMessage(String[] formats) {
            return action;
        }

        @Override
        public void formatTo(StringBuilder buffer) {
            buffer.append(action);
        }

        @Override
        public void formatTo(String[] formats, StringBuilder buffer) {
            buffer.append(action);
        }
    }
}
