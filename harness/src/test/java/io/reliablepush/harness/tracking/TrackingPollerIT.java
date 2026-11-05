package io.reliablepush.harness.tracking;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.sun.net.httpserver.HttpServer;
import io.reliablepush.client.NotificationState;
import io.reliablepush.harness.config.HarnessApp;
import io.reliablepush.harness.config.HarnessProperties;
import io.reliablepush.harness.sender.FakeSender;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** The poller and the SSE stream against a fake sender API; the Spring-managed poller runs every second. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "harness.poll-interval=1s")
@ActiveProfiles("test")
class TrackingPollerIT {

    static final UUID TID = UUID.fromString("6f1c2b9e-5d1a-4c55-9a38-0c1f3a7d9e11");
    static final AtomicInteger listCalls = new AtomicInteger();
    static final HttpServer backend = startBackend();
    static volatile String state = "PENDING";
    static volatile boolean fail = false;

    static HttpServer startBackend() {
        try {
            HttpServer s = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            s.createContext("/v1/notifications", ex -> {
                listCalls.incrementAndGet();
                int status = fail ? 503 : 200;
                String body = fail ? "{\"title\":\"down\",\"status\":503}"
                        : "{\"items\":[{\"trackingId\":\"" + TID + "\",\"state\":\"" + state
                                + "\",\"userId\":\"u1\",\"policy\":\"NOT_DELIVERED\",\"timeoutMinutes\":10,"
                                + "\"createdAt\":\"2026-11-04T10:00:00Z\",\"dueAt\":\"2026-11-04T10:10:00Z\"}],"
                                + "\"nextCursor\":null}";
                byte[] b = body.getBytes(StandardCharsets.UTF_8);
                ex.getResponseHeaders().add("Content-Type", fail ? "application/problem+json" : "application/json");
                ex.sendResponseHeaders(status, b.length);
                ex.getResponseBody().write(b);
                ex.close();
            });
            s.start();
            return s;
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("harness.backend-url", () -> "http://127.0.0.1:" + backend.getAddress().getPort());
    }

    @AfterAll
    static void stopBackend() {
        backend.stop(0);
    }

    @LocalServerPort
    int port;

    @Autowired
    ConsoleStream stream;

    @Autowired
    Timeline timeline;

    final List<Stream<String>> open = new CopyOnWriteArrayList<>();

    @BeforeEach
    void reset() {
        state = "PENDING";
        fail = false;
    }

    @AfterEach
    void closeClients() {
        open.forEach(Stream::close);
        open.clear();
    }

    HarnessProperties props() {
        return new HarnessProperties(URI.create("http://127.0.0.1:" + backend.getAddress().getPort()), Path.of("target"),
                Map.of("shop", new HarnessApp("sender-key", "g", "h", "https://shop.test.invalid")));
    }

    TrackingPoller newPoller(Timeline timeline) {
        return new TrackingPoller(props(), new FakeSender(props(), (a, b, c, d) -> { }), timeline, new ConsoleStream());
    }

    Stream<String> connect() throws Exception {
        HttpResponse<Stream<String>> r = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/console/api/stream"))
                        .header("Accept", "text/event-stream").GET().build(),
                HttpResponse.BodyHandlers.ofLines());
        assertThat(r.statusCode()).isEqualTo(200);
        open.add(r.body());
        return r.body();
    }

    @Test
    void recordsStateChangesFromBackend() {
        Timeline timeline = new Timeline();
        TrackingPoller poller = newPoller(timeline);

        poller.poll();
        state = "DELIVERED";
        poller.poll();

        assertThat(timeline.entries(TID)).extracting(TimelineEntry::kind).containsExactly("registered", "state_changed");
        assertThat(timeline.entries(TID).get(1).details()).containsEntry("from", "PENDING").containsEntry("to", "DELIVERED");
        assertThat(timeline.current(TID)).get().extracting(n -> n.state()).isEqualTo(NotificationState.DELIVERED);
    }

    @Test
    void rebuildsAfterRestart() {
        state = "DELIVERED";
        Timeline fresh = new Timeline(); // a restarted harness: empty memory

        newPoller(fresh).poll();

        assertThat(fresh.recent("shop", 10)).extracting(n -> n.trackingId()).containsExactly(TID);
        assertThat(fresh.entries(TID)).extracting(TimelineEntry::kind).containsExactly("registered");
        assertThat(fresh.entries(TID).get(0).details()).containsEntry("source", "backend");
    }

    @Test
    void backendErrorKeepsPreviousStateAndNeverThrows() {
        Timeline timeline = new Timeline();
        TrackingPoller poller = newPoller(timeline);
        poller.poll();

        fail = true;
        poller.poll();
        poller.poll();

        assertThat(timeline.current(TID)).get().extracting(n -> n.state()).isEqualTo(NotificationState.PENDING);
        assertThat(timeline.entries(TID)).hasSize(1);
    }

    @Test
    void oneBackendCallPerSecondWithThreeStreamClients() throws Exception {
        int baseline = stream.clientCount();
        for (int i = 0; i < 3; i++) {
            connect();
        }
        await().until(() -> stream.clientCount() >= baseline + 3);

        int before = listCalls.get();
        Thread.sleep(1500);

        assertThat(listCalls.get() - before).isBetween(1, 2);
    }

    @Test
    void streamDeliversTimelineEventsToEveryClient() throws Exception {
        int clients = 2;
        List<BlockingQueue<String>> inboxes = new ArrayList<>();
        for (int i = 0; i < clients; i++) {
            Stream<String> lines = connect();
            BlockingQueue<String> inbox = new LinkedBlockingQueue<>();
            inboxes.add(inbox);
            Thread.ofVirtual().start(() -> {
                try {
                    lines.forEach(inbox::add);
                } catch (RuntimeException closed) {
                    // stream closed by the test
                }
            });
        }
        await().until(() -> stream.clientCount() >= clients);
        // the Spring poller has seen PENDING before the notification moves on
        await().atMost(Duration.ofSeconds(8)).until(() -> timeline.current(TID).isPresent()
                && timeline.current(TID).get().state() == NotificationState.PENDING);
        state = "READ";

        for (BlockingQueue<String> inbox : inboxes) {
            String hit = null;
            long deadline = System.nanoTime() + Duration.ofSeconds(8).toNanos();
            while (hit == null) {
                long left = deadline - System.nanoTime();
                String line = left > 0 ? inbox.poll(left, TimeUnit.NANOSECONDS) : null;
                if (line == null) {
                    break;
                }
                if (line.startsWith("data:") && line.contains("\"to\":\"READ\"")) {
                    hit = line;
                }
            }
            assertThat(hit).as("timeline event for READ").isNotNull()
                    .contains("\"type\":\"timeline\"").contains("\"trackingId\":\"" + TID + "\"")
                    .contains("state_changed").doesNotContain("sender-key");
        }
    }
}
