package io.blaha.groovitation

import android.Manifest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.PermissionRequest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GroovitationWebViewWebRtcPermissionTest {

    @Test
    fun mapsCameraAndMicrophoneResourcesToNativePermissions() {
        val permissions = GroovitationWebView.nativePermissionsForWebRtcResources(
            arrayOf(
                PermissionRequest.RESOURCE_VIDEO_CAPTURE,
                PermissionRequest.RESOURCE_AUDIO_CAPTURE
            )
        )

        assertTrue(permissions.contains(Manifest.permission.CAMERA))
        assertTrue(permissions.contains(Manifest.permission.RECORD_AUDIO))
    }

    @Test
    fun rejectsUnsupportedWebRtcResources() {
        assertFalse(
            GroovitationWebView.hasOnlySupportedWebRtcResources(
                arrayOf(PermissionRequest.RESOURCE_PROTECTED_MEDIA_ID)
            )
        )
        assertFalse(
            GroovitationWebView.hasSupportedWebRtcResources(
                arrayOf(PermissionRequest.RESOURCE_PROTECTED_MEDIA_ID)
            )
        )
    }

    @Test
    fun acceptsMixedWebRtcRequestsWhenAudioOrVideoIsPresent() {
        val resources = arrayOf(
            PermissionRequest.RESOURCE_AUDIO_CAPTURE,
            PermissionRequest.RESOURCE_PROTECTED_MEDIA_ID
        )

        assertFalse(GroovitationWebView.hasOnlySupportedWebRtcResources(resources))
        assertTrue(GroovitationWebView.hasSupportedWebRtcResources(resources))

        assertArrayEquals(
            arrayOf(PermissionRequest.RESOURCE_AUDIO_CAPTURE),
            GroovitationWebView.grantableWebRtcResources(
                resources,
                setOf(Manifest.permission.RECORD_AUDIO)
            )
        )
    }

    @Test
    fun grantsOnlyResourcesBackedByGrantedNativePermissions() {
        val resources = GroovitationWebView.grantableWebRtcResources(
            arrayOf(
                PermissionRequest.RESOURCE_VIDEO_CAPTURE,
                PermissionRequest.RESOURCE_AUDIO_CAPTURE
            ),
            setOf(Manifest.permission.RECORD_AUDIO)
        )

        assertArrayEquals(arrayOf(PermissionRequest.RESOURCE_AUDIO_CAPTURE), resources)
    }

    @Test
    fun blocksNativeJitsiHandoffUrls() {
        assertTrue(
            GroovitationWebView.isJitsiNativeHandoffUrl(
                "intent://video.blaha.io/event-123#Intent;scheme=org.jitsi.meet;package=org.jitsi.meet;end"
            )
        )
        assertTrue(
            GroovitationWebView.isJitsiNativeHandoffUrl(
                "org.jitsi.meet://video.blaha.io/event-123"
            )
        )
        assertTrue(
            GroovitationWebView.isJitsiNativeHandoffUrl(
                "market://details?id=org.jitsi.meet"
            )
        )
        assertTrue(
            GroovitationWebView.isJitsiNativeHandoffUrl(
                "https://play.google.com/store/apps/details?id=org.jitsi.meet"
            )
        )
    }

    @Test
    fun allowsJwtBearingJitsiWebUrlsToStayInWebView() {
        assertFalse(
            GroovitationWebView.isJitsiNativeHandoffUrl(
                "https://video.blaha.io/event-123?jwt=abc.def.ghi#config.disableDeepLinking=true"
            )
        )
        assertFalse(
            GroovitationWebView.isJitsiNativeHandoffUrl(
                "https://video.blaha.io/config.js"
            )
        )
        assertFalse(
            GroovitationWebView.isJitsiNativeHandoffUrl(
                "https://chucopedia.blaha.io/landing"
            )
        )
    }

    @Test
    fun webViewClientConsumesNativeJitsiUrlsBeforeDelegateSeesThem() {
        val delegate = RecordingWebViewClient()
        val client = GroovitationWebView.JitsiHandoffBlockingWebViewClient(delegate)

        assertTrue(client.shouldOverrideUrlLoading(null, "org.jitsi.meet://video.blaha.io/event-123"))
        assertNull(delegate.lastOldUrl)
    }

    @Test
    fun webViewClientDelegatesNormalJitsiWebUrls() {
        val delegate = RecordingWebViewClient()
        val client = GroovitationWebView.JitsiHandoffBlockingWebViewClient(delegate)
        val url = "https://video.blaha.io/event-123?jwt=abc.def.ghi#config.disableDeepLinking=true"

        assertFalse(client.shouldOverrideUrlLoading(null, url))

        assertEquals(url, delegate.lastOldUrl)
    }

    private class RecordingWebViewClient : WebViewClient() {
        var lastOldUrl: String? = null

        @Deprecated("Deprecated in Android API 24")
        override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean {
            lastOldUrl = url
            return false
        }
    }
}
