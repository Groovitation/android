package io.blaha.groovitation.services

import android.Manifest
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.util.Log
import androidx.core.content.ContextCompat
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofencingClient
import com.google.android.gms.location.GeofencingRequest
import com.google.android.gms.location.LocationServices
import io.blaha.groovitation.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Manages two kinds of geofences:
 *
 * 1. **Tracking geofence** — a single rolling geofence centered on the user's
 *    last known position. When the user exits it, GeofenceBroadcastReceiver
 *    posts the new location and re-registers the tracking geofence at the new
 *    position. This chain keeps location current even in Doze mode.
 *
 * 2. **Interest geofences** — fetched from the server, centered on places
 *    matching user interests. Used for proximity notifications, not tracking.
 *
 * Android limit: 100 geofences per app. Budget: 1 tracking + up to 79 interest.
 */
class GeofenceManager(private val context: Context) {

    companion object {
        private const val TAG = "GeofenceManager"
        private const val PREFS_NAME = "location_tracking_prefs"
        private const val KEY_GEOFENCE_IDS = "registered_geofence_ids"
        private const val KEY_GEOFENCE_METADATA = "geofence_metadata"
        const val KEY_TRACKING_LAT = "tracking_geofence_lat"
        const val KEY_TRACKING_LNG = "tracking_geofence_lng"
        const val TRACKING_GEOFENCE_ID = "tracking_current_position"
        private const val TRACKING_RADIUS_METERS = 200f
        // NEVER_EXPIRE: geofences persist until explicitly removed or device reboot.
        // BootReceiver + LocationWorker time-based refresh handle re-registration.
        private const val GEOFENCE_EXPIRATION_MS = Geofence.NEVER_EXPIRE

        internal fun refreshRemovalIds(interestIds: Set<String>): List<String> =
            interestIds
                .filter { it.isNotBlank() && it != TRACKING_GEOFENCE_ID }
                .distinct()

        internal fun fullResetRemovalIds(interestIds: Set<String>): List<String> =
            (refreshRemovalIds(interestIds) + TRACKING_GEOFENCE_ID).distinct()

        internal fun clearInterestGeofencePrefs(editor: SharedPreferences.Editor) {
            editor
                .remove(KEY_GEOFENCE_IDS)
                .remove(KEY_GEOFENCE_METADATA)
        }

        internal fun clearAllGeofencePrefs(editor: SharedPreferences.Editor) {
            clearInterestGeofencePrefs(editor)
            editor
                .remove(KEY_TRACKING_LAT)
                .remove(KEY_TRACKING_LNG)
        }
    }

    private val geofencingClient: GeofencingClient =
        LocationServices.getGeofencingClient(context)

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private val geofencePendingIntent: PendingIntent by lazy {
        val intent = Intent(context, GeofenceBroadcastReceiver::class.java)
        PendingIntent.getBroadcast(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        )
    }

