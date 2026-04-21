package io.blaha.groovitation.services

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.provider.Settings
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofencingEvent
import io.blaha.groovitation.BuildConfig
import io.blaha.groovitation.GroovitationApplication
import io.blaha.groovitation.IncomingPushNotification
import io.blaha.groovitation.MainActivity
import io.blaha.groovitation.NotificationTapActivityStart
import io.blaha.groovitation.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.net.URL
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
 *    On ENTER, posts location and shows a notification.
 *    On EXIT, posts location (no notification).
 *
 * Uses goAsync() to keep the process alive while the HTTP POST completes.
 */
class GeofenceBroadcastReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "GeofenceReceiver"
        private const val PREFS_NAME = "location_tracking_prefs"
        private const val KEY_GEOFENCE_METADATA = "geofence_metadata"
        private const val NOTIFICATION_ID_BASE = 50000
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

        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val metadataStr = prefs.getString(KEY_GEOFENCE_METADATA, null)
        val metadata = if (metadataStr != null) JSONObject(metadataStr) else JSONObject()

        // Use goAsync() to extend the receiver's lifetime while the HTTP POST completes
        val pendingResult = goAsync()

        val isTrackingGeofence = triggeringGeofences.any {
            it.requestId == GeofenceManager.TRACKING_GEOFENCE_ID
        }

        when (transition) {
            Geofence.GEOFENCE_TRANSITION_ENTER -> {
                // Tracking geofence is EXIT-only, so ENTER is always a proximity
                // candidate (site or event from today's landing page, #705).
                for (geofence in triggeringGeofences) {
                    if (geofence.requestId == GeofenceManager.TRACKING_GEOFENCE_ID) continue
                    val gfMeta = metadata.optJSONObject(geofence.requestId) ?: JSONObject()
                    val placeName = gfMeta.optString("placeName", "a place").ifBlank { "a place" }
                    val interestName = gfMeta.optString("interestName", "")
                    val targetKind = gfMeta.optString("targetKind", "site")
                    val targetId = gfMeta.optString("targetId", "")
                    val imageUrl = gfMeta.optString("imageUrl", "").ifBlank { null }
                    val deepLink = gfMeta.optString("deepLink", "").ifBlank { null }

                    val message = if (interestName.isNotEmpty()) {
                        "You're near $placeName \u2014 matches your interest in $interestName"
                    } else {
                        "You're near $placeName"
                    }

                    showNotification(
                        context,
                        geofenceId = geofence.requestId,
                        title = placeName,
                        message = message,
                        imageUrl = imageUrl,
                        deepLink = deepLink
                    )
                    // Best-effort dedup ack so the server stops handing us this
                    // target on subsequent geofence refreshes. A failure here
                    // (network blip, 401, etc.) is fine: a duplicate notification
                    // is strictly better than a silent miss.
                    if (targetKind.isNotBlank() && targetId.isNotBlank()) {
                        postProximityNotified(context, targetKind, targetId)
                    }
                }

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
                        Log.d(TAG, "Tracking geofence exited — chaining to ${location.latitude}, ${location.longitude}")
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

    private fun showNotification(
        context: Context,
        geofenceId: String,
        title: String,
        message: String,
        imageUrl: String? = null,
        deepLink: String? = null
    ) {
        // Deep link falls back to /map if the server didn't supply one (older
        // builds during a mixed rollout, or a missing field). Normalize the
        // same way FCM-delivered push notifications do so the MainActivity
        // intent handler behaves consistently between geofence ENTER and push.
        val normalizedLink = IncomingPushNotification(
            title = title,
            body = message,
            deepLink = deepLink,
            channel = GroovitationApplication.CHANNEL_PLACES
        ).resolvedDeepLink()

        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(IncomingPushNotification.EXTRA_URL, normalizedLink)
        }

        val pendingIntent = PendingIntent.getActivity(
            context,
            geofenceId.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            NotificationTapActivityStart.creatorOptions()
        )

        val builder = NotificationCompat.Builder(context, GroovitationApplication.CHANNEL_PLACES)
            .setContentTitle(title)
            .setContentText(message)
            .setSmallIcon(R.drawable.ic_notification)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)

        // Load the hero image synchronously on this background thread. The
        // receiver is already inside goAsync() which extends our lifetime up
        // to ~10s, so a small HTTPS fetch fits the budget. If it fails we
        // fall back to the plain-text notification — the feature still works,
        // we just render the landing-card image when it's available.
        val bitmap = imageUrl?.let { fetchBitmap(it) }
        if (bitmap != null) {
            builder
                .setLargeIcon(bitmap)
                .setStyle(
                    NotificationCompat.BigPictureStyle()
                        .bigPicture(bitmap)
                        .bigLargeIcon(null as Bitmap?)
                )
        }

        val notificationId = NOTIFICATION_ID_BASE + (geofenceId.hashCode() and 0xFFFF)
        try {
            NotificationManagerCompat.from(context).notify(notificationId, builder.build())
        } catch (e: SecurityException) {
            Log.w(TAG, "Missing notification permission", e)
        }
    }

    private fun fetchBitmap(rawUrl: String): Bitmap? {
        return try {
            val url = URL(rawUrl)
            url.openConnection().apply {
                connectTimeout = 4000
                readTimeout = 4000
            }.getInputStream().use { stream ->
                BitmapFactory.decodeStream(stream)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to load proximity notification image: $rawUrl", e)
            null
        }
    }

    private fun postProximityNotified(context: Context, targetKind: String, targetId: String) {
        val resolvedAuth = LocationTrackingService.resolveLocationAuth(context, TAG) ?: run {
            Log.w(TAG, "No auth available for proximity/notified ack; skipping")
            return
        }

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val body = JSONObject().apply {
                    put("targetKind", targetKind)
                    put("targetId", targetId)
                }
                val request = Request.Builder()
                    .url("${BuildConfig.BASE_URL}/api/proximity/notified")
                    .post(body.toString().toRequestBody("application/json".toMediaType()))
                    .addHeader("Content-Type", "application/json")
                    .addHeader(resolvedAuth.headerName, resolvedAuth.headerValue)
                    .build()

                httpClient.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        Log.d(TAG, "Proximity notified ack: $targetKind/$targetId")
                    } else {
                        Log.w(
                            TAG,
                            "Proximity notified ack failed: ${response.code} ${response.message}"
                        )
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error posting proximity/notified for $targetKind/$targetId", e)
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
