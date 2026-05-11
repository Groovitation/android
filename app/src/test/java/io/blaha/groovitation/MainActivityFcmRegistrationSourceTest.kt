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

    @Test
    fun signedInStateTriggersFcmRegistrationRetry() {
        val body = mainActivitySource
            .substringAfter("fun onSignedInStateFromWeb(")
            .substringBefore("\n    }")

        assertTrue(
            "#1152: onSignedInStateFromWeb(signedIn=true) must retry FCM registration so " +
                "the cookie-missing silent return clears as soon as the sign-in cookie lands, " +
                "without waiting for the next app backgrounding/reopen.",
            body.contains("registerFcmTokenWithServer()")
        )
    }

    @Test
    fun nativeLocationAuthReadyTriggersFcmRegistrationRetry() {
        val body = mainActivitySource
            .substringAfter("fun onNativeLocationAuthReadyFromWeb()")
            .substringBefore("\n    }")

        assertTrue(
            "#1152: onNativeLocationAuthReadyFromWeb (the JS-bridge setSessionCookie/" +
                "setLocationToken signal) must retry FCM registration so the cookie-missing " +
                "gate clears as soon as the WebView publishes a fresh auth cookie.",
            body.contains("registerFcmTokenWithServer()")
        )
    }

    @Test
    fun eachSilentReturnGateLogsStructured() {
        // #1152: every silent-return gate in registerFcmTokenWithServer must
        // emit a structured Log.w line naming the gate, so adb logcat traces
        // identify the exact precondition that failed on a stall.
        val expectedGates = listOf(
            "registerFcmTokenWithServer: gate token returned",
            "registerFcmTokenWithServer: gate alreadyRegistered returned",
            "registerFcmTokenWithServer: gate cookie returned",
            "registerFcmTokenWithServer: gate firebaseGetToken returned"
        )
        for (gate in expectedGates) {
            assertTrue(
                "Expected structured log for gate: $gate",
                mainActivitySource.contains(gate)
            )
        }
    }
}
