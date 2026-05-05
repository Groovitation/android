package io.blaha.groovitation

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class MainActivityFcmRegistrationSourceTest {

    private val mainActivitySource: String =
        File("src/main/java/io/blaha/groovitation/MainActivity.kt").readText()

    @Test
    fun onResumeTriggersFcmRegistrationCheck() {
        val body = mainActivitySource
            .substringAfter("override fun onResume()")
            .substringBefore("\n    }")

        assertTrue(
            "MainActivity.onResume() must attempt FCM token registration so a " +
                "stale or server-cleaned token is re-confirmed without a full app restart.",
            body.contains("registerFcmTokenWithServer()")
        )
    }

    @Test
    fun registrationGateUsesPersistedTokenStateInsteadOfProcessFlag() {
        assertFalse(
            "FCM registration must not be gated by the old process-local fcmTokenRegistered flag.",
            mainActivitySource.contains("fcmTokenRegistered")
        )
        assertTrue(
            "FCM registration should use the prefs-backed state gate.",
            mainActivitySource.contains("FcmTokenStateStore.shouldRegister")
        )
    }
}
