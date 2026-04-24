package io.blaha.groovitation.services

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
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
 * #845: shared rendering for proximity notifications.
 *
 * Pre-#845 this lived inside `GeofenceBroadcastReceiver.showNotification` and
 * fired locally on every geofence ENTER. The receiver no longer renders —
 * notifications are server-driven via FCM, and this helper is what the FCM
 * service (`GroovitationMessagingService`) calls when it receives a payload
 * with `data["type"] == "proximity"`.
 *
 * Two responsibilities, kept together because they're a single conceptual
 * unit ("display a proximity notification + ack the server"):
 *
 *   - [show]: render the rich BigPictureStyle notification, identical to the
 *     pre-#845 geofence-enter rendering (title, subtitle, optional hero
 *     image, deep link, CHANNEL_PLACES).
 *   - [postProximityNotified]: best-effort POST to `/api/proximity/notified`
 *     after display. Mirrors the contract the geofence receiver used to
 *     hold so the server's 90-day suppression window keeps working from the
 *     new path.
 */
object ProximityNotificationRenderer {

    private const val TAG = "ProximityRenderer"
    private const val NOTIFICATION_ID_BASE = 50000

    private val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .writeTimeout(8, TimeUnit.SECONDS)
            .readTimeout(8, TimeUnit.SECONDS)
            .build()
    }

    /**
     * Render the notification. [dedupKey] disambiguates two simultaneous
     * proximity notifications on the same device (e.g. two cards within the
     * same fan-out batch). Use the server-provided `targetId` when available;
     * fall back to a hash of (kind, id) otherwise.
     */
    fun show(
        context: Context,
        title: String,
        message: String,
        imageUrl: String?,
        deepLink: String?,
        dedupKey: String,
    ) {
        // Normalize the deep link the same way IncomingPushNotificationNotifier
        // does for plain pushes — proximity tap should land on the deep link
        // server provided (`/map?site=...`) or fall back to /map.
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
            dedupKey.hashCode(),
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

        // Synchronous bitmap fetch on the FCM receiver thread, same budget as
        // the pre-#845 geofence path. FCM's onMessageReceived has roughly 10s
        // before the OS reclaims the process; an HTTPS image fetch fits well
        // inside that window. Falls back to plain text if the fetch fails.
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

        val notificationId = NOTIFICATION_ID_BASE + (dedupKey.hashCode() and 0xFFFF)
        try {
            NotificationManagerCompat.from(context).notify(notificationId, builder.build())
        } catch (e: SecurityException) {
            Log.w(TAG, "Missing notification permission", e)
        }
    }

    /**
     * POST to `/api/proximity/notified` so the server records the delivery.
     * Best-effort: a network blip / 401 / etc. is fine — the server's race-
     * guarded ack row is already written by the dispatcher (#845), this POST
     * just refreshes `notified_at` to NOW so the 90-day window restarts from
     * the moment the user actually saw the notification rather than the
     * moment the dispatcher claimed the row.
     */
    fun postProximityNotified(context: Context, targetKind: String, targetId: String) {
        if (targetKind.isBlank() || targetId.isBlank()) return
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
}
