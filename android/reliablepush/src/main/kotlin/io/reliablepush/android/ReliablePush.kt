package io.reliablepush.android

import android.content.Context
import android.content.Intent
import io.reliablepush.android.internal.BaseUrl
import io.reliablepush.android.internal.NotificationPoster
import io.reliablepush.android.internal.Sdk
import io.reliablepush.android.internal.SdkLog

/**
 * Entry point of the Reliable Push SDK: reports whether each push was delivered (shown) and read (tapped).
 *
 * Threading: network work runs on one SDK background thread, in call order; callbacks run on the main thread.
 * Every method may be called from any thread.
 *
 * Data sent to the backend: device id, tracking id, event type (`DELIVERED`/`READ`), timestamp and app id; at
 * registration also the host's ID token. Nothing else (no notification content).
 */
public object ReliablePush {
    /** SDK version; kept equal to `VERSION_NAME` in `android/gradle.properties` (checked by VersionTest). */
    public const val VERSION: String = "0.1.0-SNAPSHOT"

    private class State(val sdk: Sdk, val options: ReliablePushOptions)

    private val lock = Any()

    @Volatile
    private var state: State? = null

    /**
     * Configures the SDK. Call it in `Application.onCreate`, on every launch, before any other method: `Application`
     * runs before the app's `FirebaseMessagingService`, so pushes are tracked from the first one. (A push handled before
     * `configure` still uses the configuration stored by an earlier launch, but with default [options].)
     *
     * Idempotent: the same [baseUrl] and [appId] keep the registration (only [options] are replaced). A different
     * [baseUrl] or [appId] replaces the stored configuration and clears the registration, the queued events and the
     * retry job. The configuration is persisted (on the SDK thread) for the retry job. Events still queued from an
     * earlier launch are sent.
     *
     * @throws IllegalArgumentException if [baseUrl] is not `https` (plain `http` is allowed only for `localhost`,
     *   `127.0.0.1` and the emulator's `10.0.2.2`) or [appId] is blank.
     */
    public fun configure(
        context: Context,
        baseUrl: String,
        appId: String,
        options: ReliablePushOptions = ReliablePushOptions(),
    ) {
        val url = BaseUrl.validate(baseUrl)
        require(appId.isNotBlank()) { "appId must not be blank" }
        val app = context.applicationContext
        SdkLog.init(app)
        synchronized(lock) {
            val current = state
            if (current != null && current.sdk.baseUrl == url && current.sdk.appId == appId &&
                current.sdk.jobId == options.jobId
            ) {
                state = State(current.sdk, options)
                return
            }
            val sdk = Sdk.create(app, url, appId, options.jobId)
            state = State(sdk, options)
            Sdk.execute { sdk.applyConfig(previous = current?.sdk) }
        }
    }

    /**
     * Registers this installation for the signed-in user, exchanging the app's identity credential ([idToken], an
     * OIDC ID token or a signed grant) for a device token that is stored encrypted. Call it after login and on app
     * start. Asynchronous: [callback] runs on the main thread. A successful registration then sends the queued events.
     *
     * @throws IllegalStateException if [configure] has not been called.
     */
    public fun register(idToken: String, callback: ((RegisterResult) -> Unit)? = null) {
        val sdk = configured()
        Sdk.execute {
            val result = try {
                sdk.register(idToken)
            } catch (e: RuntimeException) {
                SdkLog.error("device.register_failed", "error" to e.javaClass.simpleName)
                RegisterResult.Failed(null)
            }
            if (callback != null) Sdk.onMain { callback(result) }
            if (result is RegisterResult.Success) sdk.flush()
        }
    }

