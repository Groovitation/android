package io.blaha.groovitation.components

import android.util.Log
import android.webkit.CookieManager
import androidx.fragment.app.Fragment
import dev.hotwire.core.bridge.BridgeComponent
import dev.hotwire.core.bridge.BridgeDelegate
import dev.hotwire.core.bridge.Message
import dev.hotwire.navigation.destinations.HotwireDestination
import io.blaha.groovitation.TokenStorage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

class NotificationTokenComponent(
    name: String,
    private val delegate: BridgeDelegate<HotwireDestination>
) : BridgeComponent<HotwireDestination>(name, delegate) {

    companion object {
        private const val TAG = "NotificationTokenComp"
    }

    private val fragment: Fragment
        get() = delegate.destination.fragment

    private val httpClient = OkHttpClient()
    private val scope = CoroutineScope(Dispatchers.Main)

    override fun onReceive(message: Message) {
        Log.d(TAG, "Received message: ${message.event}")

        when (message.event) {
            "register" -> handleRegister(message)
            "connect" -> handleConnect(message)
            else -> Log.w(TAG, "Unknown event: ${message.event}")
        }
    }

    private fun handleConnect(message: Message) {
        val token = TokenStorage.fcmToken
        replyTo("connect", ConnectReply(hasToken = token != null))
    }

    private fun handleRegister(message: Message) {
        val data = message.data<RegisterData>()
        val postUrl = data?.postUrl

        if (postUrl.isNullOrEmpty()) {
            Log.e(TAG, "No postUrl provided for token registration")
            replyTo("register", RegisterReply(success = false, error = "No postUrl provided"))
            return
        }

        val token = TokenStorage.fcmToken
        if (token.isNullOrEmpty()) {
            Log.e(TAG, "No FCM token available")
            replyTo("register", RegisterReply(success = false, error = "No FCM token available"))
            return
        }

        scope.launch {
            val success = postTokenToServer(postUrl, token)
            replyTo("register", RegisterReply(success = success))
        }
    }

    private suspend fun postTokenToServer(postUrl: String, token: String): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                val baseUrl = delegate.destination.location ?: ""
                val fullUrl = if (postUrl.startsWith("http")) {
                    postUrl
                } else {
                    val uri = android.net.Uri.parse(baseUrl)
                    "${uri.scheme}://${uri.host}${if (uri.port > 0) ":${uri.port}" else ""}$postUrl"
                }

                Log.d(TAG, "Posting token to: $fullUrl")

                // Get cookies from WebView's CookieManager for authentication
                val cookies = CookieManager.getInstance().getCookie(fullUrl) ?: ""
                Log.d(TAG, "Using cookies: ${cookies.take(50)}...")

                val json = JSONObject().apply {
                    put("token", token)
                    put("platform", "android")
                }

                val requestBody = json.toString()
                    .toRequestBody("application/json".toMediaType())

                val requestBuilder = Request.Builder()
                    .url(fullUrl)
                    .post(requestBody)
                    .addHeader("Content-Type", "application/json")

                // Include cookies for authentication
                if (cookies.isNotEmpty()) {
                    requestBuilder.addHeader("Cookie", cookies)
                }

                val request = requestBuilder.build()
                val response = httpClient.newCall(request).execute()

                if (response.isSuccessful) {
                    Log.d(TAG, "Token registered successfully")
                    true
                } else {
                    Log.e(TAG, "Token registration failed: ${response.code}")
                    false
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error posting token", e)
                false
            }
        }
    }

    @Serializable
    data class RegisterData(
        @SerialName("postUrl") val postUrl: String? = null
    )

    @Serializable
    data class ConnectReply(
        @SerialName("hasToken") val hasToken: Boolean
    )

    @Serializable
    data class RegisterReply(
        @SerialName("success") val success: Boolean,
        @SerialName("error") val error: String? = null
    )
}
