package io.blaha.groovitation.services

import io.blaha.groovitation.services.LocationTrackingService.Companion.buildLocationAuthDiagnostic
import io.blaha.groovitation.services.LocationTrackingService.Companion.resolveLocationAuthFromSources
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Pure-function tests for [LocationTrackingService.buildLocationAuthDiagnostic]
 * (the diagnostic that splices into `outcome=SKIPPED_NO_AUTH` log lines, #772).
 *
 * Cookie *names* must appear; cookie *values* must never appear — those would
 * leak the live session token into logcat and any downstream log shippers.
 */
@RunWith(RobolectricTestRunner::class)
class LocationAuthDiagnosticTest {

    @Test
    fun `all-absent baseline reports webViewCookies=none storedToken=absent storedSession=absent`() {
        val diag = buildLocationAuthDiagnostic(
            webViewCookie = null,
            storedLocationToken = null,
            storedSessionCookie = null
        )
        assertEquals(
            "webViewCookies=none storedToken=absent storedSession=absent",
            diag
        )
    }

    @Test
    fun `live cookie names are included but cookie values must not leak`() {
        val secret = "leak-me-not-abc123"
        val diag = buildLocationAuthDiagnostic(
            webViewCookie = "_user_interface_session=$secret; _ga=GA1.2.unrelated",
            storedLocationToken = null,
            storedSessionCookie = null
        )
        assertTrue(
            "expected cookie names in diagnostic, got: $diag",
            diag.contains("_user_interface_session") && diag.contains("_ga")
        )
        assertFalse(
            "diagnostic must never contain cookie values, got: $diag",
            diag.contains(secret)
        )
        // The format for cookie names is the joined-list shape produced by
        // describeCookieNames, so any future caller relying on the contract
        // (logs grep / unit-test asserts) keeps working.
        assertTrue(
            "expected bracketed cookie-name list, got: $diag",
            diag.contains("webViewCookies=[")
        )
    }

    @Test
    fun `present stored token reports storedToken=present`() {
        val diag = buildLocationAuthDiagnostic(
            webViewCookie = null,
            storedLocationToken = "real-location-token-value",
            storedSessionCookie = null
        )
        assertTrue(
            "expected storedToken=present, got: $diag",
            diag.contains("storedToken=present")
        )
        // Token value must not leak.
        assertFalse(
            "diagnostic must never contain the stored token value, got: $diag",
            diag.contains("real-location-token-value")
        )
    }

    @Test
    fun `whitespace-only stored token is treated as absent`() {
        // storeLocationToken treats blank as a clear, so the diagnostic must
        // agree — otherwise on-call would chase a "present" token that the
        // resolver actually rejected.
        val diag = buildLocationAuthDiagnostic(
            webViewCookie = null,
            storedLocationToken = "   \t\n  ",
            storedSessionCookie = null
        )
        assertTrue(
            "expected storedToken=absent for whitespace-only token, got: $diag",
            diag.contains("storedToken=absent")
        )
    }

    @Test
    fun `storedSession=present only when the stored value parses as auth cookie`() {
        // resolveLocationAuthFromSources only honors the stored session cookie
        // when extractSessionCookie can pull the auth cookie out of it; if the
        // stored value is unrelated padding, the resolver returns null.
        // The diagnostic must report `storedSession=absent` in that case so it
        // doesn't disagree with the resolver's null verdict.
        val unrelatedStored = "_ga=GA1.2.unused"
        val diagAbsent = buildLocationAuthDiagnostic(
            webViewCookie = null,
            storedLocationToken = null,
            storedSessionCookie = unrelatedStored
        )
        assertTrue(
            "expected storedSession=absent for non-auth stored cookie, got: $diagAbsent",
            diagAbsent.contains("storedSession=absent")
        )
        // Ground-truth cross-check against the resolver: same inputs must
        // produce a null auth so the diagnostic and resolver stay aligned.
        assertNull(
            resolveLocationAuthFromSources(
                storedLocationToken = null,
                webViewCookie = null,
                storedSessionCookie = unrelatedStored
            )
        )

        val authStored = "_user_interface_session=abcdef-real-session-id"
        val diagPresent = buildLocationAuthDiagnostic(
            webViewCookie = null,
            storedLocationToken = null,
            storedSessionCookie = authStored
        )
        assertTrue(
            "expected storedSession=present for parseable auth cookie, got: $diagPresent",
            diagPresent.contains("storedSession=present")
        )
        // And the session-stored value itself must not leak — only the
        // present/absent flag.
        assertFalse(
            "stored session value must not leak into diagnostic, got: $diagPresent",
            diagPresent.contains("abcdef-real-session-id")
        )
        assertNotNull(
            resolveLocationAuthFromSources(
                storedLocationToken = null,
                webViewCookie = null,
                storedSessionCookie = authStored
            )
        )
    }
}
