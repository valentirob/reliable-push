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
 * Register / Logout, the device id, and READ tracking: a tapped notification opens this activity with the extra
 * `rp_tid`, reported by [ReliablePush.trackOpen] from both [onCreate] and [onNewIntent] (the tap intent is SINGLE_TOP).
 *
 * Debug build: the extra `autoRegisterUser=<id>` registers that user right away (the E2E cold-start check).
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
            register(autoUser)
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
            setOnClickListener { register(userInput.text.toString().trim()) }
        }
        val logout = Button(this).apply {
            text = "Logout"
            setOnClickListener {
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

    /** Debug: grant from the harness, then `register`, then the harness learns the device id. */
    private fun register(userId: String) {
        val backend = backend
        if (backend == null) {
            status.text = "No login backend in this build"
            return
        }
        if (userId.isEmpty()) {
            status.text = "Enter a user id"
            return
        }
        status.text = "Registering…"
        Thread({
            val grant = try {
                backend.grant(BuildConfig.RP_APP_ID, userId)
            } catch (e: Exception) {
                Log.w(TAG, "grant failed: ${e.javaClass.simpleName}")
                runOnUiThread { status.text = "Could not get a grant (${e.javaClass.simpleName})" }
                return@Thread
            }
            ReliablePush.register(grant) { result -> onRegistered(backend, userId, result) }
        }, "sample-register").start()
    }

    private fun onRegistered(backend: SampleBackend, userId: String, result: RegisterResult) {
        when (result) {
            is RegisterResult.Success -> {
                status.text = "Registered"
                showDeviceId()
                Thread({
                    try {
                        // without google-services.json there is no FCM token: the device is reported without one
                        val fcmToken = FcmTokens.currentBlocking(applicationContext)
                        backend.deviceRegistered(BuildConfig.RP_APP_ID, userId, result.deviceId, fcmToken)
                    } catch (e: Exception) {
                        Log.w(TAG, "real-device report failed: ${e.javaClass.simpleName}")
                    }
                }, "sample-report").start()
            }
            is RegisterResult.Failed -> status.text = "Registration failed (HTTP ${result.httpStatus ?: "none"})"
        }
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
    }
}
