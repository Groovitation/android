package io.blaha.groovitation

import android.content.Context
import android.net.Uri
import androidx.browser.customtabs.CustomTabsIntent

object ExternalBrowserIntentFactory {
    fun launch(context: Context, url: String) {
        CustomTabsIntent.Builder()
            .setShowTitle(true)
            .build()
            .launchUrl(context, Uri.parse(url))
    }
}
