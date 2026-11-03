package io.reliablepush.shared.web;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.servlet.RequestDispatcher;
import java.sql.SQLTransientConnectionException;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.json.JsonMapper;

/** The /error path: a database pool timeout that escaped a servlet filter is a 503, not a 500. */
class ProblemErrorControllerTest {

    private final ProblemErrorController controller = new ProblemErrorController(new ProblemWriter(JsonMapper.builder().build()));

    private MockHttpServletResponse error(Throwable exception) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/error");
        request.setAttribute(RequestDispatcher.ERROR_STATUS_CODE, 500);
        request.setAttribute(RequestDispatcher.ERROR_REQUEST_URI, "/v1/events");
        if (exception != null) {
            request.setAttribute(RequestDispatcher.ERROR_EXCEPTION, exception);
        }
        MockHttpServletResponse response = new MockHttpServletResponse();
        controller.error(request, response);
        return response;
    }

    @Test
    void poolTimeoutFromAFilterIs503WithRetryAfter() throws Exception {
        MockHttpServletResponse response = error(new CannotGetJdbcConnectionException(
                "Failed to obtain JDBC Connection", new SQLTransientConnectionException("pool-timeout-marker")));

        assertThat(response.getStatus()).isEqualTo(503);
        assertThat(response.getHeader("Retry-After")).isEqualTo("5");
        assertThat(response.getContentType()).startsWith("application/problem+json");
        assertThat(response.getContentAsString()).contains("\"title\":\"Service Unavailable\"").doesNotContain("pool-timeout-marker");
    }

    @Test
    void otherExceptionsStay500() throws Exception {
        MockHttpServletResponse response = error(new IllegalStateException("boom"));

        assertThat(response.getStatus()).isEqualTo(500);
        assertThat(response.getHeader("Retry-After")).isNull();
    }

    @Test
    void wrappedPoolTimeoutIsRecognised() {
        assertThat(DatabaseUnavailable.isPoolTimeout(new RuntimeException(
                new org.springframework.transaction.CannotCreateTransactionException("tx",
                        new SQLTransientConnectionException("x"))))).isTrue();
        assertThat(DatabaseUnavailable.isPoolTimeout(
                new org.springframework.dao.DataAccessResourceFailureException("other", new java.io.IOException("x"))))
                .isFalse();
        assertThat(DatabaseUnavailable.isPoolTimeout(null)).isFalse();
    }
}
