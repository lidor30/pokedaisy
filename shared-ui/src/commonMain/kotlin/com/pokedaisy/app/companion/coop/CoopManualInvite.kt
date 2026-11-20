package com.pokedaisy.app.companion.coop

const val COOP_IP_PORT = 47321

object CoopManualInvite {
    val greeting: ByteArray get() = "PDCO?1\n".encodeToByteArray()

    const val MAX_GREETING = 16
    const val MAX_REPLY = 4_096
}

/** "192.168.1.20:47321" or "[fe80::1]:47321" -> host and port; no port or one outside 1..65535 -> null. */
fun parseHostPort(text: String): Pair<String, Int>? {
    val t = text.trim()
    val host: String
    val portText: String
    if (t.startsWith("[")) {
        val end = t.indexOf("]:")
        if (end < 2) return null
        host = t.substring(1, end)
        portText = t.substring(end + 2)
    } else {
        val i = t.lastIndexOf(':')
        if (i <= 0 || t.indexOf(':') != i) return null
        host = t.substring(0, i)
        portText = t.substring(i + 1)
    }
    if (host.isEmpty() || host.any { it.isWhitespace() }) return null
    if (portText.isEmpty() || portText.length > 5 || !portText.all { it in '0'..'9' }) return null
    val port = portText.toInt()
    return if (port in 1..65_535) host to port else null
}
