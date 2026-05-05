package io.blaha.groovitation

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class FcmTokenStateStoreTest {

    private lateinit var application: Application

    @Before
    fun setUp() {
        application = ApplicationProvider.getApplicationContext()
        FcmTokenStateStore.clear(application)
    }

    @Test
    fun firstTimeTokenShouldRegister() {
        assertTrue(FcmTokenStateStore.shouldRegister(application, "token-a", nowMs = 1_000L))
    }

    @Test
    fun sameTokenWithinWindowShouldNotRegister() {
        FcmTokenStateStore.recordSuccess(application, "token-a", nowMs = 1_000L)

        assertFalse(
            FcmTokenStateStore.shouldRegister(
                context = application,
                currentToken = "token-a",
                nowMs = 1_000L + FcmTokenStateStore.REREGISTER_INTERVAL_MS - 1L
            )
        )
    }

    @Test
    fun sameTokenAfterWindowShouldRegister() {
        FcmTokenStateStore.recordSuccess(application, "token-a", nowMs = 1_000L)

        assertTrue(
            FcmTokenStateStore.shouldRegister(
                context = application,
                currentToken = "token-a",
                nowMs = 1_000L + FcmTokenStateStore.REREGISTER_INTERVAL_MS + 1L
            )
        )
    }

    @Test
    fun differentTokenShouldRegisterRegardlessOfTimestamp() {
        FcmTokenStateStore.recordSuccess(application, "token-a", nowMs = 1_000L)

        assertTrue(FcmTokenStateStore.shouldRegister(application, "token-b", nowMs = 1_001L))
    }

    @Test
    fun recordSuccessThenShouldRegisterReturnsFalse() {
        FcmTokenStateStore.recordSuccess(application, "token-a", nowMs = 1_000L)

        assertFalse(FcmTokenStateStore.shouldRegister(application, "token-a", nowMs = 1_000L))
    }
}
