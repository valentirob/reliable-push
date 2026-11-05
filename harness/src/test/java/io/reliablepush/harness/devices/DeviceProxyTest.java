package io.reliablepush.harness.devices;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class DeviceProxyTest {

    private HttpServer target;
    private DeviceProxy proxy;
    private final HttpClient http = HttpClient.newHttpClient();

    @BeforeEach
    void start() throws IOException {
        target = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        target.createContext("/echo", ex -> {
            byte[] in = ex.getRequestBody().readAllBytes();
            byte[] out = ("echo:" + new String(in, StandardCharsets.UTF_8)).getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(201, out.length);
            ex.getResponseBody().write(out);
            ex.close();
        });
        target.start();
        proxy = new DeviceProxy(0, "127.0.0.1", target.getAddress().getPort());
        proxy.start();
    }

    @AfterEach
    void stop() {
        http.close();
        proxy.close();
        target.stop(0);
    }

    private HttpResponse<String> post(String body) throws Exception {
        return http.send(HttpRequest.newBuilder(
                URI.create("http://127.0.0.1:" + proxy.port() + "/echo"))
                .POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void forwardsRequestAndResponse() throws Exception {
        HttpResponse<String> r = post("hello");

        assertThat(r.statusCode()).isEqualTo(201);
        assertThat(r.body()).isEqualTo("echo:hello");
        assertThat(proxy.online()).isTrue();
    }

    @Test
    void offlineRefusesAndClosesOpenConnections() throws Exception {
        try (Socket open = new Socket("127.0.0.1", proxy.port())) {
            open.setSoTimeout(5000);
            open.getOutputStream().write("POST /echo HTTP/1.1\r\nHost: x\r\nContent-Length: 100\r\n\r\npartial"
                    .getBytes(StandardCharsets.UTF_8));
            open.getOutputStream().flush();
            org.awaitility.Awaitility.await().atMost(java.time.Duration.ofSeconds(5)).until(() -> proxy.openConnections() == 1);

            proxy.setOnline(false);

            assertThat(readsEndOfStream(open.getInputStream())).isTrue();
        }
        try (Socket refused = new Socket("127.0.0.1", proxy.port())) {
            refused.setSoTimeout(5000);
            assertThat(readsEndOfStream(refused.getInputStream())).isTrue();
        }
        assertThat(proxy.online()).isFalse();
        org.junit.jupiter.api.Assertions.assertThrows(IOException.class, () -> post("x"));
    }

    @Test
    void backOnlineForwardsAgain() throws Exception {
        proxy.setOnline(false);
        org.junit.jupiter.api.Assertions.assertThrows(IOException.class, () -> post("x"));

        proxy.setOnline(true);

        assertThat(post("again").body()).isEqualTo("echo:again");
    }

    @Test
    void connectionBeyondTheCapIsClosed() throws Exception {
        proxy.close();
        proxy = new DeviceProxy(0, "127.0.0.1", target.getAddress().getPort()).withMaxConnections(1);
        proxy.start();
        try (Socket first = new Socket("127.0.0.1", proxy.port())) {
            org.awaitility.Awaitility.await().atMost(java.time.Duration.ofSeconds(5))
                    .until(() -> proxy.openConnections() == 1);
            try (Socket second = new Socket("127.0.0.1", proxy.port())) {
                second.setSoTimeout(5000);
                assertThat(readsEndOfStream(second.getInputStream())).isTrue();
            }
        }
    }

    @Test
    void unreachableUpstreamIsATransportError() throws Exception {
        int dead;
        try (java.net.ServerSocket s = new java.net.ServerSocket(0)) {
            dead = s.getLocalPort();
        }
        proxy.close();
        proxy = new DeviceProxy(0, "127.0.0.1", dead);
        proxy.start();

        org.junit.jupiter.api.Assertions.assertTimeoutPreemptively(java.time.Duration.ofSeconds(10),
                () -> org.junit.jupiter.api.Assertions.assertThrows(IOException.class, () -> post("x")));
    }

    private static boolean readsEndOfStream(InputStream in) {
        try {
            return in.read() == -1;
        } catch (IOException reset) {
            return true; // a reset is a transport error too
        }
    }
}
