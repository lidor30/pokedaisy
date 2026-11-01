package com.pokedaisy.app.companion.coop

import java.io.ByteArrayOutputStream
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress

/**
 * What one device hands another to be found: its public key, optionally the secret of an invite room and up to four
 * LAN addresses. Text form: `"pdco"` + lowercase unpadded base32 (`a-z2-7`, zero pad bits) of u8 version 1, the 32 key
 * bytes, u8 flags (bit 0 = invite secret present), the 16-byte secret if flagged, u8 address count 0..4, then per address
 * u8 family (4 or 6), 4 / 16 bytes and a u16 BE port (1..65,535). Nothing after. Pure JVM; decoding never looks a name up.
 */
class CoopTicket(val peer: CoopPeerId, val inviteSecret: ByteArray?, val addresses: List<InetSocketAddress>) {
    init {
        require(inviteSecret == null || inviteSecret.size == SECRET_SIZE) { "invite secret: 16 bytes" }
    }

    fun encode(): String {
        require(addresses.size <= MAX_ADDRESSES) { "at most $MAX_ADDRESSES addresses" }
        val out = ByteArrayOutputStream()
        out.write(VERSION)
        out.write(peer.bytes())
        out.write(if (inviteSecret != null) 1 else 0)
        if (inviteSecret != null) out.write(inviteSecret)
        out.write(addresses.size)
        for (a in addresses) {
            val raw = a.address.address
            require(raw.size == 4 || raw.size == 16) { "bad address" }
            require(a.port in 1..65_535) { "bad port" }
            out.write(if (raw.size == 4) 4 else 6)
            out.write(raw)
            out.write(a.port shr 8)
            out.write(a.port and 0xFF)
        }
        return PREFIX + base32(out.toByteArray())
    }

    companion object {
        const val PREFIX = "pdco"
        private const val VERSION = 1
        private const val SECRET_SIZE = 16
        private const val MAX_ADDRESSES = 4
        private const val ALPHABET = "abcdefghijklmnopqrstuvwxyz234567"

        /** The ticket string for [peer] alone: no secret, no addresses. */
        fun peerOnly(peer: CoopPeerId): String = CoopTicket(peer, null, emptyList()).encode()

        /** The ticket in [s], or null for anything malformed (never throws). */
        fun decode(s: String): CoopTicket? = try {
            parse(s)
        } catch (_: RuntimeException) {
            null
        }

        private fun parse(s: String): CoopTicket? {
            if (!s.startsWith(PREFIX)) return null
            val raw = unbase32(s.substring(PREFIX.length)) ?: return null
            var i = 0
            fun left() = raw.size - i
            if (left() < 1 || raw[i++].toInt() != VERSION) return null
            if (left() < 32) return null
            val peer = CoopPeerId.of(raw.copyOfRange(i, i + 32))
            i += 32
            if (left() < 1) return null
            val flags = raw[i++].toInt() and 0xFF
            if (flags and 1.inv() != 0) return null
            var secret: ByteArray? = null
            if (flags and 1 != 0) {
                if (left() < SECRET_SIZE) return null
                secret = raw.copyOfRange(i, i + SECRET_SIZE)
                i += SECRET_SIZE
            }
            if (left() < 1) return null
            val count = raw[i++].toInt() and 0xFF
            if (count > MAX_ADDRESSES) return null
            val list = ArrayList<InetSocketAddress>(count)
            repeat(count) {
                if (left() < 1) return null
                val len = when (raw[i++].toInt()) { 4 -> 4; 6 -> 16; else -> return null }
                if (left() < len + 2) return null
                val addr = raw.copyOfRange(i, i + len)
                i += len
                val port = ((raw[i].toInt() and 0xFF) shl 8) or (raw[i + 1].toInt() and 0xFF)
                i += 2
                if (port == 0) return null
                list.add(InetSocketAddress(InetAddress.getByAddress(addr), port))   // bytes only: no host name is resolved
            }
            if (left() != 0) return null
            return CoopTicket(peer, secret, list)
        }

        private fun base32(bytes: ByteArray): String {
            val out = StringBuilder()
            var bits = 0
            var acc = 0
            for (b in bytes) {
                acc = (acc shl 8) or (b.toInt() and 0xFF)
                bits += 8
                while (bits >= 5) {
                    out.append(ALPHABET[(acc shr (bits - 5)) and 31])
                    bits -= 5
                }
                acc = acc and ((1 shl bits) - 1)
            }
            if (bits > 0) out.append(ALPHABET[(acc shl (5 - bits)) and 31])
            return out.toString()
        }

        /** The bytes of [text], or null for a character outside the alphabet or non-zero pad bits. */
        private fun unbase32(text: String): ByteArray? {
            val out = ByteArrayOutputStream()
            var bits = 0
            var acc = 0
            for (ch in text) {
                val v = ALPHABET.indexOf(ch)
                if (v < 0) return null
                acc = (acc shl 5) or v
                bits += 5
                if (bits >= 8) {
                    out.write((acc shr (bits - 8)) and 0xFF)
                    bits -= 8
                    acc = acc and ((1 shl bits) - 1)
                }
            }
            if (bits >= 5 || acc != 0) return null
            return out.toByteArray()
        }
    }
}
