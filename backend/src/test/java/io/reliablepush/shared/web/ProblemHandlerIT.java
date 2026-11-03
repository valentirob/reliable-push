package io.reliablepush.shared.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.jayway.jsonpath.JsonPath;
import io.reliablepush.support.PostgresTestBase;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;

@ActiveProfiles("test")
@ExtendWith(OutputCaptureExtension.class)
class ProblemHandlerIT extends PostgresTestBase {

    private static final String BASE = "/v1/notifications/_test-echo";

    private ResponseEntity<String> get(String path) {
        return http.get()
                .uri(path)
                .header("X-Api-Key", "test-sender-key-shop")
                .retrieve()
                .onStatus(status -> true, (request, response) -> {})
                .toEntity(String.class);
    }

    private ResponseEntity<String> postJson(String path, String body) {
        return http.post()
                .uri(path)
                .contentType(MediaType.APPLICATION_JSON)
                .header("X-Api-Key", "test-sender-key-shop")
                .body(body)
                .retrieve()
                .onStatus(status -> true, (request, response) -> {})
                .toEntity(String.class);
    }

    private static void assertProblem(ResponseEntity<String> response, int status, String title) {
        assertThat(response.getStatusCode().value()).isEqualTo(status);
        assertThat(response.getHeaders().getContentType().toString()).startsWith("application/problem+json");
        assertThat(JsonPath.<Integer>read(response.getBody(), "$.status")).isEqualTo(status);
        assertThat(JsonPath.<String>read(response.getBody(), "$.title")).isEqualTo(title);
    }

    @Test
    void domainExceptionsMapToTheirStatus() {
        assertProblem(get(BASE + "/throw/not-found"), 404, "Not Found");
        assertProblem(get(BASE + "/throw/conflict"), 409, "Conflict");
        assertProblem(get(BASE + "/throw/forbidden"), 403, "Forbidden");
    }

    @Test
    void beanValidationFailureIs422WithFieldErrorsAndNoRejectedValue() {
        ResponseEntity<String> response = postJson(BASE + "/validated", "{\"name\":\"\"}");

        assertProblem(response, 422, "Validation Failed");
        assertThat(JsonPath.<String>read(response.getBody(), "$.errors[0].field")).isEqualTo("name");
        assertThat(JsonPath.<String>read(response.getBody(), "$.errors[0].message")).isNotBlank();
    }

    @Test
    void constraintViolationExceptionIs422() {
        ResponseEntity<String> response = get(BASE + "/throw/constraint");

        assertProblem(response, 422, "Validation Failed");
        assertThat(JsonPath.<String>read(response.getBody(), "$.errors[0].field")).isEqualTo("name");
    }

    @Test
    void malformedJsonIsAProblemToo() {
        assertThat(postJson(BASE, "{not json").getStatusCode().value()).isEqualTo(400);
        assertThat(postJson(BASE, "{not json").getHeaders().getContentType().toString())
                .startsWith("application/problem+json");
    }

    @Test
    void filterProblemsHaveTheSameShapeAsAdviceProblems() {
        ResponseEntity<String> fromAdvice = get(BASE + "/throw/not-found");
        ResponseEntity<String> fromFilter = http.get()
                .uri("/v1/notifications/" + UUID.randomUUID())
                .retrieve()
                .onStatus(status -> true, (request, response) -> {})
                .toEntity(String.class); // no key: 401 written by ProblemWriter

        assertThat(fromFilter.getStatusCode().value()).isEqualTo(401);
        Set<String> adviceKeys = JsonPath.<Map<String, Object>>read(fromAdvice.getBody(), "$").keySet();
        Set<String> filterKeys = JsonPath.<Map<String, Object>>read(fromFilter.getBody(), "$").keySet();
        assertThat(filterKeys).isEqualTo(adviceKeys).contains("status", "title", "detail", "instance");
        assertThat(JsonPath.<String>read(fromFilter.getBody(), "$.instance")).startsWith("/v1/notifications/");
    }

    @Test
    void unexpectedControllerExceptionIsA500ProblemWithoutLeakingTheMessage(CapturedOutput output) {
        ResponseEntity<String> response = get(BASE + "/throw/other");

        assertProblem(response, 500, "Internal Server Error");
        assertThat(response.getBody()).doesNotContain("secret-internal-detail");
        assertThat(JsonPath.<String>read(response.getBody(), "$.detail")).isNotBlank();
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> assertThat(output.getAll().lines())
                .anySatisfy(line -> assertThat(line).contains("\"log.level\":\"ERROR\"").contains("secret-internal-detail")));
    }

    @Test
    void unexpectedFilterExceptionIsA500ProblemToo() {
        ResponseEntity<String> response = get("/_test-filter-boom");

        assertProblem(response, 500, "Internal Server Error");
        assertThat(response.getBody()).doesNotContain("secret-filter-detail").doesNotContain("IllegalStateException");
        assertThat(JsonPath.<String>read(response.getBody(), "$.detail")).isNotBlank();
        assertThat(response.getHeaders().getFirst("X-Request-Id")).isNotBlank();
    }

    @Test
    void connectionPoolTimeoutIs503WithRetryAfterAndAWarningWithoutStackTrace(CapturedOutput output) {
        for (String kind : java.util.List.of("db-pool", "db-tx")) {
            ResponseEntity<String> response = get(BASE + "/throw/" + kind);

            assertProblem(response, 503, "Service Unavailable");
            assertThat(response.getHeaders().getFirst("Retry-After")).isEqualTo("5");
            assertThat(response.getBody()).doesNotContain("pool-timeout-marker").doesNotContain("Hikari");
        }
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> assertThat(output.getAll().lines()
                        .filter(line -> line.contains("Database connection unavailable")))
                .hasSizeGreaterThanOrEqualTo(2)
                .allSatisfy(line -> assertThat(line).contains("\"log.level\":\"WARN\"").doesNotContain("error.stack_trace")));
        // neither the exception message nor a stack trace is logged, and nothing at ERROR
        assertThat(output.getAll()).doesNotContain("pool-timeout-marker").doesNotContain("CannotGetJdbcConnectionException:");
    }

    @Test
    void directRequestToErrorPathIsA404Problem() {
        assertProblem(get("/error"), 404, "Not Found");
    }
}
