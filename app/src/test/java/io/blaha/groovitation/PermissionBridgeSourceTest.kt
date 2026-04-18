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
        assertTrue(source.contains("fun setLocationToken(token: String)"))
        assertTrue(source.contains("LocationTrackingService.storeLocationToken"))
        assertTrue(source.contains("fun setSignedInState(signedIn: Boolean)"))
        assertTrue(source.contains("onSignedInStateFromWeb(signedIn)"))
        assertTrue(source.contains("fun getDeviceId(): String"))
        assertTrue(source.contains("Settings.Secure.ANDROID_ID"))
    }
}
