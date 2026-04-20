package io.blaha.groovitation

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MainActivityAuthReplayTest {

    @Test
    fun firstAuthSyncAlwaysReplaysNativeLocationWork() {
        assertTrue(
            MainActivity.shouldReplayNativeLocationAfterAuth(
                lastReplayElapsedMs = 0L,
                nowElapsedMs = 1_000L
            )
        )
    }

    @Test
    fun duplicateAuthSyncInsideDebounceWindowDoesNotReplay() {
        assertFalse(
            MainActivity.shouldReplayNativeLocationAfterAuth(
                lastReplayElapsedMs = 10_000L,
                nowElapsedMs = 13_000L
            )
        )
    }

    @Test
    fun authSyncAfterDebounceWindowReplaysAgain() {
        assertTrue(
            MainActivity.shouldReplayNativeLocationAfterAuth(
                lastReplayElapsedMs = 10_000L,
                nowElapsedMs = 16_000L
            )
        )
    }
}
