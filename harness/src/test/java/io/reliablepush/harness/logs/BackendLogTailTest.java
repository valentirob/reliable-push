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
        BackendLogTail empty = tail();
        empty.poll();
        assertThat(empty.query(all())).isEmpty();
    }

    @Test
    void deletedFileKeepsEntriesAndDoesNotFail() throws IOException {
        BackendLogTail tail = tail();
        append("backend-a.json", line("a", "t", "x", "kept"));
        tail.poll();
        Files.delete(dir.resolve("backend-a.json"));
        tail.poll();
        assertThat(tail.query(all())).hasSize(1);
        append("backend-a.json", line("a", "t", "x", "again"));
        tail.poll();
        assertThat(tail.query(all())).hasSize(2);
    }

    @Test
    void findsSecretsMasked() throws IOException {
        BackendLogTail tail = tail();
        append("backend-a.json", line("a", "t", "x", "token=supersecret123 short leaked"));
        tail.poll();
        assertThat(tail.findSecrets(List.of("supersecret123", "absent-secret-value", "short")))
                .containsExactly("supe…");
    }

    @Test
    void findsSecretsInMalformedAndOversizeLines() throws IOException {
        BackendLogTail tail = tail();
        append("backend-a.json", "BANNER password=hunter2hunter2\n"
                + "oversizesecret99 " + "y".repeat(BackendLogTail.MAX_LINE_BYTES + 10) + "\n");
        tail.poll();
        assertThat(tail.query(all())).isEmpty();
        assertThat(tail.findSecrets(List.of("hunter2hunter2", "oversizesecret99", "neverlogged-value")))
                .containsExactly("hunt…", "over…");
    }

    @Test
    void multiByteCharacterSplitAcrossPolls() throws IOException {
        BackendLogTail tail = tail();
        byte[] bytes = line("a", "t", "x", "caf\u00e9 \u20ac").getBytes(UTF_8);
        int split = new String(bytes, UTF_8).indexOf('\u20ac');
        int cut = new String(bytes, UTF_8).substring(0, split).getBytes(UTF_8).length + 1;
        Path f = dir.resolve("backend-a.json");
        Files.write(f, java.util.Arrays.copyOfRange(bytes, 0, cut));
        tail.poll();
        Files.write(f, java.util.Arrays.copyOfRange(bytes, cut, bytes.length), StandardOpenOption.APPEND);
        tail.poll();
        assertThat(tail.query(all())).extracting(m -> m.get("message")).containsExactly("caf\u00e9 \u20ac");
    }

    @Test
    void ringEvictsOldestPast20000() throws IOException {
        BackendLogTail tail = tail();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < BackendLogTail.MAX_ENTRIES + 10; i++) {
            sb.append(line("a", "t" + i, "x", "m"));
        }
        append("backend-a.json", sb.toString());
        tail.poll();
        assertThat(tail.query(new LogQuery(null, "t9", null, 10))).isEmpty();
        assertThat(tail.query(new LogQuery(null, "t10", null, 10))).hasSize(1);
        assertThat(tail.query(new LogQuery(null, "t" + (BackendLogTail.MAX_ENTRIES + 9), null, 10))).hasSize(1);
    }

    @Test
    void oversizeLineSpanningPolls() throws IOException {
        BackendLogTail tail = tail();
        append("backend-a.json", "z".repeat(BackendLogTail.MAX_LINE_BYTES + 5));
        tail.poll();
        append("backend-a.json", "z".repeat(100) + "\n" + line("a", "t", "x", "after"));
        tail.poll();
        assertThat(tail.query(all())).extracting(m -> m.get("message")).containsExactly("after");
        assertThat(tail.oversizeCount()).isEqualTo(1);
        assertThat(tail.malformedCount()).isZero();
    }

    @Test
    void malformedPartialLineCompletingLater() throws IOException {
        BackendLogTail tail = tail();
        append("backend-a.json", "garbage-start ");
        tail.poll();
        assertThat(tail.malformedCount()).isZero();
        append("backend-a.json", "garbage-end\n");
        tail.poll();
        assertThat(tail.malformedCount()).isEqualTo(1);
    }

    @Test
    void ordersByArrivalAcrossFiles() throws IOException {
        BackendLogTail tail = tail();
        append("backend-a.json", line("a", "t", "x", "a1"));
        tail.poll();
        append("backend-b.json", line("a", "t", "x", "b1"));
        tail.poll();
        append("backend-a.json", line("a", "t", "x", "a2"));
        tail.poll();
        assertThat(tail.query(all())).extracting(m -> m.get("message")).containsExactly("a2", "b1", "a1");
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
