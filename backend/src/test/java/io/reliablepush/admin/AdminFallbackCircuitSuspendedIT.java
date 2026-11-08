package io.reliablepush.admin;

import static org.assertj.core.api.Assertions.assertThat;

import com.jayway.jsonpath.JsonPath;
import io.reliablepush.support.OpenApiAssert;
import io.reliablepush.support.PostgresTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

/**
 * Nothing to resume while {@code fallback.suspended} is true (spec 6): {@code 409}. Also covers the admin rate limit on
 * the POST route (three requests per minute here).
 */
@ActiveProfiles({"api", "worker", "test", "admin"})
@TestPropertySource(properties = {
    "reliable-push.worker.poll-interval=1d",
    "reliable-push.fallback.suspended=true",
    "reliable-push.fallback.circuit-breaker.enabled=true",
    "reliable-push.admin.rate-limit-per-minute=3"
})
class AdminFallbackCircuitSuspendedIT extends PostgresTestBase {

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
    void suspendedIsReportedResumeIs409AndTheRateLimitApplies() {
        ResponseEntity<String> list = call(HttpMethod.GET, LIST);
        assertThat(list.getStatusCode().value()).isEqualTo(200);
        assertThat(JsonPath.<Boolean>read(list.getBody(), "$.suspended")).isTrue();
        assertThat(JsonPath.<Boolean>read(list.getBody(), "$.enabled")).isTrue();
        OpenApiAssert.assertMatchesContract("GET", LIST, list);

        String resume = LIST + "/shop:resume";
        ResponseEntity<String> conflict = call(HttpMethod.POST, resume);
        assertThat(conflict.getStatusCode().value()).isEqualTo(409);
        OpenApiAssert.assertMatchesContract("POST", resume, conflict);

        ResponseEntity<String> conflictAgain = call(HttpMethod.POST, resume);
        assertThat(conflictAgain.getStatusCode().value()).isEqualTo(409);

        ResponseEntity<String> limited = call(HttpMethod.POST, resume);
        assertThat(limited.getStatusCode().value()).isEqualTo(429);
        assertThat(Long.parseLong(limited.getHeaders().getFirst("Retry-After"))).isBetween(1L, 60L);
        OpenApiAssert.assertMatchesContract("POST", resume, limited);
    }
}
