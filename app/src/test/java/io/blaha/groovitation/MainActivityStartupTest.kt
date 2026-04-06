package io.blaha.groovitation

import android.content.Intent
import com.google.android.material.bottomnavigation.BottomNavigationView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class MainActivityStartupTest {

    @Test
    fun mainActivityLaunchesWithSupportedBottomNavItemCount() {
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        val bottomNav = activity.findViewById<BottomNavigationView>(R.id.bottom_navigation)
        assertTrue(
            "BottomNavigationView supports a maximum of 5 items; more will crash at runtime",
            bottomNav.menu.size() <= 5
        )
    }

    @Test
    fun startupPermissionChainCanBeDisabledForInstrumentationIntent() {
        assertTrue(MainActivity.shouldAutoRequestPermissions(Intent()))

        val testIntent = Intent().putExtra(
            MainActivity.EXTRA_DISABLE_STARTUP_PERMISSION_CHAIN,
            true
        )

        assertFalse(MainActivity.shouldAutoRequestPermissions(testIntent))
    }

    @Test
    fun locationPermissionChainCanBeSkippedForInstrumentationIntent() {
        assertTrue(MainActivity.shouldContinueLocationPermissionChain(Intent()))

        val testIntent = Intent().putExtra(
            MainActivity.EXTRA_SKIP_LOCATION_PERMISSION_CHAIN,
            true
        )

        assertFalse(MainActivity.shouldContinueLocationPermissionChain(testIntent))
    }

    @Test
    fun startupDeepLinkUrlIsDeferredUntilNavigatorIsReady() {
        val activity = Robolectric.buildActivity(MainActivity::class.java).create().get()
        val deepLinkUrl = "${BuildConfig.BASE_URL}/test/permission-bridge"

        activity.handleIntentForTest(Intent().putExtra("url", deepLinkUrl))

        assertEquals(deepLinkUrl, activity.lastRoutedUrlForTest())
        assertEquals(deepLinkUrl, activity.pendingRouteUrlForTest())
    }
}
