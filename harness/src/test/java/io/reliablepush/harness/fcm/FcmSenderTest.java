package io.reliablepush.harness.fcm;

import static org.assertj.core.api.Assertions.assertThat;

import io.reliablepush.harness.fcm.FcmTestSupport.MutableClock;
import io.reliablepush.harness.fcm.FcmTestSupport.Seen;
import io.reliablepush.harness.fcm.FcmTestSupport.Stub;
import io.reliablepush.harness.sender.PayloadPreview;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.Appender;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Configuration;
import org.apache.logging.log4j.core.config.Property;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FcmSenderTest {

    static final String FCM_TOKEN = "fcm-registration-token-SENSITIVE-1234567890";
    static final String ACCESS_TOKEN = "ya29.access-token-SENSITIVE";
    static final Instant T0 = Instant.parse("2026-11-05T10:00:00Z");

    @TempDir
    Path dir;

    /** Both Google endpoints on one stub: /token and /v1/projects/{p}/messages:send. */
    Stub stub(int fcmStatus, String fcmBody) throws Exception {
        return new Stub(s -> s.path().equals("/token")
                ? new Object[] {200, "{\"access_token\":\"" + ACCESS_TOKEN + "\",\"expires_in\":3600}"}
                : new Object[] {fcmStatus, fcmBody});
    }

    FcmSender sender(Stub stub, KeyPair key) throws Exception {
        FcmCredentials creds = FcmCredentials.load(FcmTestSupport.writeCredentials(dir, key)).orElseThrow();
        GoogleAccessTokens tokens = new GoogleAccessTokens(creds, new MutableClock(T0), URI.create(stub.url() + "/token"));
        return new FcmSender(creds, tokens, URI.create(stub.url()));
    }

    @Test
    void sendPostsV1DataMessageWithBearer() throws Exception {
        KeyPair key = FcmTestSupport.newKey();
        UUID tid = UUID.randomUUID();
        try (Stub stub = stub(200, "{\"name\":\"projects/example-project/messages/0:1\"}")) {
            FcmResult r = sender(stub, key).send(FCM_TOKEN, tid, "Hello \"you\"", "Body\nline");

            assertThat(r).isEqualTo(FcmResult.sent());
            Seen post = stub.seen.get(1);
            assertThat(post.method()).isEqualTo("POST");
            assertThat(post.path()).isEqualTo("/v1/projects/example-project/messages:send");
            assertThat(post.authorization()).isEqualTo("Bearer " + ACCESS_TOKEN);
            assertThat(post.contentType()).startsWith("application/json");
            assertThat(post.body()).isEqualTo(PayloadPreview.fcmV1(tid, "Hello \"you\"", "Body\nline", FCM_TOKEN));
            assertThat(post.body()).contains("\"rp_tid\": \"" + tid + "\"").contains("\"priority\": \"HIGH\"")
                    .doesNotContain("\"notification\"");
        }
    }

    @Test
    void unregisteredDropsTheToken() throws Exception {
        KeyPair key = FcmTestSupport.newKey();
        try (Stub stub = stub(404, "{\"error\":{\"code\":404,\"status\":\"NOT_FOUND\","
                + "\"details\":[{\"errorCode\":\"UNREGISTERED\"}]}}")) {
            FcmSender sender = sender(stub, key);
            assertThat(sender.send(FCM_TOKEN, UUID.randomUUID(), "t", "b")).isEqualTo(FcmResult.unregistered());

            io.reliablepush.harness.devices.RealDeviceRegistry registry =
                    new io.reliablepush.harness.devices.RealDeviceRegistry();
            UUID device = UUID.randomUUID();
            registry.add("shop", "u", device, io.reliablepush.harness.devices.Platform.ANDROID, "l", FCM_TOKEN);
            FcmPusher pusher = new FcmPusher(java.util.Optional.of(sender), registry);

            List<FcmPusher.DevicePush> out = pusher.push("shop", "u", UUID.randomUUID(), "t", "b");

            assertThat(out).hasSize(1);
            assertThat(out.get(0).result()).isEqualTo(FcmResult.unregistered());
            assertThat(registry.all("shop").get(0).fcmToken()).isNull();
            assertThat(pusher.push("shop", "u", UUID.randomUUID(), "t", "b")).isEmpty();
        }
    }

    @Test
    void otherStatusesFail() throws Exception {
        KeyPair key = FcmTestSupport.newKey();
        try (Stub stub = stub(500, "{\"error\":\"boom\"}")) {
            assertThat(sender(stub, key).send(FCM_TOKEN, UUID.randomUUID(), "t", "b")).isEqualTo(FcmResult.failed(500));
        }
    }

    @Test
    void unreachableFcmFailsWithStatusZero() throws Exception {
        KeyPair key = FcmTestSupport.newKey();
        Stub stub = stub(200, "{}");
        FcmSender s = sender(stub, key);
        stub.close();
        assertThat(s.send(FCM_TOKEN, UUID.randomUUID(), "t", "b")).isEqualTo(FcmResult.failed(0));
    }

    @Test
    void credentialsNeverInToStringOrLogs() throws Exception {
        KeyPair key = FcmTestSupport.newKey();
        Path file = FcmTestSupport.writeCredentials(dir, key);
        String privateKeyB64 = java.util.Base64.getEncoder().encodeToString(key.getPrivate().getEncoded());
        List<String> logged = new ArrayList<>();
        LoggerContext ctx = (LoggerContext) LogManager.getContext(false);
        Configuration config = ctx.getConfiguration();
        Appender capture = new AbstractAppender("capture", null, null, true, Property.EMPTY_ARRAY) {
            @Override
            public void append(LogEvent e) {
                logged.add(e.getMessage().getFormattedMessage() + (e.getThrown() == null ? "" : e.getThrown().toString()));
            }
        };
        capture.start();
        String loggerName = "io.reliablepush.harness.fcm";
        org.apache.logging.log4j.core.config.Configurator.setLevel(loggerName, org.apache.logging.log4j.Level.DEBUG);
        config.getLoggerConfig(loggerName).addAppender(capture, null, null);
        ctx.updateLoggers();
        try (Stub bad = stub(500, "{\"error\":\"" + FCM_TOKEN + "\"}")) {
            FcmCredentials creds = FcmCredentials.load(file).orElseThrow();
            GoogleAccessTokens tokens = new GoogleAccessTokens(creds, new MutableClock(T0), URI.create(bad.url() + "/token"));
            FcmSender sender = new FcmSender(creds, tokens, URI.create(bad.url()));
            sender.send(FCM_TOKEN, UUID.randomUUID(), "t", "b");
            tokens.accessToken();
            io.reliablepush.harness.devices.RealDeviceRegistry registry =
                    new io.reliablepush.harness.devices.RealDeviceRegistry();
            registry.add("shop", "u", UUID.randomUUID(), io.reliablepush.harness.devices.Platform.ANDROID, "l", FCM_TOKEN);
            new FcmPusher(java.util.Optional.of(sender), registry).push("shop", "u", UUID.randomUUID(), "t", "b");

            List<String> texts = new ArrayList<>(List.of(creds.toString(), tokens.toString(), sender.toString(),
                    registry.all("shop").get(0).toString()));
            texts.addAll(logged);
            for (String t : texts) {
                assertThat(t).doesNotContain(FCM_TOKEN, ACCESS_TOKEN, privateKeyB64, "PRIVATE KEY",
                        FcmTestSupport.CLIENT_EMAIL);
            }
            assertThat(creds.toString()).contains(FcmTestSupport.PROJECT);
            assertThat(logged).isNotEmpty();
        } finally {
            config.getLoggerConfig(loggerName).removeAppender("capture");
            capture.stop();
            ctx.updateLoggers();
        }
    }

    @Test
    void missingCredentialsDisableRealMode() throws Exception {
        assertThat(FcmCredentials.load(dir.resolve("absent.json"))).isEmpty();
        assertThat(new FcmPusher(dir.resolve("absent.json").toString(),
                new io.reliablepush.harness.devices.RealDeviceRegistry()).enabled()).isFalse();

        Path broken = dir.resolve("broken.json");
        Files.writeString(broken, "{\"project_id\":\"p\",\"private_key\":\"not a key\"}");
        FcmPusher pusher = new FcmPusher(broken.toString(), new io.reliablepush.harness.devices.RealDeviceRegistry());
        assertThat(pusher.enabled()).isFalse();
        assertThat(pusher.push("shop", "u", UUID.randomUUID(), "t", "b")).isEmpty();

        Path good = FcmTestSupport.writeCredentials(dir, FcmTestSupport.newKey());
        assertThat(new FcmPusher(good.toString(), new io.reliablepush.harness.devices.RealDeviceRegistry()).enabled())
                .isTrue();
    }

    @Test
    void invalidProjectIdIsRejectedWithoutEchoingIt() throws Exception {
        KeyPair key = FcmTestSupport.newKey();
        for (String bad : List.of("Bad_Project", "Zq9", "has space", "x/../y", "UPPER-case-id", "-leading-dash", "trailing-dash-",
                "a".repeat(31))) {
            Path file = dir.resolve("bad.json");
            Files.writeString(file, Files.readString(FcmTestSupport.writeCredentials(dir, key))
                    .replace("\"project_id\":\"example-project\"", "\"project_id\":\"" + bad + "\""));
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> FcmCredentials.load(file))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageNotContaining(bad);
        }
        assertThat(FcmCredentials.load(FcmTestSupport.writeCredentials(dir, key))).isPresent();
    }

    @Test
    void anyRuntimeFailureBecomesStatusZero() throws Exception {
        KeyPair key = FcmTestSupport.newKey();
        try (Stub stub = stub(200, "{}")) {
            FcmCredentials creds = FcmCredentials.load(FcmTestSupport.writeCredentials(dir, key)).orElseThrow();
            GoogleAccessTokens tokens = new GoogleAccessTokens(creds, new MutableClock(T0), URI.create(stub.url() + "/token"));
            // an unsupported scheme makes the JDK client throw IllegalArgumentException from send()
            FcmSender s = new FcmSender(creds, tokens, URI.create("ftp://127.0.0.1"));
            assertThat(s.send(FCM_TOKEN, UUID.randomUUID(), "t", "b")).isEqualTo(FcmResult.failed(0));
        }
    }

    @Test
    void unregisteredKeepsATokenThatWasReplacedMeanwhile() throws Exception {
        KeyPair key = FcmTestSupport.newKey();
        io.reliablepush.harness.devices.RealDeviceRegistry registry =
                new io.reliablepush.harness.devices.RealDeviceRegistry();
        UUID device = UUID.randomUUID();
        registry.add("shop", "u", device, io.reliablepush.harness.devices.Platform.ANDROID, "l", FCM_TOKEN);
        try (Stub stub = new Stub(s -> {
            if (s.path().equals("/token")) {
                return new Object[] {200, "{\"access_token\":\"" + ACCESS_TOKEN + "\",\"expires_in\":3600}"};
            }
            registry.setFcmToken(device, "newer-token"); // the app reported a fresh token while the send was in flight
            return new Object[] {404, "{\"error\":{\"details\":[{\"errorCode\":\"UNREGISTERED\"}]}}"};
        })) {
            new FcmPusher(java.util.Optional.of(sender(stub, key)), registry).push("shop", "u", UUID.randomUUID(), "t", "b");
            assertThat(registry.all("shop").get(0).fcmToken()).isEqualTo("newer-token");
        }
    }
}
