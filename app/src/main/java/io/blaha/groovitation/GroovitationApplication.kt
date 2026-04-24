package io.blaha.groovitation

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.IntentFilter
import android.os.Build
import dev.hotwire.core.bridge.BridgeComponentFactory
import dev.hotwire.core.bridge.KotlinXJsonConverter
import dev.hotwire.core.config.Hotwire
import dev.hotwire.navigation.config.registerBridgeComponents
import dev.hotwire.navigation.config.registerFragmentDestinations
import io.blaha.groovitation.components.BiometricComponent
import io.blaha.groovitation.components.LocationComponent
import io.blaha.groovitation.components.NotificationTokenComponent
import io.blaha.groovitation.components.ShareComponent
import io.blaha.groovitation.services.LocationWorker

class GroovitationApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        configureHotwire()
        createNotificationChannels()
        // Belt-and-suspenders to MainActivity.tryStartBackgroundTracking +
        // BootReceiver: if WorkManager cancels the periodic location worker
        // during OEM cleanup (Samsung Deep Sleep, Adaptive Battery, etc.)
        // and the user doesn't reopen the app, there is nothing else that
        // re-enqueues it. Application.onCreate runs every time the process
        // is started — including when a BroadcastReceiver, FCM push, or
        // geofence event wakes us — so an idempotent KEEP enqueue here
        // ensures periodic work re-homes itself the moment any part of the
        // app spins up. (#795)
        //
        // Wrapped in try/catch because Robolectric and some OEM-test setups
        // don't have WorkManager initialized at Application.onCreate time;
        // we'd rather skip the re-enqueue than crash app startup. The
        // foreground path (MainActivity.onResume → tryStartBackgroundTracking)
        // is the user-visible path and is independently exercised on every
        // app open.
        try {
            LocationWorker.enqueuePeriodicWork(this)
        } catch (e: IllegalStateException) {
            android.util.Log.w(
                "GroovitationApplication",
                "WorkManager not initialized at Application.onCreate; skipping " +
                    "periodic re-enqueue (will retry on next MainActivity.onResume)",
                e
            )
        }
        if (BuildConfig.DEBUG) {
            registerDebugReceivers()
        }
    }

    private fun registerDebugReceivers() {
        registerReceiver(
            TestPushReceiver(),
            IntentFilter(TestPushReceiver.ACTION),
            RECEIVER_EXPORTED
        )
        registerReceiver(
            NotificationTestReceiver(),
            IntentFilter().apply {
                addAction(NotificationTestReceiver.ACTION_CONFIGURE)
                addAction(NotificationTestReceiver.ACTION_GET_ACTIVE_NOTIFICATION)
                addAction(NotificationTestReceiver.ACTION_GET_LAST_TOKEN_REGISTRATION)
                addAction(NotificationTestReceiver.ACTION_SIMULATE_TOKEN_REFRESH)
                addAction(NotificationTestReceiver.ACTION_TAP_ACTIVE_NOTIFICATION)
            },
            RECEIVER_EXPORTED
        )
    }

    private fun configureHotwire() {
        Hotwire.config.debugLoggingEnabled = BuildConfig.DEBUG
        Hotwire.config.jsonConverter = KotlinXJsonConverter()
        Hotwire.config.applicationUserAgentPrefix = BuildConfig.USER_AGENT_EXTENSION
        Hotwire.config.makeCustomWebView = { context ->
            GroovitationWebView(context)
        }

        Hotwire.registerBridgeComponents(
            BridgeComponentFactory("notification-token", ::NotificationTokenComponent),
            BridgeComponentFactory("location", ::LocationComponent),
            BridgeComponentFactory("biometric", ::BiometricComponent),
            BridgeComponentFactory("share", ::ShareComponent)
        )

        // Register fragment destinations - use our custom fragment for web content
        Hotwire.registerFragmentDestinations(
            GroovitationWebFragment::class
        )
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val notificationManager = getSystemService(NotificationManager::class.java)

            val defaultChannel = NotificationChannel(
                CHANNEL_DEFAULT,
                "General Notifications",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "General app notifications"
                enableVibration(true)
            }

            val friendsChannel = NotificationChannel(
                CHANNEL_FRIENDS,
                "Friend Activity",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Notifications about your friends' activity"
                enableVibration(true)
            }

            val placesChannel = NotificationChannel(
                CHANNEL_PLACES,
                "Nearby Places",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Notifications about nearby places that match your interests"
                enableVibration(true)
            }

            notificationManager.createNotificationChannels(
                listOf(defaultChannel, friendsChannel, placesChannel)
            )
        }
    }

    companion object {
        const val CHANNEL_DEFAULT = "groovitation_default"
        const val CHANNEL_FRIENDS = "groovitation_friends"
        const val CHANNEL_PLACES = "groovitation_places"
    }
}
