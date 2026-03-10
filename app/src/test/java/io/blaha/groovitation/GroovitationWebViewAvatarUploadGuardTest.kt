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
        assertFalse(GroovitationWebView.isAllowedAvatarPayload("image/jpeg", 1024L, heicHeader))
    }
}
