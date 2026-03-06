package io.blaha.groovitation

import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MainActivityLaunchInstrumentedTest {

    @Test
    fun mainActivityLaunchesAndBottomNavIsWithinMaterialLimit() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val bottomNav = activity.findViewById<com.google.android.material.bottomnavigation.BottomNavigationView>(R.id.bottom_navigation)
                assertNotNull("Bottom navigation should be present after app launch", bottomNav)
                assertTrue(
                    "Bottom navigation item count must stay <= 5 to avoid runtime inflation crashes",
                    bottomNav.menu.size() <= 5
                )
            }
        }

        assertNotNull(
            "Instrumentation should be available for emulator smoke run",
            InstrumentationRegistry.getInstrumentation()
        )
    }

    @Test
    fun eventsTabRoutesToEventsListPath() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val bottomNav = activity.findViewById<com.google.android.material.bottomnavigation.BottomNavigationView>(R.id.bottom_navigation)
                bottomNav.selectedItemId = R.id.nav_map
                bottomNav.selectedItemId = R.id.nav_home

                assertEquals(
                    "Events tab must route to landing events list path, not plan or map",
                    "/",
                    activity.latestBottomNavPathForTest()
                )
            }
        }
    }
}
