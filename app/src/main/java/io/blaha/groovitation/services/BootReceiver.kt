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

        Log.d(TAG, "Restarting location tracking via WorkManager")
        LocationWorker.enqueuePeriodicWork(context)
        LocationWorker.enqueueOneShot(context)
    }
}
