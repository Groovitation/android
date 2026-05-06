package io.blaha.groovitation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BrandBuildConfigTest {

    @Test
    fun brandFlavorSuppliesInstallIdentityAndProductionHost() {
        when (BuildConfig.BRAND_ID) {
            "groovitation" -> {
                assertEquals("Groovitation", BuildConfig.APP_DISPLAY_NAME)
                assertEquals("io.blaha.groovitation", BuildConfig.APPLICATION_ID)
                assertEquals("groovitation.blaha.io", BuildConfig.APP_LINK_HOST)
            }
            "elPaso" -> {
                assertEquals("Chucopedia", BuildConfig.APP_DISPLAY_NAME)
                assertEquals("io.blaha.groovitation.chucopedia", BuildConfig.APPLICATION_ID)
                assertEquals("chucopedia.blaha.io", BuildConfig.APP_LINK_HOST)
            }
            else -> throw AssertionError("Unexpected brand flavor: ${BuildConfig.BRAND_ID}")
        }
    }

    @Test
    fun serverFlavorSuppliesExpectedBaseUrl() {
        val publicArtifactSlug = when (BuildConfig.BRAND_ID) {
            "groovitation" -> "groovitation"
            "elPaso" -> "chucopedia"
            else -> throw AssertionError("Unexpected brand flavor: ${BuildConfig.BRAND_ID}")
        }

        when (BuildConfig.SERVER_ID) {
            "prod" -> {
                assertEquals("https://${BuildConfig.APP_LINK_HOST}", BuildConfig.BASE_URL)
                assertEquals(
                    "${BuildConfig.BASE_URL}/android/${publicArtifactSlug}-version.json",
                    BuildConfig.VERSION_CHECK_URL
                )
            }
            "local" -> {
                assertTrue(
                    "Local variants must point at an emulator-reachable fixture backend",
                    BuildConfig.BASE_URL.startsWith("http://10.0.2.2:")
                )
            }
            else -> throw AssertionError("Unexpected server flavor: ${BuildConfig.SERVER_ID}")
        }
    }
}
