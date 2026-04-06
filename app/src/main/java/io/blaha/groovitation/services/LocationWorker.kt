package io.blaha.groovitation.services

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.Settings
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import io.blaha.groovitation.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * WorkManager periodic task for background location updates and geofence refresh.
 *
 * Runs every 15 minutes:
 * 1. Gets a single location fix (balanced power accuracy)
 * 2. Posts location to server (keeps friend proximity current)
 * 3. Refreshes geofences if the user has moved significantly
 */
class LocationWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    companion object {
        private const val TAG = "LocationWorker"
        private const val WORK_NAME_PERIODIC = "groovitation_location_periodic"
        internal const val WORK_NAME_ONESHOT = "groovitation_location_oneshot"
        private const val PREFS_NAME = "location_tracking_prefs"
        private const val KEY_LAST_GEOFENCE_LAT = "last_geofence_lat"
        private const val KEY_LAST_GEOFENCE_LNG = "last_geofence_lng"
        private const val KEY_LAST_GEOFENCE_REFRESH_TIME = "last_geofence_refresh_time"
        private const val GEOFENCE_REFRESH_DISTANCE_M = 500.0 // Refresh geofences if moved 500m
        private const val GEOFENCE_MAX_AGE_MS = 12 * 60 * 60 * 1000L // Re-register geofences every 12 hours

