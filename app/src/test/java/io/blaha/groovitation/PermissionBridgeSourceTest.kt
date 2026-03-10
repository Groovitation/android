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
}
