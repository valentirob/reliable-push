package io.reliablepush.admin;

import static org.assertj.core.api.Assertions.assertThat;

import com.jayway.jsonpath.JsonPath;
import io.reliablepush.support.OpenApiAssert;
import io.reliablepush.support.PostgresTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;

/** Nothing to resume while the breaker is disabled (spec 6), the default of the test profile: {@code 409}. */
@ActiveProfiles({"api", "worker", "test", "admin"})
class AdminFallbackCircuitDisabledIT extends PostgresTestBase {

    private static final String CTL_KEY = "test-admin-key-ctl";
    private static final String LIST = "/v1/admin/fallback-circuits";

    private ResponseEntity<String> call(HttpMethod method, String path) {
        return http.method(method)
                .uri(path)
                .header("X-Admin-Key", CTL_KEY)
                .retrieve()
                .onStatus(status -> true, (request, response) -> {})
                .toEntity(String.class);
    }

    @Test
    void disabledIsReportedAndEveryCircuitReadsClosed() {
        ResponseEntity<String> list = call(HttpMethod.GET, LIST);

        assertThat(list.getStatusCode().value()).isEqualTo(200);
        assertThat(JsonPath.<Boolean>read(list.getBody(), "$.enabled")).isFalse();
        assertThat(JsonPath.<Boolean>read(list.getBody(), "$.suspended")).isFalse();
        assertThat(JsonPath.<String>read(list.getBody(), "$.items[0].state")).isEqualTo("CLOSED");
        OpenApiAssert.assertMatchesContract("GET", LIST, list);
    }

    @Test
    void resumeIs409() {
        String resume = LIST + "/shop:resume";
        ResponseEntity<String> conflict = call(HttpMethod.POST, resume);

        assertThat(conflict.getStatusCode().value()).isEqualTo(409);
        OpenApiAssert.assertMatchesContract("POST", resume, conflict);
    }
}
