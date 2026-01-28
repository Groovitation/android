package io.blaha.groovitation

import android.content.Context
import android.util.AttributeSet
import android.util.Log
import dev.hotwire.core.turbo.webview.HotwireWebView

/**
 * Custom WebView for Groovitation.
 *
 * Note: HTTP Basic Auth is handled via the custom GroovitationWebFragment,
 * which properly extends Hotwire's WebViewClient functionality.
 * Do NOT set a custom WebViewClient here - it will break Hotwire's Turbo navigation.
 */
class GroovitationWebView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : HotwireWebView(context, attrs) {

    companion object {
        private const val TAG = "GroovitationWebView"
    }

    init {
        Log.d(TAG, "GroovitationWebView initialized")
    }
}
