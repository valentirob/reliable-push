package io.reliablepush.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.jayway.jsonpath.JsonPath;
import io.reliablepush.support.OpenApiAssert;
import io.reliablepush.support.PostgresTestBase;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

@ActiveProfiles({"api", "worker", "test"})
@TestPropertySource(properties = {
    "reliable-push.admin.rate-limit-per-minute=2",
    "reliable-push.admin.keys[0].id=admin-limited",
    // SHA-256 of the raw test key "test-admin-key-limited"
    "reliable-push.admin.keys[0].key-sha256=cc860825b0477c44288e6c071a394db9155b4493d75d5d8523295621670cf5e1",
    "reliable-push.admin.keys[0].apps[0]=shop"
})
@ExtendWith(OutputCaptureExtension.class)
class AdminRateLimitIT extends PostgresTestBase {

    private ResponseEntity<String> me() {
        return http.get()
                .uri("/v1/admin/me")
                .header("X-Admin-Key", "test-admin-key-limited")
                .retrieve()
                .onStatus(status -> true, (request, response) -> {})
                .toEntity(String.class);
    }

    @Test
    void thirdCallIs429(CapturedOutput output) {
        assertThat(me().getStatusCode().value()).isEqualTo(200);
        assertThat(me().getStatusCode().value()).isEqualTo(200);
        ResponseEntity<String> third = me();

        assertThat(third.getStatusCode().value()).isEqualTo(429);
        assertThat(Long.parseLong(third.getHeaders().getFirst("Retry-After"))).isBetween(1L, 60L);
        assertThat(third.getHeaders().getFirst("Cache-Control")).isEqualTo("no-store");
        OpenApiAssert.assertMatchesContract("GET", "/v1/admin/me", third);
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> assertThat(output.getOut().lines())
                .anySatisfy(line -> {
                    assertThat(line).contains("\"rate_limit.exceeded\"");
                    assertThat(JsonPath.<String>read(line, "$['rate_limit.scope']")).isEqualTo("admin");
                    assertThat(JsonPath.<String>read(line, "$['rp.admin_key']")).isEqualTo("admin-limited");
                }));
    }
}
