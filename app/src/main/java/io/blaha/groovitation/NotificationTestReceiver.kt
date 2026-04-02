package io.blaha.groovitation

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Base64
import android.util.Log
import android.webkit.CookieManager
import java.nio.charset.StandardCharsets

/**
 * Debug-only BroadcastReceiver that lets the external Appium lane drive
 * notification test hooks without reaching into the app process directly.
 */
class NotificationTestReceiver : BroadcastReceiver() {

    companion object {
        const val ACTION_CONFIGURE = "io.blaha.groovitation.TEST_NOTIFICATION_CONFIGURE"
        const val ACTION_GET_ACTIVE_NOTIFICATION =
            "io.blaha.groovitation.TEST_NOTIFICATION_GET_ACTIVE_NOTIFICATION"
        const val ACTION_GET_LAST_TOKEN_REGISTRATION =
            "io.blaha.groovitation.TEST_NOTIFICATION_GET_LAST_TOKEN_REGISTRATION"
        const val ACTION_SIMULATE_TOKEN_REFRESH =
            "io.blaha.groovitation.TEST_NOTIFICATION_SIMULATE_TOKEN_REFRESH"
        const val ACTION_TAP_ACTIVE_NOTIFICATION =
            "io.blaha.groovitation.TEST_NOTIFICATION_TAP_ACTIVE_NOTIFICATION"

        const val EXTRA_CAPTURE_TOKEN_REGISTRATIONS = "capture_token_registrations"
        const val EXTRA_EXPECTED_BODY = "expected_body"
        const val EXTRA_EXPECTED_TITLE = "expected_title"
        const val EXTRA_FAKE_FCM_TOKEN = "fake_fcm_token"
        const val EXTRA_SESSION_COOKIE_VALUE = "session_cookie_value"
        const val EXTRA_RESET_APP_STATE = "reset_app_state"
        const val EXTRA_REFRESH_TOKEN = "refresh_token"

        private const val TAG = "NotificationTestRecv"
        private const val ACTIVITY_PREFS = "groovitation_prefs"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (!BuildConfig.DEBUG) return

        when (intent.action) {
            ACTION_CONFIGURE -> handleConfigure(context, intent)
            ACTION_GET_ACTIVE_NOTIFICATION -> handleGetActiveNotification(context, intent)
            ACTION_GET_LAST_TOKEN_REGISTRATION -> handleGetLastRegistration(context)
            ACTION_SIMULATE_TOKEN_REFRESH -> handleSimulateTokenRefresh(context, intent)
            ACTION_TAP_ACTIVE_NOTIFICATION -> handleTapActiveNotification(context, intent)
        }
    }

    private fun handleConfigure(context: Context, intent: Intent) {
        if (intent.getBooleanExtra(EXTRA_RESET_APP_STATE, false)) {
            resetAppState(context)
        }

        NotificationTestHooks.setCaptureTokenRegistrations(
            context,
            intent.getBooleanExtra(EXTRA_CAPTURE_TOKEN_REGISTRATIONS, false)
        )
        NotificationTestHooks.setFakeFcmToken(
            context,
            intent.getStringExtra(EXTRA_FAKE_FCM_TOKEN)
        )
        setSessionCookie(
            context,
            intent.getStringExtra(EXTRA_SESSION_COOKIE_VALUE)
        )

        setResultData("configured")
    }

    private fun handleGetLastRegistration(context: Context) {
        val payload = NotificationTestHooks.lastRecordedTokenRegistration(context)?.toJson() ?: ""
        val encoded = Base64.encodeToString(
            payload.toByteArray(StandardCharsets.UTF_8),
            Base64.NO_WRAP
        )
        setResultData(encoded)
    }

    private fun handleGetActiveNotification(context: Context, intent: Intent) {
        val present = NotificationTestHooks.hasActiveNotification(
            context,
            intent.getStringExtra(EXTRA_EXPECTED_TITLE),
            intent.getStringExtra(EXTRA_EXPECTED_BODY)
        )
        setResultData(if (present) "present" else "missing")
    }

    private fun handleSimulateTokenRefresh(context: Context, intent: Intent) {
        val token = intent.getStringExtra(EXTRA_REFRESH_TOKEN)
        if (token.isNullOrBlank()) {
            Log.w(TAG, "Ignoring token-refresh simulation without a token")
            setResultData("missing-token")
            return
        }

        GroovitationMessagingService.handleTokenRefresh(
            context = context.applicationContext,
            token = token
        )
        setResultData("refreshed")
    }

    private fun handleTapActiveNotification(context: Context, intent: Intent) {
        val result = NotificationTestHooks.tapActiveNotification(
            context,
            intent.getStringExtra(EXTRA_EXPECTED_TITLE),
            intent.getStringExtra(EXTRA_EXPECTED_BODY)
        )
        setResultData(result)
    }

    private fun resetAppState(context: Context) {
        NotificationTestHooks.clear(context)
        TokenStorage.fcmToken = null
        context.getSharedPreferences(ACTIVITY_PREFS, Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
        setSessionCookie(context, null)
        val notificationManager = context.getSystemService(NotificationManager::class.java)
        notificationManager?.cancelAll()
    }

    private fun setSessionCookie(context: Context, cookieValue: String?) {
        val cookieManager = CookieManager.getInstance()
        cookieManager.setAcceptCookie(true)
        if (cookieValue.isNullOrBlank()) {
            cookieManager.setCookie(
                BuildConfig.BASE_URL,
                "_user_interface_session=; Max-Age=0; path=/"
            )
        } else {
            cookieManager.setCookie(
                BuildConfig.BASE_URL,
                "_user_interface_session=$cookieValue; path=/"
            )
        }
        cookieManager.flush()
    }
}
