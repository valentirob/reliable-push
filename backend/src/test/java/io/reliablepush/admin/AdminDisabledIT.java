package io.reliablepush.admin;

import static org.assertj.core.api.Assertions.assertThat;

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

    /** Status, content type, caching header and body (with the path-dependent instance blanked) of a response. */
    private static String fingerprint(ResponseEntity<String> response) {
        assertThat(response.getHeaders().getCacheControl()).as("no Cache-Control").isNull();
        String body = response.getBody().replaceAll("\"instance\":\"[^\"]*\"", "\"instance\":\"\"")
                .replaceAll("No static resource [^.\"]*(\\.[a-z]+)?\\.", "No static resource X.");
        return response.getStatusCode().value() + "|" + response.getHeaders().getContentType() + "|" + body;
    }

    @Test
    void disabledSurfaceAnswersExactlyLikeAnUnknownPath() {
        String expected = fingerprint(get("/v1/nope", null));
        assertThat(expected).startsWith("404|application/problem+json");
        assertThat(fingerprint(get("/nope.html", null))).isEqualTo(expected);

        assertThat(fingerprint(get("/v1/admin/me", "test-admin-key-all"))).isEqualTo(expected);
        assertThat(fingerprint(get("/v1/admin/me", null))).isEqualTo(expected);
        assertThat(fingerprint(get("/admin/", null))).isEqualTo(expected);
        assertThat(fingerprint(get("/admin", null))).isEqualTo(expected);
        assertThat(fingerprint(get("/admin/index.html", null))).isEqualTo(expected);
    }

    @Test
    void disabledSurfaceSetsNoSecurityHeaders() {
        assertThat(get("/admin/", null).getHeaders().getFirst("Content-Security-Policy")).isNull();
    }
}
