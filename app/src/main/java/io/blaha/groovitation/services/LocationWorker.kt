package io.blaha.groovitation.services

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.os.Build
import android.os.SystemClock
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
import com.google.android.gms.location.CurrentLocationRequest
import com.google.android.gms.location.FusedLocationProviderClient
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

    /**
     * Structured outcome of a single `doWork()` invocation. Every exit path
     * in the worker emits exactly one outcome via [emitOutcome] — log line
     * + capture into [LocationWorkerTestHooks.capturedOutcomes].
     *
     * Designed to make the historical "silent skip" failure mode observable.
     * Previously, paths like `resolveLocationAuth() == null` returned
     * `Result.success()` with one `Log.w` line. Externally that looked
     * identical to a healthy run; investigations had no signal to grep on.
     * With this enum, prod can grep `LocationWorker outcome=SKIPPED_NO_AUTH`
     * and tests can assert on the captured outcome list.
     */
    enum class Outcome {
        /** Posted location to /people/{uuid}/location and got a 2xx. */
        POSTED,
        /** No personUuid in prefs — user hasn't signed in / native bridge hasn't pushed yet. */
        SKIPPED_NO_PERSON_UUID,
        /** ACCESS_FINE_LOCATION and ACCESS_COARSE_LOCATION both denied. */
        SKIPPED_NO_LOCATION_PERMISSION,
        /**
         * API 29+ gap-check (#798): FINE/COARSE granted but ACCESS_BACKGROUND_LOCATION
         * denied. FusedLocationProvider would either throw SecurityException or
         * silently return null during background execution; short-circuiting here
         * gives on-call a distinct outcome to grep on and pairs with the onboarding
         * re-prompt flow landed via `human/scruff3-background-location-reprompt`.
         */
        SKIPPED_NO_BACKGROUND_LOCATION_PERMISSION,
        /** FusedLocationProvider returned null — common on screen-off / poor radio reception. */
        SKIPPED_NO_LOCATION_FIX,
        /**
         * resolveLocationAuth() returned null — no WebView cookie, no stored
         * session cookie, no stored location token. The classic silent-skip
         * branch this enum is built to expose.
         */
        SKIPPED_NO_AUTH,
        /** HTTP POST failed (non-2xx response or transport exception). */
        HTTP_FAILED,
        /** SecurityException thrown — permission revoked between check and use. */
        PERMISSION_REVOKED_AT_RUNTIME
    }

    companion object {
        private const val TAG = "LocationWorker"
        private const val WORK_NAME_PERIODIC = "groovitation_location_periodic"
        internal const val WORK_NAME_ONESHOT = "groovitation_location_oneshot"

        /**
         * Pure JSON builder for the `/people/{uuid}/location` payload. Kept
         * as a top-level companion function (no `applicationContext` reads)
         * so the cross-repo contract test can call it with fixed inputs and
         * compare against `shared-contracts/android-location-payload-v1.json`
         * (#773). The instance method [buildLocationPayload] resolves
         * `deviceId` and `timestamp` from the system and delegates here.
         *
         * Field-name and source-string changes here MUST be coordinated
         * with the matching core-side `LocationUpdate` parser
         * (`PeopleController.LocationUpdate` + `locationUpdateFormat`) and
         * the mirrored fixture in
         * `pekko-backend/src/test/resources/contracts/android-location-payload-v1.json`.
         */
        internal fun buildLocationPayloadJson(
            latitude: Double,
            longitude: Double,
            accuracy: Double,
            altitude: Double?,
            deviceId: String,
            timestamp: Long
        ): JSONObject = JSONObject().apply {
            put("latitude", latitude)
            put("longitude", longitude)
            put("accuracy", accuracy)
            altitude?.let { put("altitude", it) }
            put("deviceType", "android")
            put("source", "background-gps")
            put("deviceId", deviceId)
            put("timestamp", timestamp)
        }
        private const val PREFS_NAME = "location_tracking_prefs"
        private const val KEY_LAST_GEOFENCE_LAT = "last_geofence_lat"
        private const val KEY_LAST_GEOFENCE_LNG = "last_geofence_lng"
        private const val KEY_LAST_GEOFENCE_REFRESH_TIME = "last_geofence_refresh_time"
        internal const val MAX_CACHED_LOCATION_AGE_MS = 2 * 60 * 1000L
        internal const val CURRENT_LOCATION_MAX_UPDATE_AGE_MS = 0L
        internal const val CURRENT_LOCATION_DURATION_MS = 20_000L
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

        /**
         * Single emission point for every `doWork()` exit. Writes a
         * structured log line and records the outcome for tests to assert
         * against.
         */
        internal fun emitOutcome(outcome: Outcome, extras: String = "") {
            val suffix = if (extras.isNotEmpty()) " $extras" else ""
            val message = "LocationWorker outcome=$outcome$suffix"
            when (outcome) {
                Outcome.POSTED,
                Outcome.SKIPPED_NO_PERSON_UUID,
                Outcome.SKIPPED_NO_LOCATION_PERMISSION,
                Outcome.SKIPPED_NO_BACKGROUND_LOCATION_PERMISSION -> Log.d(TAG, message)
                Outcome.SKIPPED_NO_LOCATION_FIX,
                Outcome.SKIPPED_NO_AUTH,
                Outcome.HTTP_FAILED,
                Outcome.PERMISSION_REVOKED_AT_RUNTIME -> Log.w(TAG, message)
            }
            LocationWorkerTestHooks.capturedOutcomes.add(outcome)
        }

        internal fun locationAgeMillis(
            location: Location,
            nowElapsedRealtimeNanos: Long = SystemClock.elapsedRealtimeNanos(),
            nowWallClockMs: Long = System.currentTimeMillis()
        ): Long? {
            if (location.elapsedRealtimeNanos > 0L) {
                return TimeUnit.NANOSECONDS.toMillis(nowElapsedRealtimeNanos - location.elapsedRealtimeNanos)
            }
            if (location.time > 0L) {
                return nowWallClockMs - location.time
            }
            return null
        }

        internal fun isFreshEnough(
            location: Location,
            nowElapsedRealtimeNanos: Long = SystemClock.elapsedRealtimeNanos(),
            nowWallClockMs: Long = System.currentTimeMillis()
        ): Boolean {
            val ageMs = locationAgeMillis(location, nowElapsedRealtimeNanos, nowWallClockMs)
            return ageMs == null || ageMs <= MAX_CACHED_LOCATION_AGE_MS
        }

        internal fun currentLocationRequest(priority: Int): CurrentLocationRequest =
            CurrentLocationRequest.Builder()
                .setPriority(priority)
                .setMaxUpdateAgeMillis(CURRENT_LOCATION_MAX_UPDATE_AGE_MS)
                .setDurationMillis(CURRENT_LOCATION_DURATION_MS)
                .build()
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
            emitOutcome(Outcome.SKIPPED_NO_PERSON_UUID)
            return Result.success()
        }

        if (ContextCompat.checkSelfPermission(applicationContext, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(applicationContext, Manifest.permission.ACCESS_COARSE_LOCATION)
            != PackageManager.PERMISSION_GRANTED) {
            emitOutcome(Outcome.SKIPPED_NO_LOCATION_PERMISSION)
            return Result.success()
        }

        // API 29+ gap-check (#798): FINE/COARSE are not sufficient for background
        // fixes on Android 10+. Without ACCESS_BACKGROUND_LOCATION the
        // FusedLocationProvider will either throw SecurityException (caught
        // below as PERMISSION_REVOKED_AT_RUNTIME) or silently return null
        // (surfaces as SKIPPED_NO_LOCATION_FIX). Short-circuit to a distinct
        // outcome so prod can grep for this specific failure shape and the
        // onboarding re-prompt (landed via `human/scruff3-background-location-reprompt`)
        // has a single telemetry hook to trigger on.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
            ContextCompat.checkSelfPermission(applicationContext, Manifest.permission.ACCESS_BACKGROUND_LOCATION)
            != PackageManager.PERMISSION_GRANTED) {
            emitOutcome(Outcome.SKIPPED_NO_BACKGROUND_LOCATION_PERMISSION)
            return Result.success()
        }

        try {
            val testHooks = LocationWorkerTestHooks.takeIf { it.enabled }

            // 1. Get current location.
            //
            // Background posts must not reuse FusedLocationProvider cache. CI
            // reproduced a provider cache entry with a fresh timestamp but old
            // coordinates, which recorded a wrong background-gps row before the
            // foreground high-accuracy path produced the correct fix. Ask for a
            // no-cache HIGH_ACCURACY current fix first; keep a no-cache BALANCED
            // fallback for devices where GPS cannot resolve during the worker
            // window. The battery tradeoff is the same one accepted in the
            // 2026-04-24 Ben S24+ case: a short GPS wake every 15 minutes is
            // preferable to silently posting stale coordinates.
            val location = if (testHooks != null) {
                testHooks.overrideLocation
            } else {
                val fusedClient = LocationServices.getFusedLocationProviderClient(applicationContext)
                requestFreshCurrentLocation(
                    fusedClient = fusedClient,
                    priority = Priority.PRIORITY_HIGH_ACCURACY,
                    label = "High-accuracy"
                ) ?: requestFreshCurrentLocation(
                    fusedClient = fusedClient,
                    priority = Priority.PRIORITY_BALANCED_POWER_ACCURACY,
                    label = "Balanced-power"
                )
            }

            if (location == null) {
                // Both BALANCED and HIGH_ACCURACY returned null. At this
                // point either the GPS has no signal, the radios are off,
                // or the device is in a very restrictive power state.
                // Retry rather than fail — next tick might succeed.
                emitOutcome(Outcome.SKIPPED_NO_LOCATION_FIX)
                return Result.retry()
            }

            Log.d(TAG, "Location: ${location.latitude}, ${location.longitude} (accuracy: ${location.accuracy}m)")

            // 2. Build payload and POST to the server. #770: the HTTP path
            // always runs — including under instrumented tests, which redirect
            // the POST at a MockWebServer via baseUrlOverride and assert on
            // what the server received. The previous "test-mode captures and
            // skips" shape hid the resolveLocationAuth null-return branch
            // (Ben's 2026-04-23 outage), which is the specific regression
            // #770 prevents from ever re-entering CI.
            val payload = buildLocationPayload(
                location.latitude,
                location.longitude,
                location.accuracy.toDouble(),
                if (location.hasAltitude()) location.altitude else null
            )
            postLocationPayload(personUuid, payload)

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
            emitOutcome(Outcome.PERMISSION_REVOKED_AT_RUNTIME, "exception=${e.javaClass.simpleName}")
            return Result.failure()
        } catch (e: Exception) {
            emitOutcome(Outcome.HTTP_FAILED, "exception=${e.javaClass.simpleName}")
            return Result.retry()
        }
    }

    private suspend fun requestFreshCurrentLocation(
        fusedClient: FusedLocationProviderClient,
        priority: Int,
        label: String
    ): Location? {
        val token = CancellationTokenSource()
        val location = fusedClient.getCurrentLocation(
            currentLocationRequest(priority),
            token.token
        ).await()

        if (location == null) {
            Log.d(TAG, "$label current location returned null")
            return null
        }

        val ageMs = locationAgeMillis(location)
        if (!isFreshEnough(location)) {
            Log.w(TAG, "$label current location was stale (age=${ageMs ?: "unknown"}ms); treating as no fix")
            return null
        }

        Log.d(TAG, "$label current location accepted (age=${ageMs ?: "unknown"}ms, accuracy=${location.accuracy}m)")
        return location
    }

    private fun buildLocationPayload(
        latitude: Double,
        longitude: Double,
        accuracy: Double,
        altitude: Double?
    ): JSONObject = buildLocationPayloadJson(
        latitude = latitude,
        longitude = longitude,
        accuracy = accuracy,
        altitude = altitude,
        deviceId = Settings.Secure.getString(applicationContext.contentResolver, Settings.Secure.ANDROID_ID),
        timestamp = System.currentTimeMillis()
    )

    private suspend fun postLocationPayload(
        personUuid: String,
        json: JSONObject
    ) = withContext(Dispatchers.IO) {
        val resolvedAuth = LocationTrackingService.resolveLocationAuth(applicationContext, TAG)
            ?: run {
                emitOutcome(Outcome.SKIPPED_NO_AUTH)
                return@withContext
            }

        // #770: instrumented tests redirect POSTs at a MockWebServer by
        // setting LocationWorkerTestHooks.baseUrlOverride. Production reads
        // from BuildConfig as before.
        val baseUrl = LocationWorkerTestHooks.takeIf { it.enabled }?.baseUrlOverride
            ?: BuildConfig.BASE_URL
        try {
            val request = Request.Builder()
                .url("$baseUrl/people/$personUuid/location")
                .post(json.toString().toRequestBody("application/json".toMediaType()))
                .addHeader("Content-Type", "application/json")
                .addHeader(resolvedAuth.headerName, resolvedAuth.headerValue)
                .build()

            httpClient.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    emitOutcome(Outcome.POSTED, "code=${response.code} authSource=${resolvedAuth.source}")
                } else {
                    emitOutcome(
                        Outcome.HTTP_FAILED,
                        "code=${response.code} authSource=${resolvedAuth.source} " +
                            "webViewCookies=${resolvedAuth.webViewCookieSummary ?: "n/a"}"
                    )
                }
            }
        } catch (e: Exception) {
            emitOutcome(Outcome.HTTP_FAILED, "exception=${e.javaClass.simpleName}")
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
