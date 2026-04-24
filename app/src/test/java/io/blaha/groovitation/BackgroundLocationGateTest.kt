package io.blaha.groovitation

import android.os.Build
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Locks in the gate semantics for `MainActivity.promptBackgroundLocationPermission`.
 *
 * The historical bug this guards against: previously the gate skipped on
 * "rationale dialog already shown," which meant any user who saw the dialog
 * once — including users who tapped "Enable background tracking" then got
 * denied at the OS level, or whose permission auto-reset months later — was
 * silently treated as having explicitly chosen foreground-only and never
 * re-prompted. The fix replaces that gate with `foregroundOnlyExplicitlyChosen`,
 * which is set ONLY when the user taps "I'm sure" on the rationale dialog.
 *
 * If you find yourself adding a "skip if dialog was shown" check back into
 * `shouldPromptForBackgroundLocation`, this test will tell you why not.
 */
class BackgroundLocationGateTest {

    private val q = Build.VERSION_CODES.Q

    @Test
    fun `pre-Q never prompts for background location`() {
        assertFalse(
            "ACCESS_BACKGROUND_LOCATION did not exist before Android 10 / API 29",
            MainActivity.shouldPromptForBackgroundLocation(
                sdkInt = Build.VERSION_CODES.P,
                hasBackgroundPermission = false,
                foregroundOnlyExplicitlyChosen = false,
                promptedThisSession = false
            )
        )
    }

    @Test
    fun `skips when permission is already granted`() {
        assertFalse(
            MainActivity.shouldPromptForBackgroundLocation(
                sdkInt = q,
                hasBackgroundPermission = true,
                foregroundOnlyExplicitlyChosen = false,
                promptedThisSession = false
            )
        )
    }

    @Test
    fun `skips when user explicitly chose foreground-only`() {
        assertFalse(
            "User tapped 'I'm sure' on the rationale dialog — never re-prompt",
            MainActivity.shouldPromptForBackgroundLocation(
                sdkInt = q,
                hasBackgroundPermission = false,
                foregroundOnlyExplicitlyChosen = true,
                promptedThisSession = false
            )
        )
    }

    @Test
    fun `skips when already prompted this session`() {
        assertFalse(
            "Once-per-session in-memory cap prevents annoying loops",
            MainActivity.shouldPromptForBackgroundLocation(
                sdkInt = q,
                hasBackgroundPermission = false,
                foregroundOnlyExplicitlyChosen = false,
                promptedThisSession = true
            )
        )
    }

    @Test
    fun `prompts on a fresh session when permission is denied and no explicit choice was made`() {
        assertTrue(
            "Default state: prompt the user",
            MainActivity.shouldPromptForBackgroundLocation(
                sdkInt = q,
                hasBackgroundPermission = false,
                foregroundOnlyExplicitlyChosen = false,
                promptedThisSession = false
            )
        )
    }

    @Test
    fun `re-prompts on the next session when user denied at OS level but never tapped 'I'm sure'`() {
        // The historical bug: this case used to be silently skipped because
        // the previous gate looked at `dialogShown`, which got set to true the
        // moment the rationale appeared on screen — regardless of which button
        // the user eventually tapped (or whether the OS prompt that followed
        // was granted, denied, or dismissed by process death). After the fix,
        // only an explicit "I'm sure" tap blocks re-prompting.
        assertTrue(
            "User denied at OS level but didn't make an explicit choice — re-prompt",
            MainActivity.shouldPromptForBackgroundLocation(
                sdkInt = q,
                hasBackgroundPermission = false,
                foregroundOnlyExplicitlyChosen = false,
                promptedThisSession = false
            )
        )
    }

    @Test
    fun `re-prompts after permission is auto-reset by the system`() {
        // Android 11+ auto-resets runtime permissions for unused apps. After
        // an auto-reset, hasBackgroundPermission flips back to false. We
        // should treat that user as eligible for a re-prompt.
        assertTrue(
            MainActivity.shouldPromptForBackgroundLocation(
                sdkInt = Build.VERSION_CODES.UPSIDE_DOWN_CAKE,
                hasBackgroundPermission = false,
                foregroundOnlyExplicitlyChosen = false,
                promptedThisSession = false
            )
        )
    }

    @Test
    fun `explicit foreground-only choice trumps fresh-session eligibility`() {
        // Belt-and-suspenders: even if every other condition would say
        // "prompt," the explicit decline must win.
        assertFalse(
            MainActivity.shouldPromptForBackgroundLocation(
                sdkInt = q,
                hasBackgroundPermission = false,
                foregroundOnlyExplicitlyChosen = true,
                promptedThisSession = false
            )
        )
    }
}
