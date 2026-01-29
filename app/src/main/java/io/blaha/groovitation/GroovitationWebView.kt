package io.blaha.groovitation

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.util.AttributeSet
import android.util.Log
import android.webkit.GeolocationPermissions
import android.webkit.WebChromeClient
import androidx.core.content.ContextCompat
import dev.hotwire.core.turbo.webview.HotwireWebView

/**
 * Custom WebView for Groovitation.
 *
 * Wraps Hotwire's WebChromeClient to intercept geolocation permission
 * requests and auto-grant them when native Android permission is held,
 * eliminating the duplicate browser-level location prompt.
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
        settings.setGeolocationEnabled(true)
    }

    override fun setWebChromeClient(client: WebChromeClient?) {
        if (client != null && client !is GeolocationWebChromeClient) {
            super.setWebChromeClient(GeolocationWebChromeClient(client, context))
        } else {
            super.setWebChromeClient(client)
        }
    }

    private class GeolocationWebChromeClient(
        private val delegate: WebChromeClient,
        private val appContext: Context
    ) : WebChromeClient() {

        override fun onGeolocationPermissionsShowPrompt(
            origin: String,
            callback: GeolocationPermissions.Callback
        ) {
            val hasPermission = ContextCompat.checkSelfPermission(
                appContext, Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED || ContextCompat.checkSelfPermission(
                appContext, Manifest.permission.ACCESS_COARSE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED

            if (hasPermission) {
                Log.d(TAG, "Auto-granting WebView geolocation for $origin")
                callback.invoke(origin, true, false)
            } else {
                Log.d(TAG, "No native location permission, denying WebView geolocation")
                callback.invoke(origin, false, false)
            }
        }

        override fun onGeolocationPermissionsHidePrompt() {
            delegate.onGeolocationPermissionsHidePrompt()
        }

        override fun onProgressChanged(view: android.webkit.WebView?, newProgress: Int) {
            delegate.onProgressChanged(view, newProgress)
        }

        override fun onReceivedTitle(view: android.webkit.WebView?, title: String?) {
            delegate.onReceivedTitle(view, title)
        }

        override fun onReceivedIcon(view: android.webkit.WebView?, icon: android.graphics.Bitmap?) {
            delegate.onReceivedIcon(view, icon)
        }

        override fun onShowCustomView(view: android.view.View?, callback: CustomViewCallback?) {
            delegate.onShowCustomView(view, callback)
        }

        override fun onHideCustomView() {
            delegate.onHideCustomView()
        }

        override fun onPermissionRequest(request: android.webkit.PermissionRequest?) {
            delegate.onPermissionRequest(request)
        }

        override fun onConsoleMessage(consoleMessage: android.webkit.ConsoleMessage?): Boolean {
            return delegate.onConsoleMessage(consoleMessage)
        }

        override fun onShowFileChooser(
            webView: android.webkit.WebView?,
            filePathCallback: android.webkit.ValueCallback<Array<android.net.Uri>>?,
            fileChooserParams: FileChooserParams?
        ): Boolean {
            return delegate.onShowFileChooser(webView, filePathCallback, fileChooserParams)
        }

        override fun onJsAlert(
            view: android.webkit.WebView?, url: String?, message: String?,
            result: android.webkit.JsResult?
        ): Boolean {
            return delegate.onJsAlert(view, url, message, result)
        }

        override fun onJsConfirm(
            view: android.webkit.WebView?, url: String?, message: String?,
            result: android.webkit.JsResult?
        ): Boolean {
            return delegate.onJsConfirm(view, url, message, result)
        }

        override fun onJsPrompt(
            view: android.webkit.WebView?, url: String?, message: String?,
            defaultValue: String?, result: android.webkit.JsPromptResult?
        ): Boolean {
            return delegate.onJsPrompt(view, url, message, defaultValue, result)
        }

        override fun onCreateWindow(
            view: android.webkit.WebView?, isDialog: Boolean,
            isUserGesture: Boolean, resultMsg: android.os.Message?
        ): Boolean {
            return delegate.onCreateWindow(view, isDialog, isUserGesture, resultMsg)
        }

        override fun onCloseWindow(window: android.webkit.WebView?) {
            delegate.onCloseWindow(window)
        }
    }
}
