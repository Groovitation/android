package io.blaha.groovitation

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.view.View
import android.webkit.HttpAuthHandler
import android.webkit.JavascriptInterface
import androidx.browser.customtabs.CustomTabsIntent
import androidx.core.app.NotificationManagerCompat
import dev.hotwire.core.turbo.errors.VisitError
import dev.hotwire.core.turbo.webview.HotwireWebView
import dev.hotwire.navigation.destinations.HotwireDestinationDeepLink
import dev.hotwire.navigation.fragments.HotwireWebFragment
import org.json.JSONObject

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
        private const val LOCATION_PERMISSION_EVENT = "groovitation:location-permission"
    }

    private var attachedWebView: HotwireWebView? = null
    private var hasSuccessfulVisit = false
    private var coldBootRetryCount = 0
    private var styleRecoveryRetryCount = 0

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        Log.d(TAG, "GroovitationWebFragment onViewCreated")
    }

    override fun onColdBootPageCompleted(location: String) {
        super.onColdBootPageCompleted(location)
        hasSuccessfulVisit = true
        installNativeAppBridgeShim()
        verifyStylesheetLoadAndRecover(location)
    }

    override fun onVisitCompleted(location: String, completedOffline: Boolean) {
        super.onVisitCompleted(location, completedOffline)
        hasSuccessfulVisit = true
        installNativeAppBridgeShim()
        verifyStylesheetLoadAndRecover(location)
    }

    override fun onVisitErrorReceived(location: String, error: VisitError) {
        // On cold start from a stopped state, the first page load can fail because
        // Hotwire's onReceivedHttpError fires for the nginx 401 Basic Auth challenge
        // before onReceivedHttpAuthRequest can provide credentials. The WebView handles
        // the auth retry, but Hotwire has already reset the session and shown the error
        // view. Auto-retry: by the second attempt, auth credentials are in WebView's
        // memory cache and the request succeeds without a 401.
        if (!hasSuccessfulVisit && coldBootRetryCount < 2) {
            coldBootRetryCount++
            Log.d(TAG, "Cold boot visit failed ($error), retry #$coldBootRetryCount: $location")
            view?.postDelayed({ refresh(true) }, 500)
            return
        }
        super.onVisitErrorReceived(location, error)
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
        styleRecoveryRetryCount = 0
    }

    private fun verifyStylesheetLoadAndRecover(location: String) {
        val webView = attachedWebView ?: return

        val script = """
            (function() {
              try {
                var sheetHrefs = Array.prototype.map.call(
                  document.styleSheets || [],
                  function(sheet) { return sheet && sheet.href ? String(sheet.href) : ''; }
                );
                var hasCoreCss = sheetHrefs.some(function(href) {
                  return href.indexOf('/assets/application.css') !== -1 ||
                         href.indexOf('/bootstrap.min.css') !== -1 ||
                         href.indexOf('/bootstrap.rtl.min.css') !== -1;
                });
                var linkCount = document.querySelectorAll('link[rel="stylesheet"]').length;
                return JSON.stringify({
                  readyState: document.readyState,
                  hasCoreCss: hasCoreCss,
                  stylesheetCount: sheetHrefs.length,
                  linkCount: linkCount,
                  path: location.pathname
                });
              } catch (e) {
                return JSON.stringify({
                  readyState: document.readyState,
                  hasCoreCss: false,
                  stylesheetCount: 0,
                  linkCount: 0,
                  path: location.pathname,
                  error: String(e)
                });
              }
            })();
        """.trimIndent()

        webView.post {
            webView.evaluateJavascript(script) { rawResult ->
                val decodedJson = decodeJsString(rawResult) ?: return@evaluateJavascript
                val payload = runCatching { JSONObject(decodedJson) }.getOrNull() ?: return@evaluateJavascript
                val hasCoreCss = payload.optBoolean("hasCoreCss", false)
                val readyState = payload.optString("readyState", "")
                val stylesheetCount = payload.optInt("stylesheetCount", 0)
                val linkCount = payload.optInt("linkCount", 0)
                val path = payload.optString("path", location)

                if (hasCoreCss) {
                    styleRecoveryRetryCount = 0
                    return@evaluateJavascript
                }

                // Only self-heal when the DOM is ready and styles were expected but missing.
                if (readyState == "complete" && linkCount > 0 && styleRecoveryRetryCount < 1) {
                    styleRecoveryRetryCount++
                    Log.w(
                        TAG,
                        "Detected missing core CSS on $path " +
                            "(stylesheets=$stylesheetCount links=$linkCount). " +
                            "Auto-refreshing once to recover stylesheet load."
                    )
                    view?.postDelayed({ refresh(true) }, 250)
                }
            }
        }
    }

    private fun installNativeAppBridgeShim() {
        val webView = attachedWebView ?: return
        val script = """
            (function() {
              try {
                if (window.NativeApp && typeof window.NativeApp.postMessage === 'function') return;
                window.NativeApp = window.NativeApp || {};
                window.NativeApp.postMessage = function(rawMessage) {
                  try {
                    if (!window.nativeBridge || typeof window.nativeBridge.receive !== 'function') return false;
                    var message = (typeof rawMessage === 'string') ? JSON.parse(rawMessage) : (rawMessage || {});
                    if (!message.id) {
                      var component = String(message.component || 'bridge');
                      var event = String(message.event || 'event');
                      message.id = [component, event, Date.now().toString(36), Math.random().toString(36).slice(2, 10)].join('-');
                    }
                    if (typeof window.nativeBridge.supportsComponent === 'function' &&
                        !window.nativeBridge.supportsComponent(message.component)) {
                      return false;
                    }
                    window.nativeBridge.receive(message);
                    return true;
                  } catch (err) {
                    console.error('NativeApp shim postMessage failed', err);
                    return false;
                  }
                };
              } catch (err) {
                console.error('NativeApp shim install failed', err);
              }
            })();
        """.trimIndent()

        webView.post {
            webView.evaluateJavascript(script, null)
        }
    }

    private fun decodeJsString(rawResult: String?): String? {
        val raw = rawResult?.trim() ?: return null
        if (raw == "null" || raw.isEmpty()) return null
        return runCatching {
            if (raw.length >= 2 && raw.first() == '"' && raw.last() == '"') {
                JSONObject("{\"v\":$raw}").getString("v")
            } else {
                raw
            }
        }.getOrNull()
    }

    /**
     * Try to close the top-most visible modal in the active page.
     * Returns true when a modal was found and closed, false otherwise.
     */
    fun closeTopWebModalIfOpen(onResult: (Boolean) -> Unit) {
        val webView = attachedWebView
        if (webView == null) {
            onResult(false)
            return
        }

        val script = """
            (function() {
              var selector = '.map-site-modal.is-open, .modal.show';
              var getOpenModals = function() {
                return Array.prototype.slice.call(document.querySelectorAll(selector));
              };

              var before = getOpenModals();
              if (!before.length) return false;

              // First try existing Escape handlers (supports stacked modal semantics).
              document.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true, cancelable: true }));
              var afterEscape = getOpenModals();
              if (afterEscape.length < before.length) return true;

              // Fallback: close the last opened modal in DOM order.
              var top = before[before.length - 1];
              if (!top) return false;

              if (top.classList.contains('show') && window.bootstrap && window.bootstrap.Modal) {
                var instance = window.bootstrap.Modal.getInstance(top) || new window.bootstrap.Modal(top);
                instance.hide();
                return true;
              }

              var closeButton = top.querySelector(
                '[data-bs-dismiss="modal"], [data-event-modal-close], [data-planned-modal-close], .btn-close, .map-site-modal__backdrop'
              );
              if (closeButton) {
                closeButton.click();
                return true;
              }

              top.classList.remove('is-open');
              top.classList.remove('show');
              top.setAttribute('aria-hidden', 'true');
              document.body.classList.remove('map-site-modal-open');
              document.body.classList.remove('modal-open');
              return true;
            })();
        """.trimIndent()

        webView.post {
            webView.evaluateJavascript(script) { raw ->
                val consumed = raw == "true"
                activity?.runOnUiThread { onResult(consumed) } ?: onResult(consumed)
            }
        }
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
        fun hasLocationPermission(): Boolean {
            val mainActivity = activity as? MainActivity ?: return false
            return mainActivity.hasLocationPermission()
        }

        @JavascriptInterface
        fun requestLocationPermission() {
            val mainActivity = activity as? MainActivity ?: return
            activity?.runOnUiThread {
                mainActivity.requestLocationPermissionFromWeb()
            }
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

        @JavascriptInterface
        fun syncBottomNav(path: String) {
            activity?.runOnUiThread {
                (activity as? MainActivity)?.syncBottomNavTab(path)
            }
        }

        @JavascriptInterface
        fun share(title: String, url: String, text: String) {
            Log.d(TAG, "share called from JavaScript: title=$title url=$url")
            activity?.runOnUiThread {
                try {
                    val body = buildString {
                        if (text.isNotBlank()) append(text)
                        if (url.isNotBlank()) {
                            if (isNotEmpty()) append("\n\n")
                            append(url)
                        }
                    }.ifEmpty { title }

                    val sendIntent = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        if (title.isNotBlank()) putExtra(Intent.EXTRA_SUBJECT, title)
                        if (body.isNotBlank()) putExtra(Intent.EXTRA_TEXT, body)
                    }
                    activity?.startActivity(
                        Intent.createChooser(sendIntent, title.ifEmpty { "Share" })
                    )
                } catch (e: Exception) {
                    Log.e(TAG, "Error opening share sheet", e)
                }
            }
        }

        @JavascriptInterface
        fun openInBrowser(url: String) {
            Log.d(TAG, "openInBrowser: $url")
            activity?.let { act ->
                act.runOnUiThread {
                    val customTabsIntent = CustomTabsIntent.Builder()
                        .setShowTitle(true)
                        .build()
                    customTabsIntent.launchUrl(act, Uri.parse(url))
                }
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

            // NOTE: We no longer dispatch the cached lastLocation here. It was causing
            // a stale location feedback loop: old cached GPS coords were dispatched to the
            // web, re-sent to the server by geolocation.js, and recorded with a fresh
            // timestamp — making the server think the user was still at the old location.
            // ForegroundLocationManager now handles aggressive GPS on app resume and posts
            // directly to the server.

            // Request fresh high-accuracy GPS updates.
            // Give GPS 60 seconds to acquire a cold fix — 10s was too short
            // for areas with poor satellite visibility.
            val locationRequest = com.google.android.gms.location.LocationRequest.Builder(
                com.google.android.gms.location.Priority.PRIORITY_HIGH_ACCURACY,
                1500L
            )
                .setMaxUpdates(40)
                .setDurationMillis(60000L)
                .build()

            var bestLocation: android.location.Location? = null
            var dispatchedAny = false
            var updateCount = 0
            var lastDispatchedLocation: android.location.Location? = null
            var lastDispatchMs = 0L

            fun shouldDispatch(location: android.location.Location): Boolean {
                if (!dispatchedAny) return true
                val previous = lastDispatchedLocation ?: return true
                val movedMeters = previous.distanceTo(location)
                val elapsedMs = System.currentTimeMillis() - lastDispatchMs
                val accuracyImproved = location.accuracy + 10f < previous.accuracy
                return movedMeters >= 10f || elapsedMs >= 5000L || accuracyImproved
            }

            val callback = object : com.google.android.gms.location.LocationCallback() {
                override fun onLocationResult(result: com.google.android.gms.location.LocationResult) {
                    val location = result.lastLocation ?: return
                    updateCount++
                    Log.d(TAG, "Fresh location #$updateCount: ${location.latitude}, ${location.longitude} (${location.accuracy}m)")

                    if (bestLocation == null || location.accuracy < bestLocation!!.accuracy) {
                        bestLocation = location
                    }

                    // Stream significant movement/accuracy improvements to WebView
                    if (shouldDispatch(location)) {
                        dispatchedAny = true
                        lastDispatchedLocation = location
                        lastDispatchMs = System.currentTimeMillis()
                        dispatchLocationToWeb(location)
                    }

                    // Stop when we have good accuracy or enough samples
                    if (location.accuracy < 50 || updateCount >= 40) {
                        fusedClient.removeLocationUpdates(this)
                        val best = bestLocation
                        if (best != null) {
                            val shouldDispatchBest = lastDispatchedLocation == null ||
                                best.accuracy + 5f < lastDispatchedLocation!!.accuracy
                            if (shouldDispatchBest) {
                                dispatchLocationToWeb(best)
                            }
                        }
                    }
                }
            }

            try {
                fusedClient.requestLocationUpdates(
                    locationRequest,
                    callback,
                    android.os.Looper.getMainLooper()
                )

                // Timeout after 65 seconds (slightly longer than duration)
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                    fusedClient.removeLocationUpdates(callback)
                    val loc = bestLocation
                    if (loc != null && !dispatchedAny) {
                        dispatchLocationToWeb(loc)
                    } else if (loc == null && !dispatchedAny) {
                        dispatchLocationError("Could not get location after 65s")
                    }
                }, 65000L)
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

    fun dispatchLocationPermissionState(granted: Boolean) {
        val script = "window.dispatchEvent(new CustomEvent('$LOCATION_PERMISSION_EVENT'," +
            " { detail: { granted: $granted } }));"
        val webView = attachedWebView ?: return
        webView.post {
            webView.evaluateJavascript(script, null)
        }
    }

    fun requestFreshLocationOnResume() {
        val webView = attachedWebView ?: return
        val script = """
            (function() {
              if (window.GroovitationNative &&
                  typeof window.GroovitationNative.requestFreshLocation === 'function') {
                window.GroovitationNative.requestFreshLocation();
              }
            })();
        """.trimIndent()
        webView.post {
            webView.evaluateJavascript(script, null)
        }
    }

    fun flushMapVisibilityState() {
        val webView = attachedWebView ?: return
        val script = """
            (function() {
              try {
                if (typeof window.saveHiddenInterests === 'function') {
                  window.saveHiddenInterests();
                  return true;
                }
                return false;
              } catch (e) {
                return false;
              }
            })();
        """.trimIndent()
        webView.post {
            webView.evaluateJavascript(script, null)
        }
    }

    /**
     * Dispatch a native foreground GPS fix to the WebView for map updates.
     * Uses the same 'groovitation:location' event so existing map listeners work,
     * but includes source='foreground-gps' so geolocation.js knows not to re-send
     * it to the server (the native code already posted it directly).
     */
    fun dispatchNativeLocationToWeb(location: android.location.Location) {
        val script = """
            window.dispatchEvent(new CustomEvent('groovitation:location', {
                detail: {
                    success: true,
                    latitude: ${location.latitude},
                    longitude: ${location.longitude},
                    accuracy: ${location.accuracy},
                    altitude: ${if (location.hasAltitude()) location.altitude else "null"},
                    source: 'foreground-gps'
                }
            }));
        """.trimIndent()

        activity?.runOnUiThread {
            attachedWebView?.evaluateJavascript(script, null)
        }
    }
}
