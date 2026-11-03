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

/** Test helper: captures {@link EventLog} events in memory (non-additive, so nothing reaches the console). */
final class EventCapture implements AutoCloseable {

    private static final String LOGGER_NAME = "io.reliablepush.event";

    private final LoggerContext context = (LoggerContext) LogManager.getContext(false);
    private final ListAppender appender = new ListAppender("event-capture");

    EventCapture() {
        appender.start();
        Configuration configuration = context.getConfiguration();
        LoggerConfig loggerConfig = new LoggerConfig(LOGGER_NAME, Level.INFO, false);
        loggerConfig.addAppender(appender, null, null);
        configuration.addLogger(LOGGER_NAME, loggerConfig);
        context.updateLoggers();
    }

    List<LogEvent> events() {
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
    static Map<String, Object> data(LogEvent event) {
        return ((MapMessage<?, Object>) event.getMessage()).getData();
    }

    @Override
    public void close() {
        context.getConfiguration().removeLogger(LOGGER_NAME);
        context.updateLoggers();
        appender.stop();
    }
}
