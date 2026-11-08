package io.reliablepush.payload;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.reliablepush.payload.PayloadException.Code;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class FcmPayloadsTest {

    private static final String TID = "6f1c2b9e-5d1a-4c55-9a38-0c1f3a7d9e11";

    private static PayloadException fail(Runnable call, Code code) {
        assertThatThrownBy(call::run)
                .isInstanceOfSatisfying(PayloadException.class, e -> assertThat(e.code()).isEqualTo(code));
        try {
            call.run();
        } catch (PayloadException e) {
            return e;
        }
        throw new AssertionError("unreachable");
    }

    private static String repeat(char c, int n) {
        char[] a = new char[n];
        Arrays.fill(a, c);
        return new String(a);
    }

    @Test
    void androidPriorityIsHigh() {
        assertThat(FcmPayloads.ANDROID_PRIORITY).isEqualTo("HIGH");
    }

    @Test
    void buildEqualsTheIntegrationGuideExample() {
        assertThat(FcmPayloads.build("tok", TID, "Order shipped", "Your order 1001 is on its way.", null))
                .isEqualTo("{\"message\":{\"token\":\"tok\",\"data\":{\"rp_tid\":\"" + TID
                        + "\",\"title\":\"Order shipped\",\"body\":\"Your order 1001 is on its way.\"},"
                        + "\"android\":{\"priority\":\"HIGH\"}}}");
    }

    @Test
    void buildAppendsCustomDataInOrder() {
        Map<String, String> custom = new LinkedHashMap<String, String>();
        custom.put("orderId", "1001");
        custom.put("a", "b");
        assertThat(FcmPayloads.build("tok", TID, "T", "B", custom))
                .contains("\"data\":{\"rp_tid\":\"" + TID + "\",\"title\":\"T\",\"body\":\"B\",\"orderId\":\"1001\",\"a\":\"b\"}");
    }

    @Test
    void dataReturnsTrackedMapWithCustomKeys() {
        Map<String, String> custom = new LinkedHashMap<String, String>();
        custom.put("orderId", "1001");
        Map<String, String> data = FcmPayloads.data(TID, "T", "B", custom);

        assertThat(data).containsExactly(
                new java.util.AbstractMap.SimpleEntry<String, String>("rp_tid", TID),
                new java.util.AbstractMap.SimpleEntry<String, String>("title", "T"),
                new java.util.AbstractMap.SimpleEntry<String, String>("body", "B"),
                new java.util.AbstractMap.SimpleEntry<String, String>("orderId", "1001"));
        assertThatThrownBy(() -> data.put("x", "y")).isInstanceOf(UnsupportedOperationException.class);
        assertThat(custom).hasSize(1);
    }

    @Test
    void dataKeepsNonAscii() {
        assertThat(FcmPayloads.build("tok", TID, "Pedido \u00e9 \u65e5\u672c", "B", null))
                .contains("\"title\":\"Pedido \u00e9 \u65e5\u672c\"");
    }

    @Test
    void invalidTrackingIdIsRejected() {
        fail(() -> FcmPayloads.data("nope", "T", "B", null), Code.INVALID_TRACKING_ID);
        fail(() -> FcmPayloads.build("tok", null, "T", "B", null), Code.INVALID_TRACKING_ID);
        fail(() -> FcmPayloads.decorate("{\"message\":{}}", "nope"), Code.INVALID_TRACKING_ID);
    }

    @Test
    void reservedDataKeysAreRejected() {
        fail(() -> FcmPayloads.data(TID, "T", "B", TestMaps.map("rp_tid", "x")), Code.RESERVED_KEY);
        fail(() -> FcmPayloads.data(TID, "T", "B", TestMaps.map("title", "x")), Code.RESERVED_KEY);
        fail(() -> FcmPayloads.data(TID, "T", "B", TestMaps.map("body", "x")), Code.RESERVED_KEY);
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void nonStringDataValuesAreRejected() {
        Map raw = new LinkedHashMap();
        raw.put("n", 5);
        fail(() -> FcmPayloads.data(TID, "T", "B", raw), Code.FCM_NON_STRING_DATA);
        Map withNull = new LinkedHashMap();
        withNull.put("n", null);
        fail(() -> FcmPayloads.data(TID, "T", "B", withNull), Code.FCM_NON_STRING_DATA);
    }

    @Test
    void tooLargeDataIsRejected() {
        fail(() -> FcmPayloads.data(TID, "T", repeat('x', 4096), null), Code.FCM_TOO_LARGE);
        fail(() -> FcmPayloads.build("tok", TID, "T", repeat('\u65e5', 1400), null), Code.FCM_TOO_LARGE);
    }

    @Test
    void blankTokenIsRejected() {
        assertThatThrownBy(() -> FcmPayloads.build("", TID, "T", "B", null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> FcmPayloads.build(null, TID, "T", "B", null)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void decorateMovesNotificationIntoDataAndSetsHighPriority() {
        String in = "{\"message\":{\"token\":\"tok\",\"notification\":{\"title\":\"T\",\"body\":\"B\"},"
                + "\"data\":{\"orderId\":\"1001\"},\"android\":{\"ttl\":\"60s\",\"priority\":\"NORMAL\"}}}";
        assertThat(FcmPayloads.decorate(in, TID))
                .isEqualTo("{\"message\":{\"token\":\"tok\",\"data\":{\"orderId\":\"1001\",\"rp_tid\":\"" + TID
                        + "\",\"title\":\"T\",\"body\":\"B\"},\"android\":{\"ttl\":\"60s\",\"priority\":\"HIGH\"}}}");
    }

    @Test
    void decorateAddsMissingDataAndAndroidObjects() {
        assertThat(FcmPayloads.decorate("{\"message\":{\"topic\":\"news\",\"notification\":{\"title\":\"T\"}}}", TID))
                .isEqualTo("{\"message\":{\"topic\":\"news\",\"data\":{\"rp_tid\":\"" + TID
                        + "\",\"title\":\"T\"},\"android\":{\"priority\":\"HIGH\"}}}");
    }

    @Test
    void decorateLetsAndroidNotificationOverrideTheTopLevelOne() {
        String in = "{\"message\":{\"token\":\"tok\",\"notification\":{\"title\":\"T\",\"body\":\"B\"},"
                + "\"android\":{\"notification\":{\"title\":\"AT\"},\"ttl\":\"5s\"}}}";
        assertThat(FcmPayloads.decorate(in, TID))
                .isEqualTo("{\"message\":{\"token\":\"tok\",\"android\":{\"ttl\":\"5s\",\"priority\":\"HIGH\"},"
                        + "\"data\":{\"rp_tid\":\"" + TID + "\",\"title\":\"AT\",\"body\":\"B\"}}}");
    }

    @Test
    void decorateRejectsAMessageWithoutVisibleTitleOrBody() {
        fail(() -> FcmPayloads.decorate("{\"message\":{\"token\":\"tok\",\"data\":{\"k\":\"v\"}}}", TID),
                Code.FCM_NO_NOTIFICATION);
        fail(() -> FcmPayloads.decorate("{\"message\":{\"notification\":{}}}", TID), Code.FCM_NO_NOTIFICATION);
        fail(() -> FcmPayloads.decorate("{\"message\":{\"notification\":{\"title\":\"\",\"body\":\"\"}}}", TID),
                Code.FCM_NO_NOTIFICATION);
        fail(() -> FcmPayloads.decorate("{\"message\":{\"android\":{\"notification\":{\"title\":\"\"}}}}", TID),
                Code.FCM_NO_NOTIFICATION);
    }

    @Test
    void dataAndBuildRejectAMessageWithoutVisibleTitleOrBody() {
        fail(() -> FcmPayloads.data(TID, null, null, null), Code.FCM_NO_NOTIFICATION);
        fail(() -> FcmPayloads.data(TID, "", "", TestMaps.map("k", "v")), Code.FCM_NO_NOTIFICATION);
        fail(() -> FcmPayloads.build("tok", TID, null, "", null), Code.FCM_NO_NOTIFICATION);
        assertThat(FcmPayloads.data(TID, null, "B", null)).containsEntry("body", "B").doesNotContainKey("title");
    }

    @Test
    void upperCaseTrackingIdIsEmbeddedInLowerCase() {
        String upper = TID.toUpperCase(java.util.Locale.ROOT);
        assertThat(FcmPayloads.data(upper, "T", "B", null)).containsEntry("rp_tid", TID);
        assertThat(FcmPayloads.build("tok", upper, "T", "B", null)).contains("\"rp_tid\":\"" + TID + "\"");
        assertThat(FcmPayloads.decorate("{\"message\":{\"notification\":{\"title\":\"T\"}}}", upper))
                .contains("\"rp_tid\":\"" + TID + "\"");
    }

    @Test
    void decorateKeepsOtherFieldsAsIs() {
        String in = "{\"validate_only\":false,\"message\":{\"token\":\"tok\",\"fcm_options\":{\"analytics_label\":\"x\"},"
                + "\"webpush\":{\"headers\":{\"TTL\":\"60\"}},\"notification\":{\"title\":\"T\"}}}";
        assertThat(FcmPayloads.decorate(in, TID))
                .isEqualTo("{\"validate_only\":false,\"message\":{\"token\":\"tok\",\"fcm_options\":{\"analytics_label\":\"x\"},"
                        + "\"webpush\":{\"headers\":{\"TTL\":\"60\"}},\"data\":{\"rp_tid\":\"" + TID
                        + "\",\"title\":\"T\"},\"android\":{\"priority\":\"HIGH\"}}}");
    }

    @Test
    void decorateRejectsUnsupportedNotificationFieldsAndNamesThem() {
        PayloadException e = fail(() -> FcmPayloads.decorate(
                "{\"message\":{\"notification\":{\"title\":\"T\",\"image\":\"u\"},"
                        + "\"android\":{\"notification\":{\"channel_id\":\"c\",\"click_action\":\"a\",\"body\":\"B\"}}}}", TID),
                Code.FCM_UNSUPPORTED_NOTIFICATION_FIELD);
        assertThat(e.getMessage()).contains("image", "channel_id", "click_action").doesNotContain("title");
    }

    @Test
    void decorateRejectsNonStringDataAndNotificationValues() {
        fail(() -> FcmPayloads.decorate("{\"message\":{\"data\":{\"n\":5}}}", TID), Code.FCM_NON_STRING_DATA);
        fail(() -> FcmPayloads.decorate("{\"message\":{\"data\":{\"n\":null}}}", TID), Code.FCM_NON_STRING_DATA);
        fail(() -> FcmPayloads.decorate("{\"message\":{\"notification\":{\"title\":5}}}", TID), Code.FCM_NON_STRING_DATA);
    }

    @Test
    void decorateRejectsReservedDataKeys() {
        fail(() -> FcmPayloads.decorate("{\"message\":{\"data\":{\"rp_tid\":\"x\"}}}", TID), Code.RESERVED_KEY);
        fail(() -> FcmPayloads.decorate("{\"message\":{\"data\":{\"title\":\"x\"}}}", TID), Code.RESERVED_KEY);
        fail(() -> FcmPayloads.decorate("{\"message\":{\"data\":{\"body\":\"x\"}}}", TID), Code.RESERVED_KEY);
    }

    @Test
    void decorateRejectsInvalidInput() {
        for (String bad : new String[] {"", "[]", "not json", "{}", "{\"message\":[]}", "{\"message\":{\"data\":[]}}",
            "{\"message\":{\"notification\":\"x\"}}", "{\"message\":{\"android\":1}}",
            "{\"message\":{\"android\":{\"notification\":3}}}"}) {
            fail(() -> FcmPayloads.decorate(bad, TID), Code.INVALID_JSON);
        }
        fail(() -> FcmPayloads.decorate(null, TID), Code.INVALID_JSON);
    }

    @Test
    void decorateRejectsTooLargeData() {
        String in = "{\"message\":{\"notification\":{\"title\":\"T\",\"body\":\"" + repeat('x', 4090) + "\"}}}";
        fail(() -> FcmPayloads.decorate(in, TID), Code.FCM_TOO_LARGE);
    }
}
