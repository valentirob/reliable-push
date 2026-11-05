package io.reliablepush.sample

import android.app.Application
import io.reliablepush.android.ReliablePush
import io.reliablepush.android.ReliablePushOptions

/**
 * Configures the SDK on every launch, before any push entry point runs (also when a push starts the process: the
 * cold-start case). Base URL and app id come from BuildConfig (Gradle properties `rp.baseUrl`, `rp.appId`).
 */
class SampleApp : Application() {
    override fun onCreate() {
        super.onCreate()
        ReliablePush.configure(this, BuildConfig.RP_BASE_URL, BuildConfig.RP_APP_ID, options())
    }

    companion object {
        /** The SDK options of the sample: a tap opens [MainActivity], which reports READ. */
        fun options() = ReliablePushOptions(channelName = "Sample notifications", launchActivity = MainActivity::class.java)
    }
}

/**
 * The customer's own backend as the app sees it: it hands the logged-in user a credential for [ReliablePush.register]
 * and learns the device id. The debug build plays it with the harness (`HarnessClient`); the release build has none
 * (a real app would use its own login).
 */
interface SampleBackend {
    /** A short-lived identity credential (signed grant or ID token) for [userId]. Blocking: call off the main thread. */
    fun grant(appId: String, userId: String): String

    /** Tells the backend which device id the user's installation got. Blocking: call off the main thread. */
    fun deviceRegistered(appId: String, userId: String, deviceId: String)

    companion object {
        /** The debug build's harness client, or null in a release build (it is compiled only into debug). */
        fun load(): SampleBackend? = try {
            Class.forName("io.reliablepush.sample.HarnessClient").getDeclaredConstructor().newInstance() as SampleBackend
        } catch (e: ReflectiveOperationException) {
            null
        }
    }
}
