package io.blaha.groovitation

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #1912: Map -> Events tab cold-boots a fresh '/' fragment (clearAll) that can lose
 * the nginx Basic-Auth race and render the HTTP-200 web login even though the
 * session is valid. These tests cover the pure decision + JS detection that drive
 * the in-session recovery refresh, without needing a live WebView/nginx.
 */
class GroovitationWebFragmentLoginRecoveryTest {

    @Test
    fun recoversWhenLoginRendersAfterPriorAuthentication() {
        // The bug case: we were authenticated earlier this process, then a tab
        // return surfaced the login page. With budget remaining we should refresh.
        assertTrue(
            "A login render after prior authentication (within budget) must trigger recovery",
            GroovitationWebFragment.shouldRecoverFromLoginRender(
                isLoginPage = true,
                hasAuthenticatedBefore = true,
                retryCount = 0
            )
        )
    }

    @Test
    fun doesNotRecoverOnGenuineLogout() {
        // Never authenticated this process => the login page is the real, expected
        // state. Refreshing would just loop on a legitimately logged-out user.
        assertFalse(
            "A login render with no prior authentication is a genuine logout, not the race",
            GroovitationWebFragment.shouldRecoverFromLoginRender(
                isLoginPage = true,
                hasAuthenticatedBefore = false,
                retryCount = 0
            )
        )
    }

    @Test
    fun doesNotRecoverOnAuthenticatedPage() {
        assertFalse(
            "A non-login (authenticated) render must never trigger a recovery refresh",
            GroovitationWebFragment.shouldRecoverFromLoginRender(
                isLoginPage = false,
                hasAuthenticatedBefore = true,
                retryCount = 0
            )
        )
    }

    @Test
    fun stopsRetryingAfterBudgetExhausted() {
        // After repeated login renders (e.g. a real logout that happened mid-session)
        // recovery must give up so the login page can stand.
        assertFalse(
            "Recovery must stop once the per-fragment retry budget is exhausted",
            GroovitationWebFragment.shouldRecoverFromLoginRender(
                isLoginPage = true,
                hasAuthenticatedBefore = true,
                retryCount = 2
            )
        )
    }

    @Test
    fun detectionScriptMatchesCoreLoginMarkers() {
        // Markers come from core AuthController.scala: /users/sign_in form + user_email/password fields.
        val script = GroovitationWebFragment.buildLoginDetectionScript()

        assertTrue(
            "Login detection must key on the /users/sign_in form action",
            script.contains("form[action=\"/users/sign_in\"]")
        )
        assertTrue(
            "Login detection must recognize the user_email field",
            script.contains("getElementById('user_email')")
        )
        assertTrue(
            "Login detection must also recognize the /users/sign_in path",
            script.contains("/users/sign_in")
        )
        assertTrue(
            "Login detection must return a JSON isLogin flag the native side can parse",
            script.contains("isLogin")
        )
    }
}
