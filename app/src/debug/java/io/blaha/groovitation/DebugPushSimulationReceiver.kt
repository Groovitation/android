package io.blaha.groovitation

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

class DebugPushSimulationReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != IncomingPushNotification.DEBUG_ACTION_SIMULATE_PUSH) {
            return
        }

        val push = IncomingPushNotification.fromDebugIntent(intent)
        IncomingPushNotificationNotifier(context.applicationContext).show(push)
        Log.d(TAG, "Simulated push notification shown for CI: ${push.title}")
    }

    companion object {
        private const val TAG = "DebugPushSimulation"
    }
}
