package io.blaha.groovitation

import android.content.Context

internal object FcmTokenStateStore {
    private const val PREFS = "fcm_token_state"
    private const val KEY_TOKEN = "registered_token"
    private const val KEY_TIMESTAMP_MS = "registered_at_ms"
    internal const val REREGISTER_INTERVAL_MS = 24L * 60L * 60L * 1000L

    fun shouldRegister(
        context: Context,
        currentToken: String,
        nowMs: Long = System.currentTimeMillis()
    ): Boolean {
        val prefs = prefs(context)
        val storedToken = prefs.getString(KEY_TOKEN, null) ?: return true
        if (storedToken != currentToken) return true

        val storedTimestampMs = prefs.getLong(KEY_TIMESTAMP_MS, 0L)
        return nowMs - storedTimestampMs > REREGISTER_INTERVAL_MS
    }

    fun recordSuccess(
        context: Context,
        token: String,
        nowMs: Long = System.currentTimeMillis()
    ) {
        prefs(context).edit()
            .putString(KEY_TOKEN, token)
            .putLong(KEY_TIMESTAMP_MS, nowMs)
            .apply()
    }

    internal fun clear(context: Context) {
        prefs(context).edit().clear().commit()
    }

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(
        PREFS,
        Context.MODE_PRIVATE
    )
}
