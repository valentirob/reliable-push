package io.reliablepush.harness.fcm;

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
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;

/** A test RSA key (generated here, never a real one) and JDK HttpServer stubs for the Google endpoints. */
final class FcmTestSupport {

    static final String CLIENT_EMAIL = "harness-test@example-project.iam.gserviceaccount.com";
    static final String PROJECT = "example-project";
    static final String TOKEN_URI = "https://oauth2.googleapis.com/token";

    private FcmTestSupport() {
    }

    static KeyPair newKey() throws Exception {
        KeyPairGenerator g = KeyPairGenerator.getInstance("RSA");
        g.initialize(2048);
        return g.generateKeyPair();
    }

    static String pem(KeyPair key) {
        String b64 = Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII))
                .encodeToString(key.getPrivate().getEncoded());
        return "-----BEGIN PRIVATE KEY-----\n" + b64 + "\n-----END PRIVATE KEY-----\n";
    }

    static Path writeCredentials(Path dir, KeyPair key) throws IOException {
        String json = "{\"type\":\"service_account\",\"project_id\":\"" + PROJECT + "\",\"client_email\":\""
                + CLIENT_EMAIL + "\",\"token_uri\":\"" + TOKEN_URI + "\",\"private_key\":\""
                + pem(key).replace("\n", "\\n") + "\"}";
        Path f = dir.resolve("service-account.json");
        Files.writeString(f, json);
        return f;
    }

    /** A request the stub saw. */
    record Seen(String method, String path, String authorization, String contentType, String body) {}

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
            Seen s = new Seen(ex.getRequestMethod(), ex.getRequestURI().getPath(),
                    ex.getRequestHeaders().getFirst("Authorization"), ex.getRequestHeaders().getFirst("Content-Type"),
                    body);
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
