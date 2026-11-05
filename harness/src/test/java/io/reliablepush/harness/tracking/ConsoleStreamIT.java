package io.reliablepush.harness.tracking;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;

/** HTTP-level limits of the stream endpoint, in its own context so no other test's clients are connected. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "harness.poll-interval=1h")
@ActiveProfiles("test")
class ConsoleStreamIT {

    @LocalServerPort
    int port;

    @Autowired
    ConsoleStream stream;

    final List<Stream<String>> open = new ArrayList<>();

    @AfterEach
    void close() {
        open.forEach(Stream::close);
    }

    HttpResponse<Stream<String>> get() throws Exception {
        return HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/console/api/stream"))
                        .header("Accept", "text/event-stream").GET().build(),
                HttpResponse.BodyHandlers.ofLines());
    }

    @Test
    void the21stConnectionGets503() throws Exception {
        for (int i = 0; i < ConsoleStream.MAX_CLIENTS; i++) {
            HttpResponse<Stream<String>> r = get();
            assertThat(r.statusCode()).isEqualTo(200);
            open.add(r.body());
        }
        await().until(() -> stream.clientCount() == ConsoleStream.MAX_CLIENTS);

        HttpResponse<Stream<String>> rejected = get();
        rejected.body().close();

        assertThat(rejected.statusCode()).isEqualTo(503);
    }
}
