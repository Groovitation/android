package io.blaha.groovitation

import android.content.Context
import android.util.Log
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

object NotificationTokenRegistrar {
    private const val TAG = "NotificationTokenReg"

    fun register(
        context: Context,
        httpClient: OkHttpClient,
        url: String,
        token: String,
        cookie: String
    ): Boolean {
        if (NotificationTestHooks.shouldCaptureTokenRegistrations(context)) {
            NotificationTestHooks.recordTokenRegistration(context, url, token, cookie)
            Log.d(TAG, "Captured notification token registration for test: $token")
            return true
        }

        return try {
            val json = JSONObject().apply {
                put("token", token)
                put("platform", "android")
            }

            val request = Request.Builder()
                .url(url)
                .post(json.toString().toRequestBody("application/json".toMediaType()))
                .addHeader("Content-Type", "application/json")
                .addHeader("Cookie", cookie)
                .build()

            httpClient.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    true
                } else {
                    val bodyPreview = response.body?.let { response.peekBody(512).string() }.orEmpty()
                    Log.w(
                        TAG,
                        "Token registration failed: HTTP ${response.code} ${response.message} body=$bodyPreview"
                    )
                    false
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error registering notification token", e)
            false
        }
    }
}
