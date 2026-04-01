package io.blaha.groovitation

import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.google.firebase.messaging.RemoteMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
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
    fun debugIntentFallsBackToDefaultRouteAndChannel() {
        val push = IncomingPushNotification.fromDebugIntent(
            Intent(IncomingPushNotification.DEBUG_ACTION_SIMULATE_PUSH).apply {
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

        val tapIntent = shadowOf(notification.contentIntent).savedIntent
        assertEquals(MainActivity::class.java.name, tapIntent.component?.className)
        assertEquals(
            "${BuildConfig.BASE_URL}/map?push_ci=1",
            tapIntent.getStringExtra(IncomingPushNotification.EXTRA_URL)
        )
    }
}
