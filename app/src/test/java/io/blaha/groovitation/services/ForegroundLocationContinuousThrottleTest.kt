package io.blaha.groovitation.services

import android.location.Location
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

// Lock down the throttle that decides when continuous foreground tracking
// posts to the per-user location endpoint (#706). The WebView dispatch runs
// on every fix; the server post runs only when either the user has moved
// far enough or enough time has passed, so the landing-page distance stays
// honest without hammering the API.
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class ForegroundLocationContinuousThrottleTest {

    private fun loc(lat: Double, lon: Double): Location =
        Location("test").apply {
            latitude = lat
            longitude = lon
        }

    @Test
    fun `first fix always posts`() {
        val first = loc(32.78, -96.80)
        assertTrue(
            ForegroundLocationManager.shouldPostContinuous(
                candidate = first,
                lastPosted = null,
                lastPostedAtMs = 0L,
                nowMs = 1_000_000L
            )
        )
    }

    @Test
    fun `stationary user within the interval does not post`() {
        val pos = loc(32.78, -96.80)
        val now = 10_000L
        val lastPostedAt = now - 30_000L // 30 s ago, well under the 60 s floor
        assertFalse(
            ForegroundLocationManager.shouldPostContinuous(
                candidate = pos,
                lastPosted = pos,
                lastPostedAtMs = lastPostedAt,
                nowMs = now
            )
        )
    }

    @Test
    fun `user who has moved past the distance threshold posts immediately`() {
        val start = loc(32.78, -96.80)
        // ~60 m east at this latitude
        val moved = loc(32.78, -96.79936)
        val now = 10_000L
        val lastPostedAt = now - 10_000L
        assertTrue(
            ForegroundLocationManager.shouldPostContinuous(
                candidate = moved,
                lastPosted = start,
                lastPostedAtMs = lastPostedAt,
                nowMs = now
            )
        )
    }

    @Test
    fun `stationary user past the time interval posts`() {
        val pos = loc(32.78, -96.80)
        val now = 1_000_000L
        val lastPostedAt = now - ForegroundLocationManager.CONTINUOUS_MIN_POST_INTERVAL_MS - 1
        assertTrue(
            ForegroundLocationManager.shouldPostContinuous(
                candidate = pos,
                lastPosted = pos,
                lastPostedAtMs = lastPostedAt,
                nowMs = now
            )
        )
    }

    @Test
    fun `tiny GPS jitter below the distance floor does not post`() {
        val pos = loc(32.78, -96.80)
        // ~2 m jitter
        val jittered = loc(32.78002, -96.80002)
        val now = 10_000L
        val lastPostedAt = now - 30_000L
        assertFalse(
            ForegroundLocationManager.shouldPostContinuous(
                candidate = jittered,
                lastPosted = pos,
                lastPostedAtMs = lastPostedAt,
                nowMs = now
            )
        )
    }
}
