package com.pokedaisy.app.companion.coop

import java.io.File
import okio.Path.Companion.toOkioPath
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

    private fun pair(own: Int = 1, peerByte: Int = 7, ticket: String? = ticketOf(5), partnerTid: Int = 0x4321): CoopPair {
        val partnerSave = CoopSaveId(partnerTid, 0x8765, may)
        val partner = CoopPartner(CoopPeerId.of(ByteArray(32) { peerByte.toByte() }), "MAY", 0x4321, true, partnerSave)
        return CoopPair(CoopSaveId(own, 0xABCD, ash), "Emerald (ASH)", partner, ticket, 1_700_000_000_000L)
    }

    private fun file() = File(tmp.root, "coop/pairs.json")

    @Test fun dropsPairsFromAnOlderBuild() {
        val f = file()
        CoopPairs(f.toOkioPath()).add(pair(own = 1, peerByte = 7, ticket = "endpointabc"))
        CoopPairs(f.toOkioPath()).add(pair(own = 2, peerByte = 8, ticket = null))
        CoopPairs(f.toOkioPath()).add(pair(own = 3, peerByte = 9, ticket = ticketOf(5)))
        assertEquals(setOf(2, 3), CoopPairs(f.toOkioPath()).all().map { it.ownSave.trainerId }.toSet())
    }

    @Test fun addFindRemove() {
        val pairs = CoopPairs(file().toOkioPath())
        val p = pair()
        assertTrue(pairs.add(p))
        assertEquals(p, pairs.forSave(p.ownSave))
        assertEquals(setOf(p.partner.peer), pairs.allowedPeers())
        pairs.remove(p.ownSave)
        assertNull(pairs.forSave(p.ownSave))
        assertTrue(pairs.all().isEmpty())
        assertEquals(0, org.json.JSONArray(file().readText()).length())
    }

    /** A friend of [own]: its own device ([n] picks the key) and its own save. */
    private fun friend(n: Int, own: Int = 1) = pair(own = own, peerByte = 20 + n, partnerTid = 100 + n)

    @Test fun aSaveKeepsSeveralFriends() {
        val pairs = CoopPairs(file().toOkioPath())
        val a = friend(1)
        val b = friend(2)
        val other = friend(3, own = 2)
        assertTrue(pairs.add(a))
        assertTrue(pairs.add(b))
        assertTrue(pairs.add(other))
        assertEquals(listOf(a, b), CoopPairs(file().toOkioPath()).friendsOf(a.ownSave))
        assertEquals(a, pairs.forSave(a.ownSave))   // the oldest
        assertEquals(listOf(other), pairs.friendsOf(other.ownSave))
        assertEquals(setOf(a.partner.peer, b.partner.peer, other.partner.peer), pairs.allowedPeers())
    }

    @Test fun theSameFriendTwiceIsRefused() {
        val pairs = CoopPairs(file().toOkioPath())
        val a = friend(1)
        assertTrue(pairs.add(a))
        // The same device again, or the same partner save from another device: already friends.
        assertEquals(CoopPairs.AddResult.ALREADY_FRIENDS, pairs.addResult(a.copy(pairedAt = 5L)))
        assertEquals(CoopPairs.AddResult.ALREADY_FRIENDS, pairs.addResult(pair(peerByte = 99, partnerTid = a.partner.save.trainerId)))
        assertEquals(listOf(a), pairs.all())
        // Another save of mine may befriend that same device.
        assertTrue(pairs.add(friend(1, own = 2)))
    }

    @Test fun friendsStopAtTheLimit() {
        val pairs = CoopPairs(file().toOkioPath())
        for (n in 1..CoopPairs.MAX_FRIENDS) assertTrue(pairs.add(friend(n)))
        assertFalse(pairs.hasRoom(friend(1).ownSave))
        assertEquals(CoopPairs.AddResult.FULL, pairs.addResult(friend(CoopPairs.MAX_FRIENDS + 1)))
        assertEquals(CoopPairs.MAX_FRIENDS, pairs.friendsOf(friend(1).ownSave).size)
        assertTrue(pairs.hasRoom(friend(1, own = 2).ownSave))
    }

    @Test fun removeOneFriendKeepsTheOthers() {
        val pairs = CoopPairs(file().toOkioPath())
        val a = friend(1)
        val b = friend(2)
        pairs.add(a)
        pairs.add(b)
        pairs.remove(a.ownSave, a.partner.peer)
        assertEquals(listOf(b), CoopPairs(file().toOkioPath()).friendsOf(a.ownSave))
        pairs.remove(a.ownSave)
        assertTrue(pairs.all().isEmpty())
    }

    @Test fun ticketsArePerFriend() {
        val pairs = CoopPairs(file().toOkioPath())
        val a = friend(1)
        val b = friend(2)
        pairs.add(a)
        pairs.add(b)
        pairs.updateTicket(a.ownSave, b.partner.peer, ticketOf(9))
        val back = CoopPairs(file().toOkioPath()).friendsOf(a.ownSave)
        assertEquals(a.lastTicket, back[0].lastTicket)
        assertEquals(ticketOf(9), back[1].lastTicket)
    }

    @Test fun versionMovesOnEveryWrite() {
        val pairs = CoopPairs(file().toOkioPath())
        val v0 = pairs.version
        pairs.add(friend(1))
        val v1 = pairs.version
        pairs.updateTicket(friend(1).ownSave, friend(1).partner.peer, ticketOf(8))
        assertTrue(v1 > v0)
        assertTrue(pairs.version > v1)
    }

    @Test fun aFileFromOnePartnerPerSaveReadsAsFriends() {
        // A pairs.json from before friends: the same entries, one per save.
        val f = file()
        CoopPairs(f.toOkioPath()).add(pair(own = 1, peerByte = 7))
        CoopPairs(f.toOkioPath()).add(pair(own = 2, peerByte = 8))
        assertEquals(1, CoopPairs(f.toOkioPath()).friendsOf(pair(own = 1).ownSave).size)
        assertEquals(1, CoopPairs(f.toOkioPath()).friendsOf(pair(own = 2).ownSave).size)
    }

    @Test fun survivesReload() {
        val p = pair()
        CoopPairs(file().toOkioPath()).add(p)
        val back = CoopPairs(file().toOkioPath()).all()
        assertEquals(listOf(p), back)
        assertEquals("MAY", back[0].partner.name)
        assertArrayEquals(may, back[0].partner.save.name)
    }

    @Test fun updateTicketAndNullTicket() {
        val pairs = CoopPairs(file().toOkioPath())
        val p = pair(ticket = null)
        pairs.add(p)
        assertNull(CoopPairs(file().toOkioPath()).forSave(p.ownSave)!!.lastTicket)
        pairs.updateTicket(p.ownSave, p.partner.peer, ticketOf(6))
        assertEquals(ticketOf(6), CoopPairs(file().toOkioPath()).forSave(p.ownSave)!!.lastTicket)
    }

    @Test fun corruptFileIsKeptAside() {
        file().parentFile!!.mkdirs()
        file().writeText("{oops")
        val pairs = CoopPairs(file().toOkioPath())
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
        val good = CoopPairs(file().toOkioPath())
        assertTrue(good.add(pair()))
        val before = file().readText()
        val failing = CoopPairs(file().toOkioPath(), read = { throw java.io.IOException("transient") })
        assertTrue(failing.all().isEmpty())
        assertTrue(runCatching { failing.add(pair()) }.exceptionOrNull() is java.io.IOException)
        assertTrue(runCatching { failing.remove(pair().ownSave) }.exceptionOrNull() is java.io.IOException)
        assertTrue(runCatching { failing.updateTicket(pair().ownSave, pair().partner.peer, "t") }.exceptionOrNull() is java.io.IOException)
        assertEquals(before, file().readText())
        assertFalse(File(file().parentFile, "pairs.json.bad").exists())
        assertEquals(listOf(pair()), CoopPairs(file().toOkioPath()).all())
    }

    @Test fun aFailedMoveAsideNeverLetsAWriteOverwrite() {
        file().parentFile!!.mkdirs()
        file().writeText("{oops")
        val pairs = CoopPairs(file().toOkioPath(), moveAside = { _, _ -> false })
        assertTrue(pairs.all().isEmpty())
        assertTrue(runCatching { pairs.add(pair()) }.exceptionOrNull() is java.io.IOException)
        assertEquals("{oops", file().readText())
    }

    @Test fun anEarlierBadFileIsNeverDestroyed() {
        file().parentFile!!.mkdirs()
        file().writeText("{first")
        CoopPairs(file().toOkioPath()).all()
        file().writeText("{second")
        CoopPairs(file().toOkioPath()).all()
        val texts = file().parentFile!!.listFiles()!!.filter { it.name.startsWith("pairs.json.bad") }.map { it.readText() }.toSet()
        assertEquals(setOf("{first", "{second"), texts)
        assertFalse(file().exists())
    }
}
