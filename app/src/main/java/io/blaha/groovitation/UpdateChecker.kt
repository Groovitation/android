package io.blaha.groovitation

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

data class UpdateInfo(
    val latestVersionName: String,
    val latestVersionCode: Int,
    val downloadUrl: String
)

object UpdateChecker {
    private const val TAG = "UpdateChecker"
    private const val VERSION_URL = "${BuildConfig.BASE_URL}/android/version.json"

    suspend fun checkForUpdate(): UpdateInfo? = withContext(Dispatchers.IO) {
        try {
            val client = OkHttpClient()
            val request = Request.Builder()
                .url(VERSION_URL)
                .build()

            val response = client.newCall(request).execute()
            if (!response.isSuccessful) {
                Log.w(TAG, "Version check failed: ${response.code}")
                response.close()
                return@withContext null
            }

            val body = response.body?.string()
            response.close()

            if (body == null) {
                Log.w(TAG, "Version check returned empty body")
                return@withContext null
            }

            val json = JSONObject(body)
            val latestVersionCode = json.getInt("latest_version_code")
            val latestVersionName = json.getString("latest_version_name")
            val downloadUrl = json.getString("download_url")

            if (latestVersionCode > BuildConfig.VERSION_CODE) {
                Log.d(TAG, "Update available: $latestVersionName (code $latestVersionCode)")
                UpdateInfo(latestVersionName, latestVersionCode, downloadUrl)
            } else {
                Log.d(TAG, "App is up to date (installed=${BuildConfig.VERSION_CODE}, latest=$latestVersionCode)")
                null
            }
        } catch (e: Exception) {
            Log.w(TAG, "Version check failed", e)
            null
        }
    }
}
