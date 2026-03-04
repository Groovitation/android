package io.blaha.groovitation

import com.google.android.material.bottomnavigation.BottomNavigationView
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
}
