package io.reliablepush.payload;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.reliablepush.payload.PayloadException.Code;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ApnsPayloadsTest {

    private static final String TID = "6f1c2b9e-5d1a-4c55-9a38-0c1f3a7d9e11";

    private static void assertCode(Runnable call, Code code) {
        assertThatThrownBy(call::run)
                .isInstanceOfSatisfying(PayloadException.class, e -> assertThat(e.code()).isEqualTo(code))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static String repeat(char c, int n) {
        char[] a = new char[n];
        Arrays.fill(a, c);
        return new String(a);
    }

    @Test
    void buildProducesTheDocumentedShape() {
        assertThat(ApnsPayloads.build(TID, "T", "B", null))
                .isEqualTo("{\"aps\":{\"alert\":{\"title\":\"T\",\"body\":\"B\"},\"mutable-content\":1},"
                        + "\"rp_tid\":\"" + TID + "\"}");
    }

    @Test
    void buildAppendsCustomKeysInOrder() {
        Map<String, Object> custom = new LinkedHashMap<String, Object>();
        custom.put("orderId", 1001);
        custom.put("deep", Arrays.asList("a", true, null));
        assertThat(ApnsPayloads.build(TID, "T", "B", custom))
                .isEqualTo("{\"aps\":{\"alert\":{\"title\":\"T\",\"body\":\"B\"},\"mutable-content\":1},"
                        + "\"rp_tid\":\"" + TID + "\",\"orderId\":1001,\"deep\":[\"a\",true,null]}");
    }

    @Test
    void buildKeepsNonAsciiAsUtf8AndEscapesControlCharacters() {
        String json = ApnsPayloads.build(TID, "Pedido enviado \u00e9\u00e0 \u65e5\u672c \ud83d\ude80", "a\"b\\c\nd\u0001", null);
        assertThat(json).contains("\"title\":\"Pedido enviado \u00e9\u00e0 \u65e5\u672c \ud83d\ude80\"");
        assertThat(json).contains("\"body\":\"a\\\"b\\\\c\\nd\\u0001\"");
    }

    @Test
    void buildWithOnlyOneOfTitleOrBodyOmitsTheOther() {
        assertThat(ApnsPayloads.build(TID, null, "B", null)).contains("\"alert\":{\"body\":\"B\"}");
    }

    @Test
    void buildWithoutTitleAndBodyHasNoAlert() {
        assertCode(() -> ApnsPayloads.build(TID, null, null, null), Code.APNS_NO_ALERT);
        assertCode(() -> ApnsPayloads.build(TID, "", "", null), Code.APNS_NO_ALERT);
    }

    @Test
    void buildRejectsInvalidTrackingId() {
        assertCode(() -> ApnsPayloads.build("nope", "T", "B", null), Code.INVALID_TRACKING_ID);
        assertCode(() -> ApnsPayloads.build(null, "T", "B", null), Code.INVALID_TRACKING_ID);
    }

    @Test
    void buildRejectsReservedCustomKeys() {
        assertCode(() -> ApnsPayloads.build(TID, "T", "B", Collections1.map("rp_tid", "x")), Code.RESERVED_KEY);
        assertCode(() -> ApnsPayloads.build(TID, "T", "B", Collections1.map("aps", "x")), Code.RESERVED_KEY);
    }

    @Test
    void buildRejectsPayloadAbove4096Bytes() {
        assertCode(() -> ApnsPayloads.build(TID, "T", repeat('x', 4096), null), Code.APNS_TOO_LARGE);
        // the limit is in UTF-8 bytes, not characters: 1400 characters of 3 bytes each
        assertCode(() -> ApnsPayloads.build(TID, "T", repeat('\u65e5', 1400), null), Code.APNS_TOO_LARGE);
    }

    @Test
    void buildAcceptsPayloadOfExactly4096Bytes() {
        // overhead = bytes of the payload around the body text
        String one = ApnsPayloads.build(TID, "T", "x", null);
        int overhead = one.getBytes(StandardCharsets.UTF_8).length - 1;
        String json = ApnsPayloads.build(TID, "T", repeat('x', 4096 - overhead), null);
        assertThat(json.getBytes(StandardCharsets.UTF_8)).hasSize(4096);
    }

    @Test
    void decorateKeepsSenderKeysAndOrderAndAppendsReliablePushKeys() {
        String in = "{\"aps\":{\"alert\":{\"title\":\"T\",\"body\":\"B\"},\"sound\":\"default\",\"badge\":3},"
                + "\"orderId\": 1001, \"price\": 1.50, \"big\": 12345678901234567890, \"exp\": 1E3}";
        assertThat(ApnsPayloads.decorate(in, TID))
                .isEqualTo("{\"aps\":{\"alert\":{\"title\":\"T\",\"body\":\"B\"},\"sound\":\"default\",\"badge\":3,"
                        + "\"mutable-content\":1},\"orderId\":1001,\"price\":1.50,\"big\":12345678901234567890,"
                        + "\"exp\":1E3,\"rp_tid\":\"" + TID + "\"}");
    }

    @Test
    void decorateAcceptsAStringAlertAndOverridesAnExistingMutableContent() {
        assertThat(ApnsPayloads.decorate("{\"aps\":{\"mutable-content\":0,\"alert\":\"Hello\"}}", TID))
                .isEqualTo("{\"aps\":{\"mutable-content\":1,\"alert\":\"Hello\"},\"rp_tid\":\"" + TID + "\"}");
    }

    @Test
    void decorateAcceptsAnAlertObjectWithOnlyABody() {
        assertThat(ApnsPayloads.decorate("{\"aps\":{\"alert\":{\"body\":\"B\"}}}", TID))
                .isEqualTo("{\"aps\":{\"alert\":{\"body\":\"B\"},\"mutable-content\":1},\"rp_tid\":\"" + TID + "\"}");
    }

    @Test
    void decorateKeepsNonAsciiAndEscapesRoundTrip() {
        String in = "{\"aps\":{\"alert\":{\"title\":\"\u65e5\u672c\\u00e9\\n\\\"q\\\"\"}}}";
        assertThat(ApnsPayloads.decorate(in, TID)).startsWith("{\"aps\":{\"alert\":{\"title\":\"\u65e5\u672c\u00e9\\n\\\"q\\\"\"}");
    }

    @Test
    void decorateMapVariantReturnsAnIndependentMapAndLeavesTheInputUntouched() {
        Map<String, Object> alert = new LinkedHashMap<String, Object>();
        alert.put("title", "T");
        Map<String, Object> aps = new LinkedHashMap<String, Object>();
        aps.put("alert", alert);
        Map<String, Object> in = new LinkedHashMap<String, Object>();
        in.put("aps", aps);
        in.put("k", "v");

        Map<String, Object> out = ApnsPayloads.decorate(in, TID);

        assertThat(out.keySet()).containsExactly("aps", "k", "rp_tid");
        assertThat(out.get("rp_tid")).isEqualTo(TID);
        assertThat(((Map<?, ?>) out.get("aps")).get("mutable-content")).isEqualTo(1);
        assertThat(in.keySet()).containsExactly("aps", "k");
        assertThat(aps.keySet()).containsExactly("alert");
    }

    @Test
    @SuppressWarnings("unchecked")
    void decorateMapVariantReturnsADeeplyUnmodifiableMapNotSharingTheInput() {
        Map<String, Object> alert = new LinkedHashMap<String, Object>();
        alert.put("title", "T");
        Map<String, Object> aps = new LinkedHashMap<String, Object>();
        aps.put("alert", alert);
        java.util.List<Object> list = new java.util.ArrayList<Object>(Arrays.asList("a", alert));
        Map<String, Object> in = new LinkedHashMap<String, Object>();
        in.put("aps", aps);
        in.put("list", list);

        Map<String, Object> out = ApnsPayloads.decorate(in, TID);

        assertThatThrownBy(() -> out.put("x", 1)).isInstanceOf(UnsupportedOperationException.class);
        Map<String, Object> outAps = (Map<String, Object>) out.get("aps");
        assertThatThrownBy(() -> outAps.put("x", 1)).isInstanceOf(UnsupportedOperationException.class);
        Map<String, Object> outAlert = (Map<String, Object>) outAps.get("alert");
        assertThatThrownBy(() -> outAlert.put("x", 1)).isInstanceOf(UnsupportedOperationException.class);
        java.util.List<Object> outList = (java.util.List<Object>) out.get("list");
        assertThatThrownBy(() -> outList.add("x")).isInstanceOf(UnsupportedOperationException.class);
        alert.put("title", "changed");
        list.add("later");
        assertThat(outAlert.get("title")).isEqualTo("T");
        assertThat(outList).hasSize(2);
    }

    @Test
    void decorateRejectsPayloadsWithoutAVisibleAlert() {
        assertCode(() -> ApnsPayloads.decorate("{\"aps\":{\"content-available\":1}}", TID), Code.APNS_NO_ALERT);
        assertCode(() -> ApnsPayloads.decorate("{}", TID), Code.APNS_NO_ALERT);
        assertCode(() -> ApnsPayloads.decorate("{\"aps\":\"x\"}", TID), Code.APNS_NO_ALERT);
        assertCode(() -> ApnsPayloads.decorate("{\"aps\":{\"alert\":\"\"}}", TID), Code.APNS_NO_ALERT);
        assertCode(() -> ApnsPayloads.decorate("{\"aps\":{\"alert\":{}}}", TID), Code.APNS_NO_ALERT);
        assertCode(() -> ApnsPayloads.decorate("{\"aps\":{\"alert\":{\"title\":\"\",\"body\":3}}}", TID), Code.APNS_NO_ALERT);
        assertCode(() -> ApnsPayloads.decorate("{\"aps\":{\"alert\":5}}", TID), Code.APNS_NO_ALERT);
    }

    @Test
    void decorateRejectsAnExistingRpTid() {
        assertCode(() -> ApnsPayloads.decorate("{\"aps\":{\"alert\":\"x\"},\"rp_tid\":\"y\"}", TID), Code.RESERVED_KEY);
    }

    @Test
    void decorateRejectsInvalidJson() {
        for (String bad : new String[] {"", "[]", "\"x\"", "null", "{", "{\"a\":}", "{\"a\":1,}", "{\"a\":1} x", "{'a':1}",
            "{\"a\":01}", "{\"a\":\"\\q\"}", "{\"a\":\"\u0001\"}", "{\"a\":1,\"a\":2}"}) {
            assertCode(() -> ApnsPayloads.decorate(bad, TID), Code.INVALID_JSON);
        }
        assertCode(() -> ApnsPayloads.decorate((String) null, TID), Code.INVALID_JSON);
    }

    @Test
    void decorateRejectsInvalidTrackingId() {
        assertCode(() -> ApnsPayloads.decorate("{\"aps\":{\"alert\":\"x\"}}", "nope"), Code.INVALID_TRACKING_ID);
    }

    @Test
    void decorateRejectsPayloadAbove4096Bytes() {
        String in = "{\"aps\":{\"alert\":\"" + repeat('x', 4090) + "\"}}";
        assertCode(() -> ApnsPayloads.decorate(in, TID), Code.APNS_TOO_LARGE);
    }

    @Test
    void decorateRejectsTooDeepJson() {
        StringBuilder sb = new StringBuilder("{\"aps\":{\"alert\":\"x\"},\"a\":");
        for (int i = 0; i < 100; i++) {
            sb.append('[');
        }
        for (int i = 0; i < 100; i++) {
            sb.append(']');
        }
        sb.append('}');
        assertCode(() -> ApnsPayloads.decorate(sb.toString(), TID), Code.INVALID_JSON);
    }
}
