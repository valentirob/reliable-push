package io.reliablepush.harness.sender;

import io.reliablepush.payload.ApnsPayloads;
import io.reliablepush.payload.FcmPayloads;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * The exact payloads a customer backend would hand to APNs / FCM for a tracked push, pretty-printed with two-space
 * indentation. APNs and FCM v1 are built by the payload jar ({@code reliable-push-payload}: {@link ApnsPayloads},
 * {@link FcmPayloads}) and only re-indented here: same keys, same order, same values. The recipient token is the
 * literal placeholder {@code <device-token>}. The FCM v2 preview is not built by the jar: it only documents the future
 * shape ({@code android_v2.remote_notification}, TODO 2.1); the integration guide fixes {@code mutable_content=true}
 * and {@code rp_tid} in the data.
 */
public final class PayloadPreview {

    static final String DEVICE_TOKEN = "<device-token>";
    private static final JsonMapper STRINGS = JsonMapper.builder().build();
    private static final TypeReference<LinkedHashMap<String, Object>> OBJECT = new TypeReference<>() {};

    private PayloadPreview() {
    }

    /**
     * The compact APNs payload of the jar ({@code ApnsPayloads.build}); what the harness sends to APNs.
     *
     * @throws io.reliablepush.payload.PayloadException for example when title and body are both empty
     */
    public static String apnsCompact(UUID tid, String title, String body) {
        return ApnsPayloads.build(tid.toString(), title, body, null);
    }

    /** The compact FCM HTTP v1 message of the jar ({@code FcmPayloads.build}); what the harness sends to FCM. */
    public static String fcmV1Compact(UUID tid, String title, String body, String fcmToken) {
        return FcmPayloads.build(fcmToken, tid.toString(), title, body, null);
    }

    public static String apns(UUID tid, String title, String body) {
        return pretty(apnsCompact(tid, title, body));
    }

    /** Android mode v1: data-only, high priority, no {@code notification} key. */
    public static String fcmV1(UUID tid, String title, String body) {
        return fcmV1(tid, title, body, DEVICE_TOKEN);
    }

    /** The same message addressed to a real FCM registration token, pretty-printed. */
    public static String fcmV1(UUID tid, String title, String body, String fcmToken) {
        return pretty(fcmV1Compact(tid, title, body, fcmToken));
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

    /**
     * The {@code xcrun simctl openurl} line that hands a tracked push to the iOS sample app's debug URL without APNs.
     * Values are percent-encoded; the whole URL is single-quoted for the host shell.
     */
    public static String iosDebugOpenUrl(UUID tid, String title, String body) {
        return "xcrun simctl openurl booted " + quote("rpsample://debug-push?rp_tid=" + tid
                + "&title=" + encode(title) + "&body=" + encode(body));
    }

    private static String encode(String s) {
        return java.net.URLEncoder.encode(s, java.nio.charset.StandardCharsets.UTF_8).replace("+", "%20")
                .replace("'", "%27");
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

    /** Re-indents compact JSON from the jar, keeping key order (only objects, strings, numbers, booleans occur). */
    static String pretty(String compactJson) {
        return render(STRINGS.readValue(compactJson, OBJECT));
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
