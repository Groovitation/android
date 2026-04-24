package io.blaha.groovitation

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * #790: regression guards for the CookieManager.flush() call-sites that keep
 * the WebView's on-disk cookie store coherent with whatever the web layer has
 * just written. Background workers (LocationWorker, GeofenceBroadcastReceiver,
 * GeofenceManager) read CookieManager from disk — an unflushed in-memory
 * cookie is effectively invisible to them if the app process gets killed,
 * which Samsung OneUI does aggressively. Source-fingerprint tests (the
 * convention in this codebase) so a future edit that accidentally removes
 * a flush trips CI.
 */
class MainActivityCookieFlushTest {

    private val mainActivitySource: String =
        File("src/main/java/io/blaha/groovitation/MainActivity.kt").readText()

    private val webFragmentSource: String =
        File("src/main/java/io/blaha/groovitation/GroovitationWebFragment.kt").readText()

    @Test
    fun `MainActivity flushes cookies in onPause`() {
        assertTrue(
            "MainActivity.onPause() must call CookieManager.getInstance().flush() — " +
                "onPause is the primary path where in-memory cookies migrate to disk " +
                "before Android kills the app process on swipe-away.",
            mainActivitySource.contains("override fun onPause()") &&
                mainActivitySource.substringAfter("override fun onPause()").substringBefore("\n    }")
                    .contains("CookieManager.getInstance().flush()")
        )
    }

    @Test
    fun `MainActivity flushes cookies in onStop as belt-and-suspenders`() {
        assertTrue(
            "MainActivity.onStop() must call CookieManager.getInstance().flush() — " +
                "some OEM-initiated teardown paths (Samsung Deep Sleep, aggressive " +
                "OEM killers) skip onPause, so onStop is the second chance to flush.",
            mainActivitySource.contains("override fun onStop()") &&
                mainActivitySource.substringAfter("override fun onStop()").substringBefore("\n    }")
                    .contains("CookieManager.getInstance().flush()")
        )
    }

    @Test
    fun `MainActivity flushes cookies when the web layer reports a sign-in or sign-out`() {
        // The web JS bridge fires onSignedInStateFromWeb after a successful
        // sign-in (Set-Cookie landed) or a sign-out (expired cookies landed).
        // Either way the on-disk store needs to catch up before any
        // background worker next reads from it. This is the fix #790 adds
        // on top of the existing onPause / onStop / JS-bridge-setter flushes.
        val body = mainActivitySource
            .substringAfter("fun onSignedInStateFromWeb(signedIn: Boolean)")
            .substringBefore("\n    }")
        assertTrue(
            "onSignedInStateFromWeb must call CookieManager.getInstance().flush() — " +
                "keeps the on-disk cookie store coherent with whatever the web layer " +
                "just wrote, even when the user keeps the app in the foreground and " +
                "never triggers an onPause/onStop before the next background worker run.",
            body.contains("CookieManager.getInstance().flush()")
        )
    }

    @Test
    fun `setSessionCookie JS bridge flushes cookies after storing`() {
        val body = webFragmentSource
            .substringAfter("fun setSessionCookie(cookie: String)")
            .substringBefore("\n        }")
        assertTrue(
            "setSessionCookie JS bridge must call CookieManager.getInstance().flush() " +
                "after LocationTrackingService.storeSessionCookie — the prefs write is " +
                "synchronous but the WebView's own in-memory cookie for the same URL " +
                "still needs to land on disk before background workers wake up.",
            body.contains("CookieManager.getInstance().flush()")
        )
    }

    @Test
    fun `setLocationToken JS bridge flushes cookies after storing`() {
        val body = webFragmentSource
            .substringAfter("fun setLocationToken(token: String)")
            .substringBefore("\n        }")
        assertTrue(
            "setLocationToken JS bridge must call CookieManager.getInstance().flush() " +
                "so the native-visible auth state (prefs + cookies) stays coherent " +
                "after any JS-bridge auth push.",
            body.contains("CookieManager.getInstance().flush()")
        )
    }
}
