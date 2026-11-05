package io.reliablepush.harness.sender;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import tools.jackson.databind.json.JsonMapper;

/**
 * The exact payloads a customer backend would hand to APNs / FCM for a tracked push (spec 6), pretty-printed with
 * two-space indentation. The recipient token is the literal placeholder {@code <device-token>}.
 * FCM v2 follows the brief's shape ({@code android_v2.remote_notification}); the integration guide only fixes
 * {@code mutable_content=true} and {@code rp_tid} in the data.
 */
public final class PayloadPreview {

    static final String DEVICE_TOKEN = "<device-token>";
    private static final JsonMapper STRINGS = JsonMapper.builder().build();

    private PayloadPreview() {
    }

    public static String apns(UUID tid, String title, String body) {
        return render(map(
                "aps", map("alert", map("title", title, "body", body), "mutable-content", 1),
                "rp_tid", tid.toString()));
    }

    /** Android mode v1: data-only, high priority, no {@code notification} key. */
    public static String fcmV1(UUID tid, String title, String body) {
        return render(map("message", map(
                "token", DEVICE_TOKEN,
                "android", map("priority", "HIGH"),
                "data", map("rp_tid", tid.toString(), "title", title, "body", body))));
    }

    /** Android mode v2: RemoteNotification with mutable_content and rp_tid in its data. */
    public static String fcmV2(UUID tid, String title, String body) {
        return render(map("message", map(
                "token", DEVICE_TOKEN,
                "android_v2", map("remote_notification", map(
                        "notification", map("title", title, "body", body),
                        "mutable_content", true,
                        "data", map("rp_tid", tid.toString()))))));
    }

    /**
     * The {@code adb} line that hands a tracked push to the sample app's debug receiver without FCM. It is meant for
     * pasting into a host shell: {@code adb shell} joins its arguments into one command line for the device shell, so
     * every free-text value is single-quoted twice (once for the device shell, once for the host shell). The tracking
     * id is a UUID and needs no quoting.
     */
    public static String androidDebugBroadcast(UUID tid, String title, String body) {
        return "adb shell am broadcast -a io.reliablepush.sample.DEBUG_PUSH"
                + " -n io.reliablepush.sample/.DebugPushReceiver"
                + " --es rp_tid " + tid
                + " --es title " + quote(quote(title))
                + " --es body " + quote(quote(body));
    }

    private static String quote(String s) {
        return "'" + s.replace("'", "'\\''") + "'";
    }

    private static Map<String, Object> map(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }

    private static String render(Object value) {
        StringBuilder sb = new StringBuilder();
        write(sb, value, 0);
        return sb.toString();
    }

    private static void write(StringBuilder sb, Object v, int depth) {
        if (v instanceof Map<?, ?> m) {
            sb.append("{\n");
            int i = 0;
            for (Map.Entry<?, ?> e : m.entrySet()) {
                sb.append("  ".repeat(depth + 1)).append(STRINGS.writeValueAsString(e.getKey())).append(": ");
                write(sb, e.getValue(), depth + 1);
                sb.append(++i < m.size() ? ",\n" : "\n");
            }
            sb.append("  ".repeat(depth)).append('}');
        } else if (v instanceof String || v instanceof Number || v instanceof Boolean) {
            sb.append(STRINGS.writeValueAsString(v));
        } else {
            sb.append("null");
        }
    }
}
