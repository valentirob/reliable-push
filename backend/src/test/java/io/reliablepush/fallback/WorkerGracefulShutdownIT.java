package io.reliablepush.fallback;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import io.reliablepush.ReliablePushApplication;
import io.reliablepush.notifications.FallbackPolicy;
import io.reliablepush.notifications.Notification;
import io.reliablepush.notifications.NotificationRepository;
import io.reliablepush.notifications.NotificationState;
import io.reliablepush.shared.logging.EventCapture;
import io.reliablepush.support.TestKeys;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.core.LogEvent;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * A worker that is shut down mid-dispatch (what SIGTERM does in Kubernetes): the call in flight finishes and its outcome
 * is written before the connection pool closes, nothing more is claimed, no ERROR is logged. The application runs in
 * a context this test closes itself, against its own PostgreSQL, so no other IT's rows or workers are involved. The
 * worker polls every 50 ms, claims one row at a time ({@code batch-size} 1, so {@code max-concurrency} 1) and uses the
 * real clock; the receiver answers after a fixed 2 s delay. With one slot, a tick that dispatched a row waits for that
 * slot and would then claim again: only the stop flag ends it, so {@link #noClaimAfterClose} fails without the flag.
 */
class WorkerGracefulShutdownIT {

    private static final String APP = "shop";
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");

    private WireMockServer receiver;
    private ConfigurableApplicationContext context;
    private FallbackScheduler scheduler;
    private NotificationRepository notifications;
    private final List<EventCapture> captures = new ArrayList<>();

    @BeforeAll
    static void startDatabase() {
        POSTGRES.start();
    }

    @AfterAll
    static void stopDatabase() {
        POSTGRES.stop();
    }

    @BeforeEach
    void startWorker() {
        receiver = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        receiver.start();
        receiver.stubFor(post("/hook").willReturn(aResponse().withStatus(200).withFixedDelay(2_000)));
        var keys = TestKeys.newEcPemPair();
        context = new SpringApplicationBuilder(ReliablePushApplication.class)
                .profiles("test", "worker")
                // command-line arguments: they win over application-test.yml (default properties would not)
                .run(
                        "--server.port=0",
                        "--spring.datasource.url=" + POSTGRES.getJdbcUrl(),
                        "--spring.datasource.username=" + POSTGRES.getUsername(),
                        "--spring.datasource.password=" + POSTGRES.getPassword(),
                        "--RP_TEST_DEVICE_KEY_K1=" + keys.privateKeyPem(),
                        "--RP_TEST_DEVICE_PUB_K1=" + keys.publicKeyPem(),
                        "--reliable-push.worker.poll-interval=50ms",
                        "--reliable-push.worker.batch-size=1",
                        "--reliable-push.apps." + APP + ".fallback.url=http://localhost:" + receiver.port() + "/hook");
        scheduler = context.getBean(FallbackScheduler.class);
        notifications = context.getBean(NotificationRepository.class);
        // after startup: the logging system has been configured by then and keeps the captures
        for (String logger : List.of("io.reliablepush", "org.springframework", "com.zaxxer.hikari")) {
            captures.add(new EventCapture(logger, Level.ERROR));
        }
    }

    @AfterEach
    void cleanUp() {
        captures.forEach(EventCapture::close);
        captures.clear();
        if (context != null) {
            context.close();
        }
        receiver.stop();
        // the next test's worker must not find this test's rows (noClaimAfterClose leaves one PENDING on purpose)
        try (Connection c = POSTGRES.createConnection("");
                PreparedStatement ps = c.prepareStatement("delete from notification")) {
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    private UUID insertDue() {
        UUID id = UUID.randomUUID();
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        notifications.insert(new Notification(id, APP, "user-" + id, null, FallbackPolicy.NOT_DELIVERED, 10,
                "{\"sms\":\"x\"}", NotificationState.PENDING, now.minusSeconds(60), now.minusSeconds(1), null, null,
                null));
        return id;
    }

    /** Like {@link #insertDue()}, through a connection of its own: the application's pool may already be closing. */
    private static UUID insertDueDirect() throws SQLException {
        UUID id = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS);
        try (Connection c = POSTGRES.createConnection("");
                PreparedStatement ps = c.prepareStatement(
                        "insert into notification (id, app_id, user_id, policy, timeout_minutes, fallback_payload, state,"
                                + " created_at, due_at) values (?, ?, ?, 'NOT_DELIVERED', 10, cast(? as jsonb),"
                                + " 'PENDING', ?, ?)")) {
            ps.setObject(1, id);
            ps.setString(2, APP);
            ps.setString(3, "user-" + id);
            ps.setString(4, "{\"sms\":\"x\"}");
            ps.setObject(5, now.minusSeconds(60));
            ps.setObject(6, now.minusSeconds(1));
            ps.executeUpdate();
        }
        return id;
    }

    /** Read through a connection of its own: the application's pool is closed (or closing) when this is used. */
    private String stateAndClosed(UUID id) throws SQLException {
        try (Connection c = POSTGRES.createConnection("");
                PreparedStatement ps = c.prepareStatement("select state, closed_at from notification where id = ?")) {
            ps.setObject(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                assertThat(rs.next()).isTrue();
                return rs.getString("state") + (rs.getObject("closed_at") != null ? "/closed" : "/open");
            }
        }
    }

    private static void awaitTrue(String what, BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("timed out waiting for " + what);
            }
            Thread.sleep(20);
        }
    }

    private boolean isDispatching(UUID id) {
        try {
            return stateAndClosed(id).startsWith("DISPATCHING");
        } catch (SQLException e) {
            return false;
        }
    }

    private void assertNoErrorLogged() {
        List<String> errors = new ArrayList<>();
        for (EventCapture capture : captures) {
            for (LogEvent event : capture.events()) {
                errors.add(event.getLoggerName() + ": " + event.getMessage().getFormattedMessage());
            }
        }
        assertThat(errors).as("ERROR lines logged during shutdown").isEmpty();
    }

    @Test
    void inFlightRowsCompleteOnContextClose() throws Exception {
        UUID id = insertDue();
        awaitTrue("row DISPATCHING", () -> isDispatching(id));

        context.close();

        assertNoErrorLogged();
        assertThat(stateAndClosed(id)).isEqualTo("FALLBACK_TRIGGERED/closed");
        assertThat(receiver.findAll(postRequestedFor(urlEqualTo("/hook")))).hasSize(1);
        assertNoErrorLogged();
    }

    @Test
    void noClaimAfterClose() throws Exception {
        UUID inFlight = insertDue();
        awaitTrue("row DISPATCHING", () -> isDispatching(inFlight));

        CompletableFuture<Void> closing = CompletableFuture.runAsync(context::close);
        awaitTrue("stop requested", scheduler::isStopRequested);
        // the flag is set while the tick still runs (its row is in flight), not after the tick ended
        assertThat(stateAndClosed(inFlight)).startsWith("DISPATCHING");
        // falls due only now, while the tick waits for its one slot: once the in-flight call (2 s) frees the slot, a
        // worker without the stop flag would claim this row in the same tick
        UUID late = insertDueDirect();
        closing.get(60, TimeUnit.SECONDS);

        assertThat(stateAndClosed(inFlight)).isEqualTo("FALLBACK_TRIGGERED/closed");
        assertThat(stateAndClosed(late)).isEqualTo("PENDING/open");
        assertThat(receiver.findAll(postRequestedFor(urlEqualTo("/hook")))).hasSize(1);
        assertNoErrorLogged();
    }
}
