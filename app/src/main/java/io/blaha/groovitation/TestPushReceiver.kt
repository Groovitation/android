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
        const val ACTION = "io.blaha.groovitation.TEST_PUSH"
        private const val TAG = "TestPushReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val title = intent.getStringExtra("title") ?: "Test Notification"
        val body = intent.getStringExtra("body") ?: ""
        val url = intent.getStringExtra("url")
        val channel = intent.getStringExtra("channel") ?: GroovitationApplication.CHANNEL_DEFAULT

        Log.d(TAG, "Test push received: title=$title body=$body url=$url")

        GroovitationMessagingService.showNotification(context, title, body, url, channel)
    }
}
