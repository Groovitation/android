package io.blaha.groovitation

import android.content.Context
import android.util.Log
import android.webkit.CookieManager
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import io.blaha.groovitation.services.ProximityNotificationRenderer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient

/**
 * Firebase Cloud Messaging service for Groovitation.
 *
 * Handles incoming push notifications and FCM token refresh events.
 */
class GroovitationMessagingService : FirebaseMessagingService() {

    companion object {
        private const val TAG = "GroovitationFCM"

        fun showNotification(
            context: Context,
            title: String,
            body: String,
            deepLink: String?,
            channel: String
        ) {
            val push = IncomingPushNotification(
                title = title.ifBlank { "Groovitation" },
                body = body,
                deepLink = deepLink,
                channel = channel.ifBlank { GroovitationApplication.CHANNEL_DEFAULT }
            )
            IncomingPushNotificationNotifier(context.applicationContext).show(push)
            Log.d(TAG, "Notification shown: ${push.title}")
        }

        fun handleTokenRefresh(
            context: Context,
            token: String,
            httpClient: OkHttpClient = OkHttpClient(),
            scope: CoroutineScope = CoroutineScope(Dispatchers.IO)
        ) {
            Log.d(TAG, "FCM token refreshed: $token")
            TokenStorage.fcmToken = token

            val cookie = try {
                CookieManager.getInstance().getCookie(BuildConfig.BASE_URL)
            } catch (e: Exception) {
                null
            }
            if (cookie.isNullOrEmpty()) return

            val registrationUrl = "${BuildConfig.BASE_URL}/api/notifications/tokens"
            scope.launch {
                val success = NotificationTokenRegistrar.register(
                    context = context.applicationContext,
                    httpClient = httpClient,
                    url = registrationUrl,
                    token = token,
                    cookie = cookie
                )
                if (success) {
                    Log.d(TAG, "Refreshed FCM token registered with server")
                } else {
                    Log.w(TAG, "Token registration failed")
                }
            }
        }
    }

    private val httpClient = OkHttpClient()
    private val scope = CoroutineScope(Dispatchers.IO)

    /**
     * Called when FCM token is created or refreshed.
     */
    override fun onNewToken(token: String) {
        super.onNewToken(token)
        handleTokenRefresh(
            context = this,
            token = token,
            httpClient = httpClient,
            scope = scope
        )
    }

    /**
     * Called when a message is received.
     *
     * #845: payloads with `data["type"] == "proximity"` go through
     * [ProximityNotificationRenderer] which renders the rich
     * BigPictureStyle notification (with hero image) and POSTs the
     * `/api/proximity/notified` ack. Other payloads use the existing
     * generic [IncomingPushNotificationNotifier] path.
     */
    override fun onMessageReceived(message: RemoteMessage) {
        super.onMessageReceived(message)
        Log.d(TAG, "Message received from: ${message.from}")

        val data = message.data
        if (data["type"] == "proximity") {
            handleProximityMessage(applicationContext, message)
            return
        }

        val push = IncomingPushNotification.fromRemoteMessage(message)
        if (push != null) {
            showNotification(applicationContext, push.title, push.body, push.deepLink, push.channel)
        }
    }

    internal fun handleProximityMessage(context: Context, message: RemoteMessage) {
        val data = message.data
        val notification = message.notification
        val title = notification?.title
            ?: data[IncomingPushNotification.EXTRA_TITLE]
            ?: "Groovitation"
        val body = notification?.body
            ?: data[IncomingPushNotification.EXTRA_BODY]
            ?: ""
        val targetKind = data["targetKind"].orEmpty()
        val targetId = data["targetId"].orEmpty()
        // Server's data payload uses `imageUrl`; deep link is `url` (the same
        // field IncomingPushNotification.EXTRA_URL maps to).
        val imageUrl = data["imageUrl"]?.takeIf { it.isNotBlank() }
        val deepLink = data[IncomingPushNotification.EXTRA_URL]?.takeIf { it.isNotBlank() }
        // Dedup key: prefer the server-provided targetId, else fall back to
        // a hash of the (kind, id) pair so two simultaneous fan-out pushes
        // for different targets don't collapse into one tap-target.
        val dedupKey = if (targetId.isNotBlank()) "$targetKind:$targetId" else "proximity:${message.messageId ?: System.currentTimeMillis()}"

        ProximityNotificationRenderer.show(
            context = context,
            title = title,
            message = body,
            imageUrl = imageUrl,
            deepLink = deepLink,
            dedupKey = dedupKey,
        )
        ProximityNotificationRenderer.postProximityNotified(context, targetKind, targetId)
    }

}
