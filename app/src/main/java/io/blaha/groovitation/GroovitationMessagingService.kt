package io.blaha.groovitation

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationCompat
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage

/**
 * Firebase Cloud Messaging service for Groovitation.
 *
 * Handles incoming push notifications and FCM token refresh events.
 */
class GroovitationMessagingService : FirebaseMessagingService() {

    companion object {
        private const val TAG = "GroovitationFCM"
    }

    /**
     * Called when FCM token is created or refreshed.
     */
    override fun onNewToken(token: String) {
        super.onNewToken(token)
        Log.d(TAG, "FCM token refreshed: $token")
        // Store token for later registration via Bridge Component
        TokenStorage.fcmToken = token
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
        // Create intent for notification tap
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            deepLink?.let { putExtra("url", it) }
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
}
