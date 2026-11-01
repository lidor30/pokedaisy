package com.pokedaisy.app.companion.coop

import java.net.InetSocketAddress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CoopLanTest {
    private val a = CoopPeerId("a".repeat(64))
    private val b = CoopPeerId("b".repeat(64))

    private class Rec : CoopLan.Listener {
        val found = mutableListOf<Triple<CoopPeerId, String, List<InetSocketAddress>>>()
        val lost = mutableListOf<CoopPeerId>()
        override fun found(peer: CoopPeerId, userData: String, addresses: List<InetSocketAddress>) { found.add(Triple(peer, userData, addresses)) }
        override fun lost(peer: CoopPeerId) { lost.add(peer) }
    }

    @Test fun fakesSeeEachOtherAndNotThemselves() {
        val net = FakeLanNetwork()
        val la = FakeCoopLan(net); val lb = FakeCoopLan(net)
        val ra = Rec(); val rb = Rec()
        la.start(a, 1111, "ann", ra)
        lb.start(b, 2222, null, rb)
        assertEquals(listOf(b), ra.found.map { it.first })
        assertEquals("", ra.found[0].second)
        assertEquals(2222, ra.found[0].third[0].port)
        assertEquals(listOf(a), rb.found.map { it.first })
        assertEquals("ann", rb.found[0].second)
    }

    @Test fun userDataChangeAnnouncesAgainAndStopIsLost() {
        val net = FakeLanNetwork()
        val la = FakeCoopLan(net); val lb = FakeCoopLan(net)
        val ra = Rec(); val rb = Rec()
        la.start(a, 1, null, ra); lb.start(b, 2, null, rb)
        lb.setUserData("new")
        assertEquals("new", ra.found.last().second)
        lb.stop()
        assertEquals(listOf(b), ra.lost)
        assertTrue(rb.lost.isEmpty())
    }

    private fun bytes(m: Map<String, String>) = m.mapValues { it.value.toByteArray(Charsets.UTF_8) }

    @Test fun longLabelIsCutAtACharacterWithin200Bytes() {
        val u = NsdCoopLan.txtFor(a, "é".repeat(150) + "😀".repeat(5))["u"]!!
        assertTrue(u.toByteArray(Charsets.UTF_8).size <= 200)
        assertEquals("é".repeat(100), NsdCoopLan.txtFor(a, "é".repeat(150))["u"])
        assertEquals(u, String(u.toByteArray(Charsets.UTF_8), Charsets.UTF_8))
        assertEquals("a".repeat(199), NsdCoopLan.txtFor(a, "a".repeat(199) + "😀")["u"])
    }

    @Test fun txtRoundTrip() {
        val txt = NsdCoopLan.txtFor(a, "hello")
        val parsed = NsdCoopLan.parseTxt(bytes(txt))
        assertEquals(a, parsed?.first)
        assertEquals("hello", parsed?.second)
        assertEquals("", NsdCoopLan.parseTxt(bytes(NsdCoopLan.txtFor(a, null)))?.second)
        val u200 = "é".repeat(100)
        assertEquals(u200, NsdCoopLan.parseTxt(bytes(NsdCoopLan.txtFor(a, u200)))?.second)
    }

    @Test fun badTxtIsNotFound() {
        val k = a.hex.toByteArray()
        assertNull(NsdCoopLan.parseTxt(emptyMap()))
        assertNull(NsdCoopLan.parseTxt(mapOf("k" to "zz".toByteArray())))
        assertNull(NsdCoopLan.parseTxt(mapOf("k" to a.hex.uppercase().toByteArray())))
        assertNull(NsdCoopLan.parseTxt(mapOf("k" to k + 'a'.code.toByte())))
        assertNull(NsdCoopLan.parseTxt(mapOf("k" to k, "u" to ByteArray(201) { 'x'.code.toByte() })))
        assertNotNull(NsdCoopLan.parseTxt(mapOf("k" to k, "u" to ByteArray(200) { 'x'.code.toByte() })))
        assertNull(NsdCoopLan.parseTxt(mapOf("k" to k, "u" to byteArrayOf(0xC3.toByte()))))
    }

    @Test fun noLanDoesNothing() {
        NoCoopLan.start(a, 1, null, Rec()); NoCoopLan.setUserData("x"); NoCoopLan.stop()
    }
}
