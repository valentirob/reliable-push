package io.reliablepush.support;

import static org.assertj.core.api.Assertions.assertThat;

import com.atlassian.oai.validator.OpenApiInteractionValidator;
import com.atlassian.oai.validator.model.Request;
import com.atlassian.oai.validator.model.SimpleRequest;
import com.atlassian.oai.validator.model.SimpleResponse;
import com.atlassian.oai.validator.report.ValidationReport;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.springframework.http.ResponseEntity;

/**
 * Asserts that real requests and responses conform to {@code openapi.yaml} (the contract-first source of truth).
 * The spec is loaded from the classpath, so the test validates exactly what the service ships.
 */
public final class OpenApiAssert {

    private static final OpenApiInteractionValidator VALIDATOR = load();

    private OpenApiAssert() {}

    private static OpenApiInteractionValidator load() {
        try (InputStream in = OpenApiAssert.class.getResourceAsStream("/openapi.yaml")) {
            if (in == null) {
                throw new IllegalStateException("openapi.yaml is not on the classpath");
            }
            return OpenApiInteractionValidator.createForInlineApiSpecification(new String(in.readAllBytes(), StandardCharsets.UTF_8))
                    .build();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * Validates a response whose content type is implied by its status: no body for {@code 204}, problem+json for
     * errors, json otherwise. {@code path} is the concrete request path (no query string).
     */
    public static void assertMatchesContract(String method, String path, int status, String body) {
        String contentType = status >= 400 ? "application/problem+json" : "application/json";
        assertMatchesContract(method, path, status, contentType, body);
    }

    /** Validates a response including its real {@code Content-Type} and {@code Retry-After} headers. */
    public static void assertMatchesContract(String method, String path, ResponseEntity<String> response) {
        String contentType = response.getHeaders().getContentType() == null
                ? null
                : response.getHeaders().getContentType().toString();
        SimpleResponse.Builder builder = SimpleResponse.Builder.status(response.getStatusCode().value());
        if (response.getBody() != null && !response.getBody().isEmpty()) {
            builder.withBody(response.getBody());
        }
        if (contentType != null) {
            builder.withContentType(contentType);
        }
        String retryAfter = response.getHeaders().getFirst("Retry-After");
        if (retryAfter != null) {
            builder.withHeader("Retry-After", retryAfter);
        }
        assertValid(VALIDATOR.validateResponse(path, Request.Method.valueOf(method), builder.build()),
                method + " " + path + " -> " + response.getStatusCode().value());
    }

    private static void assertMatchesContract(String method, String path, int status, String contentType, String body) {
        SimpleResponse.Builder builder = SimpleResponse.Builder.status(status);
        if (body != null && !body.isEmpty()) {
            builder.withBody(body).withContentType(contentType);
        }
        assertValid(VALIDATOR.validateResponse(path, Request.Method.valueOf(method), builder.build()), method + " " + path + " -> " + status);
    }

    /** Validates a request (path, JSON body and, when given, the {@code X-Api-Key} or bearer credential). */
    public static void assertRequestMatchesContract(String method, String path, String body, String apiKey, String bearer) {
        assertValid(validateRequest(method, path, body, apiKey, bearer), "request " + method + " " + path);
    }

    /** The validation report of a request, for tests that expect the contract to reject it. */
    public static ValidationReport validateRequest(String method, String path, String body, String apiKey, String bearer) {
        SimpleRequest.Builder builder = new SimpleRequest.Builder(method, path);
        if (body != null) {
            builder.withBody(body).withContentType("application/json");
        }
        if (apiKey != null) {
            builder.withHeader("X-Api-Key", apiKey);
        }
        if (bearer != null) {
            builder.withHeader("Authorization", "Bearer " + bearer);
        }
        return VALIDATOR.validateRequest(builder.build());
    }

    private static void assertValid(ValidationReport report, String what) {
        assertThat(report.hasErrors()).as("%s violates openapi.yaml: %s", what, report.getMessages()).isFalse();
    }
}
