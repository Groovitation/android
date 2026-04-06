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

    private fun testPrefs() = context.getSharedPreferences(
        "location_tracking_prefs",
        Context.MODE_PRIVATE
    )
}
