package io.blaha.groovitation.services

import android.Manifest
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import com.google.android.gms.location.ActivityRecognition
import com.google.android.gms.location.ActivityTransition
import com.google.android.gms.location.ActivityTransitionRequest
import com.google.android.gms.location.DetectedActivity
import com.google.android.gms.tasks.Task

/**
 * Slice E (#1043): coarse activity classification via Google Play Services
 * `ActivityRecognitionClient`.
 *
 * Two-part design:
 * 1. [registerTransitions] subscribes to enter/exit events for the activity
 *    types the en-route flow cares about (`IN_VEHICLE`, `ON_BICYCLE`,
 *    `ON_FOOT`, `WALKING`, `RUNNING`, `STILL`). Updates are delivered to
 *    [ActivityRecognitionReceiver] which writes the latest snapshot to
 *    SharedPreferences.
 * 2. [recentActivityOrNull] is the readside used by `LocationWorker` to
 *    stamp the location-ping payload. It returns `null` when no recent
 *    classification is available, when the cached value is stale beyond
 *    [STALE_AFTER_MS], or when the client never registered (permission
 *    denied / pre-API-29 device without Play Services).
 *
 * The wire-format strings (`in_vehicle`, `on_foot`, etc.) match the slice-E
 * contract fixture and the server-side parser in
 * `PeopleController.LocationUpdate.activity`.
 */
object ActivityRecognitionTracker {

    private const val TAG = "ActivityRecognitionTracker"
    private const val PREFS_NAME = "location_tracking_prefs"
    private const val KEY_LATEST_ACTIVITY = "latest_activity"
    private const val KEY_LATEST_ACTIVITY_TIMESTAMP = "latest_activity_timestamp"

    /**
     * Cached activity is treated as stale after 30 minutes. The
     * `ActivityRecognitionClient` wakes up on real transitions, so during a
     * long stretch in one mode (a 4-hour drive, an overnight sleep) we may
     * legitimately not see a fresh transition for hours. 30 minutes is
     * conservative — if the user has genuinely been in_vehicle for an hour
     * straight we'd rather omit the field than report a stale guess.
     */
    internal const val STALE_AFTER_MS = 30L * 60L * 1000L

    private val pendingIntentFlags: Int by lazy {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
    }

    /**
     * Stable mapping from `DetectedActivity` integer codes to the wire
     * strings the server expects. Anything outside this set is dropped at
     * the receiver — we never want to ship `unknown` or `tilting` because
     * neither carries useful signal for the en-route trigger.
     */
    internal val WIRE_NAMES: Map<Int, String> = mapOf(
        DetectedActivity.IN_VEHICLE to "in_vehicle",
        DetectedActivity.ON_BICYCLE to "on_bicycle",
        DetectedActivity.ON_FOOT to "on_foot",
        DetectedActivity.WALKING to "walking",
        DetectedActivity.RUNNING to "running",
        DetectedActivity.STILL to "still"
    )

    /**
     * Register for activity transitions. Returns the underlying Play
     * Services Task so callers can attach diagnostics. Caller is responsible
     * for handling permission state — this method bails out without
     * crashing when ACTIVITY_RECOGNITION is denied.
     */
    fun registerTransitions(context: Context): Task<Void>? {
        if (!hasPermission(context)) {
            Log.d(TAG, "Activity recognition permission not granted; skipping registration")
            return null
        }

        val transitions = WIRE_NAMES.keys.flatMap { activityType ->
            listOf(
                ActivityTransition.Builder()
                    .setActivityType(activityType)
                    .setActivityTransition(ActivityTransition.ACTIVITY_TRANSITION_ENTER)
                    .build(),
                ActivityTransition.Builder()
                    .setActivityType(activityType)
                    .setActivityTransition(ActivityTransition.ACTIVITY_TRANSITION_EXIT)
                    .build()
            )
        }

        val request = ActivityTransitionRequest(transitions)
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            0,
            Intent(context, ActivityRecognitionReceiver::class.java),
            pendingIntentFlags
        )

        val client = ActivityRecognition.getClient(context)
        return try {
            @Suppress("MissingPermission")
            client.requestActivityTransitionUpdates(request, pendingIntent).also {
                Log.d(TAG, "Requested activity transition updates")
            }
        } catch (e: SecurityException) {
            Log.w(TAG, "SecurityException registering activity transitions", e)
            null
        }
    }

    /**
     * Reads the most recent activity classification. Returns `null` when:
     *   - no classification has ever been recorded, or
     *   - the cached value is stale beyond [STALE_AFTER_MS], or
     *   - the cached wire-name doesn't match the known set (defensive).
     */
    @JvmStatic
    fun recentActivityOrNull(
        context: Context,
        nowMs: Long = System.currentTimeMillis()
    ): String? {
        val prefs = preferences(context)
        val activity = prefs.getString(KEY_LATEST_ACTIVITY, null) ?: return null
        val timestamp = prefs.getLong(KEY_LATEST_ACTIVITY_TIMESTAMP, 0L)
        if (timestamp <= 0L) return null
        if (nowMs - timestamp > STALE_AFTER_MS) return null
        if (activity !in WIRE_NAMES.values) return null
        return activity
    }

    /**
     * Receiver-side write API. Called by [ActivityRecognitionReceiver]
     * whenever a transition arrives — kept on the tracker for testability.
     */
    internal fun recordLatestActivity(
        context: Context,
        activity: String,
        timestamp: Long = System.currentTimeMillis()
    ) {
        if (activity !in WIRE_NAMES.values) {
            Log.w(TAG, "Refusing to record unknown activity wire name: $activity")
            return
        }
        preferences(context).edit()
            .putString(KEY_LATEST_ACTIVITY, activity)
            .putLong(KEY_LATEST_ACTIVITY_TIMESTAMP, timestamp)
            .apply()
        Log.d(TAG, "Recorded activity=$activity at $timestamp")
    }

    private fun hasPermission(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            // Pre-API-29 the permission is install-time, not runtime.
            return true
        }
        return ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACTIVITY_RECOGNITION
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun preferences(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
