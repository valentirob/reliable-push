package io.reliablepush.admin;

import static org.assertj.core.api.Assertions.assertThat;

import com.jayway.jsonpath.JsonPath;
import io.reliablepush.support.PostgresTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;

/** No {@code reliable-push.admin.keys}: the whole admin surface answers 404. */
@ActiveProfiles({"api", "worker", "test"})
class AdminDisabledIT extends PostgresTestBase {

    private ResponseEntity<String> get(String path, String key) {
        return http.get()
                .uri(path)
                .headers(h -> {
                    if (key != null) {
                        h.set("X-Admin-Key", key);
                    }
                })
                .retrieve()
                .onStatus(status -> true, (request, response) -> {})
                .toEntity(String.class);
    }

    private static void assertNotFoundProblem(ResponseEntity<String> response) {
        assertThat(response.getStatusCode().value()).isEqualTo(404);
        assertThat(response.getHeaders().getContentType().toString()).startsWith("application/problem+json");
        assertThat(JsonPath.<Integer>read(response.getBody(), "$.status")).isEqualTo(404);
        assertThat(JsonPath.<String>read(response.getBody(), "$.title")).isEqualTo("Not Found");
    }

    @Test
    void apiIs404WithAnyKeyOrNone() {
        assertNotFoundProblem(get("/v1/admin/me", "test-admin-key-all"));
        assertNotFoundProblem(get("/v1/admin/me", null));
    }

    @Test
    void pageIs404() {
        assertNotFoundProblem(get("/admin/", null));
        assertNotFoundProblem(get("/admin", null));
        assertNotFoundProblem(get("/admin/index.html", null));
    }
}
