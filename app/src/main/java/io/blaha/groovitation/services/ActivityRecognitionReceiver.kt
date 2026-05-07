package io.blaha.groovitation.services

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.google.android.gms.location.ActivityTransition
import com.google.android.gms.location.ActivityTransitionResult

/**
 * Slice E (#1043): receives `ActivityRecognitionClient` transition events
 * and writes the latest known classification into SharedPreferences via
 * [ActivityRecognitionTracker.recordLatestActivity]. The next location ping
 * (foreground or background) reads that value and stamps it on the payload
 * so the slice-D en-route trigger has a confidence input.
 *
 * Only ENTER transitions are persisted — EXIT events would otherwise
 * overwrite the latest classification with the activity the user just left,
 * which is the opposite of what the en-route trigger wants. ENTER for
 * `ON_FOOT` then ENTER for `IN_VEHICLE` overwrites cleanly because the
 * user only physically occupies one activity at a time from Play Services'
 * perspective.
 */
class ActivityRecognitionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (!ActivityTransitionResult.hasResult(intent)) {
            return
        }

        val result = ActivityTransitionResult.extractResult(intent) ?: return
        val now = System.currentTimeMillis()

        // Process events in the order they arrived. For a sequence like
        // ENTER(IN_VEHICLE), ENTER(STILL), the last ENTER wins, which
        // matches user-perceived current activity.
        for (event in result.transitionEvents) {
            if (event.transitionType != ActivityTransition.ACTIVITY_TRANSITION_ENTER) {
                continue
            }
            val wireName = ActivityRecognitionTracker.WIRE_NAMES[event.activityType] ?: continue
            ActivityRecognitionTracker.recordLatestActivity(context, wireName, now)
            Log.d(TAG, "ENTER $wireName")
        }
    }

    companion object {
        private const val TAG = "ActivityRecognitionReceiver"
    }
}
