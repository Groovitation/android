package io.blaha.groovitation

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.google.firebase.messaging.RemoteMessage

data class IncomingPushNotification(
    val title: String,
    val body: String,
    val deepLink: String?,
    val channel: String,
) {
    fun resolvedDeepLink(): String = normalizeDeepLink(deepLink) ?: "${BuildConfig.BASE_URL}/map"

    fun buildTapIntent(context: Context): Intent =
        Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(EXTRA_URL, resolvedDeepLink())
        }

    companion object {
        const val TEST_ACTION_SIMULATE_PUSH = "io.blaha.groovitation.TEST_PUSH"
        const val EXTRA_TITLE = "title"
        const val EXTRA_BODY = "body"
        const val EXTRA_URL = "url"
        const val EXTRA_CHANNEL = "channel"

        fun fromRemoteMessage(message: RemoteMessage): IncomingPushNotification? {
            val notification = message.notification
            val title = notification?.title ?: message.data[EXTRA_TITLE] ?: "Groovitation"
            val body = notification?.body ?: message.data[EXTRA_BODY] ?: ""
            val deepLink = normalizeDeepLink(message.data[EXTRA_URL])
            val channel = message.data[EXTRA_CHANNEL].orEmpty()
                .ifBlank { GroovitationApplication.CHANNEL_DEFAULT }

            if (notification == null && message.data.isEmpty()) {
                return null
            }

            return IncomingPushNotification(
                title = title,
                body = body,
                deepLink = deepLink,
                channel = channel,
            )
        }

        fun fromTestIntent(intent: Intent): IncomingPushNotification {
            return IncomingPushNotification(
                title = intent.getStringExtra(EXTRA_TITLE).orEmpty().ifBlank { "Groovitation" },
                body = intent.getStringExtra(EXTRA_BODY).orEmpty(),
                deepLink = normalizeDeepLink(intent.getStringExtra(EXTRA_URL)),
                channel = intent.getStringExtra(EXTRA_CHANNEL).orEmpty()
                    .ifBlank { GroovitationApplication.CHANNEL_DEFAULT },
            )
        }

        private fun normalizeDeepLink(raw: String?): String? {
            val trimmed = raw?.trim().orEmpty()
            if (trimmed.isEmpty()) return null
            if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
                return trimmed
            }
            return if (trimmed.startsWith("/")) {
                "${BuildConfig.BASE_URL}$trimmed"
            } else {
                "${BuildConfig.BASE_URL}/$trimmed"
            }
        }
    }
}

class IncomingPushNotificationNotifier(
    private val context: Context,
) {
    fun show(
        push: IncomingPushNotification,
        notificationId: Int = System.currentTimeMillis().toInt(),
    ): Int {
        val pendingIntent = PendingIntent.getActivity(
            context,
            notificationId,
            push.buildTapIntent(context),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            NotificationTapActivityStart.creatorOptions(),
        )

        val notification = NotificationCompat.Builder(context, push.channel)
            .setContentTitle(push.title)
            .setContentText(push.body)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setDefaults(Notification.DEFAULT_ALL)
            .build()

        val notificationManager = context.getSystemService(NotificationManager::class.java)
        notificationManager.notify(notificationId, notification)
        return notificationId
    }
}
