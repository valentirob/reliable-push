package io.reliablepush.shared.metrics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.jayway.jsonpath.JsonPath;
import io.reliablepush.devices.DeviceClaims;
import io.reliablepush.devices.DeviceTokenService;
import io.micrometer.core.instrument.MeterRegistry;
import io.reliablepush.fallback.FallbackScheduler;
import io.reliablepush.notifications.DueBacklogJob;
import io.reliablepush.notifications.FallbackPolicy;
import io.reliablepush.notifications.Notification;
import io.reliablepush.notifications.NotificationRepository;
import io.reliablepush.notifications.NotificationState;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import io.reliablepush.support.PostgresTestBase;

/**
 * The spec's Prometheus metrics (9.2) end to end: the counters are driven through the real code paths (sender API,
 * device API, the fallback worker with the production webhook client against WireMock) and read back from
 * {@code /actuator/prometheus}. Both roles are on; the scheduled poll is pushed out to one day so only this test
 * ticks. The database and the JVM are shared with other ITs, so every assertion is a delta.
 */
@ActiveProfiles({"test", "api", "worker", "e2e"})
@TestPropertySource(properties = {
    "reliable-push.testing.minute-duration=1s",
    "reliable-push.worker.poll-interval=1d",
    // the backlog job runs once at startup, then not again during the class
    "reliable-push.worker.backlog-refresh-interval=1d"
})
class MetricsIT extends PostgresTestBase {

    private static final String SHOP_KEY = "test-sender-key-shop";
    private static final WireMockServer RECEIVER = new WireMockServer(WireMockConfiguration.options().dynamicPort());

    static {
        RECEIVER.start();
        RECEIVER.stubFor(WireMock.post("/hook").willReturn(WireMock.aResponse().withStatus(200)));
    }

    @DynamicPropertySource
    static void hookUrl(DynamicPropertyRegistry registry) {
        registry.add("reliable-push.apps.shop.fallback.url", () -> "http://localhost:" + RECEIVER.port() + "/hook");
    }

    @AfterAll
    static void stopReceiver() {
        RECEIVER.stop();
    }

    @Autowired
    JdbcClient jdbc;

    @Autowired
    DeviceTokenService tokens;

    @Autowired
    FallbackScheduler scheduler;

    @Autowired
    NotificationRepository notifications;

    @Autowired
    DueBacklogJob backlogJob;

    @Autowired
    MeterRegistry meters;

    private String scrape() {
        return http.get().uri("/actuator/prometheus").retrieve().body(String.class);
    }

    /** Value of one exposition line, e.g. {@code rp_events_total{late="false",type="DELIVERED"}}; fails when absent. */
    private static double value(String body, String series) {
        Matcher m = Pattern.compile("^" + Pattern.quote(series) + " (\\S+)$", Pattern.MULTILINE).matcher(body);
        assertThat(m.find()).as("series %s in the scrape", series).isTrue();
        return Double.parseDouble(m.group(1));
    }

