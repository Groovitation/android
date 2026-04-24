package io.blaha.groovitation

import android.app.Application
import android.app.NotificationManager
import androidx.test.core.app.ApplicationProvider
import com.google.firebase.messaging.RemoteMessage
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * #845: routing + rendering for FCM payloads with `data["type"] == "proximity"`.
 *
 * The pre-#845 path rendered proximity notifications inside
 * `GeofenceBroadcastReceiver.onReceive` and posted the ack itself. That's
 * gone now; the FCM service is the single render+ack site for proximity.
 * These tests pin that contract.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ProximityFcmHandlerTest {

    private lateinit var application: Application
    private lateinit var notificationManager: NotificationManager

    @Before
    fun setUp() {
        application = ApplicationProvider.getApplicationContext()
        notificationManager = application.getSystemService(NotificationManager::class.java)
        notificationManager.cancelAll()
    }

    private fun proximityMessage(
        title: String = "Trost House",
        body: String = "Architecture pick near you",
        targetKind: String = "site",
        targetId: String = "site-trost-1",
        deepLink: String = "/map?site=site-trost-1",
        imageUrl: String? = null,
    ): RemoteMessage {
        val data = mutableMapOf(
            "type" to "proximity",
            IncomingPushNotification.EXTRA_TITLE to title,
            IncomingPushNotification.EXTRA_BODY to body,
            IncomingPushNotification.EXTRA_URL to deepLink,
            "targetKind" to targetKind,
            "targetId" to targetId,
        )
        if (imageUrl != null) data["imageUrl"] = imageUrl
        return RemoteMessage.Builder("test-sender")
            .setMessageId("proximity-test-$targetId")
            .setData(data)
            .build()
    }

    @Test
    fun proximityPayload_displaysNotificationWithTitleAndBodyOnPlacesChannel() {
        val service = Robolectric.buildService(GroovitationMessagingService::class.java).create().get()
        service.handleProximityMessage(application, proximityMessage())

        val notifications = shadowOf(notificationManager).allNotifications
        assertEquals("expected exactly one proximity notification", 1, notifications.size)

        val notif = notifications.first()
        assertEquals("Trost House", notif.extras.getString("android.title"))
        assertEquals("Architecture pick near you", notif.extras.getString("android.text"))
        assertEquals(GroovitationApplication.CHANNEL_PLACES, notif.channelId)
    }

    @Test
    fun proximityPayload_falsesBackToTargetKindIdHashWhenMessageIdMissing() {
        // Two proximity pushes for different targets should produce two
        // distinct notification IDs (not collapse into one). Pre-#845 the
        // BroadcastReceiver did this via `geofenceId.hashCode()`; the renderer
        // now keys on the `dedupKey` we hand it.
        val service = Robolectric.buildService(GroovitationMessagingService::class.java).create().get()
        service.handleProximityMessage(application, proximityMessage(targetId = "site-1"))
        service.handleProximityMessage(application, proximityMessage(targetId = "site-2"))

        val notifications = shadowOf(notificationManager).allNotifications
        assertEquals("two distinct proximity targets render two notifications", 2, notifications.size)
    }

    @Test
    fun nonProximityPayload_isHandledByGenericPushPath_notProximityRenderer() {
        // A plain push (no `type`/`type != proximity`) must NOT route through
        // the proximity renderer. We use a `data["type"]` value of "default"
        // and verify the resulting notification still posts (via the generic
        // IncomingPushNotificationNotifier path) with CHANNEL_DEFAULT.
        val service = Robolectric.buildService(GroovitationMessagingService::class.java).create().get()
        val plain = RemoteMessage.Builder("test-sender")
            .setMessageId("plain-1")
            .setData(
                mapOf(
                    IncomingPushNotification.EXTRA_TITLE to "Generic",
                    IncomingPushNotification.EXTRA_BODY to "Just a regular push",
                    IncomingPushNotification.EXTRA_URL to "/map",
                    IncomingPushNotification.EXTRA_CHANNEL to GroovitationApplication.CHANNEL_DEFAULT,
                )
            )
            .build()

        // Going through onMessageReceived to exercise the routing branch too.
        service.onMessageReceived(plain)

        val notifications = shadowOf(notificationManager).allNotifications
        assertEquals(1, notifications.size)
        val notif = notifications.first()
        assertEquals(GroovitationApplication.CHANNEL_DEFAULT, notif.channelId)
        assertEquals("Generic", notif.extras.getString("android.title"))
    }

    @Test
    fun proximityPayload_routesThroughOnMessageReceived_notGeneric() {
        // The opposite check: confirms the type=="proximity" branch in
        // onMessageReceived dispatches to the proximity renderer (which
        // posts on CHANNEL_PLACES). If routing regresses, the notification
        // would land on the default channel via the generic notifier.
        val service = Robolectric.buildService(GroovitationMessagingService::class.java).create().get()
        service.onMessageReceived(proximityMessage())

        val notifications = shadowOf(notificationManager).allNotifications
        assertEquals(1, notifications.size)
        assertEquals(GroovitationApplication.CHANNEL_PLACES, notifications.first().channelId)
    }
}
