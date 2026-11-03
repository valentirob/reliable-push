package io.reliablepush;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.reliablepush.support.PostgresTestBase;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;

@ActiveProfiles("test")
class SchemaIT extends PostgresTestBase {

    @Autowired
    JdbcClient jdbc;

    private int insertNotification(String appId, String externalId, String state, String policy) {
        return jdbc.sql("""
                insert into notification
                  (id, app_id, user_id, external_id, policy, timeout_minutes, fallback_payload, state, created_at)
                values (?, ?, 'u1', ?, ?, 10, '{}'::jsonb, ?, now())
                """)
                .params(UUID.randomUUID(), appId, externalId, policy, state)
                .update();
    }

    private String indexDef(String name) {
        return jdbc.sql("select indexdef from pg_indexes where schemaname = current_schema() and indexname = ?")
                .param(name)
                .query(String.class)
                .single();
    }

    @Test
    void tablesExist() {
        List<String> tables = jdbc.sql("select table_name from information_schema.tables where table_schema = current_schema()")
                .query(String.class)
                .list();

        assertThat(tables).contains("notification", "notification_event", "device");
    }

    @Test
    void dueIndexIsPartial() {
        assertThat(indexDef("notification_due")).contains("WHERE (due_at IS NOT NULL)");
    }

    @Test
    void retryIndexCoversDispatchingRows() {
        String def = indexDef("notification_retry");

        assertThat(def).contains("next_attempt_at").contains("DISPATCHING");
    }

    @Test
    void leaseUntilOnlyOnDispatchingRows() {
        assertThat(jdbc.sql("""
                        select data_type || ':' || is_nullable from information_schema.columns
                         where table_schema = current_schema() and table_name = 'notification' and column_name = 'lease_until'
                        """).query(String.class).single())
                .isEqualTo("timestamp with time zone:YES");

        UUID id = UUID.randomUUID();
        jdbc.sql("""
                insert into notification
                  (id, app_id, user_id, policy, timeout_minutes, fallback_payload, state, created_at, lease_until)
                values (?, 'app-lease', 'u1', 'NOT_READ', 10, '{}'::jsonb, 'DISPATCHING', now(), now())
                """).param(id).update();

        assertThatThrownBy(() -> jdbc.sql("update notification set state = 'FALLBACK_TRIGGERED', closed_at = now() where id = ?")
                        .param(id).update())
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatCode(() -> jdbc.sql("""
                        update notification set state = 'FALLBACK_TRIGGERED', closed_at = now(), lease_until = null where id = ?
                        """).param(id).update())
                .doesNotThrowAnyException();
    }

    @Test
    void stateConstraint() {
        assertThatThrownBy(() -> insertNotification("app-state", null, "BOGUS", "NOT_READ"))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatCode(() -> insertNotification("app-state", null, "DISPATCHING", "NOT_READ"))
                .doesNotThrowAnyException();
    }

    @Test
    void policyConstraint() {
        assertThatThrownBy(() -> insertNotification("app-policy", null, "PENDING", "BOGUS"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void externalIdUniquePerApp() {
        insertNotification("app-a", "ext-1", "PENDING", "NOT_READ");

        assertThatThrownBy(() -> insertNotification("app-a", "ext-1", "PENDING", "NOT_READ"))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatCode(() -> insertNotification("app-b", "ext-1", "PENDING", "NOT_READ"))
                .doesNotThrowAnyException();
        // null external ids never collide
        assertThatCode(() -> {
            insertNotification("app-a", null, "PENDING", "NOT_READ");
            insertNotification("app-a", null, "PENDING", "NOT_READ");
        }).doesNotThrowAnyException();
    }

    @Test
    void eventTypeConstraintAndDeviceHistoryKept() {
        UUID notificationId = UUID.randomUUID();
        UUID deviceId = UUID.randomUUID();
        jdbc.sql("""
                insert into notification
                  (id, app_id, user_id, policy, timeout_minutes, fallback_payload, state, created_at)
                values (?, 'app-ev', 'u1', 'NOT_READ', 10, '{}'::jsonb, 'PENDING', now())
                """).param(notificationId).update();
        jdbc.sql("""
                insert into device (id, app_id, user_id, platform, token_version, created_at, last_seen_at)
                values (?, 'app-ev', 'u1', 'ANDROID', 1, now(), now())
                """).param(deviceId).update();

        assertThatThrownBy(() -> insertEvent(notificationId, deviceId, "BOGUS"))
                .isInstanceOf(DataIntegrityViolationException.class);
        insertEvent(notificationId, deviceId, "DELIVERED");

        jdbc.sql("delete from device where id = ?").param(deviceId).update();
        UUID kept = jdbc.sql("select device_id from notification_event where notification_id = ?")
                .param(notificationId)
                .query(UUID.class)
                .optional()
                .orElse(null);
        Integer events = jdbc.sql("select count(*) from notification_event where notification_id = ?")
                .param(notificationId).query(Integer.class).single();
        assertThat(events).isEqualTo(1);
        assertThat(kept).isNull();

        jdbc.sql("delete from notification where id = ?").param(notificationId).update();
        assertThat(jdbc.sql("select count(*) from notification_event where notification_id = ?")
                .param(notificationId).query(Integer.class).single()).isZero();
    }

    @Test
    void eventUniquePerNotificationAndType() {
        UUID notificationId = UUID.randomUUID();
        jdbc.sql("""
                insert into notification
                  (id, app_id, user_id, policy, timeout_minutes, fallback_payload, state, created_at)
                values (?, 'app-uniq', 'u1', 'NOT_READ', 10, '{}'::jsonb, 'PENDING', now())
                """).param(notificationId).update();

        insertEvent(notificationId, null, "DELIVERED");

        assertThatThrownBy(() -> insertEvent(notificationId, null, "DELIVERED"))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatCode(() -> insertEvent(notificationId, null, "READ")).doesNotThrowAnyException();
        assertThat(jdbc.sql("select count(*) from pg_indexes where schemaname = current_schema()"
                        + " and indexname = 'notification_event_notification_type'")
                .query(Integer.class).single()).isZero();
    }

    @Test
    void closedRowHasNoDueAt() {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                insert into notification
                  (id, app_id, user_id, policy, timeout_minutes, fallback_payload, state, created_at, due_at)
                values (?, 'app-close', 'u1', 'NOT_READ', 10, '{}'::jsonb, 'PENDING', now(), now())
                """).param(id).update();

        assertThatThrownBy(() -> jdbc.sql("update notification set closed_at = now() where id = ?").param(id).update())
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatCode(() -> jdbc.sql("update notification set closed_at = now(), due_at = null where id = ?")
                .param(id).update()).doesNotThrowAnyException();
    }

    @Test
    void deviceRejectsUnknownPlatform() {
        assertThatThrownBy(() -> jdbc.sql("""
                insert into device (id, app_id, user_id, platform, token_version, created_at, last_seen_at)
                values (?, 'app-dev', 'u1', 'WINDOWS', 1, now(), now())
                """).param(UUID.randomUUID()).update())
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    private void insertEvent(UUID notificationId, UUID deviceId, String type) {
        jdbc.sql("""
                insert into notification_event (notification_id, device_id, type, occurred_at, received_at, late)
                values (?, ?, ?, now(), now(), false)
                """).params(notificationId, deviceId, type).update();
    }
}
