package io.reliablepush.harness.logs;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BackendLogTailTest {

    @TempDir
    Path dir;

    private BackendLogTail tail() {
        return new BackendLogTail(dir, Duration.ofMillis(500));
    }

    private static String line(String action, String tracking, String trace, String msg) {
        return "{\"@timestamp\":\"2026-11-04T10:00:00Z\",\"log.level\":\"INFO\",\"message\":\"" + msg
                + "\",\"event.action\":\"" + action + "\",\"rp.tracking_id\":\"" + tracking
                + "\",\"trace.id\":\"" + trace + "\"}\n";
    }

    private void append(String file, String text) throws IOException {
        Files.writeString(dir.resolve(file), text, UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    private static LogQuery all() {
        return new LogQuery(null, null, null, 500);
    }

    @Test
    void readsAppendedLines() throws IOException {
        BackendLogTail tail = tail();
        append("backend-a.json", line("api.request", "t1", "x1", "one"));
        tail.poll();
        assertThat(tail.query(all())).hasSize(1);
        append("backend-a.json", line("api.request", "t2", "x2", "two"));
        append("backend-a.json", "{\"message\":\"partial\"");
        tail.poll();
        List<Map<String, Object>> r = tail.query(all());
        assertThat(r).extracting(m -> m.get("message")).containsExactly("two", "one");
        append("backend-a.json", "}\n");
        tail.poll();
        assertThat(tail.query(all())).hasSize(3);
    }

    @Test
    void mergesSeveralFilesInTheDirectory() throws IOException {
        BackendLogTail tail = tail();
        append("backend-a.json", line("a", "t", "x", "from-a"));
        append("backend-b.json", line("b", "t", "x", "from-b"));
        append("other.json", line("c", "t", "x", "ignored"));
        tail.poll();
        assertThat(tail.query(all())).extracting(m -> m.get("message")).containsExactlyInAnyOrder("from-a", "from-b");
    }

    @Test
    void skipsMalformedLines() throws IOException {
        BackendLogTail tail = tail();
        append("backend-a.json", "not json\n" + line("a", "t", "x", "ok") + "[1,2]\n");
        tail.poll();
        assertThat(tail.query(all())).hasSize(1);
        assertThat(tail.malformedCount()).isEqualTo(2);
    }

    @Test
    void filtersByTrackingIdAndAction() throws IOException {
        BackendLogTail tail = tail();
        append("backend-a.json", line("api.request", "t1", "x1", "m1")
                + line("push.sent", "t1", "x1", "m2")
                + line("push.sent", "t2", "x2", "m3"));
        tail.poll();
        assertThat(tail.query(new LogQuery(null, "t1", "push.sent", 10))).extracting(m -> m.get("message"))
                .containsExactly("m2");
        assertThat(tail.query(new LogQuery("x2", null, null, 10))).hasSize(1);
        assertThat(tail.query(new LogQuery(null, null, null, 2))).extracting(m -> m.get("message"))
                .containsExactly("m3", "m2");
    }

    @Test
    void survivesTruncation() throws IOException {
        BackendLogTail tail = tail();
        append("backend-a.json", line("a", "t", "x", "long-first-content-long-first-content"));
        tail.poll();
        Files.writeString(dir.resolve("backend-a.json"), line("a", "t", "x", "new"), UTF_8);
        tail.poll();
        assertThat(tail.query(all())).extracting(m -> m.get("message"))
                .containsExactly("new", "long-first-content-long-first-content");
    }

    @Test
    void missingFileIsEmpty() {
        BackendLogTail missingDir = new BackendLogTail(dir.resolve("nope"), Duration.ofMillis(500));
        missingDir.poll();
        assertThat(missingDir.query(all())).isEmpty();
        tail().poll();
        assertThat(tail().query(all())).isEmpty();
    }

    @Test
    void findsSecretsMasked() throws IOException {
        BackendLogTail tail = tail();
        append("backend-a.json", line("a", "t", "x", "token=supersecret123 leaked"));
        tail.poll();
        assertThat(tail.findSecrets(List.of("supersecret123", "absent-secret-value", "short")))
                .containsExactly("supe…");
    }

    @Test
    void skipsOversizeLinesAndKeepsGoing() throws IOException {
        BackendLogTail tail = tail();
        append("backend-a.json", "x".repeat(BackendLogTail.MAX_LINE_BYTES + 10) + "\n" + line("a", "t", "x", "after"));
        tail.poll();
        assertThat(tail.query(all())).hasSize(1);
        assertThat(tail.oversizeCount()).isEqualTo(1);
    }
}
