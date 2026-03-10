package io.blaha.groovitation

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GroovitationWebFragmentModalBackScriptTest {

    @Test
    fun closeScriptPrefersExplicitOverlayHelpersBeforeGenericModalFallbacks() {
        val script = GroovitationWebFragment.buildCloseTopWebModalScript()

        assertTrue(
            "Android modal back script should close the phone-entry overlay explicitly first",
            script.contains("helpers.closePhoneEntryModal()")
        )
        assertTrue(
            "Android modal back script should close the group-chat overlay explicitly before other modals",
            script.contains("helpers.closeGroupChatModal()")
        )
        assertFalse(
            "Android modal back script should not synthesize Escape events and hope the DOM settles in time",
            script.contains("KeyboardEvent('keydown'")
        )
        assertTrue(
            "Android modal back script should prefer z-index ordering over raw DOM order for generic modal fallback",
            script.contains("modalStackWeight")
        )
    }
}
