package io.blaha.groovitation.components

import org.junit.Assert.assertEquals
import org.junit.Test

class ShareComponentPayloadTest {

    @Test
    fun buildSharePayloadUsesTextAndUrl() {
        val payload = ShareComponent.buildSharePayload(
            ShareComponent.ShareData(
                title = "Invite friends",
                text = "Join us",
                url = "https://groovitation.blaha.io/invite/abc"
            )
        )

        assertEquals("Invite friends", payload.title)
        assertEquals("Join us\n\nhttps://groovitation.blaha.io/invite/abc", payload.body)
    }

    @Test
    fun buildSharePayloadFallsBackToAliasFields() {
        val payload = ShareComponent.buildSharePayload(
            ShareComponent.ShareData(
                title = "Invite friends",
                message = "Check this out",
                link = "https://groovitation.blaha.io/invite/xyz"
            )
        )

        assertEquals("Invite friends", payload.title)
        assertEquals("Check this out\n\nhttps://groovitation.blaha.io/invite/xyz", payload.body)
    }

    @Test
    fun buildSharePayloadFallsBackToTitleWhenBodyMissing() {
        val payload = ShareComponent.buildSharePayload(
            ShareComponent.ShareData(
                title = "Invite friends"
            )
        )

        assertEquals("Invite friends", payload.title)
        assertEquals("Invite friends", payload.body)
    }
}
