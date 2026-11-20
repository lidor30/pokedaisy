package com.pokedaisy.app.companion.coop

import com.pokedaisy.app.companion.i18n.tk

/** Which relay co-op uses: the player's override, else the build's, else none (the same Wi-Fi and BY IP only). */
object CoopRelay {
    private val URL = Regex("^https://([A-Za-z0-9][A-Za-z0-9.-]*)(?::([0-9]{1,5}))?/?$")
    private const val MAX_HOST = 253

    /** "https://host[:port][/]" -> "https://host[:port]"; anything else (http, paths, queries, user info, spaces) null. */
    fun normalize(input: String): String? {
        val m = URL.matchEntire(input.trim()) ?: return null
        val host = m.groupValues[1]
        if (host.length > MAX_HOST) return null
        val port = m.groupValues[2]
        if (port.isNotEmpty() && port.toInt() !in 1..65_535) return null
        return "https://" + host + (if (port.isEmpty()) "" else ":$port")
    }

    /** The relay to use; null = no relay. */
    fun effective(override: String?, built: String): String? =
        override?.trim()?.takeIf { it.isNotEmpty() } ?: built.ifEmpty { null }

    /** What the settings row shows. */
    fun rowValue(override: String?, built: String): String = when {
        !override.isNullOrBlank() -> override.trim().removePrefix("https://")
        built.isNotEmpty() -> tk("BUILT-IN")
        else -> tk("NONE")
    }
}
