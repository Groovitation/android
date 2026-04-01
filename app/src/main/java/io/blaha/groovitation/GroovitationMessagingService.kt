package io.blaha.groovitation

import android.content.Context
import android.util.Log
import android.webkit.CookieManager
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
        val push = IncomingPushNotification.fromRemoteMessage(message)
        if (push != null) {
            showNotification(applicationContext, push.title, push.body, push.deepLink, push.channel)
        }
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
