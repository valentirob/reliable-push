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
 * Tracked APNs payloads: a visible {@code aps.alert}, {@code aps.mutable-content = 1} (so the Notification Service
 * Extension runs) and the top-level string {@code rp_tid}.
 *
 * <p>All methods are static, stateless and thread-safe. JSON output is compact, keys are in input order and the
 * reliable-push keys are appended; numbers are written as given, strings are escaped per RFC 8259 and non-ASCII
 * characters are kept (the result is meant to be sent as UTF-8). Every failure is a {@link PayloadException}.
 *
 * <p>The size limit is APNs's 4,096 bytes, measured as the UTF-8 length of the produced JSON.
 */
public final class ApnsPayloads {

    /** APNs payload limit in bytes (UTF-8 length of the JSON). */
    static final int MAX_BYTES = 4096;

    private static final String TRACKING_KEY = "rp_tid";
    private static final String APS = "aps";

    private ApnsPayloads() {
    }

    /**
     * Full APNs payload {@code {"aps":{"alert":{"title":..,"body":..},"mutable-content":1},"rp_tid":..,<custom>}}.
     *
     * @param trackingId a UUID, see {@link TrackingIds}
     * @param title alert title; omitted when null or empty (at least one of {@code title}, {@code body} is required)
     * @param body alert body; omitted when null or empty
     * @param custom optional custom top-level keys, appended after {@code rp_tid} in iteration order; values may be
     *     {@code null}, {@code String}, {@code Number}, {@code Boolean}, {@code Map<String, ?>} or {@code List}
     * @throws PayloadException {@code INVALID_TRACKING_ID}, {@code APNS_NO_ALERT} (no title and no body),
     *     {@code RESERVED_KEY} (custom contains {@code rp_tid} or {@code aps}), {@code APNS_TOO_LARGE}
     * @throws IllegalArgumentException a custom value has an unsupported type
     */
    public static String build(String trackingId, String title, String body, Map<String, ?> custom) {
        requireTrackingId(trackingId);
        Map<String, Object> alert = new LinkedHashMap<String, Object>();
        if (title != null && !title.isEmpty()) {
            alert.put("title", title);
        }
        if (body != null && !body.isEmpty()) {
            alert.put("body", body);
        }
        if (alert.isEmpty()) {
            throw new PayloadException(Code.APNS_NO_ALERT, "title and body are both empty: a tracked APNs push needs a visible alert");
        }
        Map<String, Object> aps = new LinkedHashMap<String, Object>();
        aps.put("alert", alert);
        aps.put("mutable-content", 1);
        Map<String, Object> payload = new LinkedHashMap<String, Object>();
        payload.put(APS, aps);
        payload.put(TRACKING_KEY, trackingId);
        if (custom != null) {
            for (Map.Entry<String, ?> e : custom.entrySet()) {
                if (TRACKING_KEY.equals(e.getKey()) || APS.equals(e.getKey())) {
                    throw new PayloadException(Code.RESERVED_KEY, "custom key '" + e.getKey() + "' is reserved");
                }
                payload.put(e.getKey(), e.getValue());
            }
        }
        return serialize(payload);
    }

    /**
     * Adds {@code aps.mutable-content = 1} and the top-level {@code rp_tid} to the sender's own APNs
     * payload. Everything else is kept as is, in the same order (an existing {@code mutable-content} is replaced in
     * place).
     *
     * @param apnsJson the sender's payload, a JSON object with a visible alert: {@code aps.alert} is a non-empty string
     *     or an object whose {@code title} or {@code body} is a non-empty string
     * @param trackingId a UUID, see {@link TrackingIds}
     * @throws PayloadException {@code INVALID_TRACKING_ID}, {@code INVALID_JSON} (not a JSON object, including
     *     duplicate keys and nesting beyond 64 levels), {@code RESERVED_KEY} (the payload already has a top-level
     *     {@code rp_tid}), {@code APNS_NO_ALERT} (for example a {@code content-available}-only payload),
     *     {@code APNS_TOO_LARGE}
     */
    public static String decorate(String apnsJson, String trackingId) {
        requireTrackingId(trackingId);
        return serialize(decorateMap(parseObject(apnsJson), trackingId));
    }

