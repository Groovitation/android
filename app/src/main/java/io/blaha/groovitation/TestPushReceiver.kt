package io.blaha.groovitation

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Debug-only BroadcastReceiver that simulates a push notification via ADB broadcast.
 *
 * Usage (from adb shell):
 *   am broadcast -a io.blaha.groovitation.TEST_PUSH \
 *     --es title "Test Title" --es body "Test Body" \
 *     --es url "http://10.0.2.2:3000/events/123" \
 *     io.blaha.groovitation
 *
 * Registered dynamically in GroovitationApplication only when BuildConfig.DEBUG is true.
 */
class TestPushReceiver : BroadcastReceiver() {

    companion object {
        const val ACTION = IncomingPushNotification.TEST_ACTION_SIMULATE_PUSH
        private const val TAG = "TestPushReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val push = IncomingPushNotification.fromTestIntent(intent)

        Log.d(TAG, "Test push received: title=${push.title} body=${push.body} url=${push.deepLink}")

        GroovitationMessagingService.showNotification(
            context.applicationContext,
            push.title,
            push.body,
            push.deepLink,
            push.channel
        )
    }
}
