package io.reliablepush.admin;

import java.util.Set;

/** The authenticated admin key: its id (safe to log), the apps it may see and whether it may see payloads. */
public record AdminPrincipal(String keyId, Set<String> apps, boolean payload) {

    /** Request attribute under which the admin filter publishes the principal. */
    public static final String ATTR = "rp.admin_principal";

    public boolean canSee(String appId) {
        return apps.contains(appId);
    }
}
