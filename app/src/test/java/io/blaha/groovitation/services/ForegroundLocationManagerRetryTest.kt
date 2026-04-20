package io.blaha.groovitation.services

import android.location.Location
import android.os.SystemClock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ForegroundLocationManagerRetryTest {

    @Test
    fun retryDelayIsScheduledWhenInitialForegroundPostHasNoAuth() {
        assertEquals(5000L, ForegroundLocationManager.retryDelayMsForMissingAuth(null))
    }

    @Test
    fun retryDelayIsSkippedWhenInitialForegroundPostAlreadyHasAuth() {
        val resolvedAuth = ResolvedLocationAuth(
            headerName = LocationTrackingService.LOCATION_TOKEN_HEADER_NAME,
            headerValue = "native-location-token",
            source = "stored-location-token"
        )

        assertNull(ForegroundLocationManager.retryDelayMsForMissingAuth(resolvedAuth))
    }

    @Test
    fun staleForegroundFixesAreRejectedForImmediateUse() {
        val nowMs = 200_000L
        val nowElapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
        val staleLocation = Location("test").apply {
            latitude = 31.76
            longitude = -106.49
            accuracy = 12f
            time = nowMs - 30_000L
            elapsedRealtimeNanos = nowElapsedRealtimeNanos - 30_000_000_000L
        }

        assertFalse(
            ForegroundLocationManager.isFreshEnoughForImmediateUse(
                staleLocation,
                nowMs = nowMs,
                nowElapsedRealtimeNanos = nowElapsedRealtimeNanos
            )
        )
    }

    @Test
    fun freshForegroundFixesAreAcceptedForImmediateUse() {
        val nowMs = 200_000L
        val nowElapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
        val freshLocation = Location("test").apply {
            latitude = 31.76
            longitude = -106.49
            accuracy = 12f
            time = nowMs - 2_000L
            elapsedRealtimeNanos = nowElapsedRealtimeNanos - 2_000_000_000L
        }

        assertTrue(
            ForegroundLocationManager.isFreshEnoughForImmediateUse(
                freshLocation,
                nowMs = nowMs,
                nowElapsedRealtimeNanos = nowElapsedRealtimeNanos
            )
        )
    }
}
