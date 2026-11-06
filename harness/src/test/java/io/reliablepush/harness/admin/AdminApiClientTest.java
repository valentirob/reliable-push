package io.reliablepush.harness.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import tools.jackson.databind.JsonNode;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class AdminApiClientTest {

    private static final String KEY = "test-admin-key-5d2f81c0a7";
    private static final UUID KNOWN = UUID.fromString("11111111-2222-3333-4444-555555555555");

    private HttpServer server;
    private final List<String> requests = new CopyOnWriteArrayList<>();
    private AdminApiClient client;

    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/admin/", exchange -> {
            requests.add(exchange.getRequestURI().getRawPath() + "?" + exchange.getRequestURI().getRawQuery()
                    + " key=" + exchange.getRequestHeaders().getFirst("X-Admin-Key"));
            String path = exchange.getRequestURI().getPath();
            int status = 200;
            String body;
            if (path.equals("/v1/admin/overview")) {
                body = "{\"items\":[{\"appId\":\"shop\",\"window\":\"1h\",\"registered\":2,\"delivered\":1}]}";
            } else if (path.equals("/v1/admin/notifications")) {
                body = "{\"items\":[{\"trackingId\":\"" + KNOWN + "\"}],\"nextCursor\":null}";
            } else if (path.equals("/v1/admin/notifications/" + KNOWN)) {
                body = "{\"notification\":{\"trackingId\":\"" + KNOWN + "\"},\"events\":[],\"attempts\":[]}";
            } else {
                status = 404;
                body = "{\"code\":\"NOT_FOUND\"}";
            }
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        client = new AdminApiClient(URI.create("http://127.0.0.1:" + server.getAddress().getPort()), KEY);
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    @Test
    void overviewSendsTheKeyAndParsesTheItems() {
        JsonNode overview = client.overview("shop", "1h");

        assertThat(overview.at("/items/0/registered").asInt()).isEqualTo(2);
        assertThat(requests).containsExactly("/v1/admin/overview?app=shop&window=1h key=" + KEY);
    }

    @Test
    void searchEncodesTheUserId() {
        JsonNode result = client.search("shop", "a b/c");

        assertThat(result.at("/items/0/trackingId").asText()).isEqualTo(KNOWN.toString());
        assertThat(requests).hasSize(1);
        assertThat(requests.get(0)).contains("userId=a%20b%2Fc").endsWith("key=" + KEY);
    }

    @Test
    void detailParsesAndUnknownIsEmpty() {
        assertThat(client.detail(KNOWN)).get().satisfies(d ->
                assertThat(d.at("/notification/trackingId").asText()).isEqualTo(KNOWN.toString()));
        assertThat(client.detail(UUID.randomUUID())).isEmpty();
    }

    @Test
    void otherErrorsThrowWithoutTheKey() {
        AdminApiClient wrong = new AdminApiClient(URI.create("http://127.0.0.1:" + server.getAddress().getPort()), KEY);
        server.removeContext("/v1/admin/");
        assertThatThrownBy(() -> wrong.overview("shop", "1h")).isInstanceOf(IllegalStateException.class)
                .hasMessageNotContaining(KEY);
    }

    @Test
    void blankKeyFailsOnUse() {
        AdminApiClient none = new AdminApiClient(URI.create("http://127.0.0.1:1"), " ");
        assertThatThrownBy(() -> none.overview("shop", "1h")).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("HARNESS_ADMINKEY");
    }
}
