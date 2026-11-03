package io.reliablepush.shared.logging;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.servlet.FilterChain;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.logging.log4j.ThreadContext;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class TraceIdFilterTest {

    private static final String TRACE_ID = "4bf92f3577b34da6a3ce929d0e0e4736";

    private final TraceIdFilter filter = new TraceIdFilter();

    private record Result(String header, String insideContext) {}

    private Result run(MockHttpServletRequest request) throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<String> inside = new AtomicReference<>();
        FilterChain chain = (req, res) -> inside.set(ThreadContext.get("trace.id"));
        filter.doFilter(request, response, chain);
        return new Result(response.getHeader("X-Request-Id"), inside.get());
    }

    @Test
    void usesTraceparent() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("traceparent", "00-" + TRACE_ID + "-00f067aa0ba902b7-01");

        Result result = run(request);

        assertThat(result.header()).isEqualTo(TRACE_ID);
        assertThat(result.insideContext()).isEqualTo(TRACE_ID);
    }

    @Test
    void generatesWhenMissing() throws Exception {
        Result result = run(new MockHttpServletRequest());

        assertThat(result.header()).matches("[0-9a-f]{32}");
        assertThat(result.insideContext()).isEqualTo(result.header());
    }

    @Test
    void usesXRequestIdWhenNoTraceparent() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Request-Id", "req-123_abc.4");

        assertThat(run(request).header()).isEqualTo("req-123_abc.4");
    }

    @Test
    void traceparentWinsOverXRequestId() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("traceparent", "00-" + TRACE_ID + "-00f067aa0ba902b7-01");
        request.addHeader("X-Request-Id", "other");

        assertThat(run(request).header()).isEqualTo(TRACE_ID);
    }

    @Test
    void ignoresMalformedHeaders() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("traceparent", "00-zzzz-00f067aa0ba902b7-01");
        request.addHeader("X-Request-Id", "bad value\"{}");

        assertThat(run(request).header()).matches("[0-9a-f]{32}");
    }

    @Test
    void rejectsAllZeroTraceparentTraceId() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("traceparent", "00-00000000000000000000000000000000-00f067aa0ba902b7-01");

        assertThat(run(request).header()).isNotEqualTo("00000000000000000000000000000000").matches("[0-9a-f]{32}");
    }

    @Test
    void clearsContextAfterRequest() throws Exception {
        run(new MockHttpServletRequest());

        assertThat(ThreadContext.get("trace.id")).isNull();
    }

    @Test
    void clearsContextWhenChainThrows() {
        FilterChain chain = (req, res) -> {
            throw new IllegalStateException("boom");
        };
        try {
            filter.doFilter(new MockHttpServletRequest(), new MockHttpServletResponse(), chain);
        } catch (Exception expected) {
            // expected
        }

        assertThat(ThreadContext.get("trace.id")).isNull();
    }
}
