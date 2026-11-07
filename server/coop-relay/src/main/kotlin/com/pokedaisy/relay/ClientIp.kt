package com.pokedaisy.relay

import java.net.InetAddress

/** A CIDR block (IPv4 or IPv6). Parses literals only: no name lookup ever happens. */
class Cidr private constructor(private val network: ByteArray, private val bits: Int) {
    fun contains(ip: String): Boolean {
        val bytes = ClientIp.literal(ip) ?: return false
        if (bytes.size != network.size) return false
        var left = bits
        var i = 0
        while (left > 0) {
            val mask = if (left >= 8) 0xFF else (0xFF shl (8 - left)) and 0xFF
            if ((bytes[i].toInt() and mask) != (network[i].toInt() and mask)) return false
            left -= 8
            i++
        }
        return true
    }

    companion object {
        /** "10.0.0.0/8", "fc00::/7", or a bare address (a /32 or /128); null when it isn't one. */
        fun parse(text: String): Cidr? {
            val t = text.trim()
            val addr = ClientIp.literal(t.substringBefore('/')) ?: return null
            val max = addr.size * 8
            val bits = if (t.contains('/')) t.substringAfter('/').toIntOrNull() ?: return null else max
            if (bits !in 0..max) return null
            return Cidr(addr, bits)
        }

        fun parseAll(texts: List<String>): List<Cidr> = texts.mapNotNull { parse(it) }
    }
}

/** Works out the client address of a socket from its peer address and the proxy headers, by [RelayConfig]. */
class ClientIp(config: RelayConfig) {
    private val mode = config.proxyMode
    private val trusted = Cidr.parseAll(config.trustedProxies)
    private val cloudflare = Cidr.parseAll(config.cloudflareRanges)

    /** [header] reads a request header's value (empty / null when absent). */
    fun resolve(socketIp: String, header: (String) -> String?): String {
        if (mode == ProxyMode.NONE || trusted.none { it.contains(socketIp) }) return socketIp
        fun value(name: String) = header(name)?.trim().orEmpty()
        val real = value("X-Real-IP")
        val last = value("X-Forwarded-For").substringAfterLast(',').trim()
        val proxied = when {
            literal(real) != null -> real
            literal(last) != null -> last
            else -> socketIp
        }
        if (mode == ProxyMode.CLOUDFLARE) {
            val cf = value("CF-Connecting-IP")
            if (literal(cf) != null && cloudflare.any { it.contains(proxied) }) return cf
        }
        return proxied
    }

    companion object {
        /** The address bytes of an IP literal (4 or 16), else null; never resolves a name. */
        fun literal(s: String): ByteArray? {
            val t = s.trim().substringBefore('%')
            if (t.length !in 2..45 || !(t.contains('.') || t.contains(':'))) return null
            if (!t.all { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' || it == ':' || it == '.' }) return null
            return runCatching { InetAddress.getByName(t).address }.getOrNull()
        }
    }
}
