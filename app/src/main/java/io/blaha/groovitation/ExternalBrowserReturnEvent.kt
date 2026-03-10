package io.blaha.groovitation

object ExternalBrowserReturnEvent {
    const val EVENT_NAME = "groovitation:external-browser-return"

    fun buildScript(url: String): String {
        val safeUrl = buildJsStringLiteral(url)
        return "window.dispatchEvent(new CustomEvent('$EVENT_NAME', { detail: { url: $safeUrl } }));"
    }

    private fun buildJsStringLiteral(value: String): String {
        val escaped = buildString(value.length + 2) {
            append('"')
            value.forEach { ch ->
                when (ch) {
                    '\\' -> append("\\\\")
                    '"' -> append("\\\"")
                    '\b' -> append("\\b")
                    '\u000C' -> append("\\f")
                    '\n' -> append("\\n")
                    '\r' -> append("\\r")
                    '\t' -> append("\\t")
                    else -> {
                        if (ch.code < 0x20) {
                            append("\\u%04x".format(ch.code))
                        } else {
                            append(ch)
                        }
                    }
                }
            }
            append('"')
        }
        return escaped
    }
}
