package io.blaha.groovitation.services

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import io.blaha.groovitation.MainActivity
import io.blaha.groovitation.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Foreground service for background location tracking.
 *
 * Uses significant change updates (~100m) to minimize battery usage
 * while keeping the user's location current on the server.
 */
class LocationTrackingService : Service() {

    companion object {
        private const val TAG = "LocationTrackingService"
        private const val NOTIFICATION_ID = 12345
        private const val CHANNEL_ID = "location_tracking"

        // Location settings
        private const val MIN_DISTANCE_METERS = 100f  // Only update if moved 100m
        private const val MAX_INTERVAL_MS = 300000L   // Max 5 minutes between updates
        private const val MIN_INTERVAL_MS = 60000L    // Min 1 minute between updates

        // Intent extras
        const val EXTRA_POST_URL = "post_url"
        const val EXTRA_AUTH_TOKEN = "auth_token"
        const val EXTRA_PERSON_UUID = "person_uuid"

        // Actions
        const val ACTION_START = "io.blaha.groovitation.START_LOCATION_TRACKING"
        const val ACTION_STOP = "io.blaha.groovitation.STOP_LOCATION_TRACKING"
    }

    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private var locationCallback: LocationCallback? = null

    private var postUrl: String? = null
    private var authToken: String? = null
    private var personUuid: String? = null

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "Service created")
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "onStartCommand: ${intent?.action}")

        when (intent?.action) {
            ACTION_STOP -> {
                stopTracking()
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_START, null -> {
                // Extract configuration from intent
                postUrl = intent?.getStringExtra(EXTRA_POST_URL) ?: postUrl
                authToken = intent?.getStringExtra(EXTRA_AUTH_TOKEN) ?: authToken
                personUuid = intent?.getStringExtra(EXTRA_PERSON_UUID) ?: personUuid

                startForeground()
                startTracking()
            }
        }

        return START_STICKY  // Restart if killed
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        Log.d(TAG, "Service destroyed")
        stopTracking()
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Location Tracking",
                NotificationManager.IMPORTANCE_LOW  // No sound, minimal intrusion
            ).apply {
                description = "Shows when Groovitation is tracking your location in the background"
                setShowBadge(false)
            }

            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun startForeground() {
        val notification = createNotification()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun createNotification(): Notification {
        // Intent to open app when notification tapped
        val openAppIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val openAppPendingIntent = PendingIntent.getActivity(
            this, 0, openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Intent to stop tracking
        val stopIntent = Intent(this, LocationTrackingService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getService(
            this, 1, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Groovitation")
            .setContentText("Tracking location for nearby places")
            .setSmallIcon(R.drawable.ic_notification)
            .setOngoing(true)
            .setContentIntent(openAppPendingIntent)
            .addAction(0, "Stop", stopPendingIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    private fun startTracking() {
        if (locationCallback != null) {
            Log.d(TAG, "Already tracking")
            return
        }

        try {
            val locationRequest = LocationRequest.Builder(
                Priority.PRIORITY_BALANCED_POWER_ACCURACY,
                MAX_INTERVAL_MS
            )
                .setMinUpdateDistanceMeters(MIN_DISTANCE_METERS)
                .setMinUpdateIntervalMillis(MIN_INTERVAL_MS)
                .setWaitForAccurateLocation(false)
                .build()

            locationCallback = object : LocationCallback() {
                override fun onLocationResult(result: LocationResult) {
                    result.lastLocation?.let { location ->
                        Log.d(TAG, "Location update: ${location.latitude}, ${location.longitude} (accuracy: ${location.accuracy}m)")
                        postLocationToServer(
                            latitude = location.latitude,
                            longitude = location.longitude,
                            accuracy = location.accuracy.toDouble(),
                            altitude = if (location.hasAltitude()) location.altitude else null,
                            speed = if (location.hasSpeed()) location.speed.toDouble() else null,
                            heading = if (location.hasBearing()) location.bearing.toDouble() else null
                        )
                    }
                }
            }

            fusedLocationClient.requestLocationUpdates(
                locationRequest,
                locationCallback!!,
                Looper.getMainLooper()
            )

            Log.d(TAG, "Location tracking started (100m threshold)")

        } catch (e: SecurityException) {
            Log.e(TAG, "Location permission denied", e)
            stopSelf()
        }
    }

    private fun stopTracking() {
        locationCallback?.let {
            fusedLocationClient.removeLocationUpdates(it)
            locationCallback = null
            Log.d(TAG, "Location tracking stopped")
        }
    }

    private fun postLocationToServer(
        latitude: Double,
        longitude: Double,
        accuracy: Double,
        altitude: Double?,
        speed: Double?,
        heading: Double?
    ) {
        val url = postUrl
        if (url.isNullOrEmpty()) {
            Log.w(TAG, "No post URL configured")
            return
        }

        serviceScope.launch {
            try {
                val json = JSONObject().apply {
                    put("latitude", latitude)
                    put("longitude", longitude)
                    put("accuracy", accuracy)
                    altitude?.let { put("altitude", it) }
                    speed?.let { put("speed", it) }
                    heading?.let { put("heading", it) }
                    put("updateReason", "significant_distance")
                    put("priority", "background")
                    put("timestamp", System.currentTimeMillis())
                }

                val requestBody = json.toString()
                    .toRequestBody("application/json".toMediaType())

                val requestBuilder = Request.Builder()
                    .url(url)
                    .post(requestBody)
                    .addHeader("Content-Type", "application/json")

                // Add auth token if available
                authToken?.let {
                    requestBuilder.addHeader("X-Location-Token", it)
                }

                val request = requestBuilder.build()
                val response = httpClient.newCall(request).execute()

                if (response.isSuccessful) {
                    Log.d(TAG, "Location posted successfully")
                } else {
                    Log.w(TAG, "Failed to post location: ${response.code}")
                }

                response.close()

            } catch (e: Exception) {
                Log.e(TAG, "Error posting location", e)
            }
        }
    }
}
