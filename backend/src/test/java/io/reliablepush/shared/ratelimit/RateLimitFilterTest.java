package io.reliablepush.shared.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;

import io.reliablepush.apps.AppFixtures;
import io.reliablepush.apps.AppRegistry;
import io.reliablepush.shared.security.DevicePrincipal;
import io.reliablepush.shared.security.SenderPrincipal;
import io.reliablepush.shared.web.ProblemWriter;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.json.JsonMapper;

/** A principal of an app the registry does not know never reaches the 500 path: generic 401 instead. */
class RateLimitFilterTest {

    private final RateLimitFilter filter = new RateLimitFilter(
            new InMemoryRateLimiter(),
            new AppRegistry(AppFixtures.bind(AppFixtures.minimalApp("shop", "k-shop")), AppFixtures.environment()),
            new ProblemWriter(JsonMapper.builder().build()));

    private MockHttpServletResponse run(String attribute, Object principal) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/v1/devices/me");
        request.setAttribute(attribute, principal);
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();
        filter.doFilter(request, response, chain);
        if (response.getStatus() == 200) {
            assertThat(chain.getRequest()).isNotNull();
        } else {
            assertThat(chain.getRequest()).as("chain must not continue").isNull();
        }
        return response;
    }

    @Test
    void deviceOfAnUnknownAppIs401() throws Exception {
        MockHttpServletResponse response =
                run(DevicePrincipal.ATTR, new DevicePrincipal("gone", "user-1", UUID.randomUUID(), 0));

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getContentType()).startsWith("application/problem+json");
        assertThat(response.getHeader("WWW-Authenticate")).startsWith("Bearer");
    }

    @Test
    void senderOfAnUnknownAppIs401() throws Exception {
        MockHttpServletResponse response = run(SenderPrincipal.ATTR, new SenderPrincipal("gone"));

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getHeader("WWW-Authenticate")).startsWith("ApiKey");
    }

    @Test
    void knownAppPassesThrough() throws Exception {
        assertThat(run(SenderPrincipal.ATTR, new SenderPrincipal("shop")).getStatus()).isEqualTo(200);
    }
}
