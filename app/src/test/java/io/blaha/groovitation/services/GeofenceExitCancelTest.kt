package io.blaha.groovitation.services

import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Lock down the notification-ID derivation that
 * `GeofenceBroadcastReceiver.cancelProximityNotificationsForExit` (#1008)
 * shares with `ProximityNotificationRenderer.show` (#845).
 *
 * Both must produce the same Int from the same (targetKind, targetId), or
 * the EXIT-cancel will silently target a different notification slot than
 * the one the renderer posted on ENTER. Keep these formulas in lockstep.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class GeofenceExitCancelTest {

    /** Mirror of `ProximityNotificationRenderer`'s id derivation. */
    private fun rendererNotificationId(targetKind: String, targetId: String): Int {
        val dedupKey = "$targetKind:$targetId"
        return 50000 + (dedupKey.hashCode() and 0xFFFF)
    }

    @Test
    fun `EXIT-cancel id matches renderer's display id for site target`() {
        val expected = rendererNotificationId("site", "abc-123")
        val actual = GeofenceBroadcastReceiver.proximityNotificationId("site", "abc-123")
        assertEquals(expected, actual)
    }

    @Test
    fun `EXIT-cancel id matches renderer's display id for event target`() {
        val expected = rendererNotificationId("event", "xyz-789")
        val actual = GeofenceBroadcastReceiver.proximityNotificationId("event", "xyz-789")
        assertEquals(expected, actual)
    }

    @Test
    fun `dedup key uses kind colon id format`() {
        assertEquals("site:trost-1", GeofenceBroadcastReceiver.proximityDedupKey("site", "trost-1"))
        assertEquals("event:concert-2", GeofenceBroadcastReceiver.proximityDedupKey("event", "concert-2"))
    }

    @Test
    fun `notification ids are deterministic across calls (stable for cancel)`() {
        val first = GeofenceBroadcastReceiver.proximityNotificationId("site", "stable")
        val second = GeofenceBroadcastReceiver.proximityNotificationId("site", "stable")
        assertEquals(first, second)
    }

    @Test
    fun `different targets yield different ids (guards against universal-cancel)`() {
        val a = GeofenceBroadcastReceiver.proximityNotificationId("site", "a")
        val b = GeofenceBroadcastReceiver.proximityNotificationId("site", "b")
        assert(a != b) { "Different targets must yield different notification ids ($a == $b)" }
    }
}
