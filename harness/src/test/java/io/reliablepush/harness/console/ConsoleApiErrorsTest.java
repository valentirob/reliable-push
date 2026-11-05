package io.reliablepush.harness.console;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.http.ProblemDetail;

/** Exceptions that are not backend answers map to a generic 502 without leaking their message. */
class ConsoleApiErrorsTest {

    @Test
    void unknownIllegalStateIsAGeneric502() {
        var controller = new ConsoleApiController(null, null, null, null, null, null, null, null);

        var r = controller.unexpected(new IllegalStateException("internal detail zzsecretzz"));

        assertThat(r.getStatusCode().value()).isEqualTo(502);
        ProblemDetail p = (ProblemDetail) r.getBody();
        assertThat(p.getDetail()).doesNotContain("zzsecretzz").doesNotContain("unreachable");
        assertThat(p.getProperties()).isNull();
    }
}
