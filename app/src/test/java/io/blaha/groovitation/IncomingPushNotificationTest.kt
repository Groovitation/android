package io.blaha.groovitation

import android.app.Application
import android.app.ActivityOptions
import android.app.Notification
import android.app.NotificationManager
import android.content.Intent
import android.os.Bundle
import androidx.test.core.app.ApplicationProvider
import com.google.firebase.messaging.RemoteMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class IncomingPushNotificationTest {

    @Test
    fun remoteMessageDataPayloadUsesNormalizedUrlAndChannel() {
        val message = RemoteMessage.Builder("ci-debug-sender")
            .setMessageId("push-ci")
            .setData(
                mapOf(
                    IncomingPushNotification.EXTRA_TITLE to "CI Push",
                    IncomingPushNotification.EXTRA_BODY to "Tap to open map",
                    IncomingPushNotification.EXTRA_URL to "/map?push_ci=1",
                    IncomingPushNotification.EXTRA_CHANNEL to GroovitationApplication.CHANNEL_PLACES,
                )
            )
            .build()

        val push = IncomingPushNotification.fromRemoteMessage(message)

        assertNotNull(push)
        assertEquals("CI Push", push!!.title)
        assertEquals("Tap to open map", push.body)
        assertEquals(
            "${BuildConfig.BASE_URL}/map?push_ci=1",
            push.deepLink
        )
        assertEquals(GroovitationApplication.CHANNEL_PLACES, push.channel)
    }

    @Test
    fun testIntentFallsBackToDefaultRouteAndChannel() {
        val push = IncomingPushNotification.fromTestIntent(
            Intent(IncomingPushNotification.TEST_ACTION_SIMULATE_PUSH).apply {
                putExtra(IncomingPushNotification.EXTRA_TITLE, "CI Push")
            }
        )

        assertEquals("CI Push", push.title)
        assertEquals("", push.body)
        assertEquals("${BuildConfig.BASE_URL}/map", push.resolvedDeepLink())
        assertEquals(GroovitationApplication.CHANNEL_DEFAULT, push.channel)
    }

    @Test
    fun notifierPostsNotificationWithTapIntentForMainActivity() {
        val application = ApplicationProvider.getApplicationContext<Application>()
        val notificationManager = application.getSystemService(NotificationManager::class.java)
        notificationManager.cancelAll()

        val push = IncomingPushNotification(
            title = "CI Push",
            body = "Tap to open map",
            deepLink = "/map?push_ci=1",
            channel = GroovitationApplication.CHANNEL_DEFAULT,
        )

        IncomingPushNotificationNotifier(application).show(push, notificationId = 42)

        val notifications = shadowOf(notificationManager).allNotifications
        assertEquals(1, notifications.size)

        val notification = notifications.single()
        assertEquals("CI Push", notification.extras.getString(Notification.EXTRA_TITLE))
        assertEquals("Tap to open map", notification.extras.getString(Notification.EXTRA_TEXT))

        val shadowPendingIntent = shadowOf(notification.contentIntent)
        val tapIntent = shadowPendingIntent.savedIntent
        assertEquals(MainActivity::class.java.name, tapIntent.component?.className)
        assertEquals(
            "${BuildConfig.BASE_URL}/map?push_ci=1",
            tapIntent.getStringExtra(IncomingPushNotification.EXTRA_URL)
        )
        assertBundleMatches(expectedCreatorOptions(), shadowPendingIntent.options)
    }

    @Test
    fun notificationTestHooksCanFindAndTapMatchingNotification() {
        val application = ApplicationProvider.getApplicationContext<Application>()
        val notificationManager = application.getSystemService(NotificationManager::class.java)
        notificationManager.cancelAll()

        val push = IncomingPushNotification(
            title = "CI Push",
            body = "Tap to open map",
            deepLink = "/map?push_ci=1",
            channel = GroovitationApplication.CHANNEL_DEFAULT,
        )

        IncomingPushNotificationNotifier(application).show(push, notificationId = 43)

        assertTrue(
            NotificationTestHooks.hasActiveNotification(application, "CI Push", "Tap to open map")
        )
        assertEquals(
            "tapped",
            NotificationTestHooks.tapActiveNotification(application, "CI Push", "Tap to open map")
        )

        val startedIntent = shadowOf(application).nextStartedActivity
        assertEquals(MainActivity::class.java.name, startedIntent.component?.className)
        assertEquals(
            "${BuildConfig.BASE_URL}/map?push_ci=1",
            startedIntent.getStringExtra(IncomingPushNotification.EXTRA_URL)
        )
    }

    @Test
    fun notificationTapActivityStartHelpersUseBackgroundStartAllowedMode() {
        assertBundleMatches(expectedCreatorOptions(), NotificationTapActivityStart.creatorOptions())
        assertBundleMatches(expectedSenderOptions(), NotificationTapActivityStart.senderOptions())
    }

    private fun expectedCreatorOptions() = ActivityOptions.makeBasic()
        .setPendingIntentCreatorBackgroundActivityStartMode(
            ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED
        )
        .toBundle()

    private fun expectedSenderOptions() = ActivityOptions.makeBasic()
        .setPendingIntentBackgroundActivityStartMode(
            ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED
        )
        .toBundle()

    private fun assertBundleMatches(expected: Bundle, actual: Bundle?) {
        assertNotNull(actual)
        val resolved = actual!!
        assertEquals(expected.keySet(), resolved.keySet())
        expected.keySet().forEach { key ->
            assertEquals(expected.get(key), resolved.get(key))
        }
    }
}
