package io.blaha.groovitation.services

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import android.webkit.CookieManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofencingEvent
import io.blaha.groovitation.BuildConfig
import io.blaha.groovitation.GroovitationApplication
import io.blaha.groovitation.MainActivity
import io.blaha.groovitation.R
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
 * On ENTER: posts location to server and shows a contextual notification.
 * On EXIT: posts location update to server (no notification).
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

        when (transition) {
            Geofence.GEOFENCE_TRANSITION_ENTER -> {
                // Show notification immediately (no network needed)
                for (geofence in triggeringGeofences) {
                    val gfMeta = metadata.optJSONObject(geofence.requestId)
                    val placeName = gfMeta?.optString("placeName", "a place") ?: "a place"
                    val interestName = gfMeta?.optString("interestName", "") ?: ""

                    val message = if (interestName.isNotEmpty()) {
                        "You're near $placeName \u2014 matches your interest in $interestName"
                    } else {
                        "You're near $placeName"
                    }

                    showNotification(context, geofence.requestId, placeName, message)
                }

                // Post location to server
                if (location != null) {
                    postLocation(context, location.latitude, location.longitude, location.accuracy.toDouble(), pendingResult)
                } else {
                    pendingResult.finish()
                }
            }
            Geofence.GEOFENCE_TRANSITION_EXIT -> {
                // Post location update on exit (no notification)
                if (location != null) {
                    postLocation(context, location.latitude, location.longitude, location.accuracy.toDouble(), pendingResult)
                } else {
                    pendingResult.finish()
                }
            }
            else -> pendingResult.finish()
        }
    }

    private fun showNotification(context: Context, geofenceId: String, title: String, message: String) {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("url", "${BuildConfig.BASE_URL}/map")
        }

        val pendingIntent = PendingIntent.getActivity(
            context,
            geofenceId.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, GroovitationApplication.CHANNEL_PLACES)
            .setContentTitle(title)
            .setContentText(message)
            .setSmallIcon(R.drawable.ic_notification)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()

        val notificationId = NOTIFICATION_ID_BASE + (geofenceId.hashCode() and 0xFFFF)
        try {
            NotificationManagerCompat.from(context).notify(notificationId, notification)
        } catch (e: SecurityException) {
            Log.w(TAG, "Missing notification permission", e)
        }
    }

    private fun postLocation(context: Context, latitude: Double, longitude: Double, accuracy: Double, pendingResult: PendingResult) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val personUuid = prefs.getString("person_uuid", null) ?: run {
            pendingResult.finish()
            return
        }
        val cookie = CookieManager.getInstance().getCookie(BuildConfig.BASE_URL)
            ?: prefs.getString(LocationTrackingService.KEY_SESSION_COOKIE, null)
            ?: run {
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
                    Log.d(TAG, "Location posted on geofence transition")
                } else {
                    Log.w(TAG, "Failed to post location: ${response.code}")
                }
                response.close()
            } catch (e: Exception) {
                Log.e(TAG, "Error posting location", e)
            } finally {
                pendingResult.finish()
            }
        }
    }
}
