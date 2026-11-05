package io.reliablepush.loadtest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class DeviceProvisionerTest {

    private HttpServer server;
    private final List<String> bodies = new CopyOnWriteArrayList<>();
    private final AtomicInteger counter = new AtomicInteger();
    private volatile int failOnCall = -1;

    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/v1/devices", ex -> {
            bodies.add(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            int n = counter.incrementAndGet();
            boolean fail = n == failOnCall;
            byte[] out = (fail ? "{\"error\":\"leaked-token-xyz\"}"
                    : "{\"deviceId\":\"d" + n + "\",\"deviceToken\":\"tok-" + n + "\",\"expiresAt\":\"x\"}")
                    .getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(fail ? 429 : 201, out.length);
            ex.getResponseBody().write(out);
            ex.close();
        });
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private LoadConfig config(int devices) {
        return new LoadConfig(URI.create("http://127.0.0.1:" + server.getAddress().getPort()), "key", "grant-secret",
                "iss", "shop", devices, 1, Duration.ofSeconds(1), Duration.ofSeconds(1), 1, 1);
    }

    @Test
    void registersDevicesAndReturnsFeederRows() {
        List<Map<String, Object>> rows = new DeviceProvisioner().provision(config(6));
        assertThat(rows).hasSize(6);
        assertThat(rows.stream().map(r -> (String) r.get("userId")).toList())
                .containsExactly("load-user-0", "load-user-1", "load-user-2", "load-user-3", "load-user-4", "load-user-5");
        assertThat(rows).allSatisfy(r -> assertThat((String) r.get("deviceToken")).startsWith("tok-"));
        assertThat(bodies).hasSize(6);
        assertThat(bodies.stream().filter(b -> b.contains("\"platform\":\"ANDROID\"")).count()).isEqualTo(3);
        assertThat(bodies.stream().filter(b -> b.contains("\"platform\":\"IOS\"")).count()).isEqualTo(3);
        assertThat(bodies).allSatisfy(b -> assertThat(b).contains("\"appId\":\"shop\"").contains("\"idToken\":\""));
    }

    @Test
    void non201AbortsWithStatusAndIndexButNoBody() {
        failOnCall = 3;
        assertThatThrownBy(() -> new DeviceProvisioner().provision(config(5)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("429")
                .hasMessageContaining("index")
                .message().doesNotContain("leaked-token-xyz");
    }
}
