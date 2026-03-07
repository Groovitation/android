package io.blaha.groovitation

import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class MainActivityVersionStateTest {

    private lateinit var prefsName: String

    @Before
    fun setUp() {
        prefsName = "main-activity-version-state-test-${System.nanoTime()}"
    }

    @Test
    fun reconcileResetsRequestedFlagForLegacyUpgradeState() {
        val prefs = testPrefs()
        prefs.edit()
            .putBoolean("notification_permission_requested", true)
            .apply()

        val resetApplied = MainActivity.reconcileNotificationPermissionStateForVersion(
            prefs = prefs,
            currentVersionCode = 50
        )

        assertTrue(resetApplied)
        assertFalse(prefs.getBoolean("notification_permission_requested", true))
        assertEquals(50, prefs.getInt("last_seen_app_version_code", -1))
    }

    @Test
    fun reconcileResetsRequestedFlagWhenVersionCodeChanges() {
        val prefs = testPrefs()
        prefs.edit()
            .putInt("last_seen_app_version_code", 49)
            .putBoolean("notification_permission_requested", true)
            .apply()

        val resetApplied = MainActivity.reconcileNotificationPermissionStateForVersion(
            prefs = prefs,
            currentVersionCode = 50
        )

        assertTrue(resetApplied)
        assertFalse(prefs.getBoolean("notification_permission_requested", true))
        assertEquals(50, prefs.getInt("last_seen_app_version_code", -1))
    }

    @Test
    fun reconcileDoesNotResetRequestedFlagWhenVersionUnchanged() {
        val prefs = testPrefs()
        prefs.edit()
            .putInt("last_seen_app_version_code", 50)
            .putBoolean("notification_permission_requested", true)
            .apply()

        val resetApplied = MainActivity.reconcileNotificationPermissionStateForVersion(
            prefs = prefs,
            currentVersionCode = 50
        )

        assertFalse(resetApplied)
        assertTrue(prefs.getBoolean("notification_permission_requested", false))
        assertEquals(50, prefs.getInt("last_seen_app_version_code", -1))
    }

    private fun testPrefs() = RuntimeEnvironment.getApplication().getSharedPreferences(
        prefsName,
        Context.MODE_PRIVATE
    )
}
