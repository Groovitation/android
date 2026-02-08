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

        /**
         * Request fresh GPS location. The result will be dispatched as a
         * 'groovitation:location' CustomEvent on window with {latitude, longitude, accuracy}.
         * This bypasses WebView's cached geolocation.
         */
        @JavascriptInterface
        fun requestFreshLocation() {
            Log.d(TAG, "requestFreshLocation called from JavaScript")
            val mainActivity = activity as? MainActivity ?: return
            val context = mainActivity.applicationContext
            
            val fusedClient = com.google.android.gms.location.LocationServices
                .getFusedLocationProviderClient(context)
            
            val locationRequest = com.google.android.gms.location.LocationRequest.Builder(
                com.google.android.gms.location.Priority.PRIORITY_HIGH_ACCURACY,
                1000L
            )
                .setMaxUpdates(5)
                .setDurationMillis(10000L)
                .build()
            
            var bestLocation: android.location.Location? = null
            var updateCount = 0
            
            val callback = object : com.google.android.gms.location.LocationCallback() {
                override fun onLocationResult(result: com.google.android.gms.location.LocationResult) {
                    val location = result.lastLocation ?: return
                    updateCount++
                    Log.d(TAG, "Fresh location #$updateCount: ${location.latitude}, ${location.longitude} (${location.accuracy}m)")
                    
                    if (bestLocation == null || location.accuracy < bestLocation!!.accuracy) {
                        bestLocation = location
                    }
                    
                    // Accept if good accuracy or enough samples
                    if (location.accuracy < 50 || updateCount >= 5) {
                        fusedClient.removeLocationUpdates(this)
                        dispatchLocationToWeb(bestLocation!!)
                    }
                }
            }
            
            try {
                fusedClient.requestLocationUpdates(
                    locationRequest,
                    callback,
                    android.os.Looper.getMainLooper()
                )
                
                // Timeout after 12 seconds
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                    fusedClient.removeLocationUpdates(callback)
                    val loc = bestLocation
                    if (loc != null) {
                        dispatchLocationToWeb(loc)
                    } else {
                        dispatchLocationError("Could not get location")
                    }
                }, 12000L)
            } catch (e: SecurityException) {
                Log.e(TAG, "Location permission denied", e)
                dispatchLocationError("Location permission denied")
            }
        }
        
        private fun dispatchLocationToWeb(location: android.location.Location) {
            val script = """
                window.dispatchEvent(new CustomEvent('groovitation:location', {
                    detail: {
                        success: true,
                        latitude: ${location.latitude},
                        longitude: ${location.longitude},
                        accuracy: ${location.accuracy},
                        altitude: ${if (location.hasAltitude()) location.altitude else "null"}
                    }
                }));
            """.trimIndent()
            
            activity?.runOnUiThread {
                attachedWebView?.evaluateJavascript(script, null)
            }
        }
        
        private fun dispatchLocationError(error: String) {
            val script = """
                window.dispatchEvent(new CustomEvent('groovitation:location', {
                    detail: { success: false, error: '$error' }
                }));
            """.trimIndent()
            
            activity?.runOnUiThread {
                attachedWebView?.evaluateJavascript(script, null)
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
