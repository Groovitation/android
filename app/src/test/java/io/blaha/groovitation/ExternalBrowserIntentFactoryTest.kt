package io.blaha.groovitation

import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ResolveInfo
import android.net.Uri
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows

@RunWith(RobolectricTestRunner::class)
class ExternalBrowserIntentFactoryTest {

    @Test
    fun buildPrefersChromeWhenInstalled() {
        val context = RuntimeEnvironment.getApplication()
        val shadowPackageManager = Shadows.shadowOf(context.packageManager)
        val url = "https://accounts.google.com/o/oauth2/v2/auth"

        val chromeResolveInfo = ResolveInfo().apply {
            activityInfo = ActivityInfo().apply {
                packageName = "com.android.chrome"
                name = "ChromeActivity"
            }
        }

        shadowPackageManager.addResolveInfoForIntent(
            Intent(Intent.ACTION_VIEW, Uri.parse(url)).setPackage("com.android.chrome"),
            chromeResolveInfo
        )

        val intent = ExternalBrowserIntentFactory.build(context, url)

        assertEquals(Intent.ACTION_VIEW, intent.action)
        assertEquals(url, intent.dataString)
        assertEquals("com.android.chrome", intent.`package`)
    }

    @Test
    fun buildFallsBackToDefaultBrowserWhenChromeUnavailable() {
        val context = RuntimeEnvironment.getApplication()
        val url = "https://accounts.google.com/o/oauth2/v2/auth"

        val intent = ExternalBrowserIntentFactory.build(context, url)

        assertEquals(Intent.ACTION_VIEW, intent.action)
        assertEquals(url, intent.dataString)
        assertNull(intent.`package`)
    }
}
