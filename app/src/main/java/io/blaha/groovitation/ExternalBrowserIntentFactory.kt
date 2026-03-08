package io.blaha.groovitation

import android.content.Context
import android.content.Intent
import android.net.Uri

object ExternalBrowserIntentFactory {
    private const val CHROME_PACKAGE = "com.android.chrome"

    fun build(context: Context, url: String): Intent {
        val uri = Uri.parse(url)
        val fallbackIntent = Intent(Intent.ACTION_VIEW, uri)
            .addCategory(Intent.CATEGORY_BROWSABLE)

        val chromeIntent = Intent(fallbackIntent).setPackage(CHROME_PACKAGE)
        val canUseChrome = chromeIntent.resolveActivity(context.packageManager) != null

        return if (canUseChrome) chromeIntent else fallbackIntent
    }
}
