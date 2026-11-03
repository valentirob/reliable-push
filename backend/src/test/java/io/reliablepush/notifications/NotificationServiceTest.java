package io.reliablepush.notifications;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.reliablepush.apps.AppConfig;
import io.reliablepush.apps.AppFixtures;
import io.reliablepush.apps.AppRegistry;
import io.reliablepush.apps.ReliablePushProperties;
import io.reliablepush.shared.time.TimeScale;
import io.reliablepush.shared.web.InvalidRequestException;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Pure logic of {@link NotificationService} (defaults, due time, cursor); persistence is covered by
 * {@code NotificationApiIT} against a real PostgreSQL, never against a mocked database.
 */
class NotificationServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-03T10:00:00.123456Z");
    private static final JsonNode FALLBACK = JsonMapper.builder().build().readTree("{\"sms\":\"hello\"}");
    private static final UUID ID = UUID.fromString("11111111-2222-3333-4444-555555555555");

    private static AppConfig shop(Map<String, Object> extra, MockEnvironment env, ReliablePushProperties[] propsOut) {
        Map<String, Object> p = AppFixtures.minimalApp("shop", "unit-test-key");
        p.putAll(extra);
        ReliablePushProperties props = AppFixtures.bind(p);
        propsOut[0] = props;
        return new AppRegistry(props, env).byId("shop").orElseThrow();
    }

    @Test
    void appliesAppDefaults() {
        MockEnvironment env = AppFixtures.environment();
        ReliablePushProperties[] props = new ReliablePushProperties[1];
        AppConfig app = shop(Map.of(), env, props);
        TimeScale scale = new TimeScale(props[0], env);

        Notification n = NotificationService.newNotification(
                ID, app, new RegisterNotificationRequest("user-1", null, null, null, FALLBACK), NOW, scale);

        assertThat(n.policy()).isEqualTo(FallbackPolicy.NOT_DELIVERED);
        assertThat(n.timeoutMinutes()).isEqualTo(10);
        assertThat(n.createdAt()).isEqualTo(NOW);
        assertThat(n.dueAt()).isEqualTo(NOW.plus(Duration.ofMinutes(10)));
        assertThat(n.state()).isEqualTo(NotificationState.PENDING);
        assertThat(n.appId()).isEqualTo("shop");
        assertThat(n.id()).isEqualTo(ID);
        assertThat(n.externalId()).isNull();
        assertThat(n.fallbackPayload()).isEqualTo("{\"sms\":\"hello\"}");
    }

    @Test
    void appConfiguredDefaultsAndExplicitValuesWin() {
        MockEnvironment env = AppFixtures.environment();
        ReliablePushProperties[] props = new ReliablePushProperties[1];
        AppConfig app = shop(
                Map.of("reliable-push.apps.shop.default-policy", "NOT_READ", "reliable-push.apps.shop.default-timeout-minutes", "30"),
                env,
                props);
        TimeScale scale = new TimeScale(props[0], env);

        Notification defaults = NotificationService.newNotification(
                ID, app, new RegisterNotificationRequest("u", null, null, null, FALLBACK), NOW, scale);
        assertThat(defaults.policy()).isEqualTo(FallbackPolicy.NOT_READ);
        assertThat(defaults.dueAt()).isEqualTo(NOW.plus(Duration.ofMinutes(30)));

        Notification explicit = NotificationService.newNotification(
                ID, app, new RegisterNotificationRequest("u", "ext-1", FallbackPolicy.NOT_DELIVERED, 3, FALLBACK), NOW, scale);
        assertThat(explicit.policy()).isEqualTo(FallbackPolicy.NOT_DELIVERED);
        assertThat(explicit.timeoutMinutes()).isEqualTo(3);
        assertThat(explicit.dueAt()).isEqualTo(NOW.plus(Duration.ofMinutes(3)));
        assertThat(explicit.externalId()).isEqualTo("ext-1");
    }

    @Test
    void fastClockScalesDueAt() {
        MockEnvironment env = AppFixtures.environment();
        env.setActiveProfiles("e2e");
        ReliablePushProperties[] props = new ReliablePushProperties[1];
        AppConfig app = shop(Map.of("reliable-push.testing.minute-duration", "2s"), env, props);
        TimeScale scale = new TimeScale(props[0], env);

        Notification n = NotificationService.newNotification(
                ID, app, new RegisterNotificationRequest("u", null, null, 5, FALLBACK), NOW, scale);

        assertThat(n.timeoutMinutes()).isEqualTo(5);
        assertThat(n.dueAt()).isEqualTo(NOW.plusSeconds(10));
    }

    @Test
    void cursorRoundTripsAndRejectsGarbage() {
        Instant createdAt = Instant.parse("2026-11-03T10:00:00.123456Z");
        String encoded = new PageCursor(createdAt, ID).encode();

        assertThat(encoded).matches("[A-Za-z0-9_-]+");
        PageCursor decoded = PageCursor.decode(encoded);
        assertThat(decoded.createdAt()).isEqualTo(createdAt);
        assertThat(decoded.id()).isEqualTo(ID);

        String outOfRange = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(
                ("+999999999-01-01T00:00:00Z|" + ID).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        String beforeEpoch = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(
                ("1969-12-31T23:59:59Z|" + ID).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        for (String garbage : new String[] {"", "!!!", "bm90LWEtY3Vyc29y", "MjAyNnxub3QtYS11dWlk", outOfRange, beforeEpoch}) {
            assertThatThrownBy(() -> PageCursor.decode(garbage))
                    .isInstanceOf(InvalidRequestException.class)
                    .hasMessageContaining("cursor");
        }
    }

    @Test
    void externalStateNames() {
        assertThat(NotificationState.DISPATCHING.external()).isEqualTo("FALLBACK_PENDING");
        assertThat(NotificationState.PENDING.external()).isEqualTo("PENDING");
        assertThat(NotificationState.fromExternal("FALLBACK_PENDING")).isEqualTo(NotificationState.DISPATCHING);
        assertThat(NotificationState.fromExternal("CANCELLED")).isEqualTo(NotificationState.CANCELLED);
        assertThatThrownBy(() -> NotificationState.fromExternal("DISPATCHING")).isInstanceOf(IllegalArgumentException.class);
        assertThat(NotificationState.PENDING.closed()).isFalse();
        assertThat(NotificationState.DELIVERED.closed()).isFalse();
        assertThat(NotificationState.DISPATCHING.closed()).isFalse();
        assertThat(NotificationState.READ.closed()).isTrue();
        assertThat(NotificationState.CANCELLED.closed()).isTrue();
        assertThat(NotificationState.FALLBACK_TRIGGERED.closed()).isTrue();
        assertThat(NotificationState.FALLBACK_FAILED.closed()).isTrue();
        assertThat(NotificationState.FALLBACK_EXPIRED.closed()).isTrue();
    }
}
