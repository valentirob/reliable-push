package io.reliablepush.harness.apns;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.spec.ECGenParameterSpec;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;

/** A test EC P-256 key (generated here, never a real one) and a JDK HttpServer stub for the APNs endpoint. */
final class ApnsTestSupport {

    static final String KEY_ID = "ABC123DEFG";
    static final String TEAM_ID = "TEAM123456";
    static final String TOPIC = "io.reliablepush.sample";

    private ApnsTestSupport() {
    }

    static KeyPair newKey() throws Exception {
        KeyPairGenerator g = KeyPairGenerator.getInstance("EC");
        g.initialize(new ECGenParameterSpec("secp256r1"));
        return g.generateKeyPair();
    }

    static String pem(KeyPair key) {
        String b64 = Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII))
                .encodeToString(key.getPrivate().getEncoded());
        return "-----BEGIN PRIVATE KEY-----\n" + b64 + "\n-----END PRIVATE KEY-----\n";
    }

    /** AuthKey.p8 and apns.properties in {@code dir}. */
    static void writeCredentials(Path dir, KeyPair key) throws IOException {
        Files.writeString(dir.resolve("AuthKey.p8"), pem(key));
        Files.writeString(dir.resolve("apns.properties"),
                "keyId=" + KEY_ID + "\nteamId=" + TEAM_ID + "\ntopic=" + TOPIC + "\nenvironment=sandbox\n");
    }

    /** A request the stub saw; header names are lower case. */
    record Seen(String method, String path, Map<String, String> headers, String body) {}

    /** Stub server: every request is recorded, the reply comes from the handler (status, body). */
    static final class Stub implements AutoCloseable {
        final HttpServer server;
        final List<Seen> seen = new CopyOnWriteArrayList<>();

        Stub(Function<Seen, Object[]> reply) throws IOException {
            server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
            server.createContext("/", ex -> handle(ex, reply));
            server.start();
        }

        private void handle(HttpExchange ex, Function<Seen, Object[]> reply) throws IOException {
            String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            Map<String, String> headers = new java.util.HashMap<>();
            ex.getRequestHeaders().forEach((k, v) -> headers.put(k.toLowerCase(java.util.Locale.ROOT), v.get(0)));
            Seen s = new Seen(ex.getRequestMethod(), ex.getRequestURI().getPath(), headers, body);
            seen.add(s);
            Object[] r = reply.apply(s);
            byte[] out = ((String) r[1]).getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders((Integer) r[0], out.length);
            ex.getResponseBody().write(out);
            ex.close();
        }

        String url() {
            return "http://127.0.0.1:" + server.getAddress().getPort();
        }

        @Override
        public void close() {
            server.stop(0);
        }
    }

    /** A clock the test moves. */
    static final class MutableClock extends Clock {
        private Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        void advanceSeconds(long s) {
            now = now.plusSeconds(s);
        }

        @Override
        public java.time.ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
