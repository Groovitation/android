package io.blaha.groovitation.services

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ForegroundLocationManagerRetryTest {

    @Test
    fun retryDelayIsScheduledWhenInitialForegroundPostHasNoCookie() {
        assertEquals(5000L, ForegroundLocationManager.retryDelayMsForMissingCookie(null))
    }

    @Test
    fun retryDelayIsSkippedWhenInitialForegroundPostAlreadyHasCookie() {
        val resolvedCookie = ResolvedSessionCookie(
            header = "_user_interface_session=live-session",
            source = "webview-session",
            webViewCookieSummary = "[_user_interface_session]"
        )

        assertNull(ForegroundLocationManager.retryDelayMsForMissingCookie(resolvedCookie))
    }
}
