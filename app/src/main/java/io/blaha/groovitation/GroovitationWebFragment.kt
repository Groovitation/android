package io.blaha.groovitation

import android.os.Bundle
import android.util.Log
import android.view.View
import android.webkit.HttpAuthHandler
import android.webkit.JavascriptInterface
import dev.hotwire.core.turbo.webview.HotwireWebView
import dev.hotwire.navigation.destinations.HotwireDestinationDeepLink
import dev.hotwire.navigation.fragments.HotwireWebFragment

/**
 * Custom WebFragment that handles HTTP Basic Authentication for groovitation.blaha.io
 * and bridges personId from the web app to the native app for background tracking.
 */
@HotwireDestinationDeepLink(uri = "hotwire://fragment/web")
class GroovitationWebFragment : HotwireWebFragment() {

    companion object {
        private const val TAG = "GroovitationWebFragment"
        private const val AUTH_USERNAME = "groovitation"
        private const val AUTH_PASSWORD = "aldoofra"
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        Log.d(TAG, "GroovitationWebFragment onViewCreated")
    }

    override fun onWebViewAttached(webView: HotwireWebView) {
        super.onWebViewAttached(webView)
        webView.addJavascriptInterface(GroovitationNativeInterface(), "GroovitationNative")
    }

    override fun onReceivedHttpAuthRequest(
        handler: HttpAuthHandler,
        host: String,
        realm: String
    ) {
        Log.d(TAG, "Received HTTP auth request for host: $host, realm: $realm")

        if (host.contains("groovitation.blaha.io")) {
            Log.d(TAG, "Providing credentials for $host")
            handler.proceed(AUTH_USERNAME, AUTH_PASSWORD)
        } else {
            Log.w(TAG, "Unknown host requesting auth: $host")
            super.onReceivedHttpAuthRequest(handler, host, realm)
        }
    }

    private inner class GroovitationNativeInterface {
        @JavascriptInterface
        fun setPersonId(personId: String) {
            Log.d(TAG, "Received personId from web: $personId")
            if (personId.isBlank()) return

            activity?.runOnUiThread {
                (activity as? MainActivity)?.onPersonIdReceived(personId)
            }
        }
    }
}
