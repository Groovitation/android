package io.blaha.groovitation

import android.content.Intent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class JitsiMeetIntentFactoryTest {

    @Test
    fun acceptsJwtBearingHttpsRoomUrls() {
        assertTrue(
            JitsiMeetIntentFactory.isJwtBearingRoomUrl(
                "https://video.blaha.io/event-123?jwt=abc.def.ghi#config.disableDeepLinking=true"
            )
        )
        assertTrue(
            JitsiMeetIntentFactory.isJwtBearingRoomUrl(
                "https://video.blaha.io/Q11473?jwt=token"
            )
        )
    }

    @Test
    fun rejectsBareRoomsAndNativeDeepLinks() {
        assertFalse(JitsiMeetIntentFactory.isJwtBearingRoomUrl("https://video.blaha.io/event-123"))
        assertFalse(
            JitsiMeetIntentFactory.isJwtBearingRoomUrl(
                "org.jitsi.meet://video.blaha.io/event-123?jwt=abc.def.ghi"
            )
        )
        assertFalse(
            JitsiMeetIntentFactory.isJwtBearingRoomUrl(
                "intent://video.blaha.io/event-123#Intent;scheme=org.jitsi.meet;package=org.jitsi.meet;end"
            )
        )
    }

    @Test
    fun buildsPackageTargetedJitsiIntentWithJwtUrl() {
        val roomUrl = "https://video.blaha.io/event-123?jwt=abc.def.ghi#config.disableDeepLinking=true"
        val intent = JitsiMeetIntentFactory.buildRoomIntent(roomUrl)

        assertNotNull(intent)
        assertEquals(Intent.ACTION_VIEW, intent!!.action)
        assertEquals(JitsiMeetIntentFactory.JITSI_PACKAGE, intent.`package`)
        assertEquals(roomUrl, intent.data.toString())
        assertTrue(intent.hasCategory(Intent.CATEGORY_BROWSABLE))
    }

    @Test
    fun refusesToBuildIntentWithoutJwt() {
        assertNull(JitsiMeetIntentFactory.buildRoomIntent("https://video.blaha.io/event-123"))
    }

    @Test
    fun buildsPlayStoreFallbackIntent() {
        val intent = JitsiMeetIntentFactory.buildStoreIntent()

        assertEquals(Intent.ACTION_VIEW, intent.action)
        assertEquals("com.android.vending", intent.`package`)
        assertEquals("market://details?id=org.jitsi.meet", intent.data.toString())
    }
}
