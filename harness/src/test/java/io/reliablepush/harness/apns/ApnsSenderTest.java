package io.reliablepush.harness.apns;

import static org.assertj.core.api.Assertions.assertThat;

import io.reliablepush.harness.apns.ApnsTestSupport.MutableClock;
import io.reliablepush.harness.apns.ApnsTestSupport.Seen;
import io.reliablepush.harness.apns.ApnsTestSupport.Stub;
import io.reliablepush.harness.devices.Platform;
import io.reliablepush.harness.devices.RealDeviceRegistry;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
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

class ApnsSenderTest {

    static final String DEVICE_TOKEN = "00112233445566778899aabbccddeeff00112233445566778899aabbccddeeff";
    static final Instant T0 = Instant.parse("2026-11-05T10:00:00Z");

    @TempDir
    Path dir;

    ApnsSender sender(Stub stub, KeyPair key) throws Exception {
        ApnsTestSupport.writeCredentials(dir, key);
        ApnsCredentials creds = ApnsCredentials.load(dir).orElseThrow();
        return new ApnsSender(creds, new ApnsProviderToken(creds, new MutableClock(T0)), URI.create(stub.url()));
    }

    @Test
    void sendPostsAlertPushWithHeaders() throws Exception {
        UUID tid = UUID.randomUUID();
        try (Stub stub = new Stub(s -> new Object[] {200, ""})) {
            ApnsResult r = sender(stub, ApnsTestSupport.newKey()).send(DEVICE_TOKEN, tid, "Hello \"you\"", "Body\nline");

            assertThat(r).isEqualTo(ApnsResult.sent());
            Seen post = stub.seen.get(0);
            assertThat(post.method()).isEqualTo("POST");
            assertThat(post.path()).isEqualTo("/3/device/" + DEVICE_TOKEN);
            assertThat(post.headers().get("authorization")).startsWith("bearer ").matches("bearer [\\w-]+\\.[\\w-]+\\.[\\w-]+");
            assertThat(post.headers()).containsEntry("apns-topic", ApnsTestSupport.TOPIC)
                    .containsEntry("apns-push-type", "alert").containsEntry("apns-priority", "10")
                    .containsEntry("apns-expiration", "0");
            // the payload jar's compact output, byte for byte
            assertThat(post.body()).isEqualTo(io.reliablepush.payload.ApnsPayloads.build(tid.toString(),
                    "Hello \"you\"", "Body\nline", null));
            assertThat(post.body()).contains("\"mutable-content\":1").contains("\"rp_tid\":\"" + tid + "\"");
        }
    }

    @Test
    void goneOrBadTokenDropsTheToken() throws Exception {
        for (Object[] answer : new Object[][] {{410, "{\"reason\":\"Unregistered\"}"}, {400, "{\"reason\":\"BadDeviceToken\"}"}}) {
            Path sub = Files.createDirectory(dir.resolve("d" + answer[0]));
            ApnsTestSupport.writeCredentials(sub, ApnsTestSupport.newKey());
            ApnsCredentials creds = ApnsCredentials.load(sub).orElseThrow();
            try (Stub stub = new Stub(s -> answer)) {
                ApnsSender sender = new ApnsSender(creds, new ApnsProviderToken(creds, new MutableClock(T0)),
                        URI.create(stub.url()));
                assertThat(sender.send(DEVICE_TOKEN, UUID.randomUUID(), "t", "b").outcome())
                        .isEqualTo(ApnsResult.Outcome.UNREGISTERED);

                RealDeviceRegistry registry = new RealDeviceRegistry();
                UUID device = UUID.randomUUID();
                registry.add("shop", "u", device, Platform.IOS, "l");
                registry.setApnsToken(device, DEVICE_TOKEN);
                ApnsPusher pusher = new ApnsPusher(Optional.of(sender), registry);

                List<ApnsPusher.DevicePush> out = pusher.push("shop", "u", UUID.randomUUID(), "t", "b");

                assertThat(out).hasSize(1);
                assertThat(out.get(0).result().outcome()).isEqualTo(ApnsResult.Outcome.UNREGISTERED);
                assertThat(registry.all("shop").get(0).apnsToken()).isNull();
                assertThat(pusher.push("shop", "u", UUID.randomUUID(), "t", "b")).isEmpty();
            }
        }
    }

    @Test
    void otherAnswersFailAndCompareAndClearKeepsANewerToken() throws Exception {
        try (Stub stub = new Stub(s -> new Object[] {403, "{\"reason\":\"InvalidProviderToken\"}"})) {
            assertThat(sender(stub, ApnsTestSupport.newKey()).send(DEVICE_TOKEN, UUID.randomUUID(), "t", "b"))
                    .isEqualTo(ApnsResult.failed(403, "InvalidProviderToken"));
        }
        RealDeviceRegistry registry = new RealDeviceRegistry();
        UUID device = UUID.randomUUID();
        registry.add("shop", "u", device, Platform.IOS, "l");
        registry.setApnsToken(device, "aa11");
        assertThat(registry.clearApnsToken(device, "bb22")).isFalse();
        assertThat(registry.all("shop").get(0).apnsToken()).isEqualTo("aa11");
        assertThat(registry.clearApnsToken(device, "aa11")).isTrue();
    }

