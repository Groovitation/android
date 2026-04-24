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

    // -- Battery optimization exemption gate -------------------------------

    @Test
    fun `pre-M never requests battery optimization exemption`() {
        assertFalse(
            "ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS is API 23+",
            MainActivity.shouldRequestBatteryOptimizationExemption(
                sdkInt = Build.VERSION_CODES.LOLLIPOP_MR1,
                isIgnoringBatteryOptimizations = false,
                hasBackgroundPermission = true,
                promptedThisSession = false
            )
        )
    }

    @Test
    fun `does not request exemption when already exempt`() {
        assertFalse(
            MainActivity.shouldRequestBatteryOptimizationExemption(
                sdkInt = Build.VERSION_CODES.UPSIDE_DOWN_CAKE,
                isIgnoringBatteryOptimizations = true,
                hasBackgroundPermission = true,
                promptedThisSession = false
            )
        )
    }

    @Test
    fun `does not request exemption without background-location permission`() {
        assertFalse(
            MainActivity.shouldRequestBatteryOptimizationExemption(
                sdkInt = Build.VERSION_CODES.UPSIDE_DOWN_CAKE,
                isIgnoringBatteryOptimizations = false,
                hasBackgroundPermission = false,
                promptedThisSession = false
            )
        )
    }

    @Test
    fun `does not re-request exemption within the same session`() {
        // The OS dialog is modal; re-firing it on every onResume within one
        // session is hostile. Once per session is the cap.
        assertFalse(
            MainActivity.shouldRequestBatteryOptimizationExemption(
                sdkInt = Build.VERSION_CODES.UPSIDE_DOWN_CAKE,
                isIgnoringBatteryOptimizations = false,
                hasBackgroundPermission = true,
                promptedThisSession = true
            )
        )
    }

    @Test
    fun `re-requests exemption on a fresh session when still not exempt`() {
        // #787 key scenario: Samsung firmware updates silently re-add us to
        // battery-optimized apps. On the first resume of the NEXT session,
        // promptedThisSession is false by construction (it's an in-memory
        // flag, lost across process restarts), so the gate fires again —
        // exactly the recovery path the ticket asks for.
        assertTrue(
            "Fresh session with background perm still granted and not exempt must re-prompt",
            MainActivity.shouldRequestBatteryOptimizationExemption(
                sdkInt = Build.VERSION_CODES.UPSIDE_DOWN_CAKE,
                isIgnoringBatteryOptimizations = false,
                hasBackgroundPermission = true,
                promptedThisSession = false
            )
        )
    }

    @Test
    fun `requests exemption when background permission granted and not exempt and not already asked`() {
        assertTrue(
            MainActivity.shouldRequestBatteryOptimizationExemption(
                sdkInt = Build.VERSION_CODES.UPSIDE_DOWN_CAKE,
                isIgnoringBatteryOptimizations = false,
                hasBackgroundPermission = true,
                promptedThisSession = false
            )
        )
    }

    // -- Samsung sleeping-apps onboarding gate -----------------------------

    @Test
    fun `samsung onboarding skipped on non-samsung devices`() {
        assertFalse(
            "Pixel/OnePlus/etc don't have Samsung's sleeping-apps setting",
            MainActivity.shouldShowSamsungSleepingAppsOnboarding(
                manufacturer = "Google",
                hasBackgroundPermission = true,
                onboardingAlreadyShown = false
            )
        )
    }

    @Test
    fun `samsung onboarding skipped when manufacturer is null`() {
        assertFalse(
            MainActivity.shouldShowSamsungSleepingAppsOnboarding(
                manufacturer = null,
                hasBackgroundPermission = true,
                onboardingAlreadyShown = false
            )
        )
    }

    @Test
    fun `samsung onboarding matches case-insensitively`() {
        assertTrue(
            MainActivity.shouldShowSamsungSleepingAppsOnboarding(
                manufacturer = "Samsung",
                hasBackgroundPermission = true,
                onboardingAlreadyShown = false
            )
        )
        assertTrue(
            MainActivity.shouldShowSamsungSleepingAppsOnboarding(
                manufacturer = "SAMSUNG",
                hasBackgroundPermission = true,
                onboardingAlreadyShown = false
            )
        )
    }

    @Test
    fun `samsung onboarding capped to one show per install`() {
        assertFalse(
            MainActivity.shouldShowSamsungSleepingAppsOnboarding(
                manufacturer = "samsung",
                hasBackgroundPermission = true,
                onboardingAlreadyShown = true
            )
        )
    }

    @Test
    fun `samsung onboarding skipped without background permission`() {
        // Onboarding is the second step; without background permission
        // there's nothing to protect from sleeping-apps yet.
        assertFalse(
            MainActivity.shouldShowSamsungSleepingAppsOnboarding(
                manufacturer = "samsung",
                hasBackgroundPermission = false,
                onboardingAlreadyShown = false
            )
        )
    }
}
