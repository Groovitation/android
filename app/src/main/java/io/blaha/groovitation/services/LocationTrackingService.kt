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

internal data class ResolvedLocationAuth(
    val headerName: String,
    val headerValue: String,
    val source: String,
    val webViewCookieSummary: String? = null
)

/**
 * #772: composite return for [LocationTrackingService.resolveLocationAuthWithDiagnostic].
 *
 * `auth` is null when no auth source is available (the silent-skip branch
 * the structured-outcome work is built to expose). `diagnosticExtras` is
 * always populated with a single-line summary of the inputs the resolver
 * inspected, suitable for appending to a `LocationWorker outcome=...` log
 * line so on-call can grep `webViewCookies=none storedToken=absent` and
 * know exactly which auth source was missing without re-deriving it from
 * a separate Log.w preceding the outcome.
 */
internal data class LocationAuthLookup(
    val auth: ResolvedLocationAuth?,
    val diagnosticExtras: String
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
        const val KEY_LOCATION_TOKEN = "location_token"
        private const val KEY_ENABLED = "tracking_enabled"
        private const val SESSION_COOKIE_NAME = "_user_interface_session"
        const val LOCATION_TOKEN_HEADER_NAME = "X-Groovitation-Location-Token"

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
            if (cookieHeader.isBlank()) {
                clearStoredSessionCookie(context)
                Log.d(callerTag, "Cleared stored authenticated session cookie from JS bridge")
                return false
            }
            val stored = refreshStoredSessionCookie(context, cookieHeader, callerTag)
            if (stored) {
                Log.d(callerTag, "Stored authenticated session cookie from JS bridge")
            }
            return stored
        }

        fun storeLocationToken(
            context: Context,
            token: String,
            callerTag: String = TAG
        ): Boolean {
            val normalizedToken = token.trim()
            val prefs = context.getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            return if (normalizedToken.isEmpty()) {
                prefs.edit().remove(KEY_LOCATION_TOKEN).apply()
                Log.d(callerTag, "Cleared stored native location token from JS bridge")
                false
            } else {
                prefs.edit().putString(KEY_LOCATION_TOKEN, normalizedToken).apply()
                Log.d(callerTag, "Stored native location token from JS bridge")
                true
            }
        }

        internal fun resolveLocationAuth(context: Context, callerTag: String): ResolvedLocationAuth? =
            resolveLocationAuthWithDiagnostic(context, callerTag).auth

        /**
         * #772: variant of [resolveLocationAuth] that also returns a one-line
         * diagnostic summary of the inputs it inspected. The worker uses this
         * to stamp `webViewCookies=... storedToken=... storedSession=...`
         * onto its `LocationWorker outcome=SKIPPED_NO_AUTH` log line so the
         * structured outcome itself carries the why, instead of requiring
         * on-call to correlate it with an adjacent Log.w. The pure-function
         * formatter [buildLocationAuthDiagnostic] is unit-tested separately.
         */
        internal fun resolveLocationAuthWithDiagnostic(
            context: Context,
            callerTag: String
        ): LocationAuthLookup {
            val prefs = context.getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            val webViewCookie = currentWebViewCookie(callerTag)
            val storedSessionCookieRaw = prefs.getString(KEY_SESSION_COOKIE, null)
            val storedSessionCookie = extractSessionCookie(storedSessionCookieRaw)
            val storedLocationToken = prefs.getString(KEY_LOCATION_TOKEN, null)

            val resolved = resolveLocationAuthFromSources(
                storedLocationToken = storedLocationToken,
                webViewCookie = webViewCookie,
                storedSessionCookie = storedSessionCookie
            )
            val diagnostic = buildLocationAuthDiagnostic(
                webViewCookie = webViewCookie,
                storedLocationToken = storedLocationToken,
                storedSessionCookie = storedSessionCookieRaw
            )
            if (resolved == null) {
                Log.w(callerTag, "No location auth available. $diagnostic")
            }
            return LocationAuthLookup(auth = resolved, diagnosticExtras = diagnostic)
        }

        /**
         * #772: pure-function formatter for the SKIPPED_NO_AUTH (and
         * adjacent) diagnostic line. Output shape:
         * `webViewCookies=[name1, name2] storedToken=present storedSession=absent`
         * — keys are stable so prod logs can be grepped/aggregated. `[ ]`
         * brackets and the comma-space separator inside the cookie list
         * mirror the existing [describeCookieNames] format already used by
         * adjacent Log.w lines, so on-call sees a uniform shape.
         */
        internal fun buildLocationAuthDiagnostic(
            webViewCookie: String?,
            storedLocationToken: String?,
            storedSessionCookie: String?
        ): String {
            val webViewSummary = describeCookieNames(webViewCookie)
            val tokenState = if (storedLocationToken?.trim()?.isNotEmpty() == true) "present" else "absent"
            val sessionState =
                if (extractSessionCookie(storedSessionCookie) != null) "present" else "absent"
            return "webViewCookies=$webViewSummary storedToken=$tokenState storedSession=$sessionState"
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

        internal fun resolveLocationAuthFromSources(
            storedLocationToken: String?,
            webViewCookie: String?,
            storedSessionCookie: String?
        ): ResolvedLocationAuth? {
            val normalizedToken = storedLocationToken?.trim()?.takeIf { it.isNotEmpty() }
            if (normalizedToken != null) {
                return ResolvedLocationAuth(
                    headerName = LOCATION_TOKEN_HEADER_NAME,
                    headerValue = normalizedToken,
                    source = "stored-location-token"
                )
            }

            val resolvedCookie = resolveSessionCookieFromSources(webViewCookie, storedSessionCookie)
                ?: return null

            return ResolvedLocationAuth(
                headerName = "Cookie",
                headerValue = resolvedCookie.header,
                source = "session-cookie:${resolvedCookie.source}",
                webViewCookieSummary = resolvedCookie.webViewCookieSummary
            )
        }

        internal fun extractSessionCookie(cookieHeader: String?): String? {
            return parseCookiePairs(cookieHeader)[SESSION_COOKIE_NAME]
        }

        internal fun describeCookieNames(cookieHeader: String?): String {
            val names = parseCookiePairs(cookieHeader).keys
            return if (names.isEmpty()) "none" else names.joinToString(prefix = "[", postfix = "]")
        }

        private fun clearStoredSessionCookie(context: Context) {
            context.getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                .edit()
                .remove(KEY_SESSION_COOKIE)
                .apply()
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
