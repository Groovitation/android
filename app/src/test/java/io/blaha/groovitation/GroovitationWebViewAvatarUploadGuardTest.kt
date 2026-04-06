package io.blaha.groovitation

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.charset.StandardCharsets

class GroovitationWebViewAvatarUploadGuardTest {

    @Test
    fun supportsExpectedAvatarMimeTypes() {
        assertTrue(GroovitationWebView.isSupportedAvatarMimeType("image/jpeg"))
        assertTrue(GroovitationWebView.isSupportedAvatarMimeType("image/png"))
        assertTrue(GroovitationWebView.isSupportedAvatarMimeType("image/webp"))
        assertTrue(GroovitationWebView.isSupportedAvatarMimeType("image/avif"))
    }

    @Test
    fun rejectsUnsupportedAvatarMimeTypes() {
        assertFalse(GroovitationWebView.isSupportedAvatarMimeType("image/heic"))
        assertFalse(GroovitationWebView.isSupportedAvatarMimeType("text/plain"))
        assertFalse(GroovitationWebView.isSupportedAvatarMimeType(null))
    }

    @Test
    fun enforcesAvatarUploadSizeLimit() {
        assertTrue(GroovitationWebView.isAllowedAvatarPayload("image/jpeg", 5L * 1024L * 1024L))
        assertTrue(GroovitationWebView.isAllowedAvatarPayload("image/jpeg", -1L))
        assertFalse(
            GroovitationWebView.isAllowedAvatarPayload(
                "image/jpeg",
                GroovitationWebView.AVATAR_UPLOAD_MAX_BYTES + 1L
            )
        )
    }

    @Test
    fun detectsAvatarFormatFromHeaderBytes() {
        val jpegHeader = byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0xff.toByte(), 0x00.toByte())
        assertTrue(GroovitationWebView.sniffAvatarMimeType(jpegHeader) == "image/jpeg")
    }

    @Test
    fun rejectsHeicBytesMasqueradingAsJpegMimeType() {
        val heicHeader = byteArrayOf(0x00, 0x00, 0x00, 0x18) + "ftypheic".toByteArray(StandardCharsets.US_ASCII)
        assertFalse(
            GroovitationWebView.isAllowedAvatarPayload(
                "image/jpeg",
                1024L,
                heicHeader,
                "avatar.jpg"
            )
        )
    }

    @Test
    fun detectsHeicHeaderFromHeaderBytes() {
        val heicHeader = byteArrayOf(0x00, 0x00, 0x00, 0x18) +
            "ftypmif1".toByteArray(StandardCharsets.US_ASCII)
        assertTrue(GroovitationWebView.sniffAvatarMimeType(heicHeader) == "image/heic")
    }

    @Test
    fun recognizesHeifMimeTypesAndDisplayNames() {
        assertTrue(GroovitationWebView.isHeifMimeType("image/heic"))
        assertTrue(GroovitationWebView.isHeifMimeType("image/heif"))
        assertTrue(GroovitationWebView.isHeifDisplayName("profile.heic"))
        assertTrue(GroovitationWebView.isHeifDisplayName("profile.HEIF"))
        assertFalse(GroovitationWebView.isHeifMimeType("image/jpeg"))
        assertFalse(GroovitationWebView.isHeifDisplayName("profile.jpg"))
    }

    @Test
    fun normalizesNativeCameraCapturesBeforeUpload() {
        assertTrue(GroovitationWebView.isCameraCaptureDisplayName("image-intake-camera-123.jpg"))
        assertTrue(
            GroovitationWebView.shouldNormalizeAvatarForUpload(
                mimeType = "image/jpeg",
                displayName = "image-intake-camera-123.jpg"
            )
        )
    }

    @Test
    fun normalizesHeicUploadsBeforeValidation() {
        val heicHeader = byteArrayOf(0x00, 0x00, 0x00, 0x18) +
            "ftypheic".toByteArray(StandardCharsets.US_ASCII)
        assertTrue(
            GroovitationWebView.shouldNormalizeAvatarForUpload(
                mimeType = "image/heic",
                headerBytes = heicHeader,
                displayName = "profile.heic"
            )
        )
    }

    @Test
    fun leavesOrdinaryLibraryJpegUnchanged() {
        val jpegHeader = byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0xff.toByte(), 0x00.toByte())
        assertFalse(
            GroovitationWebView.shouldNormalizeAvatarForUpload(
                mimeType = "image/jpeg",
                headerBytes = jpegHeader,
                displayName = "portrait.jpg",
                sourceLabel = "content://media/external/images/media/42"
            )
        )
    }
}
