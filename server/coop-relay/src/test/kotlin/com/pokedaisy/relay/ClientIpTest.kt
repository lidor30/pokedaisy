package com.pokedaisy.relay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ClientIpTest {
    private val cloudflare = listOf("104.16.0.0/13", "2606:4700::/32")

    private fun resolve(mode: ProxyMode, socket: String, vararg headers: Pair<String, String>, trusted: List<String> = RelayConfig.DEFAULT_TRUSTED_PROXIES): String {
        val map = headers.toMap()
        return ClientIp(RelayConfig(proxyMode = mode, trustedProxies = trusted, cloudflareRanges = cloudflare))
            .resolve(socket) { map[it] }
    }

    @Test
    fun cidrMatchesIpv4AndIpv6() {
        assertTrue(Cidr.parse("10.0.0.0/8")!!.contains("10.255.1.2"))
        assertFalse(Cidr.parse("10.0.0.0/8")!!.contains("11.0.0.1"))
        assertTrue(Cidr.parse("173.245.48.0/20")!!.contains("173.245.63.255"))
        assertFalse(Cidr.parse("173.245.48.0/20")!!.contains("173.245.64.0"))
        assertTrue(Cidr.parse("fc00::/7")!!.contains("fd12:3456::1"))
        assertFalse(Cidr.parse("fc00::/7")!!.contains("fe80::1"))
        assertTrue(Cidr.parse("2a06:98c0::/29")!!.contains("2a06:98c7:1::1"))
        assertFalse(Cidr.parse("2a06:98c0::/29")!!.contains("2a06:98c8::1"))
        assertTrue(Cidr.parse("::1/128")!!.contains("::1"))
        assertTrue(Cidr.parse("1.2.3.4")!!.contains("1.2.3.4"))
        assertTrue(Cidr.parse("0.0.0.0/0")!!.contains("8.8.8.8"))
        // families never mix, and names are never looked up
        assertFalse(Cidr.parse("10.0.0.0/8")!!.contains("fc00::1"))
        assertFalse(Cidr.parse("10.0.0.0/8")!!.contains("localhost"))
        assertNull(Cidr.parse("example.com/8"))
        assertNull(Cidr.parse("10.0.0.0/33"))
        assertNull(Cidr.parse("10.0.0.0/x"))
    }

    @Test
    fun noneUsesTheSocketOnly() {
        assertEquals("10.0.0.5", resolve(ProxyMode.NONE, "10.0.0.5", "X-Forwarded-For" to "6.6.6.6", "X-Real-IP" to "7.7.7.7"))
    }

    @Test
    fun forwardedUsesRealIpThenLastForwardedFor() {
        assertEquals("7.7.7.7", resolve(ProxyMode.FORWARDED, "10.0.0.5", "X-Real-IP" to "7.7.7.7", "X-Forwarded-For" to "6.6.6.6"))
        assertEquals("5.5.5.5", resolve(ProxyMode.FORWARDED, "10.0.0.5", "X-Forwarded-For" to "6.6.6.6, 5.5.5.5"))
        assertEquals("10.0.0.5", resolve(ProxyMode.FORWARDED, "10.0.0.5"))
        assertEquals("10.0.0.5", resolve(ProxyMode.FORWARDED, "10.0.0.5", "X-Forwarded-For" to "nonsense"))
        // CF-Connecting-IP is not read in this mode
        assertEquals("5.5.5.5", resolve(ProxyMode.FORWARDED, "10.0.0.5", "X-Real-IP" to "5.5.5.5", "CF-Connecting-IP" to "1.1.1.1"))
    }

    @Test
    fun anUntrustedPeersHeadersAreIgnored() {
        assertEquals("203.0.113.9", resolve(ProxyMode.FORWARDED, "203.0.113.9", "X-Forwarded-For" to "6.6.6.6"))
        assertEquals("203.0.113.9", resolve(ProxyMode.CLOUDFLARE, "203.0.113.9", "CF-Connecting-IP" to "6.6.6.6"))
        assertEquals("10.0.0.5", resolve(ProxyMode.FORWARDED, "10.0.0.5", "X-Forwarded-For" to "6.6.6.6", trusted = listOf("192.168.0.0/16")))
    }

    @Test
    fun cloudflareTrustsItsHeaderOnlyFromItsOwnAddresses() {
        // Nginx Proxy Manager saw Cloudflare (104.16.x.x) and put that in X-Real-IP
        assertEquals("198.51.100.7", resolve(ProxyMode.CLOUDFLARE, "172.18.0.2", "X-Real-IP" to "104.16.1.1", "CF-Connecting-IP" to "198.51.100.7"))
        assertEquals("198.51.100.8", resolve(ProxyMode.CLOUDFLARE, "172.18.0.2", "X-Forwarded-For" to "2606:4700::1", "CF-Connecting-IP" to "198.51.100.8"))
        // someone hit the origin directly and forged the header: the proxy saw 6.6.6.6, not Cloudflare
        assertEquals("6.6.6.6", resolve(ProxyMode.CLOUDFLARE, "172.18.0.2", "X-Real-IP" to "6.6.6.6", "CF-Connecting-IP" to "198.51.100.7"))
        // no CF header from Cloudflare: the address itself; garbage CF header: the address itself
        assertEquals("104.16.1.1", resolve(ProxyMode.CLOUDFLARE, "172.18.0.2", "X-Real-IP" to "104.16.1.1"))
        assertEquals("104.16.1.1", resolve(ProxyMode.CLOUDFLARE, "172.18.0.2", "X-Real-IP" to "104.16.1.1", "CF-Connecting-IP" to "bad"))
        // no ranges loaded: never trusts the header
        val none = ClientIp(RelayConfig(proxyMode = ProxyMode.CLOUDFLARE))
        assertEquals("104.16.1.1", none.resolve("172.18.0.2") { mapOf("X-Real-IP" to "104.16.1.1", "CF-Connecting-IP" to "1.1.1.1")[it] })
    }

    @Test
    fun modesAndEnv() {
        assertEquals(ProxyMode.NONE, RelayConfig.modeOf(null))
        assertEquals(ProxyMode.NONE, RelayConfig.modeOf("0"))
        assertEquals(ProxyMode.FORWARDED, RelayConfig.modeOf("1"))
        assertEquals(ProxyMode.FORWARDED, RelayConfig.modeOf("Forwarded"))
        assertEquals(ProxyMode.CLOUDFLARE, RelayConfig.modeOf("cloudflare"))
        val c = RelayConfig.fromEnv(mapOf("TRUST_PROXY" to "forwarded", "TRUSTED_PROXIES" to "10.0.0.0/8, 192.168.0.0/16"))
        assertEquals(listOf("10.0.0.0/8", "192.168.0.0/16"), c.trustedProxies)
    }
}
