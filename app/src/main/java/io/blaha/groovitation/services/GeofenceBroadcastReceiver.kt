package io.blaha.groovitation.services

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.util.Log
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofencingEvent
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
 * Handles geofence transition events (enter/exit).
 *
 * Two kinds of geofences fire through here:
 *
 * 1. **Tracking geofence** (ID = TRACKING_GEOFENCE_ID): a rolling geofence
 *    centered on the user's last known position. On EXIT, posts the new
 *    location and re-registers the tracking geofence at the new position,
 *    keeping the chain going indefinitely.
 *
 * 2. **Interest geofences**: centered on places matching user interests.
 *    On ENTER, posts location only. The location POST is what triggers the
 *    server-side `ProximityNotificationDispatcher` (#845) to fan out FCM to
 *    every device the user has registered. The notification itself is
 *    rendered by `ProximityNotificationRenderer` from the FCM receiver
 *    (`GroovitationMessagingService`), not here.
 *    On EXIT, posts location (no notification, same as before).
 *
 * Pre-#845 this receiver also rendered notifications and posted the
 * `/api/proximity/notified` ack. Both responsibilities have moved to the
 * FCM path so the proximity flow is single-sourced from the server. Doing
 * it locally here caused mass-burst notifications on app restart because
 * the OS re-fires INITIAL_TRIGGER_ENTER for fences the user is already
 * inside; the new `setInitialTrigger(0)` in `GeofenceManager` blocks that
 * burst at registration time, and the server's race-guarded ack window
 * blocks repeat pushes within 90 days at the dispatch path.
 *
 * Uses goAsync() to keep the process alive while the HTTP POST completes.
 */
class GeofenceBroadcastReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "GeofenceReceiver"
        private const val PREFS_NAME = "location_tracking_prefs"
    }

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .writeTimeout(8, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .build()

    override fun onReceive(context: Context, intent: Intent) {
        val geofencingEvent = GeofencingEvent.fromIntent(intent)
        if (geofencingEvent == null) {
            Log.w(TAG, "Null geofencing event")
            return
        }
        if (geofencingEvent.hasError()) {
            Log.e(TAG, "Geofencing error: ${geofencingEvent.errorCode}")
            return
        }

        val transition = geofencingEvent.geofenceTransition
        val triggeringGeofences = geofencingEvent.triggeringGeofences ?: return
        val location = geofencingEvent.triggeringLocation

        Log.d(TAG, "Geofence transition: $transition, geofences: ${triggeringGeofences.map { it.requestId }}")

        // Use goAsync() to extend the receiver's lifetime while the HTTP POST completes
        val pendingResult = goAsync()

        val isTrackingGeofence = triggeringGeofences.any {
            it.requestId == GeofenceManager.TRACKING_GEOFENCE_ID
        }

        when (transition) {
            Geofence.GEOFENCE_TRANSITION_ENTER -> {
                // #845: ENTER no longer renders a local notification or posts
                // the proximity ack. Both responsibilities live on the server +
                // FCM path now (`ProximityNotificationDispatcher` -> FCM ->
                // `GroovitationMessagingService` -> `ProximityNotificationRenderer`).
                // We still POST the location: that POST is the trigger the
                // server uses to invoke the dispatcher and decide whether any
                // tokens for this user need a push.
                if (location != null) {
                    postLocation(context, location.latitude, location.longitude, location.accuracy.toDouble(), pendingResult)
                } else {
                    pendingResult.finish()
                }
            }
            Geofence.GEOFENCE_TRANSITION_EXIT -> {
                if (location != null) {
                    // Re-register the tracking geofence at the new position to keep the chain going
                    if (isTrackingGeofence) {
                        Log.d(TAG, "Tracking geofence exited, chaining to ${location.latitude}, ${location.longitude}")
                        GeofenceManager(context).registerTrackingGeofence(location.latitude, location.longitude)
                    }
                    postLocation(context, location.latitude, location.longitude, location.accuracy.toDouble(), pendingResult)
                } else {
                    pendingResult.finish()
                }
            }
            else -> pendingResult.finish()
        }
    }

    private fun postLocation(context: Context, latitude: Double, longitude: Double, accuracy: Double, pendingResult: PendingResult) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val personUuid = prefs.getString("person_uuid", null) ?: run {
            pendingResult.finish()
            return
        }
        val resolvedAuth = LocationTrackingService.resolveLocationAuth(context, TAG)
            ?: run {
                Log.w(TAG, "No native location auth available, skipping geofence location post")
                pendingResult.finish()
                return
            }

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val json = JSONObject().apply {
                    put("latitude", latitude)
                    put("longitude", longitude)
                    put("accuracy", accuracy)
                    put("deviceType", "android")
                    put("source", "geofence")
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
                        Log.d(TAG, "Location posted on geofence transition")
                    } else {
                        Log.w(
                            TAG,
                            "Failed to post location: ${response.code} ${response.message} " +
                                "(authSource=${resolvedAuth.source}, " +
                                "webViewCookies=${resolvedAuth.webViewCookieSummary ?: "n/a"})"
                        )
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error posting location", e)
            } finally {
                pendingResult.finish()
            }
        }
    }
}
