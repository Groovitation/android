package io.blaha.groovitation

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class PermissionBridgeSourceTest {

    @Test
    fun groovitationWebFragmentExposesNativePermissionStateToJavascript() {
        val source = File("src/main/java/io/blaha/groovitation/GroovitationWebFragment.kt").readText()

        assertTrue(source.contains("fun notificationPermissionState(): String"))
        assertTrue(source.contains("syncPermissionStatesToWeb()"))
    }

    @Test
    fun mainActivitySyncsCurrentPermissionStateBackIntoTheWebView() {
        val source = File("src/main/java/io/blaha/groovitation/MainActivity.kt").readText()

        assertTrue(source.contains("fun syncPermissionStatesToWeb()"))
        assertTrue(source.contains("dispatchLocationPermissionState(hasLocationPermission())"))
    }

    @Test
    fun groovitationWebFragmentExposesLocationAuthAndDeviceIdBridgeMethods() {
        val source = File("src/main/java/io/blaha/groovitation/GroovitationWebFragment.kt").readText()

        assertTrue(source.contains("fun setSessionCookie(cookie: String)"))
        assertTrue(source.contains("LocationTrackingService.storeSessionCookie"))
        assertTrue(source.contains("onNativeLocationAuthReadyFromWeb()"))
        assertTrue(source.contains("fun setLocationToken(token: String)"))
        assertTrue(source.contains("LocationTrackingService.storeLocationToken"))
        assertTrue(source.contains("onNativeLocationAuthReadyFromWeb()"))
        assertTrue(source.contains("fun setSignedInState(signedIn: Boolean)"))
        assertTrue(source.contains("onSignedInStateFromWeb(signedIn)"))
        assertTrue(source.contains("fun getDeviceId(): String"))
        assertTrue(source.contains("Settings.Secure.ANDROID_ID"))
    }

    @Test
    fun manifestDeclaresOptionalCameraAndMicrophoneForInlineJitsi() {
        val source = File("src/main/AndroidManifest.xml").readText()

        assertTrue(source.contains("""android.permission.CAMERA"""))
        assertTrue(source.contains("""android.permission.RECORD_AUDIO"""))
        assertTrue(source.contains("""android.permission.MODIFY_AUDIO_SETTINGS"""))
        assertTrue(source.contains("""android.hardware.camera.any"""))
        assertTrue(source.contains("""android.hardware.microphone"""))
        assertTrue(source.contains("android:required=\"false\""))
    }

    @Test
    fun mainActivityBridgesWebRtcPermissionRequestsOnDemand() {
        val source = File("src/main/java/io/blaha/groovitation/MainActivity.kt").readText()

        assertTrue(source.contains("private val webRtcPermissionLauncher = registerForActivityResult"))
        assertTrue(source.contains("ActivityResultContracts.RequestMultiplePermissions()"))
        assertTrue(source.contains("fun requestWebRtcPermissions("))
        assertTrue(source.contains("Manifest.permission.CAMERA"))
        assertTrue(source.contains("Manifest.permission.RECORD_AUDIO"))
        assertTrue(source.contains("webRtcPermissionLauncher.launch(missingPermissions.toTypedArray())"))
    }

    @Test
    fun groovitationWebViewGrantsWebRtcResourcesOnlyAfterNativePermissionGrant() {
        val source = File("src/main/java/io/blaha/groovitation/GroovitationWebView.kt").readText()

        assertTrue(source.contains("override fun onPermissionRequest(request: PermissionRequest?)"))
        assertTrue(source.contains("nativePermissionsForWebRtcResources(requestedResources)"))
        assertTrue(source.contains("activity.requestWebRtcPermissions(nativePermissions)"))
        assertTrue(source.contains("request.grant(grantableResources)"))
        assertTrue(source.contains("request.deny()"))
        assertTrue(source.contains("settings.mediaPlaybackRequiresUserGesture = false"))
    }

    @Test
    fun groovitationWebViewBlocksNativeJitsiDeepLinks() {
        val source = File("src/main/java/io/blaha/groovitation/GroovitationWebView.kt").readText()

        assertTrue(source.contains("override fun setWebViewClient(client: WebViewClient)"))
        assertTrue(source.contains("JitsiHandoffBlockingWebViewClient(client)"))
        assertTrue(source.contains("isJitsiNativeHandoffUrl(url)"))
        assertTrue(source.contains("org.jitsi.meet"))
    }
}
