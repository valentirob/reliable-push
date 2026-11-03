package io.reliablepush;

import static org.assertj.core.api.Assertions.assertThat;

import com.jayway.jsonpath.JsonPath;
import io.reliablepush.support.PostgresTestBase;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.test.context.TestPropertySource;

/** Spec 9.1: stdout is one JSON object per line (no banner, no stray text). */
@ExtendWith(OutputCaptureExtension.class)
// distinct property => fresh application context, so startup output is captured by this test class
// default profiles (api,worker) with the real clock: the worker poll is pushed out so no real webhook call happens
@TestPropertySource(properties = {"rp.test.fresh-context=json-log-output", "reliable-push.worker.poll-interval=1d"})
class JsonLogOutputIT extends PostgresTestBase {

    @Test
    void stdoutIsJsonLinesWithServiceFields(CapturedOutput output) {
        http.get().uri("/actuator/health").retrieve().toBodilessEntity();

        List<String> lines = output.getOut().lines().filter(l -> !l.isBlank()).toList();

        assertThat(lines).isNotEmpty();
        assertThat(lines).allSatisfy(line -> assertThat(JsonPath.<Object>read(line, "$")).isInstanceOf(java.util.Map.class));
        assertThat(lines).anySatisfy(line -> {
            assertThat(JsonPath.<String>read(line, "$['service.name']")).isEqualTo("reliable-push");
            assertThat(JsonPath.<String>read(line, "$['service.version']")).isNotBlank();
        });
    }
}
