package io.reliablepush.fallback;

import io.reliablepush.apps.AppConfig;

/**
 * Sends one fallback webhook attempt for an app (spec 5). Implementations never throw for a delivery problem
 * (HTTP status, timeout, I/O error): they classify it as a {@link WebhookResult}. The caller holds no database lock
 * or transaction during the call.
 */
public interface WebhookClient {

    WebhookResult send(AppConfig app, FallbackCall call);
}