    /**
     * Register (or re-register) the rolling tracking geofence at the given position.
     * EXIT-only: when the user leaves the 200m radius, the receiver posts the new
     * location and calls this again to keep the chain going.
     */
    fun registerTrackingGeofence(lat: Double, lon: Double) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, "Missing location permission, cannot register tracking geofence")
            return
        }

        // Remove the old tracking geofence first
        geofencingClient.removeGeofences(listOf(TRACKING_GEOFENCE_ID))

        val geofence = Geofence.Builder()
            .setRequestId(TRACKING_GEOFENCE_ID)
            .setCircularRegion(lat, lon, TRACKING_RADIUS_METERS)
            .setExpirationDuration(GEOFENCE_EXPIRATION_MS)
            .setTransitionTypes(Geofence.GEOFENCE_TRANSITION_EXIT)
            .build()

        val request = GeofencingRequest.Builder()
            .setInitialTrigger(0) // No initial trigger — we're already inside
            .addGeofence(geofence)
            .build()

        geofencingClient.addGeofences(request, geofencePendingIntent)
            .addOnSuccessListener {
                Log.d(TAG, "Tracking geofence registered at $lat, $lon (${TRACKING_RADIUS_METERS}m)")
            }
            .addOnFailureListener { e ->
                Log.e(TAG, "Failed to register tracking geofence", e)
            }

        // Persist the position so BootReceiver can re-register after reboot
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putFloat(KEY_TRACKING_LAT, lat.toFloat())
            .putFloat(KEY_TRACKING_LNG, lon.toFloat())
            .apply()
    }

    /**
     * Fetch interest geofences from the server and register them with the Geofencing API.
     */
    suspend fun refreshGeofences(lat: Double, lon: Double) {
        try {
            val geofenceData = fetchGeofencesFromServer(lat, lon)
            if (geofenceData.length() == 0) return

            // Keep the rolling tracking geofence alive while replacing
            // interest geofences, otherwise the background exit-chain dies.
            removeInterestGeofences()

            val geofences = mutableListOf<Geofence>()
            val metadata = JSONObject()

            val geofencesArray = geofenceData.getJSONArray("geofences")
            for (i in 0 until geofencesArray.length()) {
                val gf = geofencesArray.getJSONObject(i)
                val id = gf.getString("id")
                val latitude = gf.getDouble("latitude")
                val longitude = gf.getDouble("longitude")
                val radius = gf.getDouble("radiusMeters").toFloat()
                val placeName = gf.optString("placeName", "")
                val interestName = gf.optString("interestName", "")

                geofences.add(
                    Geofence.Builder()
                        .setRequestId(id)
                        .setCircularRegion(latitude, longitude, radius)
                        .setExpirationDuration(GEOFENCE_EXPIRATION_MS)
                        .setTransitionTypes(
                            Geofence.GEOFENCE_TRANSITION_ENTER or Geofence.GEOFENCE_TRANSITION_EXIT
                        )
                        .build()
                )

                // Cache metadata for the broadcast receiver
                metadata.put(id, JSONObject().apply {
                    put("placeName", placeName)
                    put("interestName", interestName)
                    put("latitude", latitude)
                    put("longitude", longitude)
                })
            }

            if (geofences.isEmpty()) return

            // Save metadata and IDs to SharedPreferences
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val ids = geofences.map { it.requestId }.toSet()
            prefs.edit()
                .putStringSet(KEY_GEOFENCE_IDS, ids)
                .putString(KEY_GEOFENCE_METADATA, metadata.toString())
                .apply()

            // Check permission before registering
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
                Log.w(TAG, "Missing location permission, cannot register geofences")
                return
            }

            val request = GeofencingRequest.Builder()
                .setInitialTrigger(GeofencingRequest.INITIAL_TRIGGER_ENTER)
                .addGeofences(geofences)
                .build()

            geofencingClient.addGeofences(request, geofencePendingIntent)
                .addOnSuccessListener {
                    Log.d(TAG, "Registered ${geofences.size} geofences")
                }
                .addOnFailureListener { e ->
                    Log.e(TAG, "Failed to register geofences", e)
                }

        } catch (e: Exception) {
            Log.e(TAG, "Error refreshing geofences", e)
        }
    }

    /**
     * Remove all registered geofences (both interest and tracking).
     */
    fun removeAllGeofences() {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val ids = prefs.getStringSet(KEY_GEOFENCE_IDS, emptySet()) ?: emptySet()
        val allIds = fullResetRemovalIds(ids)

        geofencingClient.removeGeofences(allIds)
            .addOnSuccessListener {
                Log.d(TAG, "Removed ${allIds.size} geofences (including tracking)")
            }
            .addOnFailureListener { e ->
                Log.e(TAG, "Failed to remove geofences", e)
            }

        prefs.edit().apply {
            clearAllGeofencePrefs(this)
            apply()
        }
    }

    private fun removeInterestGeofences() {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val ids = refreshRemovalIds(prefs.getStringSet(KEY_GEOFENCE_IDS, emptySet()) ?: emptySet())

        if (ids.isNotEmpty()) {
            geofencingClient.removeGeofences(ids)
                .addOnSuccessListener {
                    Log.d(TAG, "Removed ${ids.size} interest geofences")
                }
                .addOnFailureListener { e ->
                    Log.e(TAG, "Failed to remove interest geofences", e)
                }
        }

        prefs.edit().apply {
            clearInterestGeofencePrefs(this)
            apply()
        }
    }

    private suspend fun fetchGeofencesFromServer(lat: Double, lon: Double): JSONObject =
        withContext(Dispatchers.IO) {
            val resolvedCookie = LocationTrackingService.resolveSessionCookie(context, TAG)
                ?: run {
                    Log.w(TAG, "No authenticated session cookie available, skipping geofence refresh fetch")
                    return@withContext JSONObject()
                }

            val url = "${BuildConfig.BASE_URL}/api/geofences?lat=$lat&lng=$lon"
            val request = Request.Builder()
                .url(url)
                .get()
                .addHeader("Cookie", resolvedCookie.header)
                .build()

            try {
                httpClient.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        val body = response.body?.string() ?: "{}"
                        JSONObject(body)
                    } else {
                        Log.w(
                            TAG,
                            "Geofences API returned ${response.code} ${response.message} " +
                                "(cookieSource=${resolvedCookie.source}, " +
                                "webViewCookies=${resolvedCookie.webViewCookieSummary})"
                        )
                        JSONObject()
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error fetching geofences", e)
                JSONObject()
            }
        }
}
