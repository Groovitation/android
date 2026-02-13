package io.blaha.groovitation.components

import android.Manifest
import android.content.pm.PackageManager
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import dev.hotwire.core.bridge.BridgeComponent
import dev.hotwire.core.bridge.BridgeDelegate
import dev.hotwire.core.bridge.Message
import dev.hotwire.navigation.destinations.HotwireDestination
import io.blaha.groovitation.MainActivity
import io.blaha.groovitation.services.GeofenceManager
import io.blaha.groovitation.services.LocationTrackingService
import io.blaha.groovitation.services.LocationWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

class LocationComponent(
    name: String,
    private val delegate: BridgeDelegate<HotwireDestination>
) : BridgeComponent<HotwireDestination>(name, delegate) {

    companion object {
        private const val TAG = "LocationComponent"
        private const val LOCATION_UPDATE_INTERVAL = 60000L
        private const val LOCATION_FASTEST_INTERVAL = 30000L
    }

    private val fragment: Fragment
        get() = delegate.destination.fragment

    private val activity: MainActivity?
        get() = fragment.activity as? MainActivity

    private var fusedLocationClient: FusedLocationProviderClient? = null
    private var locationCallback: LocationCallback? = null
    private var postUrl: String? = null
    private val httpClient = OkHttpClient()
    private val scope = CoroutineScope(Dispatchers.Main)

    override fun onReceive(message: Message) {
        Log.d(TAG, "Received message: ${message.event}")

        when (message.event) {
            "requestLocation" -> handleRequestLocation(message)
            "startBackgroundTracking" -> handleStartBackgroundTracking(message)
            "stopTracking" -> handleStopTracking(message)
            // New: True background service (survives app death)
            "startBackgroundService" -> handleStartBackgroundService(message)
            "stopBackgroundService" -> handleStopBackgroundService(message)
            else -> Log.w(TAG, "Unknown event: ${message.event}")
        }
    }

    private fun handleRequestLocation(message: Message) {
        val context = fragment.context ?: return

        if (!hasLocationPermission(context)) {
            activity?.requestLocationPermission()
            replyTo("requestLocation", LocationReply(success = false, error = "Location permission required"))
            return
        }

        if (fusedLocationClient == null) {
            fusedLocationClient = LocationServices.getFusedLocationProviderClient(context)
        }

        // Always request fresh location - lastLocation cache is unreliable
        // and can return stale coordinates with misleading timestamps
        Log.d(TAG, "Requesting fresh GPS fix (ignoring lastLocation cache)")
        requestFreshLocation(message)
    }

    private fun requestFreshLocation(message: Message) {
        val context = fragment.context ?: return

        try {
            // Request multiple updates over 10 seconds to get best GPS fix
            val locationRequest = LocationRequest.Builder(
                Priority.PRIORITY_HIGH_ACCURACY,
                2000L  // Request every 2 seconds
            )
                .setDurationMillis(10000L)  // For up to 10 seconds
                .setMaxUpdates(5)  // Max 5 updates
                .build()

            var bestLocation: android.location.Location? = null
            var updateCount = 0
            var replied = false
            
            val callback = object : LocationCallback() {
                override fun onLocationResult(result: LocationResult) {
                    if (replied) return
                    val location = result.lastLocation ?: return
                    updateCount++
                    Log.d(TAG, "Location update #$updateCount: ${location.latitude}, ${location.longitude} (accuracy: ${location.accuracy}m)")
                    
                    // Keep the most accurate location we've seen
                    if (bestLocation == null || location.accuracy < bestLocation!!.accuracy) {
                        bestLocation = location
                    }
                    
                    // Accept immediately if we get good GPS accuracy (<50m) or after 5 updates
                    if (location.accuracy < 50 || updateCount >= 5) {
                        replied = true
                        fusedLocationClient?.removeLocationUpdates(this)
                        val best = bestLocation!!
                        Log.d(TAG, "Accepting location: ${best.latitude}, ${best.longitude} (accuracy: ${best.accuracy}m)")
                        replyTo("requestLocation", LocationReply(
                            success = true,
                            latitude = best.latitude,
                            longitude = best.longitude,
                            accuracy = best.accuracy.toDouble(),
                            altitude = best.altitude
                        ))
                    }
                }
            }

            fusedLocationClient?.requestLocationUpdates(
                locationRequest,
                callback,
                Looper.getMainLooper()
            )
            
            // Timeout after 12 seconds - return best location we have
            android.os.Handler(Looper.getMainLooper()).postDelayed({
                if (!replied) {
                    replied = true
                    fusedLocationClient?.removeLocationUpdates(callback)
                    val best = bestLocation
                    if (best != null) {
                        Log.d(TAG, "Timeout - using best location: ${best.latitude}, ${best.longitude} (accuracy: ${best.accuracy}m)")
                        replyTo("requestLocation", LocationReply(
                            success = true,
                            latitude = best.latitude,
                            longitude = best.longitude,
                            accuracy = best.accuracy.toDouble(),
                            altitude = best.altitude
                        ))
                    } else {
                        Log.w(TAG, "Timeout - no location received")
                        replyTo("requestLocation", LocationReply(success = false, error = "Could not determine location"))
                    }
                }
            }, 12000L)
            
        } catch (e: SecurityException) {
            Log.e(TAG, "Location permission denied", e)
            replyTo("requestLocation", LocationReply(success = false, error = "Location permission denied"))
        }
    }

    /**
     * Start background tracking (only works while app is open/recent)
     */
    private fun handleStartBackgroundTracking(message: Message) {
        val context = fragment.context ?: return

        val data = message.data<TrackingData>()
        postUrl = data?.postUrl

        if (!hasLocationPermission(context)) {
            activity?.requestLocationPermission()
            replyTo("startBackgroundTracking", TrackingReply(started = false, error = "Location permission required"))
            return
        }

        if (fusedLocationClient == null) {
            fusedLocationClient = LocationServices.getFusedLocationProviderClient(context)
        }

        try {
            val locationRequest = LocationRequest.Builder(
                Priority.PRIORITY_BALANCED_POWER_ACCURACY,
                LOCATION_UPDATE_INTERVAL
            )
                .setMinUpdateIntervalMillis(LOCATION_FASTEST_INTERVAL)
                .build()

            locationCallback = object : LocationCallback() {
                override fun onLocationResult(result: LocationResult) {
                    result.lastLocation?.let { location ->
                        Log.d(TAG, "Background location update: ${location.latitude}, ${location.longitude}")
                        postLocationToServer(location.latitude, location.longitude, location.accuracy.toDouble())
                    }
                }
            }

            fusedLocationClient?.requestLocationUpdates(
                locationRequest,
                locationCallback!!,
                Looper.getMainLooper()
            )

            Log.d(TAG, "Background location tracking started")
            replyTo("startBackgroundTracking", TrackingReply(started = true))

        } catch (e: SecurityException) {
            Log.e(TAG, "Location permission denied for background tracking", e)
            replyTo("startBackgroundTracking", TrackingReply(started = false, error = "Location permission denied"))
        }
    }

    private fun handleStopTracking(message: Message) {
        locationCallback?.let {
            fusedLocationClient?.removeLocationUpdates(it)
            locationCallback = null
            Log.d(TAG, "Location tracking stopped")
        }
        replyTo("stopTracking", StopReply(stopped = true))
    }

    /**
     * Start background tracking via WorkManager + geofences.
     * Persists config so it restarts on boot.
     */
    private fun handleStartBackgroundService(message: Message) {
        val data = message.data<BackgroundServiceData>()
        val personUuid = data?.personUuid

        if (personUuid.isNullOrEmpty()) {
            replyTo("startBackgroundService", ServiceReply(started = false, error = "personUuid required"))
            return
        }

        try {
            activity?.enableBackgroundTracking(personUuid)
            Log.d(TAG, "Background location tracking enabled for $personUuid")
            replyTo("startBackgroundService", ServiceReply(started = true))
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start background tracking", e)
            replyTo("startBackgroundService", ServiceReply(started = false, error = e.message))
        }
    }

    /**
     * Stop background tracking — cancel WorkManager and remove geofences.
     */
    private fun handleStopBackgroundService(message: Message) {
        val context = fragment.context ?: return

        try {
            LocationWorker.cancel(context)
            GeofenceManager(context).removeAllGeofences()
            LocationTrackingService.clearConfig(context)
            Log.d(TAG, "Background location tracking stopped")
            replyTo("stopBackgroundService", ServiceReply(stopped = true))
        } catch (e: Exception) {
            Log.e(TAG, "Failed to stop background tracking", e)
            replyTo("stopBackgroundService", ServiceReply(stopped = false, error = e.message))
        }
    }

    private fun postLocationToServer(latitude: Double, longitude: Double, accuracy: Double) {
        val url = postUrl ?: return

        scope.launch {
            withContext(Dispatchers.IO) {
                try {
                    val json = JSONObject().apply {
                        put("latitude", latitude)
                        put("longitude", longitude)
                        put("accuracy", accuracy)
                    }

                    val requestBody = json.toString()
                        .toRequestBody("application/json".toMediaType())

                    val request = Request.Builder()
                        .url(url)
                        .post(requestBody)
                        .addHeader("Content-Type", "application/json")
                        .build()

                    val response = httpClient.newCall(request).execute()

                    if (!response.isSuccessful) {
                        Log.w(TAG, "Failed to post location: ${response.code}")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error posting location to server", e)
                }
            }
        }
    }

    private fun hasLocationPermission(context: android.content.Context): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED ||
        ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
    }

    @Serializable
    data class TrackingData(
        @SerialName("postUrl") val postUrl: String? = null
    )

    @Serializable
    data class BackgroundServiceData(
        @SerialName("postUrl") val postUrl: String? = null,
        @SerialName("authToken") val authToken: String? = null,
        @SerialName("personUuid") val personUuid: String? = null
    )

    @Serializable
    data class LocationReply(
        @SerialName("success") val success: Boolean,
        @SerialName("latitude") val latitude: Double? = null,
        @SerialName("longitude") val longitude: Double? = null,
        @SerialName("accuracy") val accuracy: Double? = null,
        @SerialName("altitude") val altitude: Double? = null,
        @SerialName("error") val error: String? = null
    )

    @Serializable
    data class TrackingReply(
        @SerialName("started") val started: Boolean,
        @SerialName("error") val error: String? = null
    )

    @Serializable
    data class StopReply(
        @SerialName("stopped") val stopped: Boolean
    )

    @Serializable
    data class ServiceReply(
        @SerialName("started") val started: Boolean? = null,
        @SerialName("stopped") val stopped: Boolean? = null,
        @SerialName("error") val error: String? = null
    )
}
