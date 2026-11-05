package io.reliablepush.harness.console;

import static org.assertj.core.api.Assertions.assertThat;

import io.reliablepush.harness.config.HarnessProperties;
import io.reliablepush.harness.devices.GrantMinter;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Grant endpoint and real-device registry used by the Android sample app on an emulator. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "harness.poll-interval=1h")
@ActiveProfiles("test")
class RealDevicesIT {

    @LocalServerPort
    int port;

    @Autowired
    HarnessProperties properties;

    @Autowired
    GrantMinter minter;

    final HttpClient http = HttpClient.newHttpClient();
    final JsonMapper json = JsonMapper.builder().build();

    HttpResponse<String> get(String path) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    HttpResponse<String> post(String path, String contentType, String body) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .POST(HttpRequest.BodyPublishers.ofString(body));
        if (contentType != null) {
            b.header("Content-Type", contentType);
        }
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    HttpResponse<String> post(String path, String body) throws Exception {
        return post(path, "application/json", body);
    }

    @Test
    void grantEndpointMintsVerifiableGrant() throws Exception {
        HttpResponse<String> r = post("/console/api/grants", "{\"appId\":\"shop\",\"userId\":\"emu-user\"}");

        assertThat(r.statusCode()).isEqualTo(201);
        JsonNode out = json.readTree(r.body());
        String grant = out.get("grant").asString();
        String[] parts = grant.split("\\.");
        assertThat(parts).hasSize(3);
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(properties.app("shop").grantSecret().getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        byte[] expected = mac.doFinal((parts[0] + "." + parts[1]).getBytes(StandardCharsets.US_ASCII));
        assertThat(MessageDigest.isEqual(expected, Base64.getUrlDecoder().decode(parts[2]))).isTrue();
        JsonNode claims = json.readTree(Base64.getUrlDecoder().decode(parts[1]));
        assertThat(claims.get("aud").asString()).isEqualTo("shop");
        assertThat(claims.get("sub").asString()).isEqualTo("emu-user");
        assertThat(claims.get("exp").asLong() - claims.get("iat").asLong()).isEqualTo(120);
        assertThat(r.headers().firstValue("Cache-Control").orElse("")).contains("no-store");
        assertThat(out.get("expiresAt").asString()).isNotBlank();
        assertThat(minter.issuedGrantsForLogHygieneScan()).contains(grant);
    }

    @Test
    void grantRequiresJsonBodyAndKnownApp() throws Exception {
        assertThat(post("/console/api/grants", "text/plain", "{\"appId\":\"shop\",\"userId\":\"u\"}").statusCode())
                .isEqualTo(415);
        assertThat(post("/console/api/grants", "{\"appId\":\"nope\",\"userId\":\"u\"}").statusCode()).isEqualTo(404);
        assertThat(post("/console/api/grants", "{\"appId\":\"shop\",\"userId\":\"\"}").statusCode()).isEqualTo(400);
        assertThat(post("/console/api/grants", "{\"appId\":\"shop\",\"userId\":\"" + "x".repeat(201) + "\"}")
                .statusCode()).isEqualTo(400);
        HttpResponse<String> cross = http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port
                + "/console/api/grants")).header("Content-Type", "application/json")
                .header("Origin", "http://evil.example")
                .POST(HttpRequest.BodyPublishers.ofString("{\"appId\":\"shop\",\"userId\":\"u\"}")).build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(cross.statusCode()).isEqualTo(403);
    }

    @Test
    void realDeviceAppearsInDeviceList() throws Exception {
        String id = UUID.randomUUID().toString();
        HttpResponse<String> r = post("/console/api/real-devices", "{\"appId\":\"shop\",\"userId\":\"emu-user\","
                + "\"deviceId\":\"" + id + "\",\"platform\":\"ANDROID\",\"label\":\"Pixel emulator\"}");
        assertThat(r.statusCode()).as(r.body()).isEqualTo(201);

        JsonNode list = json.readTree(get("/console/api/devices?appId=shop&userId=emu-user").body());
        JsonNode found = null;
        for (JsonNode d : list) {
            if (id.equals(d.get("deviceId").asString())) {
                found = d;
            }
        }
        assertThat(found).isNotNull();
        assertThat(found.get("kind").asString()).isEqualTo("REAL");
        assertThat(found.get("label").asString()).isEqualTo("Pixel emulator");
        assertThat(found.get("platform").asString()).isEqualTo("ANDROID");
        assertThat(found.get("online").asBoolean()).isTrue();
    }

    @Test
    void realDeviceInputIsValidatedAndBounded() throws Exception {
        String ok = UUID.randomUUID().toString();
        String base = "\"appId\":\"shop\",\"userId\":\"v-user\",\"platform\":\"ANDROID\",\"label\":\"l\"";
        assertThat(post("/console/api/real-devices", "{" + base + ",\"deviceId\":\"nope\"}").statusCode()).isEqualTo(400);
        assertThat(post("/console/api/real-devices", "{\"appId\":\"zzz\",\"userId\":\"u\",\"deviceId\":\"" + ok
                + "\",\"platform\":\"ANDROID\"}").statusCode()).isEqualTo(404);
        assertThat(post("/console/api/real-devices", "{\"appId\":\"shop\",\"userId\":\"u\",\"deviceId\":\"" + ok
                + "\",\"platform\":\"WINDOWS\"}").statusCode()).isEqualTo(400);
        assertThat(post("/console/api/real-devices", "{\"appId\":\"shop\",\"userId\":\"u\",\"deviceId\":\"" + ok
                + "\",\"platform\":\"ANDROID\",\"label\":\"" + "x".repeat(101) + "\"}").statusCode()).isEqualTo(400);
        assertThat(post("/console/api/real-devices", "{\"appId\":\"shop\",\"userId\":\"" + "u".repeat(201)
                + "\",\"deviceId\":\"" + ok + "\",\"platform\":\"ANDROID\"}").statusCode()).isEqualTo(400);

        for (int i = 0; i < 55; i++) {
            assertThat(post("/console/api/real-devices", "{\"appId\":\"shop\",\"userId\":\"cap-user\",\"deviceId\":\""
                    + UUID.randomUUID() + "\",\"platform\":\"IOS\",\"label\":\"d" + i + "\"}").statusCode()).isEqualTo(201);
        }
        JsonNode list = json.readTree(get("/console/api/devices?appId=shop&userId=cap-user").body());
        assertThat(list).hasSize(50);
        assertThat(list.get(0).get("label").asString()).isEqualTo("d5");
    }

    @Test
    void fcmTokenIsValidatedStoredAndNeverReturned() throws Exception {
        String token = "fcm-token-SENSITIVE-" + "x".repeat(40);
        String id = UUID.randomUUID().toString();
        String head = "{\"appId\":\"shop\",\"userId\":\"fcm-user\",\"deviceId\":\"" + id
                + "\",\"platform\":\"ANDROID\",\"label\":\"l\",\"fcmToken\":";
        assertThat(post("/console/api/real-devices", head + "\"\"}").statusCode()).isEqualTo(400);
        assertThat(post("/console/api/real-devices", head + "\"   \"}").statusCode()).isEqualTo(400);
        HttpResponse<String> tooLong = post("/console/api/real-devices", head + "\"" + "t".repeat(4097) + "\"}");
        assertThat(tooLong.statusCode()).isEqualTo(400);
        assertThat(tooLong.body()).doesNotContain("tttt");

        HttpResponse<String> created = post("/console/api/real-devices", head + "\"" + token + "\"}");
        assertThat(created.statusCode()).isEqualTo(201);
        assertThat(created.body()).doesNotContain(token).contains("\"hasFcmToken\":true");
        assertThat(get("/console/api/devices?appId=shop&userId=fcm-user").body()).doesNotContain(token)
                .contains("\"hasFcmToken\":true");

        String next = "fcm-token-NEXT-" + "y".repeat(40);
        HttpResponse<String> updated = post("/console/api/real-devices/" + id + "/fcm-token",
                "{\"fcmToken\":\"" + next + "\"}");
        assertThat(updated.statusCode()).isEqualTo(200);
        assertThat(updated.body()).doesNotContain(next);
        assertThat(post("/console/api/real-devices/" + UUID.randomUUID() + "/fcm-token",
                "{\"fcmToken\":\"" + next + "\"}").statusCode()).isEqualTo(404);
        assertThat(post("/console/api/real-devices/" + id + "/fcm-token", "{\"fcmToken\":\" \"}").statusCode())
                .isEqualTo(400);
        assertThat(post("/console/api/real-devices/nope/fcm-token", "{\"fcmToken\":\"a\"}").statusCode())
                .isEqualTo(400);
    }

    @Test
    void appsReportRealFcmModeOffWithoutCredentials() throws Exception {
        JsonNode apps = json.readTree(get("/console/api/apps").body());
        assertThat(apps.get(0).get("fcmEnabled").asBoolean()).isFalse();
    }
}
