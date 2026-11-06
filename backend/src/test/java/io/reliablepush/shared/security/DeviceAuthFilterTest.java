package io.reliablepush.shared.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.reliablepush.apps.AppFixtures;
import io.reliablepush.apps.AppRegistry;
import io.reliablepush.devices.DeviceClaims;
import io.reliablepush.devices.DeviceService;
import io.reliablepush.devices.DeviceTokenService;
import io.reliablepush.shared.web.ProblemWriter;
import java.sql.SQLTransientConnectionException;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
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
        when(tokens.verify("some.device.token"))
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
}
