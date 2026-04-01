package io.blaha.groovitation

import android.content.Context
import org.json.JSONObject

data class RecordedTokenRegistration(
    val url: String,
    val token: String,
    val cookie: String
) {
    fun toJson(): String = JSONObject()
        .put("url", url)
        .put("token", token)
        .put("cookie", cookie)
        .toString()

    companion object {
        fun fromJson(raw: String): RecordedTokenRegistration? = runCatching {
            val json = JSONObject(raw)
            RecordedTokenRegistration(
                url = json.getString("url"),
                token = json.getString("token"),
                cookie = json.getString("cookie")
            )
        }.getOrNull()
    }
}

object NotificationTestHooks {
    private const val PREFS_NAME = "notification_test_hooks"
    private const val KEY_CAPTURE_TOKEN_REGISTRATIONS = "capture_token_registrations"
    private const val KEY_FAKE_FCM_TOKEN = "fake_fcm_token"
    private const val KEY_LAST_TOKEN_REGISTRATION = "last_token_registration"

    fun shouldCaptureTokenRegistrations(context: Context): Boolean {
        if (!BuildConfig.DEBUG) return false
        return prefs(context).getBoolean(KEY_CAPTURE_TOKEN_REGISTRATIONS, false)
    }

    fun fakeFcmToken(context: Context): String? {
        if (!BuildConfig.DEBUG) return null
        return prefs(context).getString(KEY_FAKE_FCM_TOKEN, null)
    }

    fun lastRecordedTokenRegistration(context: Context): RecordedTokenRegistration? {
        if (!BuildConfig.DEBUG) return null
        val raw = prefs(context).getString(KEY_LAST_TOKEN_REGISTRATION, null) ?: return null
        return RecordedTokenRegistration.fromJson(raw)
    }

    fun recordTokenRegistration(
        context: Context,
        url: String,
        token: String,
        cookie: String
    ) {
        if (!BuildConfig.DEBUG) return
        prefs(context).edit()
            .putString(
                KEY_LAST_TOKEN_REGISTRATION,
                RecordedTokenRegistration(url = url, token = token, cookie = cookie).toJson()
            )
            .commit()
    }

    fun setCaptureTokenRegistrations(context: Context, enabled: Boolean) {
        prefs(context).edit()
            .putBoolean(KEY_CAPTURE_TOKEN_REGISTRATIONS, enabled)
            .commit()
    }

    fun setFakeFcmToken(context: Context, token: String?) {
        val editor = prefs(context).edit()
        if (token == null) {
            editor.remove(KEY_FAKE_FCM_TOKEN)
        } else {
            editor.putString(KEY_FAKE_FCM_TOKEN, token)
        }
        editor.commit()
    }

    fun clear(context: Context) {
        prefs(context).edit().clear().commit()
    }

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(
        PREFS_NAME,
        Context.MODE_PRIVATE
    )
}
