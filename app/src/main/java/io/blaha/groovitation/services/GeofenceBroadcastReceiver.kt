package io.blaha.groovitation.services

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.util.Log
import androidx.core.app.NotificationManagerCompat
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
 *    On EXIT (#1008): posts location AND cancels the in-tray notification
 *    for any proximity fence the user just walked out of, so a notification
 *    the user didn't engage with disappears automatically rather than
 *    lingering for hours. The notification ID is recomputed locally from
 *    the cached metadata's targetKind/targetId — same deterministic formula
 *    `ProximityNotificationRenderer` used to display it. No server round
 *    trip required: the cancel is pure UX cleanup.
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
        private const val GEOFENCE_METADATA_KEY = "geofence_metadata"
        // Must match `ProximityNotificationRenderer.NOTIFICATION_ID_BASE`. The
        // renderer derives a notification ID via
        //   `NOTIFICATION_ID_BASE + (dedupKey.hashCode() and 0xFFFF)`
        // where `dedupKey = "$targetKind:$targetId"` (see
        // `GroovitationMessagingService.handleProximityMessage`). We rebuild
        // the same key from the geofence's cached metadata so cancel() targets
        // the same notification the FCM-triggered renderer posted.
        private const val NOTIFICATION_ID_BASE = 50000

        internal fun proximityDedupKey(targetKind: String, targetId: String): String =
            "$targetKind:$targetId"

        internal fun proximityNotificationId(targetKind: String, targetId: String): Int =
            NOTIFICATION_ID_BASE + (proximityDedupKey(targetKind, targetId).hashCode() and 0xFFFF)
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
                // #1008: cancel any in-tray proximity notification for fences
                // the user just walked out of. Pure UX cleanup; no server
                // round trip and no DB write — the server-side cooldown was
                // already recorded on ENTER's display. Skip the tracking
                // geofence (it's not a proximity target).
                cancelProximityNotificationsForExit(context, triggeringGeofences)

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

    /**
     * Cancel any in-tray proximity notifications for fences the user just
     * exited. Reads the targetKind/targetId from the cached metadata in
     * `GeofenceManager`'s SharedPreferences and rebuilds the notification ID
     * the renderer used. Falls back to a silent no-op for fences without
     * metadata (e.g. legacy geofences without `targetId` from a server build
     * predating #845, or the tracking geofence itself).
     */
    private fun cancelProximityNotificationsForExit(
        context: Context,
        exitedFences: List<com.google.android.gms.location.Geofence>
    ) {
        val proximityFences = exitedFences.filter { it.requestId != GeofenceManager.TRACKING_GEOFENCE_ID }
        if (proximityFences.isEmpty()) return

        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val metadataJson = prefs.getString(GEOFENCE_METADATA_KEY, null) ?: run {
            Log.d(TAG, "No geofence metadata cached, skipping EXIT cancel for ${proximityFences.size} fence(s)")
            return
        }

        val metadata = try {
            JSONObject(metadataJson)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse cached geofence metadata", e)
            return
        }

        val notifManager = NotificationManagerCompat.from(context)
        for (fence in proximityFences) {
            val entry = metadata.optJSONObject(fence.requestId) ?: continue
            val targetKind = entry.optString("targetKind", "")
            val targetId = entry.optString("targetId", "")
            if (targetKind.isBlank() || targetId.isBlank()) {
                Log.d(TAG, "Geofence ${fence.requestId} has no targetKind/targetId; skip cancel")
                continue
            }
            val notificationId = proximityNotificationId(targetKind, targetId)
            try {
                notifManager.cancel(notificationId)
                Log.d(TAG, "Cancelled in-tray proximity notification id=$notificationId for $targetKind/$targetId")
            } catch (e: SecurityException) {
                Log.w(TAG, "Missing notification permission for cancel id=$notificationId", e)
            }
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
