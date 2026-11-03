package io.reliablepush.shared.logging;

import static org.assertj.core.api.Assertions.assertThat;

import com.jayway.jsonpath.JsonPath;
import java.util.Map;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.core.config.DefaultConfiguration;
import org.apache.logging.log4j.core.impl.Log4jLogEvent;
import org.apache.logging.log4j.layout.template.json.JsonTemplateLayout;
import org.apache.logging.log4j.message.MapMessage;
import org.apache.logging.log4j.message.SimpleMessage;
import org.apache.logging.log4j.util.SortedArrayStringMap;
import org.junit.jupiter.api.Test;

/** The project ECS template puts event fields and trace/rp context at the top level of the JSON line. */
class EcsTemplateTest {

    private final JsonTemplateLayout layout = JsonTemplateLayout.newBuilder()
            .setConfiguration(new DefaultConfiguration())
            .setEventTemplateUri("classpath:log4j2/ecs-reliable-push.json")
            .build();

    private String render(org.apache.logging.log4j.message.Message message, Map<String, String> context) {
        SortedArrayStringMap contextData = new SortedArrayStringMap();
        context.forEach(contextData::putValue);
        return layout.toSerializable(Log4jLogEvent.newBuilder()
                .setLoggerName("test")
                .setLevel(Level.INFO)
                .setMessage(message)
                .setContextData(contextData)
                .build());
    }

    @Test
    void mapMessageEntriesAndContextAreTopLevelEcsFields() {
        // goes through EventLog so the real message type is rendered
        try (EventCapture capture = new EventCapture()) {
            EventLog.info("fallback.claimed", Map.of("rp.tracking_id", "t1", "event.duration", 42L));
            String line = render(
                    capture.only().getMessage(), Map.of("trace.id", "abc", "rp.device_id", "d1", "other", "x"));

            assertThat(JsonPath.<String>read(line, "$['message']")).isEqualTo("fallback.claimed");
            assertThat(JsonPath.<String>read(line, "$['event.action']")).isEqualTo("fallback.claimed");
            assertThat(JsonPath.<String>read(line, "$['rp.tracking_id']")).isEqualTo("t1");
            assertThat(JsonPath.<Integer>read(line, "$['event.duration']")).isEqualTo(42);
            assertThat(JsonPath.<String>read(line, "$['trace.id']")).isEqualTo("abc");
            assertThat(JsonPath.<String>read(line, "$['rp.device_id']")).isEqualTo("d1");
            assertThat(JsonPath.<String>read(line, "$['labels.other']")).isEqualTo("x");
        }
    }

    @Test
    void plainMessagesStayPlain() {
        String line = render(new SimpleMessage("hello"), Map.of());

        assertThat(JsonPath.<String>read(line, "$['message']")).isEqualTo("hello");
        assertThat(JsonPath.<Map<String, Object>>read(line, "$")).doesNotContainKey("event.action");
    }
}
