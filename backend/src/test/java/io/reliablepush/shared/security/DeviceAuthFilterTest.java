package io.reliablepush.shared.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.reliablepush.apps.AppFixtures;
import io.reliablepush.apps.AppRegistry;
import io.reliablepush.devices.DeviceClaims;
import io.reliablepush.devices.DeviceService;
import io.reliablepush.devices.DeviceTokenService;
import io.reliablepush.shared.web.ProblemWriter;
import java.sql.SQLTransientConnectionException;
import java.time.Duration;
import java.time.Instant;
import java.util.function.Function;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.json.JsonMapper;

/**
 * The device-row lookup of {@link DeviceAuthFilter} under database backpressure (the database itself is covered by the
 * ITs; here only the lookup's failure is forced): a pool timeout is a 503 with {@code Retry-After}, not a 500.
 */
class DeviceAuthFilterTest {

    private final DeviceTokenService tokens = mock(DeviceTokenService.class);
    private final DeviceService devices = mock(DeviceService.class);
    private final DeviceAuthFilter filter = new DeviceAuthFilter(
            tokens,
            devices,
            new AppRegistry(AppFixtures.bind(AppFixtures.minimalApp("shop", "k-shop")), AppFixtures.environment()),
            new ProblemWriter(JsonMapper.builder().build()));

    private final MockHttpServletRequest request = new MockHttpServletRequest("POST", "/v1/events");
    private final MockHttpServletResponse response = new MockHttpServletResponse();
    private final MockFilterChain chain = new MockFilterChain();

    DeviceAuthFilterTest() {
        request.addHeader("Authorization", "Bearer some.device.token");
        Instant now = Instant.now();
        when(tokens.verify(eq("some.device.token"), any()))
                .thenReturn(new DeviceClaims("shop", "user-1", UUID.randomUUID(), 0, now, now.plusSeconds(60)));
    }

    @Test
    void poolTimeoutDuringTheDeviceLookupIs503WithRetryAfter() throws Exception {
        when(devices.isActive(any())).thenThrow(new CannotGetJdbcConnectionException(
                "Failed to obtain JDBC Connection", new SQLTransientConnectionException("pool-timeout-marker")));

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(503);
        assertThat(response.getHeader("Retry-After")).isEqualTo("5");
        assertThat(response.getContentType()).startsWith("application/problem+json");
        assertThat(response.getContentAsString()).doesNotContain("pool-timeout-marker");
        assertThat(chain.getRequest()).isNull();
    }

    @Test
    void otherDatabaseFailuresAreNotDisguisedAs503() {
        when(devices.isActive(any())).thenThrow(new DataIntegrityViolationException("unexpected"));

        assertThatThrownBy(() -> filter.doFilter(request, response, chain))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void activeDeviceContinuesTheChain() throws Exception {
        when(devices.isActive(any())).thenReturn(true);

        filter.doFilter(request, response, chain);

        assertThat(chain.getRequest()).isNotNull();
        assertThat(request.getAttribute(DevicePrincipal.ATTR)).isInstanceOf(DevicePrincipal.class);
    }

    @SuppressWarnings("unchecked")
    private Duration graceFor(String method, String uri, String servletPath) throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest(method, uri);
        req.setServletPath(servletPath);
        req.addHeader("Authorization", "Bearer some.device.token");
        Mockito.clearInvocations(tokens);
        filter.doFilter(req, new MockHttpServletResponse(), new MockFilterChain());
        if (Mockito.mockingDetails(tokens).getInvocations().isEmpty()) {
            return Duration.ZERO; // the filter does not apply to this path at all: no token, no grace
        }
        ArgumentCaptor<Function<String, Duration>> captor = ArgumentCaptor.forClass(Function.class);
        Mockito.verify(tokens).verify(eq("some.device.token"), captor.capture());
        return captor.getValue().apply("shop");
    }

    @Test
    void graceIsGrantedOnExactlyEventsPostAndLogoutDelete() throws Exception {
        assertThat(graceFor("POST", "/v1/events", "/v1/events")).isEqualTo(Duration.ofDays(90));
        assertThat(graceFor("DELETE", "/v1/devices/me", "/v1/devices/me")).isEqualTo(Duration.ofDays(90));
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
        "GET|/v1/events|/v1/events",
        "PUT|/v1/events|/v1/events",
        "DELETE|/v1/events|/v1/events",
        "POST|/v1/devices/me|/v1/devices/me",
        "GET|/v1/devices/me|/v1/devices/me",
        "POST|/v1/devices/token:refresh|/v1/devices/token:refresh",
        "DELETE|/v1/devices/token:refresh|/v1/devices/token:refresh",
        "POST|/v1/events/|/v1/events/",
        "POST|/v1/events;x=1|/v1/events",
        "POST|/v1/events;x|/v1/events;x",
        "POST|//v1/events|/v1/events",
        "POST|//v1/events|//v1/events",
        "POST|/v1/events/../devices/token:refresh|/v1/devices/token:refresh",
        "POST|/v1/events/../devices/token:refresh|/v1/events/../devices/token:refresh",
        "POST|/v1/events/%2e%2e/devices/token:refresh|/v1/devices/token:refresh",
        "POST|/v1/events/%2E%2E/devices/token:refresh|/v1/events/../devices/token:refresh",
        "POST|/v1/events/..;/devices/token:refresh|/v1/devices/token:refresh",
        "POST|/V1/EVENTS|/V1/EVENTS",
        "DELETE|/v1/devices/me/|/v1/devices/me/",
        "DELETE|/v1/devices/me;x|/v1/devices/me",
        "DELETE|/v1/devices/me/../token:refresh|/v1/devices/token:refresh",
        "POST|/v1/%65vents|/v1/events",
    })
    void graceIsZeroForEveryOtherRouteOrSpelling(String method, String uri, String servletPath) throws Exception {
        assertThat(graceFor(method, uri, servletPath)).isEqualTo(Duration.ZERO);
    }
}
