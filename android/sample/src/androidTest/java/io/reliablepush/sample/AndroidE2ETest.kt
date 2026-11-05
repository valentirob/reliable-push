package io.reliablepush.sample

import android.app.NotificationManager
import android.content.Context
import android.net.ConnectivityManager
import android.os.ParcelFileDescriptor
import android.os.Process
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import io.reliablepush.android.RegisterResult
import io.reliablepush.android.ReliablePush
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Emulator E2E against the local harness stack (`scripts/android-e2e.sh`): the real SDK in the sample app talks to the
 * real backend at `baseUrl`, the harness (`harnessUrl`) plays the customer backend (grants, sends) and observes the
 * notification state. The push itself is handed to [ReliablePush.handle] as the debug receiver does (no FCM).
 *
 * Runs under the AndroidX Test Orchestrator with `clearPackageData`: every test is a fresh process with empty storage.
 * Instrumentation arguments: `harnessUrl`, `baseUrl` (defaults: the debug BuildConfig values).
 */
@RunWith(AndroidJUnit4::class)
class AndroidE2ETest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context: Context = instrumentation.targetContext
    private val args = InstrumentationRegistry.getArguments()
    private val baseUrl = args.getString("baseUrl") ?: BuildConfig.RP_BASE_URL
    private val harness = HarnessClient(args.getString("harnessUrl") ?: BuildConfig.RP_HARNESS_URL)
    private val notifications = context.getSystemService(NotificationManager::class.java)

    @Before
    fun setUp() {
        shell("pm grant $PACKAGE android.permission.POST_NOTIFICATIONS")
        // Android 17 local network protection: the harness stack is at 10.0.2.2, a private address
        shell("pm grant $PACKAGE android.permission.ACCESS_LOCAL_NETWORK")
        shell("cmd connectivity airplane-mode disable")
        ReliablePush.configure(context, baseUrl, SHOP, SampleApp.options())
    }

    @After
    fun tearDown() {
        shell("pm grant $PACKAGE android.permission.POST_NOTIFICATIONS")
        shell("cmd connectivity airplane-mode disable")
        notifications.cancelAll()
        UiDevice.getInstance(instrumentation).pressHome()
    }

    @Test
    fun deliveredIsReported() {
        val user = register(SHOP)
        val tid = harness.push(SHOP, user, "Delivered", "E2E delivered", "NOT_DELIVERED", 5)
        assertTrue("the SDK posts the notification", deliver(tid, "Delivered"))
        awaitState(tid, "DELIVERED", 5)
        Thread.sleep(7_000) // past the 5 s (fast-clock) timeout
        assertEquals("DELIVERED", state(tid))
        assertEquals("no fallback webhook after the timeout", 0, webhookCount(tid))
        assertEquals("no inbox call for $tid", 0, harness.inboxCallsFor(SHOP, tid))
    }

    @Test
    fun readIsReportedOnTap() {
        val user = register(SHOP)
        val title = "Read me ${UUID.randomUUID().toString().take(8)}"
        val tid = harness.push(SHOP, user, title, "Tap to open", "NOT_READ", 120)
        assertTrue(deliver(tid, title))
        awaitState(tid, "DELIVERED", 20)

        val device = UiDevice.getInstance(instrumentation)
        device.openNotification()
        val entry = device.wait(Until.findObject(By.text(title)), 15_000)
        assertNotNull("notification '$title' in the shade", entry)
        entry.click()
        assertNotNull(
            "MainActivity opened by the tap",
            device.wait(Until.hasObject(By.pkg(PACKAGE).depth(0)), 15_000),
        )
        awaitState(tid, "READ", 30)
    }

    @Test
    fun offlineDeliveryIsSentWhenNetworkReturns() {
        val user = register(SHOP)
        // Timeout 120 (fast clock: s), not 60: the retry job's 30 s minimum latency ends with JobScheduler's delay
        // alarm, an inexact alarm (window 75 % of the delay, seen on API 37), so the job runs 30-60+ s after the push.
        val tid = harness.push(SHOP, user, "Offline", "E2E offline", "NOT_DELIVERED", 120)
        assertEquals("PENDING", state(tid))

        shell("cmd connectivity airplane-mode enable")
        awaitOffline()
        assertTrue("the notification is shown offline", deliver(tid, "Offline"))
        Thread.sleep(3_000)
        shell("cmd connectivity airplane-mode disable")
        awaitOnline()
        // the retry job has a 30 s minimum latency from the delivery: the event cannot have been sent yet
        assertEquals("still PENDING while the event waits for the retry job", "PENDING", state(tid))
        awaitState(tid, "DELIVERED", 110)
    }

    @Test
    fun notificationsBlockedMeansFallback() {
        val user = register(SHOP)
        revokeNotificationPermissionWithoutKill()
        assertFalse("notifications reported blocked", notifications.areNotificationsEnabled())

        val tid = harness.push(SHOP, user, "Blocked", "E2E blocked", "NOT_DELIVERED", 5)
        assertFalse("handle reports the notification as not shown", deliver(tid, "Blocked"))
        assertTrue("nothing posted", notifications.activeNotifications.none { it.tag == "rp" })
        awaitState(tid, "FALLBACK_TRIGGERED", 30)
        awaitWebhooks(tid, 1)
        assertFalse("no DELIVERED in the timeline", timelineHas(tid, "DELIVERED"))
    }

    @Test
    fun tokenRefreshNearExpiry() {
        // app news: device tokens valid 20 s; the SDK refreshes when less than 20 % (4 s) is left
        ReliablePush.configure(context, baseUrl, NEWS, SampleApp.options())
        val registeredAt = System.currentTimeMillis()
        val user = register(NEWS)
        val deviceId = checkNotNull(ReliablePush.deviceId)
        Thread.sleep((registeredAt + 17_000 - System.currentTimeMillis()).coerceAtLeast(0))

        val tid = harness.push(NEWS, user, "Refresh", "E2E refresh", "NOT_DELIVERED", 30)
        assertTrue(deliver(tid, "Refresh"))
        awaitState(tid, "DELIVERED", 20)
        awaitUntil(15, "backend log device.token_refreshed for device $deviceId") {
            harness.logs("device.token_refreshed").any { it.optString("rp.device_id") == deviceId }
        }
    }

    @Test
    fun logoutRevokesDevice() {
        val user = register(SHOP)
        val confirmed = AtomicReference<Boolean>()
        val done = CountDownLatch(1)
        ReliablePush.logout { confirmed.set(it); done.countDown() }
        assertTrue("logout callback", done.await(30, TimeUnit.SECONDS))
        assertEquals("backend confirmed the revoke", true, confirmed.get())

        val tid = harness.push(SHOP, user, "Logged out", "E2E logout", "NOT_DELIVERED", 5)
        assertTrue("the notification is still shown", deliver(tid, "Logged out"))
        // NotificationManager.notify is asynchronous: the system lists it a moment later
        awaitUntil(10, "notification $tid posted") {
            notifications.activeNotifications.any { it.tag == "rp" && it.id == tid.hashCode() }
        }
        awaitState(tid, "FALLBACK_TRIGGERED", 30)
        awaitWebhooks(tid, 1)
        assertFalse("no DELIVERED in the timeline", timelineHas(tid, "DELIVERED"))
    }

    // ---- helpers ---------------------------------------------------------------------------------------------

    /** A new user registered through the SDK with a harness grant; the device is announced to the harness. */
    private fun register(appId: String): String {
        val user = "android-e2e-${UUID.randomUUID()}"
        val grant = harness.grant(appId, user)
        val result = AtomicReference<RegisterResult>()
        val done = CountDownLatch(1)
        ReliablePush.register(grant) { result.set(it); done.countDown() }
        assertTrue("register callback within 30 s", done.await(30, TimeUnit.SECONDS))
        val success = result.get() as? RegisterResult.Success ?: run {
            fail("registration failed: ${result.get()}")
            error("unreachable")
        }
        harness.deviceRegistered(appId, user, success.deviceId)
        return user
    }

    /** What [DebugPushReceiver] does with the broadcast extras (off the main thread, like the FCM entry point). */
    private fun deliver(tid: String, title: String): Boolean =
        ReliablePush.handle(context, mapOf("rp_tid" to tid, "title" to title, "body" to "E2E"))

    private fun notification(tid: String): JSONObject = harness.notification(tid)

    private fun state(tid: String): String? = notification(tid).optJSONObject("notification")?.optString("state")

    private fun webhookCount(tid: String): Int = notification(tid).optJSONArray("webhooks")?.length() ?: 0

    private fun timelineHas(tid: String, state: String): Boolean {
        val timeline = notification(tid).optJSONArray("timeline") ?: return false
        return (0 until timeline.length()).any { timeline.getJSONObject(it).toString().contains("\"$state\"") }
    }

    private fun awaitState(tid: String, expected: String, seconds: Int) {
        var last: String? = null
        awaitUntil(seconds, { "state $expected for $tid (last seen: $last)" }) {
            last = state(tid)
            last == expected
        }
    }

    private fun awaitWebhooks(tid: String, expected: Int) {
        var last = -1
        awaitUntil(15, { "$expected webhook call(s) for $tid (last seen: $last)" }) {
            last = webhookCount(tid)
            last >= expected
        }
        Thread.sleep(2_000) // a second call would follow quickly; there must be exactly one
        assertEquals("webhook calls for $tid", expected, webhookCount(tid))
    }

    private fun awaitOffline() = awaitUntil(20, "no active network after airplane mode") { activeNetwork() == null }

    private fun awaitOnline() = awaitUntil(30, "harness reachable after airplane mode") {
        activeNetwork() != null && harness.reachable()
    }

    private fun activeNetwork() = context.getSystemService(ConnectivityManager::class.java).activeNetwork

    private fun awaitUntil(seconds: Int, what: String, condition: () -> Boolean) =
        awaitUntil(seconds, { what }, condition)

    private fun awaitUntil(seconds: Int, what: () -> String, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + seconds * 1_000L
        while (true) {
            val ok = try {
                condition()
            } catch (e: Exception) {
                false
            }
            if (ok) return
            if (System.currentTimeMillis() > deadline) fail("expected within $seconds s: ${what()}")
            Thread.sleep(250)
        }
    }

    /**
     * Denies POST_NOTIFICATIONS as a user who turns the app's notifications off would, but without killing the app
     * process, which is this test's process (`pm revoke` kills it; `appops … ignore` does not change
     * `areNotificationsEnabled`). `PermissionManager.revokePostNotificationPermissionWithoutKillForTest` is a test API
     * (instrumentation has test-API access) that needs two permissions the shell holds. `@After` grants it back.
     */
    private fun revokeNotificationPermissionWithoutKill() {
        val ui = instrumentation.uiAutomation
        ui.adoptShellPermissionIdentity(
            "android.permission.REVOKE_POST_NOTIFICATIONS_WITHOUT_KILL",
            "android.permission.REVOKE_RUNTIME_PERMISSIONS",
        )
        try {
            val type = Class.forName("android.permission.PermissionManager")
            type.getMethod("revokePostNotificationPermissionWithoutKillForTest", String::class.java, Int::class.java)
                .invoke(context.getSystemService(type), PACKAGE, Process.myUid() / PER_USER_RANGE)
        } finally {
            ui.dropShellPermissionIdentity()
        }
    }

    /** Runs a shell command as the shell user (holds the permissions adb has) and waits for it to finish. */
    private fun shell(command: String): String {
        val pfd = instrumentation.uiAutomation.executeShellCommand(command)
        return ParcelFileDescriptor.AutoCloseInputStream(pfd).use { String(it.readBytes()) }
    }

    private companion object {
        const val PACKAGE = "io.reliablepush.sample"
        const val SHOP = "shop"
        const val NEWS = "news"
        const val PER_USER_RANGE = 100_000 // UserHandle.PER_USER_RANGE: uid = userId * 100000 + appId
    }
}
