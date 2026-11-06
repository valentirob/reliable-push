package io.reliablepush.admin;

import io.reliablepush.shared.logging.EventLog;
import io.reliablepush.shared.logging.UserHash;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

/**
 * Audit events of the support lookups (spec 4). Only the key id, app, tracking id and the user hash are logged: never
 * the key, a raw user id, a filter value or the payload.
 */
final class AdminAudit {

    private AdminAudit() {}

    /** {@code filterNames} are the names of the filters used, never their values. */
    static void search(AdminPrincipal principal, String appId, Set<String> filterNames) {
        EventLog.info("admin.search", Map.of(
                "rp.admin_key", principal.keyId(),
                "rp.app_id", appId,
                "filters", String.join(",", new TreeSet<>(filterNames))));
    }

    static void viewed(AdminPrincipal principal, String appId, UUID id, String userId) {
        EventLog.info("admin.notification_viewed", fields(principal, appId, id, userId));
    }

    static void payloadViewed(AdminPrincipal principal, String appId, UUID id, String userId) {
        EventLog.info("admin.payload_viewed", fields(principal, appId, id, userId));
    }

    /** A devices lookup by user id: the user hash only, never the raw user id. */
    static void devicesViewed(AdminPrincipal principal, String appId, String userId) {
        EventLog.info("admin.devices_viewed", Map.of(
                "rp.admin_key", principal.keyId(),
                "rp.app_id", appId,
                "rp.user_hash", UserHash.of(userId)));
    }

    private static Map<String, Object> fields(AdminPrincipal principal, String appId, UUID id, String userId) {
        return Map.of(
                "rp.admin_key", principal.keyId(),
                "rp.app_id", appId,
                "rp.tracking_id", id.toString(),
                "rp.user_hash", UserHash.of(userId));
    }
}