    /**
     * Handles a push. Returns within milliseconds; call it from the push entry point (see [ReliablePushMessaging]).
     *
     * A message without the data key `rp_tid` is not the SDK's: `false`, nothing happens. A malformed `rp_tid` (not a
     * UUID) is ignored with a WARN: `false`. Otherwise the notification is posted on the calling thread, so it is
     * visible when this returns. Only if it was actually posted is `DELIVERED` written to the on-disk queue and the
     * retry job scheduled, also before returning (disk only, a few milliseconds), so the event survives the process
     * being killed as soon as the push entry point returns; the send itself happens later on the SDK thread. Called
     * on the main thread, the disk write moves to a background thread instead. When the device is not registered the
     * notification is shown but nothing is queued (WARN `tracking.not_registered`).
     *
     * Title: data `title`, else [fallbackTitle], else the app label. Body: data `body`, else [fallbackBody], else empty.
     *
     * @return `true` when the notification was posted; `false` when the message is not the SDK's or could not be shown
     *   (notifications disabled for the app, `POST_NOTIFICATIONS` denied, or the channel set to none). Nothing is
     *   tracked when it returns `false`, so the backend's fallback decides.
     */
    public fun handle(
        context: Context,
        data: Map<String, String>,
        fallbackTitle: String? = null,
        fallbackBody: String? = null,
    ): Boolean {
        val trackingId = data[NotificationPoster.EXTRA_TRACKING_ID] ?: return false
        if (!Sdk.isTrackingId(trackingId)) {
            SdkLog.warn("tracking.event_dropped", "reason" to "malformed_rp_tid")
            return false
        }
        val app = context.applicationContext
        val current = stateFor(app)
        val title = data["title"].orIfEmpty(fallbackTitle) ?: app.applicationInfo.loadLabel(app.packageManager).toString()
        val body = data["body"].orIfEmpty(fallbackBody) ?: ""
        if (!NotificationPoster(app, current?.options ?: ReliablePushOptions()).post(trackingId, title, body)) return false
        val occurredAt = System.currentTimeMillis()
        if (current == null) {
            SdkLog.warn("tracking.not_registered", "tid" to trackingId, "reason" to "not_configured")
        } else {
            Sdk.persistOffMain { current.sdk.record(trackingId, "DELIVERED", occurredAt) }
        }
        return true
    }

    /**
     * Reports that the user opened a notification. Call it from the launch activity's `onCreate` and `onNewIntent`
     * with the intent. If the intent carries the extra `rp_tid` (a UUID), `READ` is queued and sent, and the extra is
     * removed so a recreated activity does not report it again. Other intents are ignored. The event is written to
     * disk before returning (from the main thread: on a disk-only background thread), then sent on the SDK thread.
     */
    public fun trackOpen(intent: Intent?) {
        val trackingId = try {
            intent?.getStringExtra(NotificationPoster.EXTRA_TRACKING_ID)
        } catch (e: RuntimeException) { // unparcelable extras of a foreign intent
            null
        } ?: return
        if (!Sdk.isTrackingId(trackingId)) return
        intent?.removeExtra(NotificationPoster.EXTRA_TRACKING_ID)
        val occurredAt = System.currentTimeMillis()
        val current = state
        if (current == null) {
            SdkLog.warn("tracking.not_registered", "tid" to trackingId, "reason" to "not_configured")
            return
        }
        Sdk.persistOffMain { current.sdk.record(trackingId, "READ", occurredAt) }
    }

    /**
     * Logs this installation out: revokes the device token at the backend (best effort), then clears the token, the
     * queued events and the retry job in any case. [callback] runs on the main thread with `true` when the backend
     * confirmed the revoke (or nothing was registered) and `false` when it could not be reached or refused.
     *
     * @throws IllegalStateException if [configure] has not been called.
     */
    public fun logout(callback: ((Boolean) -> Unit)? = null) {
        val sdk = configured()
        Sdk.execute {
            val confirmed = try {
                sdk.logout()
            } catch (e: RuntimeException) {
                SdkLog.error("device.revoke_failed", "error" to e.javaClass.simpleName)
                false
            }
            if (callback != null) Sdk.onMain { callback(confirmed) }
        }
    }

    /**
     * The backend's id for this installation, or `null` when not configured or not registered. It reads the SDK's
     * private preferences (in memory once loaded); prefer the [RegisterResult.Success] value on hot paths.
     */
    public val deviceId: String?
        get() = state?.sdk?.store?.deviceId()

    /**
     * The configured state or, for a host that has not called [configure] yet in this process, one built from the
     * configuration an earlier launch stored (default options), kept so [trackOpen] (which has no context) finds it.
     */
    private fun stateFor(app: Context): State? = state ?: synchronized(lock) {
        state ?: Sdk.fromStoredConfig(app, ReliablePushOptions().jobId)?.let { sdk ->
            SdkLog.init(app)
            State(sdk, ReliablePushOptions()).also { state = it }
        }
    }

    private fun configured(): Sdk = checkNotNull(state) { "ReliablePush.configure must be called first" }.sdk

    private fun String?.orIfEmpty(fallback: String?): String? =
        this?.takeIf { it.isNotEmpty() } ?: fallback?.takeIf { it.isNotEmpty() }

    /** Test only: forgets the in-memory configuration (as a new process would), after pending SDK work has run. */
    internal fun resetForTest() {
        Sdk.awaitIdle()
        synchronized(lock) { state = null }
    }
}
