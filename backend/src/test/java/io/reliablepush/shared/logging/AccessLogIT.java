package io.reliablepush.shared.logging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.jayway.jsonpath.JsonPath;
import io.reliablepush.support.PostgresTestBase;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;

@ActiveProfiles("test")
@ExtendWith(OutputCaptureExtension.class)
class AccessLogIT extends PostgresTestBase {

    private static final String TRACEPARENT = "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01";

    @Test
    void logsEveryRequestWithPathTemplate(CapturedOutput output) {
        ResponseEntity<Void> response = http.get()
                .uri("/actuator/health")
                .header("traceparent", TRACEPARENT)
                .retrieve()
                .toBodilessEntity();

        assertThat(response.getHeaders().getFirst("X-Request-Id")).isEqualTo("4bf92f3577b34da6a3ce929d0e0e4736");
        // the access log line is written after the response is sent, so poll the captured stdout
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            List<String> lines = output.getOut().lines().filter(l -> l.contains("\"api.request\"")).toList();
            assertThat(lines).isNotEmpty();
            String line = lines.get(lines.size() - 1);
            assertThat(JsonPath.<String>read(line, "$['@timestamp']")).isNotBlank();
            assertThat(JsonPath.<String>read(line, "$['log.level']")).isEqualTo("INFO");
            assertThat(JsonPath.<String>read(line, "$['trace.id']")).isEqualTo("4bf92f3577b34da6a3ce929d0e0e4736");
            assertThat(JsonPath.<String>read(line, "$['event.action']")).isEqualTo("api.request");
            assertThat(JsonPath.<String>read(line, "$['event.outcome']")).isEqualTo("success");
            assertThat(JsonPath.<String>read(line, "$['http.request.method']")).isEqualTo("GET");
            assertThat(JsonPath.<String>read(line, "$['url.path']")).isEqualTo("/actuator/health");
            assertThat(JsonPath.<Integer>read(line, "$['http.response.status_code']")).isEqualTo(200);
            assertThat(JsonPath.<Number>read(line, "$['event.duration']").longValue()).isPositive();
            assertThat(JsonPath.<String>read(line, "$['message']")).isEqualTo("api.request");
        });
    }
}
