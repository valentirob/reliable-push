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
}
