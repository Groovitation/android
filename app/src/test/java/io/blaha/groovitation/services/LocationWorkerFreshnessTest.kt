package io.blaha.groovitation.services

import android.location.Location
import com.google.android.gms.location.Priority
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class LocationWorkerFreshnessTest {

    @Test
    fun `fresh elapsed-realtime location is accepted`() {
        val nowNanos = 100_000_000_000L
        val location = Location("fused").apply {
            elapsedRealtimeNanos = nowNanos - 30_000_000_000L
        }

        assertTrue(LocationWorker.isFreshEnough(location, nowElapsedRealtimeNanos = nowNanos))
        assertEquals(30_000L, LocationWorker.locationAgeMillis(location, nowElapsedRealtimeNanos = nowNanos))
    }

    @Test
    fun `stale elapsed-realtime location is rejected`() {
        val nowNanos = 500_000_000_000L
        val location = Location("fused").apply {
            elapsedRealtimeNanos = nowNanos - 180_000_000_000L
        }

        assertFalse(LocationWorker.isFreshEnough(location, nowElapsedRealtimeNanos = nowNanos))
        assertEquals(180_000L, LocationWorker.locationAgeMillis(location, nowElapsedRealtimeNanos = nowNanos))
    }

    @Test
    fun `wall-clock timestamp is used when elapsed realtime is unavailable`() {
        val nowMs = 1_800_000L
        val location = Location("legacy").apply {
            time = nowMs - 121_000L
        }

        assertFalse(LocationWorker.isFreshEnough(location, nowWallClockMs = nowMs))
        assertEquals(121_000L, LocationWorker.locationAgeMillis(location, nowWallClockMs = nowMs))
    }

    @Test
    fun `location with no timestamp is accepted`() {
        val location = Location("unknown")

        assertTrue(LocationWorker.isFreshEnough(location))
        assertNull(LocationWorker.locationAgeMillis(location))
    }

    @Test
    fun `current location request disallows fused provider cache`() {
        val request = LocationWorker.currentLocationRequest(Priority.PRIORITY_HIGH_ACCURACY)

        assertEquals(Priority.PRIORITY_HIGH_ACCURACY, request.priority)
        assertEquals(LocationWorker.CURRENT_LOCATION_MAX_UPDATE_AGE_MS, request.maxUpdateAgeMillis)
        assertEquals(LocationWorker.CURRENT_LOCATION_DURATION_MS, request.durationMillis)
    }
}