    private ResponseEntity<String> register(String userId, String externalId, int timeoutMinutes) {
        // one tracking id per external id, so a replay of the same external id is a replay of the same registration
        String trackingId = UUID.nameUUIDFromBytes(externalId.getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString();
        String body = "{\"trackingId\":\"" + trackingId + "\",\"userId\":\"" + userId + "\",\"externalId\":\""
                + externalId + "\",\"policy\":\"NOT_DELIVERED\","
                + "\"timeoutMinutes\":" + timeoutMinutes + ",\"fallback\":{\"sms\":\"hi\"}}";
        return http.post().uri("/v1/notifications")
                .contentType(MediaType.APPLICATION_JSON)
                .header("X-Api-Key", SHOP_KEY)
                .body(body)
                .retrieve()
                .onStatus(status -> true, (request, r) -> {})
                .toEntity(String.class);
    }

    private int postEvent(String bearer, String trackingId, String type) {
        String body = "{\"events\":[{\"trackingId\":\"" + trackingId + "\",\"type\":\"" + type
                + "\",\"occurredAt\":\"" + Instant.now() + "\"}]}";
        return http.post().uri("/v1/events")
                .contentType(MediaType.APPLICATION_JSON)
                .header("Authorization", "Bearer " + bearer)
                .body(body)
                .retrieve()
                .onStatus(status -> true, (request, r) -> {})
                .toEntity(String.class)
                .getStatusCode()
                .value();
    }

    private String state(String trackingId) {
        return jdbc.sql("select state from notification where id = ?").param(UUID.fromString(trackingId))
                .query(String.class).single();
    }

    @Test
    void exposesSpecMetrics() {
        String user = "user-" + UUID.randomUUID();
        UUID deviceId = UUID.randomUUID();
        jdbc.sql("""
                insert into device (id, app_id, user_id, platform, token_version, created_at, last_seen_at, token_expires_at)
                values (?, 'shop', ?, 'ANDROID', 0, now(), now(), now() + interval '90 days')
                """).params(deviceId, user).update();
        Instant now = Instant.now();
        String bearer = tokens.issue(new DeviceClaims("shop", user, deviceId, 0, now, now.plus(Duration.ofDays(1)))).token();

        // every series is there from the start, before anything happened
        String before = scrape();
        assertThat(before)
                .contains("rp_notifications_registered_total{outcome=\"pending\"}")
                .contains("rp_notifications_registered_total{outcome=\"no_device_registered\"}")
                .contains("rp_notification_registration_seconds_bucket{le=\"1.0\"}")
                .contains("rp_events_total{late=\"false\",type=\"DELIVERED\"}")
                .contains("rp_events_total{late=\"true\",type=\"DELIVERED\"}")
                .contains("rp_events_total{late=\"false\",type=\"READ\"}")
                .contains("rp_events_total{late=\"true\",type=\"READ\"}")
                .contains("rp_fallback_total{outcome=\"triggered\"}")
                .contains("rp_fallback_total{outcome=\"failed\"}")
                .contains("rp_fallback_total{outcome=\"expired\"}")
                .contains("rp_webhook_latency_seconds_count")
                .contains("rp_due_backlog")
                // the circuit breaker: transitions per configured app (bounded tag set), failed checks (worker)
                .contains("rp_fallback_circuit_transitions_total{app=\"shop\",transition=\"OPENED\"}")
                .contains("rp_fallback_circuit_transitions_total{app=\"shop\",transition=\"CLOSED\"}")
                .contains("rp_fallback_circuit_transitions_total{app=\"shop\",transition=\"FORCED\"}")
                .contains("rp_fallback_circuit_transitions_total{app=\"shop\",transition=\"FORCE_ENDED\"}")
                .contains("rp_fallback_circuit_check_failed_total");
        double registered = value(before, "rp_notifications_registered_total{outcome=\"pending\"}");
        double timed = value(before, "rp_notification_registration_seconds_count");
        double deliveredOnTime = value(before, "rp_events_total{late=\"false\",type=\"DELIVERED\"}");
        double deliveredLate = value(before, "rp_events_total{late=\"true\",type=\"DELIVERED\"}");
        double triggered = value(before, "rp_fallback_total{outcome=\"triggered\"}");
        double latency = value(before, "rp_webhook_latency_seconds_count");
        int hooksBefore = RECEIVER.getAllServeEvents().size();

        // register: one real insert (A), its replay (duplicate: not counted), and B, due after one fast "minute"
        ResponseEntity<String> a = register(user, "metrics-a-" + user, 60);
        assertThat(a.getStatusCode().value()).isEqualTo(201);
        assertThat(register(user, "metrics-a-" + user, 60).getStatusCode().value()).isIn(200, 201);
        String trackingA = JsonPath.read(a.getBody(), "$.trackingId");
        ResponseEntity<String> b = register(user, "metrics-b-" + user, 1);
        assertThat(b.getStatusCode().value()).isEqualTo(201);
        String trackingB = JsonPath.read(b.getBody(), "$.trackingId");

        // events: A delivered once (a replay is a duplicate: not counted); an unknown tracking id is not counted
        assertThat(postEvent(bearer, trackingA, "DELIVERED")).isEqualTo(200);
        assertThat(postEvent(bearer, trackingA, "DELIVERED")).isEqualTo(200);
        assertThat(postEvent(bearer, UUID.randomUUID().toString(), "DELIVERED")).isEqualTo(200);

        // fallback: B is never delivered, so the worker fires its webhook (production client, WireMock)
        await().atMost(Duration.ofSeconds(20)).pollInterval(Duration.ofMillis(300)).untilAsserted(() -> {
            scheduler.tick();
            assertThat(state(trackingB)).isEqualTo("FALLBACK_TRIGGERED");
        });
        // B delivered after its fallback fired: stored, but late
        assertThat(postEvent(bearer, trackingB, "DELIVERED")).isEqualTo(200);

        String after = scrape();
        assertThat(value(after, "rp_notifications_registered_total{outcome=\"pending\"}") - registered).isEqualTo(2.0);
        assertThat(value(after, "rp_notification_registration_seconds_count") - timed).isEqualTo(3.0);
        assertThat(value(after, "rp_events_total{late=\"false\",type=\"DELIVERED\"}") - deliveredOnTime).isEqualTo(1.0);
        assertThat(value(after, "rp_events_total{late=\"true\",type=\"DELIVERED\"}") - deliveredLate).isEqualTo(1.0);
        // rows of other ITs may be claimed by the same ticks: the counter equals what the webhook actually received
        double hooks = RECEIVER.getAllServeEvents().size() - hooksBefore;
        assertThat(hooks).isGreaterThanOrEqualTo(1.0);
        assertThat(value(after, "rp_fallback_total{outcome=\"triggered\"}") - triggered).isEqualTo(hooks);
        assertThat(value(after, "rp_webhook_latency_seconds_count") - latency).isGreaterThanOrEqualTo(hooks);
        assertThat(after).contains("rp_due_backlog");
    }

    @Test
    void dueBacklogCountsExactlyTheClaimableRows() {
        // a year-2200 "now": nothing else in the shared database is relevant to the difference
        Instant t = Instant.parse("2200-01-01T00:00:00Z");
        long baseline = notifications.countDue(t);
        List<UUID> ids = List.of(
                row("PENDING", t.minusSeconds(5), null),     // counts: due
                row("DELIVERED", t.minusSeconds(5), null),   // counts: delivered but its due_at passed (NOT_READ policy)
                row("DISPATCHING", t.plusSeconds(60), t.minusSeconds(1)), // counts: retry is due
                row("PENDING", t.plusSeconds(1), null),      // not yet due
                row("DISPATCHING", t.minusSeconds(60), t.plusSeconds(1)), // retry not yet due (due_at is not looked at)
                row("CANCELLED", null, null));               // closed rows have no due_at
        try {
            assertThat(notifications.countDue(t) - baseline).isEqualTo(3);
        } finally {
            jdbc.sql("delete from notification where id in (:ids)").param("ids", ids).update();
        }
    }

    @Test
    void dueBacklogGaugeIsRefreshedByTheJobAndScrapeNeverQueries() {
        UUID id = row("PENDING", Instant.now().minusSeconds(30), null);
        try {
            long due = backlogJob.refresh();

            assertThat(due).isGreaterThanOrEqualTo(1);
            assertThat(value(scrape(), "rp_due_backlog")).isEqualTo((double) due);
            assertThat(meters.get("rp.due.backlog").gauge().value()).isEqualTo((double) due);
        } finally {
            jdbc.sql("delete from notification where id = ?").param(id).update();
        }
    }

    private UUID row(String state, Instant dueAt, Instant nextAttemptAt) {
        UUID id = UUID.randomUUID();
        Notification n = new Notification(id, "shop", "user-" + id, null, FallbackPolicy.NOT_READ, 10,
                "{\"sms\":\"x\"}", NotificationState.PENDING, dueAt == null ? Instant.now() : dueAt.minusSeconds(600),
                dueAt, null, null, null);
        notifications.insert(n);
        boolean closed = state.equals("CANCELLED");
        jdbc.sql("update notification set state = ?, next_attempt_at = ?, closed_at = ? where id = ?")
                .params(state, nextAttemptAt == null ? null : nextAttemptAt.atOffset(java.time.ZoneOffset.UTC),
                        closed ? java.time.OffsetDateTime.now() : null, id)
                .update();
        return id;
    }
}
