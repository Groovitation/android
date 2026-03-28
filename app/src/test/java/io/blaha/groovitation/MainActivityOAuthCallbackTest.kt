package io.blaha.groovitation

import android.content.Intent
import android.net.Uri
import org.junit.Assert.assertEquals
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
                Uri.parse("groovitation://oauth-callback?token=test-token&redirect=%2Fplan")
            )
        )

        assertEquals(
            "${BuildConfig.BASE_URL}/oauth/native-authenticate?token=test-token&redirect=/plan&platform=android",
            activity.lastRoutedUrlForTest()
        )
    }
}