        fun enqueuePeriodicWork(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val request = PeriodicWorkRequestBuilder<LocationWorker>(
                15, TimeUnit.MINUTES
            )
                .setConstraints(constraints)
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME_PERIODIC,
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )
            Log.d(TAG, "Periodic work enqueued (15 min interval)")
        }

        fun enqueueOneShot(context: Context) {
            val constraintsBuilder = Constraints.Builder()
            if (!LocationWorkerTestHooks.enabled) {
                constraintsBuilder.setRequiredNetworkType(NetworkType.CONNECTED)
            }

            val request = OneTimeWorkRequestBuilder<LocationWorker>()
                .setConstraints(constraintsBuilder.build())
                .build()

            WorkManager.getInstance(context).enqueueUniqueWork(
                WORK_NAME_ONESHOT,
                ExistingWorkPolicy.REPLACE,
                request
            )
            Log.d(TAG, "One-shot work enqueued")
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME_PERIODIC)
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME_ONESHOT)
            Log.d(TAG, "All location work cancelled")
        }
    }

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    override suspend fun doWork(): Result {
        val prefs = applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val personUuid = prefs.getString("person_uuid", null)
        if (personUuid == null) {
            Log.d(TAG, "No person UUID configured, skipping")
            return Result.success()
        }

        if (ContextCompat.checkSelfPermission(applicationContext, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(applicationContext, Manifest.permission.ACCESS_COARSE_LOCATION)
            != PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, "No location permission, skipping")
            return Result.success()
        }

        try {
            val testHooks = LocationWorkerTestHooks.takeIf { it.enabled }

            // 1. Get current location
            val location = if (testHooks != null) {
                testHooks.overrideLocation
            } else {
                val fusedClient = LocationServices.getFusedLocationProviderClient(applicationContext)
                val cancellationToken = CancellationTokenSource()
                fusedClient.getCurrentLocation(
                    Priority.PRIORITY_BALANCED_POWER_ACCURACY,
                    cancellationToken.token
                ).await()
            }

            if (location == null) {
                Log.w(TAG, "Could not get location")
                return Result.retry()
            }

            Log.d(TAG, "Location: ${location.latitude}, ${location.longitude} (accuracy: ${location.accuracy}m)")

            // 2. Build payload and either capture (test) or post (production)
            val payload = buildLocationPayload(
                location.latitude,
                location.longitude,
                location.accuracy.toDouble(),
                if (location.hasAltitude()) location.altitude else null
            )

            if (testHooks != null) {
                testHooks.capturedUploads.add(
                    LocationWorkerTestHooks.CapturedUpload(personUuid, payload)
                )
            } else {
                postLocationPayload(personUuid, payload)
            }

            // 3-4. Geofence operations (skipped when test hooks suppress them)
            if (testHooks == null || !testHooks.suppressGeofence) {
                GeofenceManager(applicationContext).registerTrackingGeofence(
                    location.latitude, location.longitude
                )

                val lastLat = prefs.getFloat(KEY_LAST_GEOFENCE_LAT, 0f).toDouble()
                val lastLng = prefs.getFloat(KEY_LAST_GEOFENCE_LNG, 0f).toDouble()
                val lastRefreshTime = prefs.getLong(KEY_LAST_GEOFENCE_REFRESH_TIME, 0L)
                val timeSinceRefresh = System.currentTimeMillis() - lastRefreshTime
                val distance = if (lastLat == 0.0 && lastLng == 0.0) {
                    Double.MAX_VALUE
                } else {
                    haversineDistance(location.latitude, location.longitude, lastLat, lastLng)
                }

                if (distance > GEOFENCE_REFRESH_DISTANCE_M || timeSinceRefresh > GEOFENCE_MAX_AGE_MS) {
                    val reason = if (distance > GEOFENCE_REFRESH_DISTANCE_M) "moved ${distance.toInt()}m" else "stale (${timeSinceRefresh / 3600000}h)"
                    Log.d(TAG, "Refreshing geofences: $reason")
                    val geofenceManager = GeofenceManager(applicationContext)
                    geofenceManager.refreshGeofences(location.latitude, location.longitude)

                    prefs.edit()
                        .putFloat(KEY_LAST_GEOFENCE_LAT, location.latitude.toFloat())
                        .putFloat(KEY_LAST_GEOFENCE_LNG, location.longitude.toFloat())
                        .putLong(KEY_LAST_GEOFENCE_REFRESH_TIME, System.currentTimeMillis())
                        .apply()
                } else {
                    Log.d(TAG, "Moved only ${distance.toInt()}m, skipping geofence refresh")
                }
            }

            return Result.success()

        } catch (e: SecurityException) {
            Log.e(TAG, "Location permission denied", e)
            return Result.failure()
        } catch (e: Exception) {
            Log.e(TAG, "Error in location worker", e)
            return Result.retry()
        }
    }

    private fun buildLocationPayload(
        latitude: Double,
        longitude: Double,
        accuracy: Double,
        altitude: Double?
    ): JSONObject = JSONObject().apply {
        put("latitude", latitude)
        put("longitude", longitude)
        put("accuracy", accuracy)
        altitude?.let { put("altitude", it) }
        put("deviceType", "android")
        put("source", "background")
        put("deviceId", Settings.Secure.getString(applicationContext.contentResolver, Settings.Secure.ANDROID_ID))
        put("timestamp", System.currentTimeMillis())
    }

    private suspend fun postLocationPayload(
        personUuid: String,
        json: JSONObject
    ) = withContext(Dispatchers.IO) {
        val resolvedCookie = LocationTrackingService.resolveSessionCookie(applicationContext, TAG)
            ?: run {
                Log.w(TAG, "No authenticated session cookie available, skipping background location post")
                return@withContext
            }

        try {
            val request = Request.Builder()
                .url("${BuildConfig.BASE_URL}/people/$personUuid/location")
                .post(json.toString().toRequestBody("application/json".toMediaType()))
                .addHeader("Content-Type", "application/json")
                .addHeader("Cookie", resolvedCookie.header)
                .build()

            httpClient.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    Log.d(TAG, "Location posted successfully")
                } else {
                    Log.w(
                        TAG,
                        "Failed to post location: ${response.code} ${response.message} " +
                            "(cookieSource=${resolvedCookie.source}, " +
                            "webViewCookies=${resolvedCookie.webViewCookieSummary})"
                    )
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error posting location", e)
        }
    }

    private fun haversineDistance(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val R = 6371000.0 // Earth radius in meters
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
                Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) *
                Math.sin(dLon / 2) * Math.sin(dLon / 2)
        val c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a))
        return R * c
    }
}
