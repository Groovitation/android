package io.blaha.groovitation

import android.Manifest
import android.webkit.PermissionRequest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
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
}