    @Test
    void credentialsNeverInToStringOrLogs() throws Exception {
        KeyPair key = ApnsTestSupport.newKey();
        ApnsTestSupport.writeCredentials(dir, key);
        String privateKeyB64 = java.util.Base64.getEncoder().encodeToString(key.getPrivate().getEncoded());
        List<String> logged = new ArrayList<>();
        LoggerContext ctx = (LoggerContext) LogManager.getContext(false);
        Configuration config = ctx.getConfiguration();
        Appender capture = new AbstractAppender("capture-apns", null, null, true, Property.EMPTY_ARRAY) {
            @Override
            public void append(LogEvent e) {
                logged.add(e.getMessage().getFormattedMessage() + (e.getThrown() == null ? "" : e.getThrown().toString()));
            }
        };
        capture.start();
        String loggerName = "io.reliablepush.harness.apns";
        org.apache.logging.log4j.core.config.Configurator.setLevel(loggerName, org.apache.logging.log4j.Level.DEBUG);
        config.getLoggerConfig(loggerName).addAppender(capture, null, null);
        ctx.updateLoggers();
        try (Stub bad = new Stub(s -> new Object[] {500, "{\"reason\":\"" + DEVICE_TOKEN + "\"}"})) {
            ApnsCredentials creds = ApnsCredentials.load(dir).orElseThrow();
            ApnsProviderToken tokens = new ApnsProviderToken(creds, new MutableClock(T0));
            ApnsSender sender = new ApnsSender(creds, tokens, URI.create(bad.url()));
            sender.send(DEVICE_TOKEN, UUID.randomUUID(), "t", "b");
            RealDeviceRegistry registry = new RealDeviceRegistry();
            UUID device = UUID.randomUUID();
            registry.add("shop", "u", device, Platform.IOS, "l");
            registry.setApnsToken(device, DEVICE_TOKEN);
            new ApnsPusher(Optional.of(sender), registry).push("shop", "u", UUID.randomUUID(), "t", "b");

            String jwt = tokens.token();
            List<String> texts = new ArrayList<>(List.of(creds.toString(), tokens.toString(), sender.toString(),
                    registry.all("shop").get(0).toString()));
            texts.addAll(logged);
            for (String t : texts) {
                assertThat(t).doesNotContain(DEVICE_TOKEN, jwt, privateKeyB64, "PRIVATE KEY", ApnsTestSupport.TEAM_ID);
            }
            assertThat(creds.toString()).contains(ApnsTestSupport.KEY_ID, ApnsTestSupport.TOPIC, "sandbox");
            assertThat(logged).isNotEmpty();
        } finally {
            config.getLoggerConfig(loggerName).removeAppender("capture-apns");
            capture.stop();
            ctx.updateLoggers();
        }
    }

    @Test
    void missingCredentialsDisableRealMode() throws Exception {
        assertThat(ApnsCredentials.load(dir)).isEmpty();
        assertThat(ApnsCredentials.load(dir.resolve("absent"))).isEmpty();
        assertThat(new ApnsPusher(dir.toString(), new RealDeviceRegistry()).enabled()).isFalse();

        Files.writeString(dir.resolve("apns.properties"), "keyId=" + ApnsTestSupport.KEY_ID + "\n");
        assertThat(ApnsCredentials.load(dir)).as("no key file, incomplete properties").isEmpty();

        Path broken = Files.createDirectory(dir.resolve("broken"));
        Files.writeString(broken.resolve("AuthKey.p8"), "not a key");
        Files.writeString(broken.resolve("apns.properties"), "keyId=" + ApnsTestSupport.KEY_ID + "\nteamId="
                + ApnsTestSupport.TEAM_ID + "\ntopic=" + ApnsTestSupport.TOPIC + "\nenvironment=sandbox\n");
        ApnsPusher pusher = new ApnsPusher(broken.toString(), new RealDeviceRegistry());
        assertThat(pusher.enabled()).isFalse();
        assertThat(pusher.push("shop", "u", UUID.randomUUID(), "t", "b")).isEmpty();

        Path good = Files.createDirectory(dir.resolve("good"));
        ApnsTestSupport.writeCredentials(good, ApnsTestSupport.newKey());
        assertThat(new ApnsPusher(good.toString(), new RealDeviceRegistry()).enabled()).isTrue();
    }
}
