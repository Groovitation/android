package io.blaha.groovitation

import android.content.Intent
import android.net.Uri
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class MainActivityOAuthCallbackTest {

    @Test
    fun customSchemeOauthCallbackRoutesToNativeAuthenticate() {
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()

        activity.handleIntentForTest(
            Intent(
                Intent.ACTION_VIEW,
                Uri.parse("groovitation://oauth-callback?code=abc-handoff-code&redirect=%2Fplan")
            )
        )

        assertEquals(
            "${BuildConfig.BASE_URL}/oauth/native-authenticate?code=abc-handoff-code&redirect=/plan&platform=android",
            activity.lastRoutedUrlForTest()
        )
    }

    @Test
    fun customSchemeOauthLinkCallbackSelectsAccountTab() {
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()

        activity.handleIntentForTest(
            Intent(
                Intent.ACTION_VIEW,
                Uri.parse("groovitation://oauth-callback?code=abc-handoff-code&redirect=%2Fusers%2Fedit")
            )
        )

        assertEquals(
            "${BuildConfig.BASE_URL}/oauth/native-authenticate?code=abc-handoff-code&redirect=/users/edit&platform=android",
            activity.lastRoutedUrlForTest()
        )
        assertEquals(
            R.id.nav_account,
            activity.findViewById<com.google.android.material.bottomnavigation.BottomNavigationView>(R.id.bottom_navigation).selectedItemId
        )
    }

    @Test
    fun customSchemeOauthCallbackWithLegacyTokenParamIsIgnored() {
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()

        activity.handleIntentForTest(
            Intent(
                Intent.ACTION_VIEW,
                Uri.parse("groovitation://oauth-callback?token=leftover-token&redirect=%2Fplan")
            )
        )

        assertNull(activity.lastRoutedUrlForTest())
    }

    @Test
    fun httpsAppLinkOauthCallbackRoutesToNativeAuthenticateAndSelectsEventsTab() {
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        val appLinkUrl = "https://groovitation.blaha.io/oauth/native-authenticate?code=abc-handoff-code&redirect=%2F&platform=android"

        activity.handleIntentForTest(
            Intent(
                Intent.ACTION_VIEW,
                Uri.parse(appLinkUrl)
            )
        )

        assertEquals(
            appLinkUrl,
            activity.lastRoutedUrlForTest()
        )
        assertEquals(R.id.nav_home, activity.findViewById<com.google.android.material.bottomnavigation.BottomNavigationView>(R.id.bottom_navigation).selectedItemId)
    }
}
