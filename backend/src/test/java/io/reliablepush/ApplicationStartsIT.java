package io.reliablepush;

import static org.assertj.core.api.Assertions.assertThat;

import com.jayway.jsonpath.JsonPath;
import io.reliablepush.support.PostgresTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.TestPropertySource;

// default profiles (api,worker) with the real clock: no worker poll, so no real webhook call happens
@TestPropertySource(properties = "reliable-push.worker.poll-interval=1d")
class ApplicationStartsIT extends PostgresTestBase {

    private ResponseEntity<String> get(String path) {
        return http.get().uri(path).retrieve().toEntity(String.class);
    }

    @Test
    void healthIsUp() {
        ResponseEntity<String> response = get("/actuator/health");

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(JsonPath.<String>read(response.getBody(), "$.status")).isEqualTo("UP");
    }

    @Test
    void livenessAndReadinessExposed() {
        assertThat(get("/actuator/health/liveness").getStatusCode().value()).isEqualTo(200);
        assertThat(get("/actuator/health/readiness").getStatusCode().value()).isEqualTo(200);
    }

    @Test
    void prometheusExposed() {
        ResponseEntity<String> response = get("/actuator/prometheus");

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).contains("jvm_threads");
    }
}
