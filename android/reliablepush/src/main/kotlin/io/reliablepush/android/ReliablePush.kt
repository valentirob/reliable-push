package io.reliablepush.android

import android.content.Context
import android.content.Intent
import io.reliablepush.android.internal.BaseUrl
import io.reliablepush.android.internal.EnsureRegistration
import io.reliablepush.android.internal.NotificationPoster
import io.reliablepush.android.internal.Sdk
import io.reliablepush.android.internal.SdkLog

/**
 * Entry point of the Reliable Push SDK: reports whether each push was delivered (shown) and read (tapped).
 *
 * Threading: network work runs on one SDK background thread, in call order; callbacks run on the main thread.
 * Every method may be called from any thread.
 *
 * Token renewal happens only in [ensureRegistered] (and [register]), which the app calls where it chooses; receiving
 * a push, sending events and the retry job never renew the device token.
 *
 * Data sent to the backend: device id, tracking id, event type (`DELIVERED`/`READ`), timestamp and app id; at
 * registration also the host's ID token. Nothing else (no notification content).
 */
public object ReliablePush {
    /** SDK version; kept equal to `VERSION_NAME` in `android/gradle.properties` (checked by VersionTest). */
    public const val VERSION: String = "0.2.0-SNAPSHOT"

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
     * OIDC ID token or a signed grant) for a device token that is stored encrypted. Asynchronous: [callback] runs on
     * the main thread. A successful registration then sends the queued events. Prefer [ensureRegistered], which only
     * registers when needed; this call always registers.
     *
     * @throws IllegalStateException if [configure] has not been called.
     */
    public fun register(idToken: String, callback: ((RegisterResult) -> Unit)? = null) {
        val sdk = configured()
        Sdk.execute {
            val result = try {
                sdk.register(idToken)
            } catch (e: Exception) {
                SdkLog.error("device.register_failed", "error" to e.javaClass.simpleName)
                RegisterResult.Failed(null)
            }
            if (callback != null) Sdk.onMain { callback(result) }
            if (result is RegisterResult.Success) sdk.flush()
        }
    }

    /**
     * Makes sure this installation has a usable device token, renewing or re-registering only when needed. Call it at
     * app start (or when the app comes to the foreground) and right after login; it is the only place where the SDK
     * renews the device token. Returns at once and never throws; the work runs on the SDK thread and [callback] runs on
     * the main thread.
     *
     * Decision, local and in this order:
     * 1. no device token, or it is expired: ask [idTokenProvider]; no token ([RegisterResult.NotLoggedIn]) or register
     *    with it (`POST /v1/devices`, like [register]);
     * 2. less than 20 % of its validity left, or the backend rejected it on the events endpoint (`401`): renew it
     *    (`POST /v1/devices/token:refresh`); a `401` there falls back to step 1's ID-token path;
     * 3. otherwise [RegisterResult.AlreadyRegistered], without any network call.
     *
     * A success stores the token and sends the queued events. One run at a time: a call while a run is in progress
     * joins it (its [callback] gets the same result, its provider is not called).
     *
     * [idTokenProvider] is called on the SDK thread, only when an ID token is needed, with a `deliver` function: call it
     * exactly once, from any thread, with the signed-in user's ID token (or signed grant), or with `null` when nobody is
     * logged in. Extra calls are ignored. Without a call within 30 s the run ends [RegisterResult.NotLoggedIn]. Do not
     * block in the provider; fetch the token asynchronously and deliver it. A provider that throws counts as `null`.
     *
     * Not configured: [RegisterResult.Failed] with `null` (WARN). Call [logout] on user switch: it clears the token,
     * so the next call registers the new user.
     */
    public fun ensureRegistered(
        idTokenProvider: (deliver: (String?) -> Unit) -> Unit,
        callback: ((RegisterResult) -> Unit)? = null,
    ) {
        try {
            val sdk = state?.sdk
            if (sdk == null) {
                SdkLog.warn("device.register_failed", "reason" to "not_configured")
                if (callback != null) Sdk.onMain { callback(RegisterResult.Failed(null)) }
                return
            }
            EnsureRegistration.start(sdk, idTokenProvider, callback)
        } catch (t: Throwable) {
            internalError(t)
        }
    }

    /**
     * Handles a push. Returns within milliseconds; call it from the push entry point (see [ReliablePushMessaging]).
     *
     * A message without the data key `rp_tid` is not the SDK's: `false`, nothing happens. A malformed `rp_tid` (not a
     * UUID) is ignored with a WARN: `false`. Otherwise, in this order (spec §3.4):
     * 1. the stored configuration and registration are looked up;
     * 2. the notification is built (with [ReliablePushOptions.customizer]), not posted yet;
     * 3. the gate, on the notification's final channel: can the app show it (notifications enabled,
     *    `POST_NOTIFICATIONS` granted, the channel present and neither it nor its group blocked)? If not, the
     *    notification is still handed to the system, which decides, and nothing is recorded, so the backend's fallback
     *    decides;
     * 4. `DELIVERED` is written to the on-disk queue and the retry job scheduled (disk only, a few milliseconds; when
     *    called on the main thread the write runs on a background thread and this call waits for it, at most 500 ms);
     * 5. the notification is posted on the calling thread, so it is visible when this returns;
     * 6. the send is scheduled on the SDK thread (it happens after this returns).
     *
     * `DELIVERED` is on disk before the notification is posted, so the event survives the process being killed as soon
     * as the push entry point returns. If posting then throws, the minimal notification below is posted and the event
     * stays; should even that fail, no fallback fires for that push (a missed fallback is preferred to a duplicate
     * one). When the device is not registered the notification is shown but nothing is queued (WARN
     * `tracking.not_registered`).
     *
     * Display never depends on tracking: nothing thrown inside the SDK escapes (WARN `sdk.internal_error`). If anything
     * throws before the notification is posted, the SDK posts a minimal one instead (title and body, else the app label,
     * on the configured channel, with the `rp_tid` tap extra).
     *
     * Title: data `title`, else [fallbackTitle], else the app label. Body: data `body`, else [fallbackBody], else empty.
     *
     * @return `true` when the notification was posted. `false` when:
     *   - the message is not the SDK's (no or malformed `rp_tid`);
     *   - the gate is closed: notifications disabled for the app, `POST_NOTIFICATIONS` denied, notifications paused;
     *   - the final channel is unusable: missing, set to none, or in a blocked group (also a channel the customizer
     *     moved the notification to);
     *   - even the minimal notification could not be posted.
     *   Nothing is recorded in the first three cases. The host may then show its own notification.
     */
    public fun handle(
        context: Context,
        data: Map<String, String>,
        fallbackTitle: String? = null,
        fallbackBody: String? = null,
    ): Boolean {
        var trackingId: String? = null
        var app: Context = context
        var options = DEFAULT_OPTIONS
        var poster: NotificationPoster? = null
        var showable: Boolean? = null
        var tracking: Sdk? = null // set once DELIVERED is on disk
        var posted: Boolean? = null
        try {
            val tid = data[NotificationPoster.EXTRA_TRACKING_ID] ?: return false
            if (!Sdk.isTrackingId(tid)) {
                SdkLog.warn("tracking.event_dropped", "reason" to "malformed_rp_tid")
                return false
            }
            trackingId = tid
            app = context.applicationContext
            val current = stateFor(app)
            options = current?.options ?: DEFAULT_OPTIONS
            val p = posterFactory(app, options).also { poster = it }
            val notification = p.prepare(tid, titleOf(app, data, fallbackTitle), bodyOf(data, fallbackBody))
            // the gate on the channel the notification will really use (the host's customizer may have moved it)
            val canShow = p.canShow(notification.channelId ?: options.channelId).also { showable = it }
            if (current == null) {
                SdkLog.warn("tracking.not_registered", "tid" to tid, "reason" to "not_configured")
            } else if (canShow) {
                val occurredAt = System.currentTimeMillis()
                if (Sdk.persistBeforePost { current.sdk.persist(tid, "DELIVERED", occurredAt) } == true) {
                    tracking = current.sdk
                }
            }
            val shown = p.notify(tid, notification).also { posted = it }
            tracking?.let { sdk -> Sdk.execute { sdk.flush() } }
            return canShow && shown
        } catch (t: Throwable) {
            internalError(t)
            val tid = trackingId ?: return false
            posted?.let { return it && showable == true } // threw after the post
            val shown = try {
                (poster ?: NotificationPoster(app, options))
                    .postMinimal(tid, minimalTitleOf(app, data, fallbackTitle), bodyOf(data, fallbackBody))
                true
            } catch (t2: Throwable) {
                internalError(t2)
                false
            }
            try {
                tracking?.let { sdk -> Sdk.execute { sdk.flush() } }
            } catch (t3: Throwable) {
                internalError(t3) // the retry job sends it
            }
            return shown && showable != false
        }
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
        try {
            if (!Sdk.isTrackingId(trackingId)) return
            intent?.removeExtra(NotificationPoster.EXTRA_TRACKING_ID)
            val occurredAt = System.currentTimeMillis()
            val current = state
            if (current == null) {
                SdkLog.warn("tracking.not_registered", "tid" to trackingId, "reason" to "not_configured")
                return
            }
            Sdk.persistOffMain { current.sdk.record(trackingId, "READ", occurredAt) }
        } catch (t: Throwable) {
            internalError(t)
        }
    }

    /**
     * Logs this installation out: ends an [ensureRegistered] run in progress ([RegisterResult.NotLoggedIn]), revokes
     * the device token at the backend (best effort), then clears the token, the queued events and the retry job in any
     * case. [callback] runs on the main thread with `true` when the backend confirmed the revoke (or nothing was
     * registered) and `false` when it could not be reached or refused.
     *
     * @throws IllegalStateException if [configure] has not been called.
     */
    public fun logout(callback: ((Boolean) -> Unit)? = null) {
        val sdk = configured()
        EnsureRegistration.cancel() // its callbacks get NotLoggedIn; a later deliver cannot register the old user
        Sdk.execute {
            val confirmed = try {
                sdk.logout()
            } catch (e: Exception) {
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
        get() = try {
            state?.sdk?.store?.deviceId()
        } catch (t: Throwable) {
            internalError(t)
            null
        }

    /**
     * The configured state or, for a host that has not called [configure] yet in this process, one built from the
     * configuration an earlier launch stored (default options), kept so [trackOpen] (which has no context) finds it.
     */
    private fun stateFor(app: Context): State? = state ?: synchronized(lock) {
        state ?: Sdk.fromStoredConfig(app, DEFAULT_OPTIONS.jobId)?.let { sdk ->
            SdkLog.init(app)
            State(sdk, DEFAULT_OPTIONS).also { state = it }
        }
    }

    private fun configured(): Sdk = checkNotNull(state) { "ReliablePush.configure must be called first" }.sdk

    private fun String?.orIfEmpty(fallback: String?): String? =
        this?.takeIf { it.isNotEmpty() } ?: fallback?.takeIf { it.isNotEmpty() }

    private fun appLabel(app: Context): String = app.applicationInfo.loadLabel(app.packageManager).toString()

    private fun titleOf(app: Context, data: Map<String, String>, fallback: String?): String =
        data["title"].orIfEmpty(fallback) ?: appLabel(app)

    /** As [titleOf], but never throws: an app label that cannot be read gives an empty title. */
    private fun minimalTitleOf(app: Context, data: Map<String, String>, fallback: String?): String = try {
        titleOf(app, data, fallback)
    } catch (t: Throwable) {
        ""
    }

    private fun bodyOf(data: Map<String, String>, fallback: String?): String = try {
        data["body"].orIfEmpty(fallback) ?: ""
    } catch (t: Throwable) {
        ""
    }

    /** WARN `sdk.internal_error` with the class name only (never a message: it may carry content). */
    private fun internalError(t: Throwable) {
        try {
            SdkLog.warn("sdk.internal_error", "error" to t.javaClass.simpleName)
        } catch (ignored: Throwable) { // logging must not break the display path
        }
    }

    private val DEFAULT_OPTIONS = ReliablePushOptions()

    /** Test hook: builds the poster [handle] uses (fault injection). */
    @Volatile
    internal var posterFactory: (Context, ReliablePushOptions) -> NotificationPoster = { c, o -> NotificationPoster(c, o) }

    /** Test only: forgets the in-memory configuration (as a new process would), after pending SDK work has run. */
    internal fun resetForTest() {
        Sdk.awaitIdle()
        synchronized(lock) { state = null }
        EnsureRegistration.resetForTest()
        posterFactory = { c, o -> NotificationPoster(c, o) }
    }
}
