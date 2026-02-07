package io.blaha.groovitation

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
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

class GroovitationApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        configureHotwire()
        createNotificationChannels()
    }

    private fun configureHotwire() {
        Hotwire.config.debugLoggingEnabled = BuildConfig.DEBUG
        Hotwire.config.jsonConverter = KotlinXJsonConverter()
        Hotwire.config.userAgent = BuildConfig.USER_AGENT_EXTENSION
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
