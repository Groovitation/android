package io.blaha.groovitation.services

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log
import androidx.core.content.ContextCompat

/**
 * Restarts background location tracking after device boot.
 * Enqueues WorkManager periodic task instead of starting foreground service.
 */
class BootReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "BootReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return

        Log.d(TAG, "Boot completed, checking if location tracking should restart")

        if (!LocationTrackingService.isEnabled(context)) {
            Log.d(TAG, "Tracking not enabled, skipping")
            return
        }

        val hasPermission = ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED || ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        if (!hasPermission) {
            Log.w(TAG, "Location permission not granted, skipping")
            return
        }

        // Geofences are lost on reboot (even with NEVER_EXPIRE).
        // Clear interest geofence position so the one-shot worker forces a refresh.
        val prefs = context.getSharedPreferences("location_tracking_prefs", Context.MODE_PRIVATE)
        prefs.edit()
            .remove("last_geofence_lat")
            .remove("last_geofence_lng")
            .remove("last_geofence_refresh_time")
            .apply()

        // Re-register the rolling tracking geofence at the last known position
        // so the chain resumes immediately without waiting for WorkManager.
        val trackingLat = prefs.getFloat(GeofenceManager.KEY_TRACKING_LAT, 0f).toDouble()
        val trackingLng = prefs.getFloat(GeofenceManager.KEY_TRACKING_LNG, 0f).toDouble()
        if (trackingLat != 0.0 || trackingLng != 0.0) {
            Log.d(TAG, "Re-registering tracking geofence at $trackingLat, $trackingLng")
            GeofenceManager(context).registerTrackingGeofence(trackingLat, trackingLng)
        }

        Log.d(TAG, "Restarting location tracking via WorkManager")
        LocationWorker.enqueuePeriodicWork(context)
        LocationWorker.enqueueOneShot(context)
    }
}
