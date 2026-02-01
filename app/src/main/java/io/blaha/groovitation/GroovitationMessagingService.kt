package io.blaha.groovitation

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.util.Log
import android.webkit.CookieManager
import androidx.core.app.NotificationCompat
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

/**
 * Firebase Cloud Messaging service for Groovitation.
 *
 * Handles incoming push notifications and FCM token refresh events.
 */
class GroovitationMessagingService : FirebaseMessagingService() {

    companion object {
        private const val TAG = "GroovitationFCM"
    }

    private val httpClient = OkHttpClient()
    private val scope = CoroutineScope(Dispatchers.IO)

    /**
     * Called when FCM token is created or refreshed.
     */
    override fun onNewToken(token: String) {
        super.onNewToken(token)
        Log.d(TAG, "FCM token refreshed: $token")
        TokenStorage.fcmToken = token
        registerTokenWithServer(token)
    }

    /**
     * Called when a message is received.
     */
    override fun onMessageReceived(message: RemoteMessage) {
        super.onMessageReceived(message)
        Log.d(TAG, "Message received from: ${message.from}")

        // Handle notification payload
        message.notification?.let { notification ->
            showNotification(
                title = notification.title ?: "Groovitation",
                body = notification.body ?: "",
                deepLink = message.data["url"],
                channel = message.data["channel"] ?: GroovitationApplication.CHANNEL_DEFAULT
            )
        }

        // Handle data-only messages
        if (message.notification == null && message.data.isNotEmpty()) {
            val title = message.data["title"] ?: "Groovitation"
            val body = message.data["body"] ?: ""
            val deepLink = message.data["url"]
            val channel = message.data["channel"] ?: GroovitationApplication.CHANNEL_DEFAULT

            showNotification(title, body, deepLink, channel)
        }
    }

    /**
     * Display a notification to the user.
     */
    private fun showNotification(
        title: String,
        body: String,
        deepLink: String?,
        channel: String
    ) {
        // Create intent for notification tap — always navigate to map
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("url", deepLink ?: "${BuildConfig.BASE_URL}/map")
        }

        val pendingIntent = PendingIntent.getActivity(
            this,
            System.currentTimeMillis().toInt(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Build notification
        val notification = NotificationCompat.Builder(this, channel)
            .setContentTitle(title)
            .setContentText(body)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .build()

        // Show notification
        val notificationManager = getSystemService(NotificationManager::class.java)
        val notificationId = System.currentTimeMillis().toInt()
        notificationManager.notify(notificationId, notification)

        Log.d(TAG, "Notification shown: $title")
    }

    /**
     * Register refreshed token with the server if we have an active session.
     */
    private fun registerTokenWithServer(token: String) {
        val cookie = try {
            CookieManager.getInstance().getCookie(BuildConfig.BASE_URL)
        } catch (e: Exception) {
            null
        }
        if (cookie.isNullOrEmpty()) return

        scope.launch {
            try {
                val json = JSONObject().apply {
                    put("token", token)
                    put("platform", "android")
                }

                val request = Request.Builder()
                    .url("${BuildConfig.BASE_URL}/api/notifications/tokens")
                    .post(json.toString().toRequestBody("application/json".toMediaType()))
                    .addHeader("Content-Type", "application/json")
                    .addHeader("Cookie", cookie)
                    .build()

                val response = httpClient.newCall(request).execute()
                if (response.isSuccessful) {
                    Log.d(TAG, "Refreshed FCM token registered with server")
                } else {
                    Log.w(TAG, "Token registration failed: ${response.code}")
                }
                response.close()
            } catch (e: Exception) {
                Log.e(TAG, "Error registering token with server", e)
            }
        }
    }
}
