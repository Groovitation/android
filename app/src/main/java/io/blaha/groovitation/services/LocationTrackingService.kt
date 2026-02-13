package io.blaha.groovitation.services

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.util.Log
import android.webkit.CookieManager
import io.blaha.groovitation.BuildConfig

/**
 * Transition shim for upgrading from foreground service to geofence-based tracking.
 *
 * On ACTION_START: enqueues WorkManager periodic task and stops self (no foreground service).
 * On ACTION_STOP: cancels WorkManager and removes geofences.
 *
 * Keeps companion object static helpers (saveConfig, refreshCookie, etc.) unchanged
 * since they write to SharedPreferences used by LocationWorker and GeofenceManager.
 *
 * Remove this service in a follow-up release once the foreground service is no longer
 * referenced by any installed version.
 */
class LocationTrackingService : Service() {

    companion object {
        private const val TAG = "LocationTrackingService"
        private const val PREFS_NAME = "location_tracking_prefs"
        private const val KEY_PERSON_UUID = "person_uuid"
        const val KEY_SESSION_COOKIE = "session_cookie"
        private const val KEY_ENABLED = "tracking_enabled"

        const val ACTION_START = "io.blaha.groovitation.START_LOCATION_TRACKING"
        const val ACTION_STOP = "io.blaha.groovitation.STOP_LOCATION_TRACKING"
        const val EXTRA_PERSON_UUID = "person_uuid"

        fun isEnabled(context: Context): Boolean {
            return context.getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                .getBoolean(KEY_ENABLED, false)
        }

        fun getPersonUuid(context: Context): String? {
            return context.getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                .getString(KEY_PERSON_UUID, null)
        }

        fun saveConfig(context: Context, personUuid: String) {
            val cookie = CookieManager.getInstance()
                .getCookie(BuildConfig.BASE_URL) ?: ""

            context.getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
                .putString(KEY_PERSON_UUID, personUuid)
                .putString(KEY_SESSION_COOKIE, cookie)
                .putBoolean(KEY_ENABLED, true)
                .apply()

            Log.d(TAG, "Config saved for person $personUuid")
        }

        fun refreshCookie(context: Context) {
            val prefs = context.getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            if (!prefs.getBoolean(KEY_ENABLED, false)) return

            val cookie = CookieManager.getInstance()
                .getCookie(BuildConfig.BASE_URL) ?: ""
            if (cookie.isNotEmpty()) {
                prefs.edit().putString(KEY_SESSION_COOKIE, cookie).apply()
                Log.d(TAG, "Session cookie refreshed")
            }
        }

        fun clearConfig(context: Context) {
            context.getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
                .clear()
                .apply()
        }

        /**
         * Formerly started the foreground service. Now enqueues WorkManager instead.
         * Kept for backward compatibility with BootReceiver and other callers.
         */
        fun startIfEnabled(context: Context) {
            val prefs = context.getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            if (!prefs.getBoolean(KEY_ENABLED, false)) return
            prefs.getString(KEY_PERSON_UUID, null) ?: return

            LocationWorker.enqueuePeriodicWork(context)
            LocationWorker.enqueueOneShot(context)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "onStartCommand (transition shim): ${intent?.action}")

        when (intent?.action) {
            ACTION_STOP -> {
                LocationWorker.cancel(this)
                GeofenceManager(this).removeAllGeofences()
                clearConfig(this)
            }
            ACTION_START, null -> {
                // Transition: enqueue WorkManager instead of starting foreground service
                LocationWorker.enqueuePeriodicWork(this)
                LocationWorker.enqueueOneShot(this)
            }
        }

        stopSelf()
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