    /**
     * Same rules as {@link #decorate(String, String)} for a payload held as a map. Returns a new map (the input is not
     * modified) with {@code mutable-content} and {@code rp_tid} added. The result is deeply unmodifiable: nested maps
     * and lists are copied and wrapped, so it shares no mutable state with the input. The result must fit 4,096 bytes when serialized as compact UTF-8 JSON.
     *
     * @throws PayloadException as for {@link #decorate(String, String)} except {@code INVALID_JSON}
     * @throws IllegalArgumentException a key is not a string or a value has an unsupported type
     */
    public static Map<String, Object> decorate(Map<String, ?> apnsPayload, String trackingId) {
        requireTrackingId(trackingId);
        if (apnsPayload == null) {
            throw new PayloadException(Code.INVALID_JSON, "the APNs payload is null");
        }
        Map<String, Object> result = decorateMap(apnsPayload, trackingId);
        serialize(result);
        return freezeMap(result);
    }

    private static Map<String, Object> freezeMap(Map<?, ?> in) {
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        for (Map.Entry<?, ?> e : in.entrySet()) {
            out.put((String) e.getKey(), freeze(e.getValue()));
        }
        return Collections.unmodifiableMap(out);
    }

    private static Object freeze(Object v) {
        if (v instanceof Map) {
            return freezeMap((Map<?, ?>) v);
        }
        if (v instanceof List) {
            List<Object> out = new ArrayList<Object>();
            for (Object o : (List<?>) v) {
                out.add(freeze(o));
            }
            return Collections.unmodifiableList(out);
        }
        return v;
    }

    private static Map<String, Object> decorateMap(Map<?, ?> in, String trackingId) {
        if (in.containsKey(TRACKING_KEY)) {
            throw new PayloadException(Code.RESERVED_KEY, "the payload already has a top-level 'rp_tid'");
        }
        Object aps = in.get(APS);
        if (!(aps instanceof Map) || !hasVisibleAlert((Map<?, ?>) aps)) {
            throw new PayloadException(Code.APNS_NO_ALERT,
                    "aps.alert must be a non-empty string or an object with a title or body: tracking needs a visible alert");
        }
        Map<String, Object> apsCopy = copy((Map<?, ?>) aps);
        apsCopy.put("mutable-content", 1);
        Map<String, Object> result = copy(in);
        result.put(APS, apsCopy);
        result.put(TRACKING_KEY, trackingId);
        return result;
    }

    private static boolean hasVisibleAlert(Map<?, ?> aps) {
        Object alert = aps.get("alert");
        if (alert instanceof String) {
            return !((String) alert).isEmpty();
        }
        if (alert instanceof Map) {
            return nonEmptyString(((Map<?, ?>) alert).get("title")) || nonEmptyString(((Map<?, ?>) alert).get("body"));
        }
        return false;
    }

    private static boolean nonEmptyString(Object o) {
        return o instanceof String && !((String) o).isEmpty();
    }

    private static Map<String, Object> copy(Map<?, ?> in) {
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        for (Map.Entry<?, ?> e : in.entrySet()) {
            if (!(e.getKey() instanceof String)) {
                throw new IllegalArgumentException("JSON object keys must be strings");
            }
            out.put((String) e.getKey(), e.getValue());
        }
        return out;
    }

    private static Map<String, Object> parseObject(String json) {
        Object parsed;
        try {
            parsed = Json.read(json);
        } catch (IllegalArgumentException e) {
            throw new PayloadException(Code.INVALID_JSON, e.getMessage());
        }
        if (!(parsed instanceof Map)) {
            throw new PayloadException(Code.INVALID_JSON, "the APNs payload must be a JSON object");
        }
        return copy((Map<?, ?>) parsed);
    }

    private static String serialize(Map<String, Object> payload) {
        String json = JsonWriter.write(payload);
        int size = JsonWriter.utf8Length(json);
        if (size > MAX_BYTES) {
            throw new PayloadException(Code.APNS_TOO_LARGE, "APNs payload is " + size + " bytes; the limit is " + MAX_BYTES);
        }
        return json;
    }

    private static void requireTrackingId(String trackingId) {
        if (!TrackingIds.isValid(trackingId)) {
            throw new PayloadException(Code.INVALID_TRACKING_ID, "trackingId must be a UUID");
        }
    }
}
