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
