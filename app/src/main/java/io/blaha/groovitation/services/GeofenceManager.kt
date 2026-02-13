package io.blaha.groovitation.services

import android.Manifest
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log
import android.webkit.CookieManager
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
 * Manages geofence registration and refresh for background place awareness.
 *
 * Fetches geofence regions from the server (places matching user interests)
 * and registers them with the Android Geofencing API. On enter/exit,
 * GeofenceBroadcastReceiver fires to post location and show notifications.
 *
 * Android limit: 100 geofences per app. Budget: 80 for places, 20 reserved.
 */
class GeofenceManager(private val context: Context) {

    companion object {
        private const val TAG = "GeofenceManager"
        private const val PREFS_NAME = "location_tracking_prefs"
        private const val KEY_GEOFENCE_IDS = "registered_geofence_ids"
        private const val KEY_GEOFENCE_METADATA = "geofence_metadata"
        private const val GEOFENCE_EXPIRATION_MS = 24 * 60 * 60 * 1000L // 24 hours
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
     * Fetch geofences from the server and register them with the Geofencing API.
     */
    suspend fun refreshGeofences(lat: Double, lon: Double) {
        try {
            val geofenceData = fetchGeofencesFromServer(lat, lon)
            if (geofenceData.isEmpty()) return

            // Remove existing geofences before registering new ones
            removeAllGeofences()

            val geofences = mutableListOf<Geofence>()
            val metadata = JSONObject()

            val geofencesArray = geofenceData.getJSONArray("geofences")
            for (i in 0 until geofencesArray.length()) {
                val gf = geofencesArray.getJSONObject(i)
                val id = gf.getString("id")
                val latitude = gf.getDouble("latitude")
                val longitude = gf.getDouble("longitude")
                val radius = gf.getDouble("radiusMeters").toFloat
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
     * Remove all registered geofences.
     */
    fun removeAllGeofences() {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val ids = prefs.getStringSet(KEY_GEOFENCE_IDS, emptySet()) ?: emptySet()

        if (ids.isNotEmpty()) {
            geofencingClient.removeGeofences(ids.toList())
                .addOnSuccessListener {
                    Log.d(TAG, "Removed ${ids.size} geofences")
                }
                .addOnFailureListener { e ->
                    Log.e(TAG, "Failed to remove geofences", e)
                }
        }

        prefs.edit()
            .remove(KEY_GEOFENCE_IDS)
            .remove(KEY_GEOFENCE_METADATA)
            .apply()
    }

    private suspend fun fetchGeofencesFromServer(lat: Double, lon: Double): JSONObject =
        withContext(Dispatchers.IO) {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val cookie = CookieManager.getInstance().getCookie(BuildConfig.BASE_URL)
                ?: prefs.getString(LocationTrackingService.KEY_SESSION_COOKIE, null)
                ?: run {
                    Log.w(TAG, "No session cookie available")
                    return@withContext JSONObject()
                }

            val url = "${BuildConfig.BASE_URL}/api/geofences?lat=$lat&lng=$lon"
            val request = Request.Builder()
                .url(url)
                .get()
                .addHeader("Cookie", cookie)
                .build()

            try {
                val response = httpClient.newCall(request).execute()
                if (response.isSuccessful) {
                    val body = response.body?.string() ?: "{}"
                    response.close()
                    JSONObject(body)
                } else {
                    Log.w(TAG, "Geofences API returned ${response.code}")
                    response.close()
                    JSONObject()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error fetching geofences", e)
                JSONObject()
            }
        }
}
