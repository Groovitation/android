package io.blaha.groovitation

import android.app.Activity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows

@RunWith(RobolectricTestRunner::class)
class ExternalBrowserIntentFactoryTest {

    @Test
    fun launchOpensCustomTabWithCorrectUrl() {
        val activity = Robolectric.buildActivity(Activity::class.java).create().get()
        val url = "https://accounts.google.com/o/oauth2/v2/auth"

        ExternalBrowserIntentFactory.launch(activity, url)

        val shadowActivity = Shadows.shadowOf(activity)
        val intent = shadowActivity.nextStartedActivity
        assertNotNull("Custom Tab intent should be started", intent)
        assertEquals(url, intent.data.toString())
    }

    @Test
    fun launchIncludesCustomTabsSessionExtra() {
        val activity = Robolectric.buildActivity(Activity::class.java).create().get()
        val url = "https://example.com"

        ExternalBrowserIntentFactory.launch(activity, url)

        val shadowActivity = Shadows.shadowOf(activity)
        val intent = shadowActivity.nextStartedActivity
        assertNotNull(intent)
        // Custom Tabs intents include a session extra to distinguish from plain ACTION_VIEW
        assert(intent.hasExtra("android.support.customtabs.extra.SESSION"))
    }
}
