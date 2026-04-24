package io.blaha.groovitation.services

import android.content.Context
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class LocationTrackingServiceCookieTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        testPrefs().edit().clear().commit()
    }

    @After
    fun tearDown() {
        testPrefs().edit().clear().commit()
    }

    @Test
    fun resolveUsesWebViewSessionCookieWhenPresent() {
        val resolved = LocationTrackingService.resolveSessionCookieFromSources(
            webViewCookie = "_user_interface_session=live-session; cf_clearance=cloudflare",
            storedSessionCookie = "_user_interface_session=stored-session"
        )

        assertNotNull(resolved)
        assertEquals("webview-session", resolved?.source)
        assertEquals(
            "_user_interface_session=live-session; cf_clearance=cloudflare",
            resolved?.header
        )
    }

    @Test
    fun resolveFallsBackToStoredSessionWhenWebViewCookieLacksAuthCookie() {
        val resolved = LocationTrackingService.resolveSessionCookieFromSources(
            webViewCookie = "cf_clearance=cloudflare; __cf_bm=bot-manager",
            storedSessionCookie = "_user_interface_session=stored-session"
        )

        assertNotNull(resolved)
        assertEquals("merged-stored-session-fallback", resolved?.source)
        assertEquals(
            "cf_clearance=cloudflare; __cf_bm=bot-manager; _user_interface_session=stored-session",
            resolved?.header
        )
    }

    @Test
    fun resolveReturnsNullWhenNoAuthenticatedCookieExists() {
        val resolved = LocationTrackingService.resolveSessionCookieFromSources(
            webViewCookie = "cf_clearance=cloudflare",
            storedSessionCookie = null
        )

        assertNull(resolved)
    }

    @Test
    fun refreshCookieStoresOnlyAuthenticatedSessionCookie() {
        val refreshed = LocationTrackingService.refreshStoredSessionCookie(
            context = context,
            cookieHeader = "cf_clearance=cloudflare; _user_interface_session=fresh-session; remember_user_token=abc"
        )

        assertTrue(refreshed)
        assertEquals(
            "_user_interface_session=fresh-session",
            testPrefs().getString(LocationTrackingService.KEY_SESSION_COOKIE, null)
        )
    }

    @Test
    fun refreshCookieKeepsStoredFallbackWhenWebViewCookieLacksAuthCookie() {
        testPrefs().edit()
            .putString(
                LocationTrackingService.KEY_SESSION_COOKIE,
                "_user_interface_session=stored-session"
            )
            .commit()

        val refreshed = LocationTrackingService.refreshStoredSessionCookie(
            context = context,
            cookieHeader = "cf_clearance=cloudflare; __cf_bm=bot-manager"
        )

        assertFalse(refreshed)
        assertEquals(
            "_user_interface_session=stored-session",
            testPrefs().getString(LocationTrackingService.KEY_SESSION_COOKIE, null)
        )
    }

    @Test
    fun storeSessionCookiePersistsOnlyAuthenticatedSessionCookie() {
        LocationTrackingService.storeSessionCookie(
            context,
            "cf_clearance=cloudflare; _user_interface_session=pushed-session; remember_user_token=abc"
        )

        assertEquals(
            "_user_interface_session=pushed-session",
            testPrefs().getString(LocationTrackingService.KEY_SESSION_COOKIE, null)
        )
    }

    @Test
    fun storeLocationTokenPersistsAndBeatsCookieFallbackForBackgroundAuth() {
        LocationTrackingService.storeLocationToken(context, "native-location-token")

        val resolved = LocationTrackingService.resolveLocationAuthFromSources(
            storedLocationToken = testPrefs().getString(LocationTrackingService.KEY_LOCATION_TOKEN, null),
            webViewCookie = "cf_clearance=cloudflare",
            storedSessionCookie = "_user_interface_session=stored-session"
        )

        assertNotNull(resolved)
        assertEquals(LocationTrackingService.LOCATION_TOKEN_HEADER_NAME, resolved?.headerName)
        assertEquals("native-location-token", resolved?.headerValue)
        assertEquals("stored-location-token", resolved?.source)
    }

    // #772: SKIPPED_NO_AUTH outcome line includes a structured diagnostic so
    // prod logs grepping `outcome=SKIPPED_NO_AUTH` see exactly which auth
    // source was missing without consulting an adjacent Log.w. Pure-function
    // formatter so the keys/format are stable across releases.
    @Test
    fun buildLocationAuthDiagnosticReportsAllSourcesAbsent() {
        val diag = LocationTrackingService.buildLocationAuthDiagnostic(
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
    fun buildLocationAuthDiagnosticReportsLiveCookieNamesWithoutLeakingValues() {
        val diag = LocationTrackingService.buildLocationAuthDiagnostic(
            webViewCookie = "cf_clearance=cloudflare; _user_interface_session=secret-do-not-log",
            storedLocationToken = null,
            storedSessionCookie = null
        )
        // Cookie names appear in [name1,name2] form (matching describeCookieNames).
        // Cookie values must not leak.
        assertTrue(diag.contains("webViewCookies=[cf_clearance, _user_interface_session]"))
        assertFalse(diag.contains("secret-do-not-log"))
        assertFalse(diag.contains("cloudflare"))
        assertTrue(diag.contains("storedToken=absent"))
        assertTrue(diag.contains("storedSession=absent"))
    }

    @Test
    fun buildLocationAuthDiagnosticMarksStoredTokenPresentWhenNonBlank() {
        val diag = LocationTrackingService.buildLocationAuthDiagnostic(
            webViewCookie = null,
            storedLocationToken = "native-location-token",
            storedSessionCookie = null
        )
        assertTrue(diag.contains("storedToken=present"))
    }

    @Test
    fun buildLocationAuthDiagnosticTreatsBlankStoredTokenAsAbsent() {
        // Trim-then-empty mirrors the storeLocationToken contract: blank
        // values clear the pref. The diagnostic must agree to avoid a
        // misleading "storedToken=present" while resolveLocationAuth still
        // returns null.
        val diag = LocationTrackingService.buildLocationAuthDiagnostic(
            webViewCookie = null,
            storedLocationToken = "   ",
            storedSessionCookie = null
        )
        assertTrue(diag.contains("storedToken=absent"))
    }

    @Test
    fun buildLocationAuthDiagnosticMarksStoredSessionPresentOnlyWhenAuthSessionCookieParsesOut() {
        // The pref blob can hold cf_clearance+_user_interface_session merged.
        // storedSession=present iff the auth cookie itself is parseable —
        // matches resolveLocationAuthFromSources which only uses that cookie.
        val authPresent = LocationTrackingService.buildLocationAuthDiagnostic(
            webViewCookie = null,
            storedLocationToken = null,
            storedSessionCookie = "cf_clearance=cf; _user_interface_session=stored-session"
        )
        assertTrue(authPresent.contains("storedSession=present"))

        val noAuth = LocationTrackingService.buildLocationAuthDiagnostic(
            webViewCookie = null,
            storedLocationToken = null,
            storedSessionCookie = "cf_clearance=cf; remember_user_token=abc"
        )
        assertTrue(noAuth.contains("storedSession=absent"))
    }

    @Test
    fun blankBridgeValuesClearStoredSessionCookieAndLocationToken() {
        testPrefs().edit()
            .putString(LocationTrackingService.KEY_SESSION_COOKIE, "_user_interface_session=stored-session")
            .putString(LocationTrackingService.KEY_LOCATION_TOKEN, "stored-location-token")
            .commit()

        LocationTrackingService.storeSessionCookie(context, "")
        LocationTrackingService.storeLocationToken(context, "")

        assertNull(testPrefs().getString(LocationTrackingService.KEY_SESSION_COOKIE, null))
        assertNull(testPrefs().getString(LocationTrackingService.KEY_LOCATION_TOKEN, null))
    }

    private fun testPrefs() = context.getSharedPreferences(
        "location_tracking_prefs",
        Context.MODE_PRIVATE
    )
}
