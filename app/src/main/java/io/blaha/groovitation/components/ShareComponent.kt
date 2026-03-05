package io.blaha.groovitation.components

import android.content.Intent
import android.util.Log
import androidx.fragment.app.Fragment
import dev.hotwire.core.bridge.BridgeComponent
import dev.hotwire.core.bridge.BridgeDelegate
import dev.hotwire.core.bridge.Message
import dev.hotwire.navigation.destinations.HotwireDestination
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

class ShareComponent(
    name: String,
    private val delegate: BridgeDelegate<HotwireDestination>
) : BridgeComponent<HotwireDestination>(name, delegate) {

    companion object {
        private const val TAG = "ShareComponent"

        internal fun buildSharePayload(data: ShareData?): SharePayload {
            val title = data?.title?.trim().orEmpty()
            val text = data?.text?.ifBlank { null } ?: data?.message?.ifBlank { null } ?: ""
            val url = data?.url?.ifBlank { null } ?: data?.link?.ifBlank { null } ?: ""

            val body = buildString {
                if (text.isNotEmpty()) append(text)
                if (url.isNotEmpty()) {
                    if (isNotEmpty()) append("\n\n")
                    append(url)
                }
            }.ifEmpty { title }

            return SharePayload(
                title = title,
                body = body
            )
        }
    }

    private val fragment: Fragment
        get() = delegate.destination.fragment

    override fun onReceive(message: Message) {
        Log.d(TAG, "Received message: ${message.event}")

        when (message.event) {
            "share" -> handleShare(message)
            else -> Log.w(TAG, "Unknown event: ${message.event}")
        }
    }

    private fun handleShare(message: Message) {
        val activity = fragment.activity
        if (activity == null) {
            replyTo("share", ShareReply(success = false, error = "Activity not available"))
            return
        }

        val payload = buildSharePayload(message.data())
        val title = payload.title
        val shareText = payload.body

        if (shareText.isEmpty() && title.isEmpty()) {
            replyTo("share", ShareReply(success = false, error = "Nothing to share"))
            return
        }

        try {
            val sendIntent = Intent().apply {
                action = Intent.ACTION_SEND
                type = "text/plain"
                if (title.isNotEmpty()) {
                    putExtra(Intent.EXTRA_SUBJECT, title)
                }
                if (shareText.isNotEmpty()) {
                    putExtra(Intent.EXTRA_TEXT, shareText)
                }
            }

            val shareIntent = Intent.createChooser(sendIntent, title.ifEmpty { "Share" })
            activity.startActivity(shareIntent)

            Log.d(TAG, "Share sheet opened")
            replyTo("share", ShareReply(success = true))

        } catch (e: Exception) {
            Log.e(TAG, "Error showing share sheet", e)
            replyTo("share", ShareReply(success = false, error = e.message ?: "Failed to open share sheet"))
        }
    }

    @Serializable
    data class ShareData(
        @SerialName("title") val title: String? = null,
        @SerialName("text") val text: String? = null,
        @SerialName("url") val url: String? = null,
        @SerialName("message") val message: String? = null,
        @SerialName("link") val link: String? = null
    )

    data class SharePayload(
        val title: String,
        val body: String
    )

    @Serializable
    data class ShareReply(
        @SerialName("success") val success: Boolean,
        @SerialName("error") val error: String? = null
    )
}
