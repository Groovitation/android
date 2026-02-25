package io.blaha.groovitation.services

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.CookieManager
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
 * Aggressive foreground GPS acquisition on app open/resume.
 *
 * Posts location directly to the server (bypassing WebView) with
 * source="foreground-gps" so the backend gives it strong scoring preference.
 * Also dispatches fixes to the WebView for map UI updates.
 */
class ForegroundLocationManager(private val context: Context) {

    companion object {
        private const val TAG = "ForegroundLocationMgr"
        private const val PREFS_NAME = "location_tracking_prefs"
    }

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    private val scope = CoroutineScope(Dispatchers.IO)
    private var activeCallback: LocationCallback? = null

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
                Log.d(TAG, "Foreground fix #$updateCount: ${location.latitude}, ${location.longitude} (${location.accuracy}m)")

                if (bestLocation == null || location.accuracy < bestLocation!!.accuracy) {
                    bestLocation = location
                }

                // Dispatch every fix to WebView for map updates
                webDispatcher?.invoke(location)

                // Post to server when we get a good fix (<50m) or after 5 updates
                if (!serverPostSent && (location.accuracy < 50 || updateCount >= 5)) {
                    serverPostSent = true
                    val best = bestLocation!!
                    Log.d(TAG, "Posting foreground-gps to server: ${best.latitude}, ${best.longitude} (${best.accuracy}m)")
                    postToServer(personUuid, best)
                }

                // Stop when accuracy is good or max updates reached
                if (location.accuracy < 30 || updateCount >= 15) {
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

    private fun postToServer(personUuid: String, location: Location) {
        val cookie = CookieManager.getInstance().getCookie(BuildConfig.BASE_URL)
            ?: context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getString(LocationTrackingService.KEY_SESSION_COOKIE, null)
            ?: run {
                Log.w(TAG, "No session cookie available, skipping server post")
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
                    put("timestamp", System.currentTimeMillis())
                }

                val request = Request.Builder()
                    .url("${BuildConfig.BASE_URL}/people/$personUuid/location")
                    .post(json.toString().toRequestBody("application/json".toMediaType()))
                    .addHeader("Content-Type", "application/json")
                    .addHeader("Cookie", cookie)
                    .build()

                val response = httpClient.newCall(request).execute()
                if (response.isSuccessful) {
                    Log.d(TAG, "Foreground GPS posted successfully")
                } else {
                    Log.w(TAG, "Failed to post foreground GPS: ${response.code}")
                }
                response.close()
            } catch (e: Exception) {
                Log.e(TAG, "Error posting foreground GPS", e)
            }
        }
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
