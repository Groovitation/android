package io.blaha.groovitation

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.view.View
import android.webkit.HttpAuthHandler
import android.webkit.JavascriptInterface
import androidx.core.app.NotificationManagerCompat
import dev.hotwire.core.turbo.webview.HotwireWebView
import dev.hotwire.navigation.destinations.HotwireDestinationDeepLink
import dev.hotwire.navigation.fragments.HotwireWebFragment

/**
 * Custom WebFragment that handles HTTP Basic Authentication for groovitation.blaha.io
 * and bridges personId from the web app to the native app for background tracking.
 */
@HotwireDestinationDeepLink(uri = "hotwire://fragment/web")
class GroovitationWebFragment : HotwireWebFragment() {

    companion object {
        private const val TAG = "GroovitationWebFragment"
        private const val AUTH_USERNAME = "groovitation"
        private const val AUTH_PASSWORD = "aldoofra"
        private const val NOTIFICATION_PERMISSION_EVENT = "groovitation:notification-permission"
    }

    private var attachedWebView: HotwireWebView? = null

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        Log.d(TAG, "GroovitationWebFragment onViewCreated")
    }

    override fun onWebViewAttached(webView: HotwireWebView) {
        super.onWebViewAttached(webView)
        attachedWebView = webView
        webView.addJavascriptInterface(GroovitationNativeInterface(), "GroovitationNative")
        (activity as? MainActivity)?.registerWebFragment(this)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        (activity as? MainActivity)?.unregisterWebFragment(this)
        attachedWebView = null
    }

    override fun onReceivedHttpAuthRequest(
        handler: HttpAuthHandler,
        host: String,
        realm: String
    ) {
        Log.d(TAG, "Received HTTP auth request for host: $host, realm: $realm")

        if (host.contains("groovitation.blaha.io")) {
            Log.d(TAG, "Providing credentials for $host")
            handler.proceed(AUTH_USERNAME, AUTH_PASSWORD)
        } else {
            Log.w(TAG, "Unknown host requesting auth: $host")
            super.onReceivedHttpAuthRequest(handler, host, realm)
        }
    }

    private inner class GroovitationNativeInterface {
        @JavascriptInterface
        fun setPersonId(personId: String) {
            Log.d(TAG, "Received personId from web: $personId")
            if (personId.isBlank()) return

            activity?.runOnUiThread {
                (activity as? MainActivity)?.onPersonIdReceived(personId)
            }
        }

        @JavascriptInterface
        fun hasNotificationPermission(): Boolean {
            val mainActivity = activity as? MainActivity ?: return false
            val enabled = NotificationManagerCompat.from(mainActivity).areNotificationsEnabled()
            return if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                val granted = androidx.core.content.ContextCompat.checkSelfPermission(
                    mainActivity,
                    android.Manifest.permission.POST_NOTIFICATIONS
                ) == android.content.pm.PackageManager.PERMISSION_GRANTED
                granted && enabled
            } else {
                enabled
            }
        }

        @JavascriptInterface
        fun requestNotificationPermission() {
            val mainActivity = activity as? MainActivity ?: return
            mainActivity.requestNotificationPermissionFromWeb()
        }

        @JavascriptInterface
        fun openNotificationSettings() {
            activity?.let { act ->
                val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                    putExtra(Settings.EXTRA_APP_PACKAGE, act.packageName)
                }
                act.startActivity(intent)
            }
        }

        @JavascriptInterface
        fun setBottomNavVisible(visible: Boolean) {
            activity?.runOnUiThread {
                (activity as? MainActivity)?.setBottomNavVisible(visible)
            }
        }
    }

    fun dispatchNotificationPermissionState(state: String) {
        val normalizedState = when (state) {
            "granted", "denied", "prompt" -> state
            else -> "denied"
        }
        val script = "window.dispatchEvent(new CustomEvent('$NOTIFICATION_PERMISSION_EVENT'," +
            " { detail: { state: '$normalizedState' } }));"
        val webView = attachedWebView ?: return
        webView.post {
            webView.evaluateJavascript(script, null)
        }
    }
}
