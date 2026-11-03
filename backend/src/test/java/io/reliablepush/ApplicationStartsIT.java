package io.reliablepush;

import static org.assertj.core.api.Assertions.assertThat;

import com.jayway.jsonpath.JsonPath;
import io.reliablepush.support.PostgresTestBase;
import com.zaxxer.hikari.HikariDataSource;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

// both roles (the production default api,worker) plus the test apps, real clock: no worker poll, so no real webhook call
@ActiveProfiles({"api", "worker", "test"})
@TestPropertySource(properties = "reliable-push.worker.poll-interval=1d")
class ApplicationStartsIT extends PostgresTestBase {

    @Autowired
    DataSource dataSource;

    @Test
    void connectionPoolWaitIsBoundedToFiveSeconds() throws Exception {
        // a request waiting longer for a connection fails fast with 503 + Retry-After instead of hanging 30 s
        assertThat(dataSource.unwrap(HikariDataSource.class).getConnectionTimeout()).isEqualTo(5_000L);
    }

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
