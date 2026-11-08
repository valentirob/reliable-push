package io.reliablepush.sample

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import io.reliablepush.android.RegisterResult
import io.reliablepush.android.ReliablePush
import java.util.UUID

/**
 * Login (Register) / Logout, the device id, and READ tracking: a tapped notification opens this activity with the extra
 * `rp_tid`, reported by [ReliablePush.trackOpen] from both [onCreate] and [onNewIntent] (the tap intent is SINGLE_TOP).
 *
 * Registration follows the SDK guide: [ReliablePush.ensureRegistered] at start and right after login. It renews the
 * device token only when needed; its ID-token provider asks the "login backend" (the harness in the debug build) for
 * a grant of the logged-in user, or delivers `null` when nobody is logged in. The logged-in user id is kept in the
 * sample's own preferences; logging in as another user logs the previous one out first (FR-8).
 *
 * Debug build: the extra `autoRegisterUser=<id>` logs that user in right away (the E2E cold-start check).
 */
@SuppressLint("SetTextI18n") // a demo screen in English only
class MainActivity : Activity() {
    private val backend: SampleBackend? by lazy { SampleBackend.load() }
    private lateinit var status: TextView
    private lateinit var deviceIdView: TextView
    private lateinit var userInput: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ReliablePush.trackOpen(intent)
        setContentView(buildUi())
        showDeviceId()
        requestNotificationPermission()

        val autoUser = intent.getStringExtra(EXTRA_AUTO_REGISTER_USER)
        if (savedInstanceState == null && BuildConfig.DEBUG && !autoUser.isNullOrBlank()) {
            userInput.setText(autoUser)
            login(autoUser)
        } else if (savedInstanceState == null) {
            loggedInUser()?.let { userInput.setText(it) }
            ensureRegistered(reportDevice = false) // app start: renews the device token only when needed
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        ReliablePush.trackOpen(intent)
    }

    private fun buildUi(): LinearLayout {
        val pad = (16 * resources.displayMetrics.density).toInt()
        status = TextView(this).apply { text = "Not registered" }
        deviceIdView = TextView(this)
        userInput = EditText(this).apply {
            hint = "User id"
            setText("sample-user-${UUID.randomUUID().toString().take(8)}")
        }
        val register = Button(this).apply {
            text = "Register"
            setOnClickListener { login(userInput.text.toString().trim()) }
        }
        val logout = Button(this).apply {
            text = "Logout"
            setOnClickListener {
                setLoggedInUser(null)
                ReliablePush.logout { confirmed ->
                    status.text = if (confirmed) "Logged out" else "Logged out locally (backend not confirmed)"
                    showDeviceId()
                }
            }
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            fitsSystemWindows = true
            setPadding(pad, pad, pad, pad)
            listOf(status, deviceIdView, userInput, register, logout).forEach {
                addView(it, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            }
        }
    }

    /** Login: remembers [userId] (after logging the previous user out), then `ensureRegistered`. */
    private fun login(userId: String) {
        if (backend == null) {
            status.text = "No login backend in this build"
            return
        }
        if (userId.isEmpty()) {
            status.text = "Enter a user id"
            return
        }
        status.text = "Registering…"
        val previous = loggedInUser()
        setLoggedInUser(userId)
        if (previous != null && previous != userId) {
            ReliablePush.logout { ensureRegistered(reportDevice = true) } // user switch: the old token must go
        } else {
            ensureRegistered(reportDevice = true)
        }
    }

    /**
     * The SDK decides whether a network call is needed. The ID-token provider runs only when a (new) registration is
     * needed: it fetches a grant for the logged-in user off the main thread and delivers it, or delivers `null`.
     */
    private fun ensureRegistered(reportDevice: Boolean) {
        val backend = backend
        val user = loggedInUser()
        ReliablePush.ensureRegistered({ deliver ->
            if (backend == null || user == null) {
                deliver(null)
            } else {
                Thread({
                    val grant = try {
                        backend.grant(BuildConfig.RP_APP_ID, user)
                    } catch (e: Exception) {
                        Log.w(TAG, "grant failed: ${e.javaClass.simpleName}")
                        null
                    }
                    deliver(grant)
                }, "sample-grant").start()
            }
        }) { result -> onRegistered(backend, user, result, reportDevice) }
    }

    private fun onRegistered(backend: SampleBackend?, userId: String?, result: RegisterResult, reportDevice: Boolean) {
        when (result) {
            is RegisterResult.Success -> {
                status.text = "Registered"
                report(backend, userId, result.deviceId)
            }
            RegisterResult.AlreadyRegistered -> {
                status.text = "Registered"
                if (reportDevice) report(backend, userId, null)
            }
            RegisterResult.NotLoggedIn -> status.text = "Not logged in"
            is RegisterResult.Failed -> status.text = "Registration failed (HTTP ${result.httpStatus ?: "none"})"
        }
        showDeviceId()
    }

    /** Tells the login backend which device id the user got ([deviceId], or the stored one when null). */
    private fun report(backend: SampleBackend?, userId: String?, deviceId: String?) {
        if (backend == null || userId == null) return
        Thread({
            try {
                val id = deviceId ?: ReliablePush.deviceId ?: return@Thread
                // without google-services.json there is no FCM token: the device is reported without one
                val fcmToken = FcmTokens.currentBlocking(applicationContext)
                backend.deviceRegistered(BuildConfig.RP_APP_ID, userId, id, fcmToken)
            } catch (e: Exception) {
                Log.w(TAG, "real-device report failed: ${e.javaClass.simpleName}")
            }
        }, "sample-report").start()
    }

    private fun loggedInUser(): String? = getSharedPreferences(PREFS, MODE_PRIVATE).getString(KEY_USER, null)

    private fun setLoggedInUser(userId: String?) {
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().apply {
            if (userId == null) remove(KEY_USER) else putString(KEY_USER, userId)
        }.apply()
    }

    private fun showDeviceId() {
        Thread({
            val id = ReliablePush.deviceId
            runOnUiThread { deviceIdView.text = "Device id: ${id ?: "–"}" }
        }, "sample-device-id").start()
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
    }

    private companion object {
        const val TAG = "ReliablePushSample"
        const val EXTRA_AUTO_REGISTER_USER = "autoRegisterUser"
        const val PREFS = "sample"
        const val KEY_USER = "logged_in_user"
    }
}
