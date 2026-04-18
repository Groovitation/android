package io.blaha.groovitation.services

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

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
}
