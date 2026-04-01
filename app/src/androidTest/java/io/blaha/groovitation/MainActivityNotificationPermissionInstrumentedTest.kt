package io.blaha.groovitation

import android.content.Context
import android.content.Intent
import android.os.Build
import android.webkit.CookieManager
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.regex.Pattern

@RunWith(AndroidJUnit4::class)
class MainActivityNotificationPermissionInstrumentedTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val targetContext = instrumentation.targetContext
    private val packageName = targetContext.packageName
    private val uiDevice = UiDevice.getInstance(instrumentation)

    @Before
    fun setUp() {
        assumeTrue(Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
        resetNotificationPermission()
        clearActivityPrefs()
        NotificationTestHooks.clear(targetContext)
        TokenStorage.fcmToken = null
        clearSessionCookie()
    }

    @After
    fun tearDown() {
        NotificationTestHooks.clear(targetContext)
        TokenStorage.fcmToken = null
        clearSessionCookie()
    }

    @Test
    fun notificationDenialPersistsAcrossRelaunchWithoutReprompt() {
        launchNotificationScenario().use { scenario ->
            clickNotificationPermissionButton(allow = false)
            waitForNotificationPermissionState(scenario, expected = "denied")
        }

        assertNull(
            "Denying notification permission should not register a token",
            NotificationTestHooks.lastRecordedTokenRegistration(targetContext)
        )

        launchNotificationScenario().use { scenario ->
            assertNull(
                "App should not auto-reprompt for notification permission after same-version denial",
                findPermissionButton(allow = true, timeoutMs = 1_500)
            )
            scenario.onActivity { activity ->
                assertEquals("denied", activity.currentNotificationPermissionState())
            }
        }
    }

    @Test
    fun grantingNotificationPermissionRegistersFakeTokenAndRefreshPathReusesRegistrar() {
        val initialToken = "debug-main-token"
        val refreshedToken = "debug-refreshed-token"

        NotificationTestHooks.setCaptureTokenRegistrations(targetContext, true)
        NotificationTestHooks.setFakeFcmToken(targetContext, initialToken)
        installSessionCookie()

        launchNotificationScenario().use { scenario ->
            clickNotificationPermissionButton(allow = true)
            waitForNotificationPermissionState(scenario, expected = "granted")
            val registration = waitForRecordedToken(expectedToken = initialToken)
            assertEquals("${BuildConfig.BASE_URL}/api/notifications/tokens", registration.url)
            assertTrue(registration.cookie.contains("_user_interface_session=test-session"))
        }

        launchNotificationScenario().use { scenario ->
            assertNull(
                "Granted notification permission should stay granted across relaunch",
                findPermissionButton(allow = true, timeoutMs = 1_500)
            )
            scenario.onActivity { activity ->
                assertEquals("granted", activity.currentNotificationPermissionState())
            }
        }

        GroovitationMessagingService.handleTokenRefresh(
            context = targetContext,
            token = refreshedToken
        )
        val refreshedRegistration = waitForRecordedToken(expectedToken = refreshedToken)
        assertEquals("${BuildConfig.BASE_URL}/api/notifications/tokens", refreshedRegistration.url)
        assertTrue(refreshedRegistration.cookie.contains("_user_interface_session=test-session"))
    }

    private fun launchNotificationScenario(): ActivityScenario<MainActivity> {
        val intent = Intent(targetContext, MainActivity::class.java).apply {
            putExtra(MainActivity.EXTRA_SKIP_LOCATION_PERMISSION_CHAIN, true)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return ActivityScenario.launch(intent)
    }

    private fun waitForNotificationPermissionState(
        scenario: ActivityScenario<MainActivity>,
        expected: String,
        timeoutMs: Long = 10_000
    ) {
        val deadline = System.currentTimeMillis() + timeoutMs
        var lastState = "unknown"
        while (System.currentTimeMillis() < deadline) {
            scenario.onActivity { activity ->
                lastState = activity.currentNotificationPermissionState()
            }
            if (lastState == expected) return
            Thread.sleep(250)
        }
        throw AssertionError("Expected notification permission state $expected but was $lastState")
    }

    private fun waitForRecordedToken(
        expectedToken: String,
        timeoutMs: Long = 10_000
    ): RecordedTokenRegistration {
        val deadline = System.currentTimeMillis() + timeoutMs
        var lastRegistration: RecordedTokenRegistration? = null
        while (System.currentTimeMillis() < deadline) {
            lastRegistration = NotificationTestHooks.lastRecordedTokenRegistration(targetContext)
            if (lastRegistration?.token == expectedToken) {
                return lastRegistration
            }
            Thread.sleep(250)
        }
        throw AssertionError(
            "Expected recorded notification token $expectedToken but saw ${lastRegistration?.token}"
        )
    }

    private fun clickNotificationPermissionButton(allow: Boolean) {
        val button = findPermissionButton(allow, timeoutMs = 15_000)
        assertNotNull("Expected notification permission dialog", button)
        button!!.click()
        uiDevice.waitForIdle()
    }

    private fun findPermissionButton(allow: Boolean, timeoutMs: Long): UiObject2? {
        val resName = if (allow) "permission_allow_button" else "permission_deny_button"
        val textRegex = if (allow) "(?i)allow" else "(?i)don't allow|do not allow|deny"
        return uiDevice.wait(
            Until.findObject(By.res("com.android.permissioncontroller", resName)),
            timeoutMs
        ) ?: uiDevice.wait(
            Until.findObject(By.text(Pattern.compile(textRegex))),
            timeoutMs
        )
    }

    private fun installSessionCookie() {
        instrumentation.runOnMainSync {
            val cookieManager = CookieManager.getInstance()
            cookieManager.setAcceptCookie(true)
            cookieManager.setCookie(BuildConfig.BASE_URL, "_user_interface_session=test-session")
            cookieManager.flush()
        }
    }

    private fun clearSessionCookie() {
        instrumentation.runOnMainSync {
            val cookieManager = CookieManager.getInstance()
            cookieManager.setCookie(
                BuildConfig.BASE_URL,
                "_user_interface_session=; Max-Age=0; path=/"
            )
            cookieManager.flush()
        }
    }

    private fun clearActivityPrefs() {
        targetContext.getSharedPreferences("groovitation_prefs", Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
    }

    private fun resetNotificationPermission() {
        runShell("pm revoke $packageName android.permission.POST_NOTIFICATIONS")
        runShell("pm clear-permission-flags $packageName android.permission.POST_NOTIFICATIONS user-set")
        runShell("pm clear-permission-flags $packageName android.permission.POST_NOTIFICATIONS user-fixed")
        runShell("appops set $packageName POST_NOTIFICATION default")
    }

    private fun runShell(command: String) {
        instrumentation.uiAutomation.executeShellCommand(command).close()
    }
}
