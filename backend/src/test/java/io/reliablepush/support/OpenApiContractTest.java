package io.reliablepush.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Pins the device and events contract with literal samples (independent of any implementation), so it cannot drift
 * silently. The implementations are checked against the same contract in their own integration tests.
 */
class OpenApiContractTest {

    private static final String TID = UUID.randomUUID().toString();

    @Test
    void deviceRegistrationRequestAndResponse() {
        OpenApiAssert.assertRequestMatchesContract(
                "POST", "/v1/devices", "{\"appId\":\"shop\",\"idToken\":\"x.y.z\",\"platform\":\"ANDROID\"}", null, null);
        OpenApiAssert.assertMatchesContract(
                "POST",
                "/v1/devices",
                201,
                "{\"deviceId\":\"" + TID + "\",\"deviceToken\":\"a.b.c\",\"expiresAt\":\"2026-11-03T10:00:00Z\"}");
        assertThat(OpenApiAssert.validateRequest(
                        "POST", "/v1/devices", "{\"appId\":\"shop\",\"idToken\":\"t\",\"platform\":\"WINDOWS\"}", null, null)
                .hasErrors()).isTrue();
    }

    @Test
    void tokenRefreshAndRevoke() {
        OpenApiAssert.assertRequestMatchesContract("POST", "/v1/devices/token:refresh", null, null, "a.b.c");
        OpenApiAssert.assertMatchesContract(
                "POST", "/v1/devices/token:refresh", 200, "{\"deviceToken\":\"a.b.c\",\"expiresAt\":\"2026-11-03T10:00:00Z\"}");
        OpenApiAssert.assertRequestMatchesContract("DELETE", "/v1/devices/me", null, null, "a.b.c");
        OpenApiAssert.assertMatchesContract("DELETE", "/v1/devices/me", 204, null);
        assertThat(OpenApiAssert.validateRequest("DELETE", "/v1/devices/me", null, null, null).hasErrors())
                .as("device credential is required")
                .isTrue();
    }

    @Test
    void eventsBatch() {
        String event = "{\"trackingId\":\"" + TID + "\",\"type\":\"DELIVERED\",\"occurredAt\":\"2026-11-03T10:00:00Z\"}";
        OpenApiAssert.assertRequestMatchesContract("POST", "/v1/events", "{\"events\":[" + event + "]}", null, "a.b.c");
        OpenApiAssert.assertMatchesContract(
                "POST",
                "/v1/events",
                200,
                "{\"accepted\":1,\"ignored\":0,\"items\":[{\"trackingId\":\"" + TID + "\",\"outcome\":\"ACCEPTED\"}]}");

        String fiftyOne = "{\"events\":[" + String.join(",", java.util.Collections.nCopies(51, event)) + "]}";
        assertThat(OpenApiAssert.validateRequest("POST", "/v1/events", fiftyOne, null, "a.b.c").hasErrors())
                .as("more than 50 events")
                .isTrue();
    }

    @Test
    void problemDocumentsAreDescribed() {
        OpenApiAssert.assertMatchesContract(
                "POST",
                "/v1/events",
                422,
                "{\"type\":\"about:blank\",\"title\":\"Validation Failed\",\"status\":422,\"detail\":\"The request is not valid.\","
                        + "\"errors\":[{\"field\":\"events\",\"message\":\"size must be between 1 and 50\"}]}");
    }

    @Test
    void validatorRejectsNonConformingResponses() {
        String path = "/v1/notifications/" + TID;
        // required fields missing
        assertThatThrownBy(() -> OpenApiAssert.assertMatchesContract("GET", path, 200, "{\"state\":\"PENDING\"}"))
                .isInstanceOf(AssertionError.class);
        // internal state name must never leak
        String view = "{\"trackingId\":\"" + TID + "\",\"userId\":\"u\",\"state\":\"DISPATCHING\",\"policy\":\"NOT_READ\","
                + "\"timeoutMinutes\":5,\"createdAt\":\"2026-11-03T10:00:00Z\"}";
        assertThatThrownBy(() -> OpenApiAssert.assertMatchesContract("GET", path, 200, view)).isInstanceOf(AssertionError.class);
        // undocumented status and unknown path
        assertThatThrownBy(() -> OpenApiAssert.assertMatchesContract("GET", path, 500, null)).isInstanceOf(AssertionError.class);
        assertThatThrownBy(() -> OpenApiAssert.assertMatchesContract("GET", "/v1/unknown", 200, "{}")).isInstanceOf(AssertionError.class);
        OpenApiAssert.assertMatchesContract("GET", path, 200, view.replace("DISPATCHING", "FALLBACK_PENDING"));
    }
}
