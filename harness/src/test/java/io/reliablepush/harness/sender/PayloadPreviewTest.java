package io.reliablepush.harness.sender;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class PayloadPreviewTest {

    static final UUID TID = UUID.fromString("6f1c2b9e-5d1a-4c55-9a38-0c1f3a7d9e11");
    final JsonMapper json = JsonMapper.builder().build();

    @Test
    void apnsHasMutableContentAndTid() {
        String out = PayloadPreview.apns(TID, "Order shipped", "On its way.");

        JsonNode n = json.readTree(out);
        assertThat(n.at("/aps/alert/title").asString()).isEqualTo("Order shipped");
        assertThat(n.at("/aps/alert/body").asString()).isEqualTo("On its way.");
        assertThat(n.at("/aps/mutable-content").asInt()).isEqualTo(1);
        assertThat(n.get("rp_tid").asString()).isEqualTo(TID.toString());
    }

    @Test
    void fcmV1IsDataOnlyHighPriority() {
        JsonNode m = json.readTree(PayloadPreview.fcmV1(TID, "T", "B")).get("message");

        assertThat(m.has("notification")).isFalse();
        assertThat(m.get("token").asString()).isEqualTo("<device-token>");
        assertThat(m.at("/android/priority").asString()).isEqualTo("HIGH");
        assertThat(m.at("/data/rp_tid").asString()).isEqualTo(TID.toString());
        assertThat(m.at("/data/title").asString()).isEqualTo("T");
        assertThat(m.at("/data/body").asString()).isEqualTo("B");
    }

    @Test
    void fcmV2HasMutableContent() {
        JsonNode m = json.readTree(PayloadPreview.fcmV2(TID, "T", "B")).get("message");

        assertThat(m.get("token").asString()).isEqualTo("<device-token>");
        JsonNode rn = m.at("/android_v2/remote_notification");
        assertThat(rn.get("mutable_content").asBoolean()).isTrue();
        assertThat(rn.at("/data/rp_tid").asString()).isEqualTo(TID.toString());
        assertThat(rn.at("/notification/title").asString()).isEqualTo("T");
        assertThat(rn.at("/notification/body").asString()).isEqualTo("B");
    }

    @Test
    void escapesQuotesAndUnicodeAndIndentsByTwoSpaces() {
        String title = "He said \"hi\" \\ é中\n";
        String out = PayloadPreview.fcmV1(TID, title, "b");

        assertThat(json.readTree(out).at("/message/data/title").asString()).isEqualTo(title);
        assertThat(out).startsWith("{\n  \"message\": {\n    \"token\": \"<device-token>\",\n");
        assertThat(out).contains("\\\"hi\\\"").contains("\\n");
    }

    @Test
    void debugBroadcastQuotesValues(@org.junit.jupiter.api.io.TempDir java.nio.file.Path tmp) throws Exception {
        java.nio.file.Path canary = tmp.resolve("pwned");
        for (String[] c : new String[][] {
                {"It's $(touch " + canary + ") `touch " + canary + "` \"b\" a  b", "Two words; echo hi | cat & \\n end"},
                {"a\nb", "line1\nline2\n"},
                {"", ""}}) {
            assertRoundTrip(tmp, c[0], c[1]);
        }
        assertThat(java.nio.file.Files.exists(canary)).isFalse();
    }

    private void assertRoundTrip(java.nio.file.Path tmp, String title, String body) throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue(java.nio.file.Files.isExecutable(java.nio.file.Path.of("/bin/sh")));
        String cmd = PayloadPreview.androidDebugBroadcast(TID, title, body);
        assertThat(cmd).startsWith("adb shell am broadcast -a io.reliablepush.sample.DEBUG_PUSH"
                + " -n io.reliablepush.sample/.DebugPushReceiver --es rp_tid " + TID + " --es title ");
        // Round trip through two real shells: the host shell, then adb joining the args for the device shell.
        java.nio.file.Path bin = java.nio.file.Files.createTempDirectory(tmp, "bin");
        java.nio.file.Files.writeString(bin.resolve("adb"), "#!/bin/sh\nshift\nexec /bin/sh -c \"$*\"\n");
        java.nio.file.Files.writeString(bin.resolve("am"),
                "#!/bin/sh\nfor a in \"$@\"; do printf '%s\\0' \"$a\"; done\n");
        bin.resolve("adb").toFile().setExecutable(true);
        bin.resolve("am").toFile().setExecutable(true);
        ProcessBuilder pb = new ProcessBuilder("/bin/sh", "-c", cmd).redirectErrorStream(true);
        pb.environment().put("PATH", bin + ":" + pb.environment().get("PATH"));
        Process p = pb.start();
        String out = new String(p.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        assertThat(p.waitFor()).isZero();
        java.util.List<String> args = java.util.List.of(out.split("\0", -1));
        assertThat(args.subList(0, 14)).containsExactly(
                "broadcast", "-a", "io.reliablepush.sample.DEBUG_PUSH", "-n", "io.reliablepush.sample/.DebugPushReceiver",
                "--es", "rp_tid", TID.toString(), "--es", "title", title, "--es", "body", body);
    }

    @Test
    void iosDebugOpenUrlEncodesAndQuotes() {
        String cmd = PayloadPreview.iosDebugOpenUrl(TID, "It's a&b c", "B\u00e9 ?");

        assertThat(cmd).isEqualTo("xcrun simctl openurl booted 'rpsample://debug-push?rp_tid=" + TID
                + "&title=It%27s%20a%26b%20c&body=B%C3%A9%20%3F'");
    }
}
