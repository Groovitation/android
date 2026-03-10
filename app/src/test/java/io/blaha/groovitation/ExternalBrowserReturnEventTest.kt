package io.blaha.groovitation

import org.junit.Assert.assertEquals
import org.junit.Test

class ExternalBrowserReturnEventTest {

    @Test
    fun buildScriptEscapesUrlInsideCustomEventDetail() {
        val url = "https://video.blaha.io/groovitation-123?name=Ben \"B\""

        val script = ExternalBrowserReturnEvent.buildScript(url)

        assertEquals(
            "window.dispatchEvent(new CustomEvent('groovitation:external-browser-return', { detail: { url: \"https://video.blaha.io/groovitation-123?name=Ben \\\"B\\\"\" } }));",
            script
        )
    }
}
