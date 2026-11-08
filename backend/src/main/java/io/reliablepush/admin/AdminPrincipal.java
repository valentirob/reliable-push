package io.reliablepush.admin;

import java.util.Set;

/**
 * The authenticated admin key: its id (safe to log), the apps it may see, whether it may see payloads and whether it
 * may resume fallback circuits ({@code fallback-control}).
 */
public record AdminPrincipal(String keyId, Set<String> apps, boolean payload, boolean fallbackControl) {

    /** Request attribute under which the admin filter publishes the principal. */
    public static final String ATTR = "rp.admin_principal";

    public boolean canControlFallback() {
        return fallbackControl;
    }

    public boolean canSee(String appId) {
        return apps.contains(appId);
    }
}
