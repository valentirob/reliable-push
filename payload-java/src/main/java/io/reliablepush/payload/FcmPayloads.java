package io.reliablepush.payload;

import io.reliablepush.payload.PayloadException.Code;
import io.reliablepush.payload.internal.Json;
import io.reliablepush.payload.internal.JsonWriter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Tracked FCM HTTP v1 messages for the Android SDK's default mode: <b>data-only</b> with {@code android.priority}
 * {@value #ANDROID_PRIORITY}; {@code rp_tid}, {@code title} and {@code body} travel in {@code data}, and the SDK posts
 * the visible notification itself.
 *
 * <p>All methods are static, stateless and thread-safe. JSON output is compact, keys are in input order and the
 * reliable-push keys are appended; strings are escaped per RFC 8259 and non-ASCII characters are kept (send as
 * UTF-8). Every failure is a {@link PayloadException}.
 *
 * <p><b>Size limit.</b> FCM allows 4,096 bytes of data. What is measured here is the UTF-8 length of the compact JSON
 * serialization of the final {@code data} map (braces, quotes, colons and commas included, which makes it a slightly
 * conservative upper bound of FCM's key-plus-value count). The rest of the message (token, android options) is not
 * counted.
 */
public final class FcmPayloads {

    /** The value of {@code android.priority} in a tracked message. */
    public static final String ANDROID_PRIORITY = "HIGH";

    /** FCM data limit in bytes (UTF-8 length of the JSON serialization of the data map). */
    static final int MAX_DATA_BYTES = 4096;

    private static final String TRACKING_KEY = "rp_tid";
    private static final String TITLE = "title";
    private static final String BODY = "body";
    private static final String NOTIFICATION = "notification";

    private FcmPayloads() {
    }

    /**
     * Full FCM HTTP v1 request body {@code {"message":{"token":..,"data":{rp_tid,title,body,custom..},
     * "android":{"priority":"HIGH"}}}}.
     *
     * @param fcmToken the device registration token, not empty
     * @param trackingId a UUID, see {@link TrackingIds}
     * @param title notification title, omitted when null
     * @param body notification body, omitted when null
     * @param data optional custom data (string values only), appended after {@code body}
     * @throws PayloadException see {@link #data(String, String, String, Map)}
     * @throws IllegalArgumentException the token is null or empty
     */
    public static String build(String fcmToken, String trackingId, String title, String body, Map<String, String> data) {
        if (fcmToken == null || fcmToken.isEmpty()) {
            throw new IllegalArgumentException("fcmToken must not be empty");
        }
        Map<String, String> tracked = data(trackingId, title, body, data);
        Map<String, Object> message = new LinkedHashMap<String, Object>();
        message.put("token", fcmToken);
        message.put("data", tracked);
        message.put("android", priority(new LinkedHashMap<String, Object>()));
        Map<String, Object> root = new LinkedHashMap<String, Object>();
        root.put("message", message);
        return JsonWriter.write(root);
    }

    /**
     * The data map only, for client libraries such as the Firebase Admin SDK:
     * {@code Message.builder().putAllData(FcmPayloads.data(...)).setAndroidConfig(AndroidConfig.builder()
     * .setPriority(AndroidConfig.Priority.HIGH).build())}. Contains {@code rp_tid}, {@code title}, {@code body}
     * (the last two omitted when null) and then the custom entries, in that order. The result is unmodifiable.
     *
     * @throws PayloadException {@code INVALID_TRACKING_ID}; {@code RESERVED_KEY} ({@code data} contains
     *     {@code rp_tid}, {@code title} or {@code body}); {@code FCM_NON_STRING_DATA} (a value is not a string,
     *     including {@code null}); {@code FCM_TOO_LARGE} (see the class comment for what is measured)
     * @throws IllegalArgumentException a key is null
     */
    public static Map<String, String> data(String trackingId, String title, String body, Map<String, String> data) {
        requireTrackingId(trackingId);
        Map<String, String> out = new LinkedHashMap<String, String>();
        out.put(TRACKING_KEY, trackingId);
        if (title != null) {
            out.put(TITLE, title);
        }
        if (body != null) {
            out.put(BODY, body);
        }
        if (data != null) {
            for (Map.Entry<String, ?> e : data.entrySet()) {
                requireUsable(e.getKey(), e.getValue());
                out.put(e.getKey(), (String) e.getValue());
            }
        }
        requireSize(out);
        return Collections.unmodifiableMap(out);
    }

    /**
     * Converts the sender's own FCM HTTP v1 request body ({@code {"message":{...}}}) into the tracked data-only form:
     * {@code message.notification.title/body} and {@code message.android.notification.title/body} (the Android one wins)
     * move into {@code message.data}, both {@code notification} objects are removed, {@code message.android.priority}
     * becomes {@value #ANDROID_PRIORITY} and {@code rp_tid} is added. Existing {@code data} entries are kept (they come
     * first; {@code rp_tid}, {@code title} and {@code body} are appended). Everything else, including the rest of the
     * request body, {@code token} or {@code topic} and {@code android.ttl}, is kept as is and in order.
     *
     * @throws PayloadException {@code INVALID_TRACKING_ID}; {@code INVALID_JSON} (not a JSON object with a
     *     {@code message} object, or {@code data}, {@code android}, {@code notification} of the wrong type, duplicate
     *     keys, nesting beyond 64 levels); {@code FCM_UNSUPPORTED_NOTIFICATION_FIELD} (a notification object holds a
     *     field other than {@code title} and {@code body}: the message lists them); {@code FCM_NON_STRING_DATA};
     *     {@code RESERVED_KEY} (existing data uses {@code rp_tid}, {@code title} or {@code body}); {@code FCM_TOO_LARGE}
     */
    public static String decorate(String fcmV1Json, String trackingId) {
        requireTrackingId(trackingId);
        Map<String, Object> root = object(parse(fcmV1Json), "the FCM request body");
        if (!root.containsKey("message")) {
            throw new PayloadException(Code.INVALID_JSON, "the FCM request body must have a 'message' object");
        }
        Map<String, Object> message = object(root.get("message"), "message");
        Map<String, Object> android = optionalObject(message, "android");
        Map<String, Object> topNotification = optionalObject(message, NOTIFICATION);
        Map<String, Object> androidNotification = android == null ? null : optionalObject(android, NOTIFICATION);
        Map<String, Object> existingData = optionalObject(message, "data");

        List<String> unsupported = new ArrayList<String>();
        collectUnsupported(topNotification, NOTIFICATION, unsupported);
        collectUnsupported(androidNotification, "android.notification", unsupported);
        if (!unsupported.isEmpty()) {
            throw new PayloadException(Code.FCM_UNSUPPORTED_NOTIFICATION_FIELD,
                    "cannot track notification fields: " + join(unsupported)
                            + " (a tracked FCM message is data-only; remove them or send the push untracked)");
        }

        Map<String, Object> data = new LinkedHashMap<String, Object>();
        if (existingData != null) {
            for (Map.Entry<String, Object> e : existingData.entrySet()) {
                requireUsable(e.getKey(), e.getValue());
                data.put(e.getKey(), e.getValue());
            }
        }
        data.put(TRACKING_KEY, trackingId);
        putText(data, TITLE, pick(androidNotification, topNotification, TITLE));
        putText(data, BODY, pick(androidNotification, topNotification, BODY));
        requireSize(data);

        Map<String, Object> newMessage = new LinkedHashMap<String, Object>();
        for (Map.Entry<String, Object> e : message.entrySet()) {
            if (NOTIFICATION.equals(e.getKey())) {
                continue;
            }
            if ("android".equals(e.getKey())) {
                newMessage.put("android", priority(withoutNotification(android)));
            } else if ("data".equals(e.getKey())) {
                newMessage.put("data", data);
            } else {
                newMessage.put(e.getKey(), e.getValue());
            }
        }
        if (!newMessage.containsKey("data")) {
            newMessage.put("data", data);
        }
        if (!newMessage.containsKey("android")) {
            newMessage.put("android", priority(new LinkedHashMap<String, Object>()));
        }
        Map<String, Object> newRoot = new LinkedHashMap<String, Object>(root);
        newRoot.put("message", newMessage);
        return JsonWriter.write(newRoot);
    }

    private static Map<String, Object> priority(Map<String, Object> android) {
        android.put("priority", ANDROID_PRIORITY);
        return android;
    }

    private static Map<String, Object> withoutNotification(Map<String, Object> android) {
        Map<String, Object> out = new LinkedHashMap<String, Object>(android);
        out.remove(NOTIFICATION);
        return out;
    }

    private static void collectUnsupported(Map<String, Object> notification, String path, List<String> into) {
        if (notification == null) {
            return;
        }
        for (String key : notification.keySet()) {
            if (!TITLE.equals(key) && !BODY.equals(key)) {
                into.add(path + "." + key);
            }
        }
    }

    /** The android notification's value if it has the key (it overrides), else the top-level one; must be a string. */
    private static Object pick(Map<String, Object> androidNotification, Map<String, Object> topNotification, String key) {
        Map<String, Object> source = androidNotification != null && androidNotification.containsKey(key)
                ? androidNotification : topNotification;
        if (source == null || !source.containsKey(key)) {
            return null;
        }
        Object value = source.get(key);
        if (!(value instanceof String)) {
            throw new PayloadException(Code.FCM_NON_STRING_DATA, "notification " + key + " must be a string");
        }
        return value;
    }

    private static void putText(Map<String, Object> data, String key, Object value) {
        if (value != null) {
            data.put(key, value);
        }
    }

    private static void requireUsable(String key, Object value) {
        if (key == null) {
            throw new IllegalArgumentException("data keys must not be null");
        }
        if (TRACKING_KEY.equals(key) || TITLE.equals(key) || BODY.equals(key)) {
            throw new PayloadException(Code.RESERVED_KEY, "data key '" + key + "' is reserved");
        }
        if (!(value instanceof String)) {
            throw new PayloadException(Code.FCM_NON_STRING_DATA, "data value of '" + key + "' must be a string");
        }
    }

    private static void requireSize(Map<String, ?> data) {
        int size = JsonWriter.utf8Length(JsonWriter.write(data));
        if (size > MAX_DATA_BYTES) {
            throw new PayloadException(Code.FCM_TOO_LARGE, "FCM data is " + size + " bytes; the limit is " + MAX_DATA_BYTES);
        }
    }

    private static Object parse(String json) {
        try {
            return Json.read(json);
        } catch (IllegalArgumentException e) {
            throw new PayloadException(Code.INVALID_JSON, e.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> object(Object value, String what) {
        if (!(value instanceof Map)) {
            throw new PayloadException(Code.INVALID_JSON, what + " must be a JSON object");
        }
        return (Map<String, Object>) value;
    }

    private static Map<String, Object> optionalObject(Map<String, Object> parent, String key) {
        return parent.containsKey(key) ? object(parent.get(key), key) : null;
    }

    private static String join(List<String> parts) {
        StringBuilder sb = new StringBuilder();
        for (String p : parts) {
            if (sb.length() > 0) {
                sb.append(", ");
            }
            sb.append(p);
        }
        return sb.toString();
    }

    private static void requireTrackingId(String trackingId) {
        if (!TrackingIds.isValid(trackingId)) {
            throw new PayloadException(Code.INVALID_TRACKING_ID, "trackingId must be a UUID");
        }
    }
}
