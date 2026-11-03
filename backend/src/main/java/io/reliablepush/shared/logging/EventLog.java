package io.reliablepush.shared.logging;

import java.util.List;
import java.util.Map;
import java.util.Set;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.ThreadContext;
import org.apache.logging.log4j.message.MapMessage;
import org.apache.logging.log4j.status.StatusLogger;

/**
 * Emits the catalogue events of spec 9.1. Every event is a Log4j {@link MapMessage} whose entries
 * ({@code event.action}, {@code event.outcome}, {@code rp.*}, ...) become top-level ECS fields in the
 * JSON output (see {@code log4j2/ecs-reliable-push.json}); the {@code message} field is the action.
 */
public final class EventLog {

    /** Keys that can carry secrets, personal data or message content: never loggable. */
    private static final Set<String> DENIED_KEYS =
            Set.of("token", "idToken", "apiKey", "secret", "signature", "message", "body", "userId");

    /** Fixed by the JSON template (or by Log4j itself): a MapMessage entry with this name would duplicate the key. */
    private static final Set<String> RESERVED_KEYS = Set.of(
            "@timestamp", "ecs.version", "log.level", "log.logger", "process.thread.name",
            "service.name", "service.version", "tags", "trace.id");

    private static final List<String> RESERVED_PREFIXES = List.of("error.", "labels.");

    /** ThreadContext keys with this prefix are rendered at top level by the template, like MapMessage entries. */
    private static final String CONTEXT_PREFIX = "rp.";

    private static final String ACTION_KEY = "event.action";

    private static final Logger LOGGER = LogManager.getLogger("io.reliablepush.event");

    private EventLog() {}

    public static void info(String action, Map<String, ?> fields) {
        log(Level.INFO, action, fields);
    }

    public static void warn(String action, Map<String, ?> fields) {
        log(Level.WARN, action, fields);
    }

    /**
     * Each JSON key must appear once per line (duplicate names are undefined in RFC 8259 and strict
     * ingesters drop the line). Logging must never break request handling, so only static programming
     * errors throw:
     * <ul>
     *   <li>keys of the deny-list, and keys fixed by the template ({@code log.level}, {@code trace.id},
     *       {@code error.*}, {@code labels.*}, ...), are rejected with {@link IllegalArgumentException};</li>
     *   <li>an {@code rp.*} field that the ThreadContext already holds with an equal value is not repeated
     *       (the context renders it);</li>
     *   <li>an {@code rp.*} field whose value differs from the ThreadContext one is data dependent, so it
     *       never throws: the ThreadContext value wins, the explicit field is dropped, and a warning naming
     *       only the key goes to Log4j's StatusLogger.</li>
     * </ul>
     */
    private static void log(Level level, String action, Map<String, ?> fields) {
        for (String key : fields.keySet()) {
            if (DENIED_KEYS.contains(key)) {
                throw new IllegalArgumentException("Field is never logged: " + key);
            }
            if (isReserved(key)) {
                throw new IllegalArgumentException("Field name is reserved by the log template: " + key);
            }
        }
        EventMessage message = new EventMessage(action);
        fields.forEach((key, value) -> {
            if (value == null || ACTION_KEY.equals(key) || repeatsContext(key, value)) {
                return;
            }
            message.with(key, value);
        });
        if (!LOGGER.isEnabled(level)) {
            return;
        }
        LOGGER.log(level, message);
    }

    private static boolean isReserved(String key) {
        return RESERVED_KEYS.contains(key) || RESERVED_PREFIXES.stream().anyMatch(key::startsWith);
    }

    /** True when the ThreadContext already renders this key, so the explicit field must be dropped. */
    private static boolean repeatsContext(String key, Object value) {
        if (!key.startsWith(CONTEXT_PREFIX)) {
            return false;
        }
        String inContext = ThreadContext.get(key);
        if (inContext == null) {
            return false;
        }
        if (!inContext.equals(String.valueOf(value))) {
            // never log the values: they may be identifiers
            StatusLogger.getLogger()
                    .warn("Event field {} differs from the ThreadContext value; keeping the ThreadContext value", key);
        }
        return true;
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
