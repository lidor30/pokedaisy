package com.pokedaisy.app.companion.coop

import java.io.File
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class CoopPairsTest {
    @get:Rule val tmp = TemporaryFolder()

    private val may = byteArrayOf(0xC7.toByte(), 0xBB.toByte(), 0xD3.toByte(), 0xFF.toByte(), 0, 0, 0, 0)
    private val ash = byteArrayOf(0xBB.toByte(), 0xCD.toByte(), 0xC2.toByte(), 0xFF.toByte(), 0, 0, 0, 0)

    private fun pair(own: Int = 1, peerByte: Int = 7, ticket: String? = ticketOf(5)): CoopPair {
        val partnerSave = CoopSaveId(0x4321, 0x8765, may)
        val partner = CoopPartner(CoopPeerId.of(ByteArray(32) { peerByte.toByte() }), "MAY", 0x4321, true, partnerSave)
        return CoopPair(CoopSaveId(own, 0xABCD, ash), "Emerald (ASH)", partner, ticket, 1_700_000_000_000L)
    }

    private fun file() = File(tmp.root, "coop/pairs.json")

    @Test fun dropsPairsFromAnOlderBuild() {
        val f = file()
        CoopPairs(f).add(pair(own = 1, peerByte = 7, ticket = "endpointabc"))
        CoopPairs(f).add(pair(own = 2, peerByte = 8, ticket = null))
        CoopPairs(f).add(pair(own = 3, peerByte = 9, ticket = ticketOf(5)))
        assertEquals(setOf(2, 3), CoopPairs(f).all().map { it.ownSave.trainerId }.toSet())
    }

    @Test fun addFindRemove() {
        val pairs = CoopPairs(file())
        val p = pair()
        assertTrue(pairs.add(p))
        assertEquals(p, pairs.forSave(p.ownSave))
        assertEquals(setOf(p.partner.peer), pairs.allowedPeers())
        pairs.remove(p.ownSave)
        assertNull(pairs.forSave(p.ownSave))
        assertTrue(pairs.all().isEmpty())
        assertEquals(0, org.json.JSONArray(file().readText()).length())
    }

    @Test fun onePartnerPerSave() {
        val pairs = CoopPairs(file())
        val first = pair(peerByte = 7)
        assertTrue(pairs.add(first))
        assertFalse(pairs.add(pair(peerByte = 9)))
        assertEquals(listOf(first), pairs.all())
    }

    @Test fun survivesReload() {
        val p = pair()
        CoopPairs(file()).add(p)
        val back = CoopPairs(file()).all()
        assertEquals(listOf(p), back)
        assertEquals("MAY", back[0].partner.name)
        assertArrayEquals(may, back[0].partner.save.name)
    }

    @Test fun updateTicketAndNullTicket() {
        val pairs = CoopPairs(file())
        val p = pair(ticket = null)
        pairs.add(p)
        assertNull(CoopPairs(file()).forSave(p.ownSave)!!.lastTicket)
        pairs.updateTicket(p.ownSave, ticketOf(6))
        assertEquals(ticketOf(6), CoopPairs(file()).forSave(p.ownSave)!!.lastTicket)
    }

    @Test fun corruptFileIsKeptAside() {
        file().parentFile!!.mkdirs()
        file().writeText("{oops")
        val pairs = CoopPairs(file())
        assertTrue(pairs.all().isEmpty())
        assertEquals("{oops", File(file().parentFile, "pairs.json.bad").readText())
        assertTrue(pairs.add(pair()))
        assertEquals("{oops", File(file().parentFile, "pairs.json.bad").readText())
    }

    @Test fun secretKeyIsStable() {
        val dir = File(tmp.root, "coop")
        val a = CoopIdentity.secretKey(dir)
        assertEquals(32, a.size)
        assertArrayEquals(a, CoopIdentity.secretKey(dir))
        File(dir, "secret.key").writeBytes(ByteArray(5))
        val b = CoopIdentity.secretKey(dir)
        assertEquals(32, b.size)
        assertEquals(32L, File(dir, "secret.key").length())
        assertNotEquals(0, b.count { it.toInt() != 0 })
    }
}

class CoopPairsSafetyTest {
    @get:Rule val tmp = TemporaryFolder()

    private val may = byteArrayOf(0xC7.toByte(), 0xBB.toByte(), 0xD3.toByte(), 0xFF.toByte(), 0, 0, 0, 0)

    private fun pair(): CoopPair {
        val partner = CoopPartner(CoopPeerId.of(ByteArray(32) { 3 }), "MAY", 1, false, CoopSaveId(1, 2, may))
        return CoopPair(CoopSaveId(9, 8, may), "label", partner, null, 5L)
    }

    private fun file() = File(tmp.root, "coop/pairs.json")

    @Test fun anIoErrorKeepsTheFileAndBlocksWrites() {
        val good = CoopPairs(file())
        assertTrue(good.add(pair()))
        val before = file().readText()
        val failing = CoopPairs(file(), read = { throw java.io.IOException("transient") })
        assertTrue(failing.all().isEmpty())
        assertTrue(runCatching { failing.add(pair()) }.exceptionOrNull() is java.io.IOException)
        assertTrue(runCatching { failing.remove(pair().ownSave) }.exceptionOrNull() is java.io.IOException)
        assertTrue(runCatching { failing.updateTicket(pair().ownSave, "t") }.exceptionOrNull() is java.io.IOException)
        assertEquals(before, file().readText())
        assertFalse(File(file().parentFile, "pairs.json.bad").exists())
        assertEquals(listOf(pair()), CoopPairs(file()).all())
    }

    @Test fun aFailedMoveAsideNeverLetsAWriteOverwrite() {
        file().parentFile!!.mkdirs()
        file().writeText("{oops")
        val pairs = CoopPairs(file(), moveAside = { _, _ -> false })
        assertTrue(pairs.all().isEmpty())
        assertTrue(runCatching { pairs.add(pair()) }.exceptionOrNull() is java.io.IOException)
        assertEquals("{oops", file().readText())
    }

    @Test fun anEarlierBadFileIsNeverDestroyed() {
        file().parentFile!!.mkdirs()
        file().writeText("{first")
        CoopPairs(file()).all()
        file().writeText("{second")
        CoopPairs(file()).all()
        val texts = file().parentFile!!.listFiles()!!.filter { it.name.startsWith("pairs.json.bad") }.map { it.readText() }.toSet()
        assertEquals(setOf("{first", "{second"), texts)
        assertFalse(file().exists())
    }
}
