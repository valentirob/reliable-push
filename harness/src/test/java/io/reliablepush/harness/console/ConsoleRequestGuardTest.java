package io.reliablepush.harness.console;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.servlet.FilterChain;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/** DNS-rebinding guard: the Host allowlist applies to every request under /console/api/. */
class ConsoleRequestGuardTest {

    final ConsoleRequestGuard guard = new ConsoleRequestGuard();

    record Result(int status, boolean passed, String body) {}

    Result run(String method, String path, String host, String contentType) throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest(method, path);
        if (host != null) {
            req.addHeader("Host", host);
        }
        if (contentType != null) {
            req.setContentType(contentType);
        }
        MockHttpServletResponse res = new MockHttpServletResponse();
        AtomicBoolean passed = new AtomicBoolean();
        FilterChain chain = (a, b) -> passed.set(true);
        guard.doFilter(req, res, chain);
        return new Result(res.getStatus(), passed.get(), res.getContentAsString());
    }

    @ParameterizedTest
    @ValueSource(strings = {"evil.example", "evil.example:8090", "localhost.evil.example", "127.0.0.1.evil.example:8090",
            "10.0.2.3", "[::2]:8090", ""})
    void foreignHostIsRejectedOnGetAndPostGrants(String host) throws Exception {
        Result get = run("GET", "/console/api/logs", host, null);
        Result post = run("POST", "/console/api/grants", host, "application/json");

        assertThat(get.status()).isEqualTo(403);
        assertThat(get.passed()).isFalse();
        assertThat(post.status()).isEqualTo(403);
        assertThat(post.passed()).isFalse();
        assertThat(post.body()).doesNotContain("evil");
    }

    @Test
    void missingHostIsRejected() throws Exception {
        assertThat(run("GET", "/console/api/apps", null, null).status()).isEqualTo(403);
    }

    @ParameterizedTest
    @ValueSource(strings = {"localhost", "LOCALHOST:8090", "127.0.0.1:8090", "[::1]:8090", "[::1]", "10.0.2.2:8090",
            "Harness:8090"})
    void allowedHostsPass(String host) throws Exception {
        assertThat(run("GET", "/console/api/apps", host, null).passed()).isTrue();
        assertThat(run("POST", "/console/api/grants", host, "application/json").passed()).isTrue();
    }

    @Test
    void otherChecksStillApply() throws Exception {
        assertThat(run("POST", "/console/api/grants", "localhost:8090", "text/plain").status()).isEqualTo(415);
    }

    @Test
    void pathsOutsideConsoleApiAreNotChecked() throws Exception {
        assertThat(run("POST", "/hooks/shop", "backend:8080", null).passed()).isTrue();
    }
}
