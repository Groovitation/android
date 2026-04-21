package io.blaha.groovitation.services

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import androidx.core.content.ContextCompat
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import io.blaha.groovitation.BuildConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Native foreground GPS acquisition.
 *
 * Two modes:
 *  - `requestForegroundFix` (legacy): 30-second aggressive one-shot on app
 *    open, used to snap the stored position at resume time.
 *  - `startContinuousTracking` (#706): sustained tracking while the app is
 *    in the foreground (regardless of which tab is showing), so the landing
 *    page sees fresh positions instead of going stale while the user moves.
 *
 * Posts location directly to the server (bypassing WebView) with
 * source="foreground-gps" so the backend gives it strong scoring preference.
 * Also dispatches fixes to the WebView so web code listening for native
 * location bridge events can re-rank/re-distance landing cards live.
 */
class ForegroundLocationManager(private val context: Context) {

    companion object {
        private const val TAG = "ForegroundLocationMgr"
        private const val COOKIE_RETRY_DELAY_MS = 5000L
        private const val FRESH_LOCATION_MAX_AGE_MS = 15_000L

        // Throttle: post to server on first fix, then only when the user has
        // moved this far OR this much time has passed. Keeps the landing-page
        // distance honest without hammering the per-user location endpoint.
        internal const val CONTINUOUS_MIN_POST_INTERVAL_MS = 60_000L
        internal const val CONTINUOUS_MIN_POST_DISTANCE_M = 50.0

        internal fun retryDelayMsForMissingAuth(resolvedAuth: ResolvedLocationAuth?): Long? {
            return if (resolvedAuth == null) COOKIE_RETRY_DELAY_MS else null
        }

        internal fun locationAgeMs(
            location: Location,
            nowMs: Long = System.currentTimeMillis(),
            nowElapsedRealtimeNanos: Long = SystemClock.elapsedRealtimeNanos()
        ): Long {
            val elapsedRealtimeNanos = location.elapsedRealtimeNanos
            if (elapsedRealtimeNanos > 0L) {
                val ageNanos = nowElapsedRealtimeNanos - elapsedRealtimeNanos
                if (ageNanos >= 0L) {
                    return TimeUnit.NANOSECONDS.toMillis(ageNanos)
                }
            }

            val ageMs = nowMs - location.time
            return if (ageMs >= 0L) ageMs else Long.MAX_VALUE
        }

        internal fun isFreshEnoughForImmediateUse(
            location: Location,
            nowMs: Long = System.currentTimeMillis(),
            nowElapsedRealtimeNanos: Long = SystemClock.elapsedRealtimeNanos()
        ): Boolean =
            locationAgeMs(location, nowMs, nowElapsedRealtimeNanos) <= FRESH_LOCATION_MAX_AGE_MS

        /**
         * Pure decision function for the continuous-mode throttle so the
         * policy can be unit-tested without the fused provider.
         */
        internal fun shouldPostContinuous(
            candidate: Location,
            lastPosted: Location?,
            lastPostedAtMs: Long,
            nowMs: Long
        ): Boolean {
            if (lastPosted == null) return true
            if (nowMs - lastPostedAtMs >= CONTINUOUS_MIN_POST_INTERVAL_MS) return true
            return candidate.distanceTo(lastPosted) >= CONTINUOUS_MIN_POST_DISTANCE_M
        }
    }

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    private val scope = CoroutineScope(Dispatchers.IO)
    private var activeCallback: LocationCallback? = null
    private var continuousCallback: LocationCallback? = null
    private var lastContinuousPosted: Location? = null
    private var lastContinuousPostedAtMs: Long = 0L

    /**
     * Request aggressive high-accuracy GPS fix.
     * Posts directly to server with source="foreground-gps".
     * Calls webDispatcher with each fix for map UI updates.
     */
    fun requestForegroundFix(webDispatcher: ((Location) -> Unit)? = null) {
        if (!hasLocationPermission()) {
            Log.d(TAG, "No location permission, skipping foreground fix")
            return
        }

        val personUuid = LocationTrackingService.getPersonUuid(context)
        if (personUuid == null) {
            Log.d(TAG, "No personUuid configured, skipping foreground fix")
            return
        }

        val fusedClient = LocationServices.getFusedLocationProviderClient(context)

        // Cancel any existing foreground request
        activeCallback?.let {
            fusedClient.removeLocationUpdates(it)
            activeCallback = null
        }

        val locationRequest = LocationRequest.Builder(
            Priority.PRIORITY_HIGH_ACCURACY,
            2000L  // Every 2 seconds
        )
            .setMaxUpdates(15)
            .setDurationMillis(30000L)  // 30 seconds
            .build()

        var bestLocation: Location? = null
        var updateCount = 0
        var serverPostSent = false

        val callback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                val location = result.lastLocation ?: return
                updateCount++
                val ageMs = locationAgeMs(location)
                Log.d(
                    TAG,
                    "Foreground fix #$updateCount: ${location.latitude}, ${location.longitude} " +
                        "(${location.accuracy}m, age=${ageMs}ms)"
                )

                val isFreshFix = isFreshEnoughForImmediateUse(location)
                if (isFreshFix) {
                    if (bestLocation == null || location.accuracy < bestLocation!!.accuracy) {
                        bestLocation = location
                    }

                    // Only dispatch fresh native fixes into the WebView. Cached stale
                    // fused-provider points are what make the app appear "stuck at home"
                    // until a later open.
                    webDispatcher?.invoke(location)
                } else {
                    Log.d(TAG, "Skipping stale foreground fix for immediate use (${ageMs}ms old)")
                }

                // Post to server when we get a good fix (<50m) or after 5 updates
                if (!serverPostSent && bestLocation != null && (bestLocation!!.accuracy < 50 || updateCount >= 5)) {
                    serverPostSent = true
                    val best = bestLocation!!
                    Log.d(TAG, "Posting foreground-gps to server: ${best.latitude}, ${best.longitude} (${best.accuracy}m)")
                    postToServer(personUuid, best)
                }

                // Stop when accuracy is good or max updates reached
                if ((bestLocation?.accuracy ?: Float.MAX_VALUE) < 30 || updateCount >= 15) {
                    fusedClient.removeLocationUpdates(this)
                    activeCallback = null
                    if (!serverPostSent) {
                        serverPostSent = true
                        bestLocation?.let { postToServer(personUuid, it) }
                    }
                }
            }
        }

        activeCallback = callback

        try {
            fusedClient.requestLocationUpdates(
                locationRequest,
                callback,
                Looper.getMainLooper()
            )
            Log.d(TAG, "Foreground GPS request started (30s, up to 15 updates)")
        } catch (e: SecurityException) {
            Log.e(TAG, "Location permission denied", e)
            return
        }

        // Timeout cleanup after 35 seconds
        Handler(Looper.getMainLooper()).postDelayed({
            if (activeCallback === callback) {
                fusedClient.removeLocationUpdates(callback)
                activeCallback = null
                if (!serverPostSent) {
                    bestLocation?.let {
                        Log.d(TAG, "Timeout — posting best foreground-gps: ${it.latitude}, ${it.longitude} (${it.accuracy}m)")
                        postToServer(personUuid, it)
                    } ?: Log.w(TAG, "Timeout — no foreground GPS fix received")
                }
            }
        }, 35000L)
    }

    fun postRecentForegroundLocationIfFresh(location: Location?): Boolean {
        val freshLocation = location?.takeIf { isFreshEnoughForImmediateUse(it) } ?: return false
        val personUuid = LocationTrackingService.getPersonUuid(context) ?: return false
        Log.d(
            TAG,
            "Replaying recent foreground-gps after auth sync: " +
                "${freshLocation.latitude}, ${freshLocation.longitude} (${freshLocation.accuracy}m)"
        )
        postToServer(personUuid, freshLocation)
        return true
    }

    private fun postToServer(personUuid: String, location: Location, attempt: Int = 0) {
        val resolvedAuth = LocationTrackingService.resolveLocationAuth(context, TAG)
        if (resolvedAuth == null) {
            val retryDelayMs =
                if (attempt == 0) retryDelayMsForMissingAuth(resolvedAuth) else null
            if (retryDelayMs != null) {
                Log.w(
                    TAG,
                    "No native location auth available for foreground GPS post; " +
                        "retrying in ${retryDelayMs}ms"
                )
                Handler(Looper.getMainLooper()).postDelayed(
                    { postToServer(personUuid, location, attempt + 1) },
                    retryDelayMs
                )
            } else {
                Log.w(TAG, "No native location auth available, skipping foreground GPS post")
            }
            return
        }

        scope.launch {
            try {
                val json = JSONObject().apply {
                    put("latitude", location.latitude)
                    put("longitude", location.longitude)
                    put("accuracy", location.accuracy.toDouble())
                    if (location.hasAltitude()) put("altitude", location.altitude)
                    put("deviceType", "android")
                    put("source", "foreground-gps")
                    put("deviceId", Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID))
                    put("timestamp", System.currentTimeMillis())
                }

                val request = Request.Builder()
                    .url("${BuildConfig.BASE_URL}/people/$personUuid/location")
                    .post(json.toString().toRequestBody("application/json".toMediaType()))
                    .addHeader("Content-Type", "application/json")
                    .addHeader(resolvedAuth.headerName, resolvedAuth.headerValue)
                    .build()

                httpClient.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        Log.d(TAG, "Foreground GPS posted successfully")
                        // Update the rolling tracking geofence to current position
                        GeofenceManager(context).registerTrackingGeofence(
                            location.latitude, location.longitude
                        )
                    } else {
                        Log.w(
                            TAG,
                            "Failed to post foreground GPS: ${response.code} ${response.message} " +
                                "(authSource=${resolvedAuth.source}, " +
                                "webViewCookies=${resolvedAuth.webViewCookieSummary ?: "n/a"})"
                        )
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error posting foreground GPS", e)
            }
        }
    }

    /**
     * Begin sustained foreground tracking. Safe to call repeatedly — a
     * second call replaces the first request. Runs until
     * `stopContinuousTracking` is called, typically from `onPause`.
     *
     * The server post is throttled; the WebView dispatch is not, so the
     * landing page can re-render every few seconds while the user walks.
     */
    fun startContinuousTracking(webDispatcher: ((Location) -> Unit)? = null) {
        if (!hasLocationPermission()) {
            Log.d(TAG, "No location permission, skipping continuous tracking")
            return
        }
        val personUuid = LocationTrackingService.getPersonUuid(context)
        if (personUuid == null) {
            Log.d(TAG, "No personUuid configured, skipping continuous tracking")
            return
        }

        val fusedClient = LocationServices.getFusedLocationProviderClient(context)

        // Cancel any prior continuous request before installing a new one.
        continuousCallback?.let {
            fusedClient.removeLocationUpdates(it)
            continuousCallback = null
        }

        val request = LocationRequest.Builder(
            Priority.PRIORITY_HIGH_ACCURACY,
            10_000L
        )
            .setMinUpdateIntervalMillis(5_000L)
            .build()

        val callback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                val location = result.lastLocation ?: return
                // Same freshness guard as requestForegroundFix: cached fused-
                // provider points would make the map and landing page look
                // "stuck at home" even after the user has driven away. Skip
                // them entirely until we get a real fix.
                if (!isFreshEnoughForImmediateUse(location)) {
                    val ageMs = locationAgeMs(location)
                    Log.d(TAG, "Continuous: skipping stale fix (${ageMs}ms old)")
                    return
                }
                webDispatcher?.invoke(location)

                val nowMs = System.currentTimeMillis()
                if (shouldPostContinuous(location, lastContinuousPosted, lastContinuousPostedAtMs, nowMs)) {
                    lastContinuousPosted = location
                    lastContinuousPostedAtMs = nowMs
                    postToServer(personUuid, location)
                }
            }
        }
        continuousCallback = callback

        try {
            fusedClient.requestLocationUpdates(request, callback, Looper.getMainLooper())
            Log.d(TAG, "Continuous foreground tracking started")
        } catch (e: SecurityException) {
            Log.e(TAG, "Location permission denied for continuous tracking", e)
            continuousCallback = null
        }
    }

    fun stopContinuousTracking() {
        val callback = continuousCallback ?: return
        val fusedClient = LocationServices.getFusedLocationProviderClient(context)
        fusedClient.removeLocationUpdates(callback)
        continuousCallback = null
        Log.d(TAG, "Continuous foreground tracking stopped")
    }

    private fun hasLocationPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED ||
        ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
    }
}
