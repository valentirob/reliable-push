package io.reliablepush.shared.logging;

import java.util.List;
import java.util.Map;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.config.Configuration;
import org.apache.logging.log4j.core.config.LoggerConfig;
import org.apache.logging.log4j.core.test.appender.ListAppender;
import org.apache.logging.log4j.message.MapMessage;

/** Test helper: captures {@link EventLog} events (or another logger) in memory (non-additive, so nothing reaches the console). */
public final class EventCapture implements AutoCloseable {

    private static final String EVENT_LOGGER = "io.reliablepush.event";

    private final LoggerContext context = (LoggerContext) LogManager.getContext(false);
    private final ListAppender appender = new ListAppender("event-capture");
    private final String loggerName;

    /** Captures the catalogue events ({@link EventLog}). */
    public EventCapture() {
        this(EVENT_LOGGER);
    }

    /** Captures INFO and above of the named logger (e.g. a class logging outside the catalogue). */
    public EventCapture(String loggerName) {
        this(loggerName, Level.INFO);
    }

    /** Captures the named logger (and its children) from {@code level} up, e.g. DEBUG to check debug lines too. */
    public EventCapture(String loggerName, Level level) {
        this.loggerName = loggerName;
        appender.start();
        Configuration configuration = context.getConfiguration();
        LoggerConfig loggerConfig = new LoggerConfig(loggerName, level, false);
        loggerConfig.addAppender(appender, null, null);
        configuration.addLogger(loggerName, loggerConfig);
        context.updateLoggers();
    }

    public List<LogEvent> events() {
        return appender.getEvents();
    }

    LogEvent only() {
        List<LogEvent> events = events();
        if (events.size() != 1) {
            throw new AssertionError("expected exactly one event but got " + events.size());
        }
        return events.get(0);
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> data(LogEvent event) {
        return ((MapMessage<?, Object>) event.getMessage()).getData();
    }

    @Override
    public void close() {
        context.getConfiguration().removeLogger(loggerName);
        context.updateLoggers();
        appender.stop();
    }
}
