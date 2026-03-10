package io.blaha.groovitation

import org.json.JSONObject

object ExternalBrowserReturnEvent {
    const val EVENT_NAME = "groovitation:external-browser-return"

    fun buildScript(url: String): String {
        val safeUrl = JSONObject.quote(url)
        return "window.dispatchEvent(new CustomEvent('$EVENT_NAME', { detail: { url: $safeUrl } }));"
    }
}
