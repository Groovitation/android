package io.blaha.groovitation.services

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.util.Log
import android.webkit.CookieManager
import io.blaha.groovitation.BuildConfig

internal data class ResolvedSessionCookie(
    val header: String,
    val source: String,
    val webViewCookieSummary: String
)

/**
 * Transition shim for upgrading from foreground service to geofence-based tracking.
 *
 * On ACTION_START: enqueues WorkManager periodic task and stops self (no foreground service).
 * On ACTION_STOP: cancels WorkManager and removes geofences.
 *
 * Keeps companion object static helpers (saveConfig, refreshCookie, etc.) unchanged
 * since they write to SharedPreferences used by LocationWorker and GeofenceManager.
 *
 * Remove this service in a follow-up release once the foreground service is no longer
 * referenced by any installed version.
 */
class LocationTrackingService : Service() {

    companion object {
        private const val TAG = "LocationTrackingService"
        private const val PREFS_NAME = "location_tracking_prefs"
        private const val KEY_PERSON_UUID = "person_uuid"
        const val KEY_SESSION_COOKIE = "session_cookie"
        private const val KEY_ENABLED = "tracking_enabled"
        private const val SESSION_COOKIE_NAME = "_user_interface_session"

        const val ACTION_START = "io.blaha.groovitation.START_LOCATION_TRACKING"
        const val ACTION_STOP = "io.blaha.groovitation.STOP_LOCATION_TRACKING"
        const val EXTRA_PERSON_UUID = "person_uuid"

        fun isEnabled(context: Context): Boolean {
            return context.getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                .getBoolean(KEY_ENABLED, false)
        }

        fun getPersonUuid(context: Context): String? {
            return context.getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                .getString(KEY_PERSON_UUID, null)
        }

        fun saveConfig(context: Context, personUuid: String) {
            context.getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
                .putString(KEY_PERSON_UUID, personUuid)
                .putBoolean(KEY_ENABLED, true)
                .apply()

            refreshCookie(context)
            Log.d(TAG, "Config saved for person $personUuid")
        }

        fun refreshCookie(context: Context) {
            val prefs = context.getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            if (!prefs.getBoolean(KEY_ENABLED, false)) return

            if (refreshStoredSessionCookie(context, currentWebViewCookie(TAG), TAG)) {
                Log.d(TAG, "Session cookie refreshed")
            }
        }

        fun storeSessionCookie(
            context: Context,
            cookieHeader: String,
            callerTag: String = TAG
        ): Boolean {
            val stored = refreshStoredSessionCookie(context, cookieHeader, callerTag)
            if (stored) {
                Log.d(callerTag, "Stored authenticated session cookie from JS bridge")
            }
            return stored
        }

        internal fun resolveSessionCookie(context: Context, callerTag: String): ResolvedSessionCookie? {
            val prefs = context.getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            val webViewCookie = currentWebViewCookie(callerTag)
            val storedSessionCookie = extractSessionCookie(
                prefs.getString(KEY_SESSION_COOKIE, null)
            )

            val resolved = resolveSessionCookieFromSources(webViewCookie, storedSessionCookie)
            if (resolved == null) {
                Log.w(
                    callerTag,
                    "No authenticated session cookie available. webViewCookies=${describeCookieNames(webViewCookie)}"
                )
                return null
            }

            if (resolved.source != "webview-session") {
                Log.w(
                    callerTag,
                    "WebView cookie missing authenticated session cookie; using ${resolved.source}. " +
                        "webViewCookies=${resolved.webViewCookieSummary}"
                )
            }

            val liveSessionCookie = extractSessionCookie(webViewCookie)
            if (liveSessionCookie != null && liveSessionCookie != storedSessionCookie) {
                prefs.edit().putString(KEY_SESSION_COOKIE, liveSessionCookie).apply()
                Log.d(callerTag, "Stored authenticated session cookie refreshed from WebView")
            }

            return resolved
        }

        internal fun refreshStoredSessionCookie(
            context: Context,
            cookieHeader: String?,
            callerTag: String = TAG
        ): Boolean {
            val prefs = context.getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            val sessionCookie = extractSessionCookie(cookieHeader)
            if (sessionCookie != null) {
                prefs.edit().putString(KEY_SESSION_COOKIE, sessionCookie).apply()
                return true
            }

            val storedSessionCookie = extractSessionCookie(prefs.getString(KEY_SESSION_COOKIE, null))
            val message = if (storedSessionCookie != null) {
                "WebView cookie missing authenticated session cookie; keeping stored fallback. " +
                    "webViewCookies=${describeCookieNames(cookieHeader)}"
            } else {
                "WebView cookie missing authenticated session cookie. " +
                    "webViewCookies=${describeCookieNames(cookieHeader)}"
            }
            Log.w(callerTag, message)
            return false
        }

        internal fun resolveSessionCookieFromSources(
            webViewCookie: String?,
            storedSessionCookie: String?
        ): ResolvedSessionCookie? {
            val normalizedStoredSessionCookie = extractSessionCookie(storedSessionCookie)
            val webViewSessionCookie = extractSessionCookie(webViewCookie)
            val webViewCookieSummary = describeCookieNames(webViewCookie)

            return when {
                webViewSessionCookie != null -> ResolvedSessionCookie(
                    header = mergeCookieHeaders(webViewCookie, webViewSessionCookie),
                    source = "webview-session",
                    webViewCookieSummary = webViewCookieSummary
                )

                normalizedStoredSessionCookie != null -> ResolvedSessionCookie(
                    header = mergeCookieHeaders(webViewCookie, normalizedStoredSessionCookie),
                    source = if (parseCookiePairs(webViewCookie).isEmpty()) {
                        "stored-session-fallback"
                    } else {
                        "merged-stored-session-fallback"
                    },
                    webViewCookieSummary = webViewCookieSummary
                )

                else -> null
            }
        }

        internal fun extractSessionCookie(cookieHeader: String?): String? {
            return parseCookiePairs(cookieHeader)[SESSION_COOKIE_NAME]
        }

        internal fun describeCookieNames(cookieHeader: String?): String {
            val names = parseCookiePairs(cookieHeader).keys
            return if (names.isEmpty()) "none" else names.joinToString(prefix = "[", postfix = "]")
        }

        private fun currentWebViewCookie(callerTag: String): String? {
            return try {
                CookieManager.getInstance().getCookie(BuildConfig.BASE_URL)
            } catch (e: Exception) {
                Log.w(callerTag, "Failed to read WebView cookies", e)
                null
            }
        }

        private fun mergeCookieHeaders(
            cookieHeader: String?,
            sessionCookie: String
        ): String {
            val merged = parseCookiePairs(cookieHeader)
            merged[SESSION_COOKIE_NAME] = sessionCookie
            return merged.values.joinToString("; ")
        }

        private fun parseCookiePairs(cookieHeader: String?): LinkedHashMap<String, String> {
            val cookies = LinkedHashMap<String, String>()
            cookieHeader
                ?.split(";")
                ?.asSequence()
                ?.map { it.trim() }
                ?.filter { it.isNotEmpty() }
                ?.forEach { pair ->
                    val equalsIndex = pair.indexOf('=')
                    if (equalsIndex <= 0 || equalsIndex == pair.lastIndex) {
                        return@forEach
                    }

                    val name = pair.substring(0, equalsIndex).trim()
                    val value = pair.substring(equalsIndex + 1).trim()
                    if (name.isNotEmpty() && value.isNotEmpty()) {
                        cookies[name] = "$name=$value"
                    }
                }
            return cookies
        }

        fun clearConfig(context: Context) {
            context.getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
                .clear()
                .apply()
        }

        /**
         * Formerly started the foreground service. Now enqueues WorkManager instead.
         * Kept for backward compatibility with BootReceiver and other callers.
         */
        fun startIfEnabled(context: Context) {
            val prefs = context.getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            if (!prefs.getBoolean(KEY_ENABLED, false)) return
            prefs.getString(KEY_PERSON_UUID, null) ?: return

            LocationWorker.enqueuePeriodicWork(context)
            LocationWorker.enqueueOneShot(context)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "onStartCommand (transition shim): ${intent?.action}")

        when (intent?.action) {
            ACTION_STOP -> {
                LocationWorker.cancel(this)
                GeofenceManager(this).removeAllGeofences()
                clearConfig(this)
            }
            ACTION_START, null -> {
                // Transition: enqueue WorkManager instead of starting foreground service
                LocationWorker.enqueuePeriodicWork(this)
                LocationWorker.enqueueOneShot(this)
            }
        }

        stopSelf()
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
