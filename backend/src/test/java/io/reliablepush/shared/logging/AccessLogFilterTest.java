package io.reliablepush.shared.logging;

import static io.reliablepush.shared.logging.EventCapture.data;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import jakarta.servlet.FilterChain;
import org.apache.logging.log4j.ThreadContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.servlet.HandlerMapping;

class AccessLogFilterTest {

    private final AccessLogFilter filter = new AccessLogFilter();
    private EventCapture capture;

    @BeforeEach
    void startCapture() {
        capture = new EventCapture();
    }

    @AfterEach
    void stopCapture() {
        capture.close();
    }

    @Test
    void usesBestMatchingPatternAndAppId() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/v1/notifications/abc");
        request.setQueryString("secret=1");
        FilterChain chain = (req, res) -> {
            req.setAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE, "/v1/notifications/{trackingId}");
            req.setAttribute("rp.app_id", "app-1");
            ((MockHttpServletResponse) res).setStatus(404);
        };

        filter.doFilter(request, new MockHttpServletResponse(), chain);

        assertThat(data(capture.only()))
                .containsEntry("event.action", "api.request")
                .containsEntry("http.request.method", "GET")
                .containsEntry("url.path", "/v1/notifications/{trackingId}")
                .containsEntry("http.response.status_code", 404)
                .containsEntry("event.outcome", "failure")
                .containsEntry("rp.app_id", "app-1");
    }

    @Test
    void doesNotDuplicateAppIdAlreadyInThreadContext() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/x");
        request.setAttribute("rp.app_id", "app-1");
        ThreadContext.put("rp.app_id", "app-1");
        try {
            filter.doFilter(request, new MockHttpServletResponse(), (req, res) -> {});
        } finally {
            ThreadContext.clearMap();
        }

        // rendered once by the ThreadContext, not repeated in the event
        assertThat(data(capture.only())).doesNotContainKey("rp.app_id");
    }

    @Test
    void fallsBackToRawPathWithoutQuery() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/unknown/path");
        request.setQueryString("a=b");

        filter.doFilter(request, new MockHttpServletResponse(), (req, res) -> {});

        assertThat(data(capture.only()))
                .containsEntry("url.path", "/unknown/path")
                .containsEntry("event.outcome", "success")
                .doesNotContainKey("rp.app_id");
    }

    @Test
    void recordsNumericDuration() throws Exception {
        filter.doFilter(new MockHttpServletRequest("GET", "/x"), new MockHttpServletResponse(), (req, res) -> {});

        assertThat(data(capture.only()).get("event.duration")).isInstanceOf(Long.class);
        assertThat((Long) data(capture.only()).get("event.duration")).isGreaterThanOrEqualTo(0L);
    }

    @Test
    void logsFailureAs500WhenChainThrows() {
        FilterChain chain = (req, res) -> {
            throw new IllegalStateException("boom");
        };

        assertThatThrownBy(() ->
                        filter.doFilter(new MockHttpServletRequest("GET", "/x"), new MockHttpServletResponse(), chain))
                .isInstanceOf(IllegalStateException.class);

        assertThat(data(capture.only()))
                .containsEntry("event.outcome", "failure")
                .containsEntry("http.response.status_code", 500);
    }
}
