package io.blaha.groovitation

import com.google.android.material.bottomnavigation.BottomNavigationView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class MainActivityEventsTabNavigationTest {

    @Test
    fun eventsTabPathIsLandingRoot() {
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        assertEquals(
            "Events tab (nav_home) must map to '/' so it loads the events landing page",
            "/",
            activity.bottomNavPathForItemForTest(R.id.nav_home)
        )
    }

    @Test
    fun eventsTabUsesClearAllNotRoute() {
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        val bottomNav = activity.findViewById<BottomNavigationView>(R.id.bottom_navigation)

        // Simulate selecting Map tab first, then switching back to Events
        bottomNav.selectedItemId = R.id.nav_map
        bottomNav.selectedItemId = R.id.nav_home

        assertTrue(
            "Events tab must use clearAll() to force a fresh Turbo visit, " +
                "not route() which causes a stale POP showing the previous tab's content",
            activity.lastNavUsedClearAllForTest()
        )
    }

    @Test
    fun mapTabDoesNotUseClearAll() {
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        val bottomNav = activity.findViewById<BottomNavigationView>(R.id.bottom_navigation)

        bottomNav.selectedItemId = R.id.nav_map

        assertTrue(
            "Non-Events tabs should use route(), not clearAll()",
            !activity.lastNavUsedClearAllForTest()
        )
    }
}
