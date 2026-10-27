package com.pokedaisy.app.companion.coop

import com.pokedaisy.app.companion.coop.CoopPairing.CoopPairFail
import com.pokedaisy.app.companion.coop.CoopPairing.LanEntry
import com.pokedaisy.app.companion.coop.CoopPairing.Step
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CoopPairingTest {
    private val tmp: File = java.nio.file.Files.createTempDirectory("coop").toFile()

    @org.junit.After fun removeTmp() { tmp.deleteRecursively() }

    private val net = FakeNet()
    private var now = 5_000_000L

    private val saveA = CoopSaveId(1, 11, byteArrayOf(0xBB.toByte(), 0xFF.toByte(), 0, 0, 0, 0, 0, 0))
    private val saveB = CoopSaveId(2, 22, byteArrayOf(0xC7.toByte(), 0xBB.toByte(), 0xD3.toByte(), 0xFF.toByte(), 0, 0, 0, 0))
    private val tickA = ticketOf(0xA1)
    private val tickB = ticketOf(0xB1)
    private val tA = net.node(0xA1, tickA)
    private val tB = net.node(0xB2, tickB)
    private val pairsA = CoopPairs(File(tmp, "a/pairs.json"))
    private val pairsB = CoopPairs(File(tmp, "b/pairs.json"))

    private fun hello(save: CoopSaveId, ticket: String) = CoopHello(1, "1.3.1", EMERALD_COOP_SHA1, save, false, ticket)

    private fun pairing(t: FakeCoopTransport, pairs: CoopPairs, save: CoopSaveId, ticket: String) =
        CoopPairing(t, pairs, { hello(save, ticket) }, { now }).also { p -> t.sink = { p.onEvent(it) } }

    private val a = pairing(tA, pairsA, saveA, tickA)
    private val b = pairing(tB, pairsB, saveB, tickB)

    private fun toConfirm() {
        a.open(saveA)
        b.open(saveB)
        b.join(tickA)
        net.pump()
    }

    @Test fun bothAcceptPairs() {
        toConfirm()
        assertTrue(a.step.value is Step.Confirm)
        assertTrue(b.step.value is Step.Confirm)
        assertEquals(saveB, (a.step.value as Step.Confirm).partner.save)
        assertEquals("MAY", (a.step.value as Step.Confirm).partner.name)
        a.accept()
        net.pump()
        assertTrue(a.step.value is Step.WaitingForPartner)
        b.accept()
        net.pump()
        assertTrue(a.step.value is Step.Paired)
        assertTrue(b.step.value is Step.Paired)
        assertEquals(tB.id, pairsA.forSave(saveA)!!.partner.peer)
        assertEquals(tA.id, pairsB.forSave(saveB)!!.partner.peer)
        assertEquals(tickB, pairsA.forSave(saveA)!!.lastTicket)
        assertTrue(tB.id in tA.accepted)
        assertTrue(tA.id in tB.accepted)
    }

    @Test fun openAgainAfterAFailureStartsClean() {
        toConfirm()
        b.deny()
        net.pump()
        assertTrue(a.step.value is Step.Failed)
        a.open(saveA)
        assertTrue(a.step.value is Step.Inviting)
        b.open(saveB)
        assertTrue(b.step.value is Step.Inviting)
        b.join(tickA)
        net.pump()
        assertTrue(a.step.value is Step.Confirm)
        assertTrue(b.step.value is Step.Confirm)
    }

    @Test fun denyOnEitherSide() {
        toConfirm()
        b.deny()
        net.pump()
        assertEquals(Step.Failed(CoopPairFail.DENIED), a.step.value)
        assertEquals(Step.Failed(CoopPairFail.DENIED), b.step.value)
        assertTrue(pairsA.all().isEmpty())
        assertTrue(pairsB.all().isEmpty())
    }

    @Test fun denyAfterAcceptingStillFailsBoth() {
        toConfirm()
        a.accept()
        net.pump()
        b.deny()
        net.pump()
        assertEquals(Step.Failed(CoopPairFail.DENIED), a.step.value)
        assertTrue(pairsA.all().isEmpty())
    }

    @Test fun alreadyPairedSaveFailsBeforeShowingAnything() {
        val partner = CoopPartner(tB.id, "MAY", 2, false, saveB)
        pairsA.add(CoopPair(saveA, "Emerald", partner, null, 1L))
        a.open(saveA)
        assertEquals(Step.Failed(CoopPairFail.SAVE_ALREADY_PAIRED), a.step.value)
        assertTrue(tA.policies.none { it.pairingOpen })
    }

    @Test fun confirmTimesOut() {
        toConfirm()
        now += 59_999
        a.tick()
        assertTrue(a.step.value is Step.Confirm)
        now += 1
        a.tick()
        assertEquals(Step.Failed(CoopPairFail.TIMEOUT), a.step.value)
        assertTrue(tB.id in tA.disconnected)
    }

    @Test fun lanEntriesFromUserData() {
        a.open(saveA)
        val x = CoopPeerId.of(ByteArray(32) { 5 })
        val y = CoopPeerId.of(ByteArray(32) { 6 })
        a.onEvent(CoopEvent.LanPeer(x, "pokedaisy-coop/1;c7bbd3;4660"))
        a.onEvent(CoopEvent.LanPeer(y, "something-else"))
        assertEquals(listOf(LanEntry(x, "MAY")), (a.step.value as Step.Inviting).lan)
        a.onEvent(CoopEvent.LanGone(x))
        assertTrue((a.step.value as Step.Inviting).lan.isEmpty())
    }

    @Test fun openAnnouncesAndShowsTheTicket() {
        a.open(saveA)
        val policy = tA.policies.last()
        assertTrue(policy.pairingOpen)
        assertEquals("pokedaisy-coop/1;bbff000000000000;1", policy.lanUserData)
        assertEquals(tickA, (a.step.value as Step.Inviting).ticket)
    }

    @Test fun closeClosesThePolicy() {
        a.open(saveA)
        a.close()
        val p = tA.policies.last()
        assertFalse(p.pairingOpen)
        assertNull(p.lanUserData)
    }

    @Test fun badInviteFails() {
        b.open(saveB)
        b.join("not a ticket")
        assertEquals(Step.Failed(CoopPairFail.BAD_INVITE), b.step.value)
    }

    @Test fun differentRomFails() {
        val other = CoopPairing(tB, pairsB, { CoopHello(1, "1.3.1", "0".repeat(40), saveB, false, tickB) }, { now })
        tB.sink = { other.onEvent(it) }
        a.open(saveA)
        other.open(saveB)
        other.join(tickA)
        net.pump()
        assertTrue(a.step.value is Step.Inviting)   // the host drops the stray quietly
        assertTrue(other.step.value is Step.Failed)
    }

    @Test fun dialFailureIsNetwork() {
        b.open(saveB)
        b.join(tickA)   // A never opened: refused
        net.pump()
        assertEquals(Step.Failed(CoopPairFail.NETWORK), b.step.value)
    }

    private fun stray(idByte: Int) = net.node(idByte, ticketOf(0x50 + idByte))

    @Test fun aSilentStrayDoesNotBlockTheRealJoiner() {
        a.open(saveA)
        val c = stray(0xC1)
        c.setPolicy(emptySet(), false, null)
        c.connect(tickA)
        net.pump()
        assertTrue(a.step.value is Step.Inviting)
        b.open(saveB)
        b.join(tickA)
        net.pump()
        assertEquals(saveB, (a.step.value as Step.Confirm).partner.save)
        assertTrue(c.id in tA.disconnected)
    }

    @Test fun aSilentStrayTimesOutQuietly() {
        a.open(saveA)
        stray(0xC2).connect(tickA)
        net.pump()
        now += 10_000
        a.tick()
        assertTrue(a.step.value is Step.Inviting)
        b.open(saveB)
        b.join(tickA)
        net.pump()
        assertTrue(a.step.value is Step.Confirm)
    }

    @Test fun aStrayWithABadHelloDoesNotEndPairing() {
        a.open(saveA)
        val c = stray(0xC3)
        c.connect(tickA)
        net.pump()
        a.onEvent(CoopEvent.Frame(c.id, CoopWire.encode(CoopWire.HELLO, CoopHello(2, "1.3.1", EMERALD_COOP_SHA1, saveB, false, null).encode())))
        assertTrue(a.step.value is Step.Inviting)
        b.open(saveB)
        b.join(tickA)
        net.pump()
        assertTrue(a.step.value is Step.Confirm)
    }

    @Test fun aStrayDroppingBeforeHelloIsForgotten() {
        a.open(saveA)
        val c = stray(0xC4)
        c.connect(tickA)
        net.pump()
        c.disconnect(tA.id)
        net.pump()
        assertTrue(a.step.value is Step.Inviting)
    }

    @Test fun anAlreadyPairedPeerReconnectingIsIgnored() {
        val x = stray(0xC5)
        val other = CoopSaveId(9, 99, saveB.name)
        pairsA.add(CoopPair(other, "Emerald", CoopPartner(x.id, "MAY", 2, false, saveB), null, 1L))
        a.open(saveA)
        x.connect(tickA)
        net.pump()
        assertTrue(a.step.value is Step.Inviting)
        assertTrue(tA.sent.isEmpty())
    }

    @Test fun peerOnlyTicketBytes() {
        val key = ByteArray(32) { (it * 7 + 3).toByte() }
        val t = CoopPairing.peerOnlyTicket(CoopPeerId.of(key))
        assertTrue(CoopInvite.isTicket(t))
        assertEquals(4 + 56, t.length)
        val d = CoopTicket.decode(t)!!
        assertEquals(CoopPeerId.of(key), d.peer)
        assertNull(d.inviteSecret)
        assertTrue(d.addresses.isEmpty())
    }
}
