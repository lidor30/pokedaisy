package com.pokedaisy.app.companion.coop

import java.io.File
import kotlin.random.Random
import okio.Path.Companion.toOkioPath
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Three devices: A is friends with B and C; B and C each only with A (they don't know each other). */
@OptIn(kotlinx.coroutines.DelicateCoroutinesApi::class)
class CoopFriendsTest {
    private val tmp: File = java.nio.file.Files.createTempDirectory("coop").toFile()
    private val jobs = mutableListOf<Job>()

    @org.junit.After fun cleanUp() {
        jobs.forEach { it.cancel() }
        tmp.deleteRecursively()
    }

    private val net = FakeNet()
    private var now = 1_000_000L

    private fun save(tid: Int, c: Int) = CoopSaveId(tid, tid * 11, byteArrayOf(c.toByte(), 0xFF.toByte(), 0, 0, 0, 0, 0, 0))
    private val saveA = save(1, 0xBB)
    private val saveB = save(2, 0xBC)
    private val saveC = save(3, 0xBD)
    private val tickA = ticketOf(0xA1)
    private val tickB = ticketOf(0xB1)
    private val tickC = ticketOf(0xC1)
    private val tA = net.node(0xA1, tickA)
    private val tB = net.node(0xB1, tickB)
    private val tC = net.node(0xC1, tickC)

    private fun hello(save: CoopSaveId, ticket: String) = CoopHello(1, "1.3.1", EMERALD_COOP_SHA1, save, false, ticket)
    private fun pairOf(own: CoopSaveId, peer: CoopPeerId, other: CoopSaveId, ticket: String?) =
        CoopPair(own, "Emerald", CoopPartner(peer, "X", other.trainerId, false, other), ticket, 1L)

    private val pairsA = CoopPairs(File(tmp, "a/pairs.json").toOkioPath()).apply {
        add(pairOf(saveA, tB.id, saveB, tickB))
        add(pairOf(saveA, tC.id, saveC, tickC))
    }
    private val pairsB = CoopPairs(File(tmp, "b/pairs.json").toOkioPath()).apply { add(pairOf(saveB, tA.id, saveA, tickA)) }
    private val pairsC = CoopPairs(File(tmp, "c/pairs.json").toOkioPath()).apply { add(pairOf(saveC, tA.id, saveA, tickA)) }

    private fun friends(t: FakeCoopTransport, pairs: CoopPairs, save: CoopSaveId, ticket: String) =
        CoopFriends(t, pairs, { hello(save, ticket) }, { now }, backoffs = { CoopBackoff(Random(1)) },
            scope = CoroutineScope(Dispatchers.Unconfined)).also { f -> t.sink = { f.onEvent(it) } }

    private val fA = friends(tA, pairsA, saveA, tickA)
    private val fB = friends(tB, pairsB, saveB, tickB)
    private val fC = friends(tC, pairsC, saveC, tickC)

    private fun <T> collect(flow: Flow<T>): MutableList<T> {
        val out = mutableListOf<T>()
        jobs += GlobalScope.launch(Dispatchers.Unconfined, CoroutineStart.UNDISPATCHED) { flow.collect { out += it } }
        return out
    }

    private fun all() {
        fB.startFor(saveB)
        fC.startFor(saveC)
        fA.startFor(saveA)
        net.pump()
    }

    private fun connected(f: CoopFriends) = f.friends.value.filter { it.connected }.map { it.peer }.toSet()

    @Test fun everyFriendConnectsAtOnce() {
        all()
        assertEquals(setOf(tB.id, tC.id), connected(fA))
        assertEquals(setOf(tA.id), connected(fB))
        assertEquals(setOf(tA.id), connected(fC))
        // A allows exactly its two friends; B and C don't know each other.
        assertEquals(setOf(tB.id, tC.id), tA.allowed)
        assertEquals(setOf(tA.id), tB.allowed)
    }

    @Test fun messagesCarryTheirSenderAndBroadcastReachesEveryone() {
        val atA = collect(fA.messages)
        val atB = collect(fB.messages)
        val atC = collect(fC.messages)
        all()
        assertTrue(fB.send(tA.id, 0x0100, byteArrayOf(1)))
        assertTrue(fC.send(tA.id, 0x0100, byteArrayOf(2)))
        net.pump()
        assertEquals(listOf(tB.id to 1, tC.id to 2), atA.map { it.peer to it.message.payload[0].toInt() })
        assertEquals(2, fA.broadcast(0x0100, byteArrayOf(9)))
        net.pump()
        assertEquals(listOf(tA.id), atB.map { it.peer })
        assertEquals(listOf(tA.id), atC.map { it.peer })
        // Not a friend: nothing goes.
        assertFalse(fB.send(tC.id, 0x0100, byteArrayOf(3)))
    }

    @Test fun aFriendAddedMidSessionGetsALink() {
        val a2 = CoopPairs(File(tmp, "a2/pairs.json").toOkioPath()).apply { add(pairOf(saveA, tB.id, saveB, tickB)) }
        val f = friends(tA, a2, saveA, tickA)
        fB.startFor(saveB)
        fC.startFor(saveC)
        f.startFor(saveA)
        net.pump()
        assertEquals(setOf(tB.id), connected(f))
        a2.add(pairOf(saveA, tC.id, saveC, tickC))   // paired meanwhile (the invite page)
        f.tick()
        net.pump()
        assertEquals(setOf(tB.id, tC.id), connected(f))
        assertEquals(setOf(tB.id, tC.id), tA.allowed)
    }

    @Test fun aFriendRemovedMidSessionHearsBye() {
        all()
        pairsA.remove(saveA, tC.id)
        fA.tick()
        net.pump()
        assertEquals(listOf(tB.id), fA.friends.value.map { it.peer })
        assertTrue(tA.sent.any { it.first == tC.id && it.second.type == CoopWire.BYE })
        assertTrue(fC.friends.value.single().state is CoopState.Disconnected)
        assertEquals(setOf(tA.id), connected(fB))
        assertEquals(setOf(tB.id), tA.allowed)
    }

    @Test fun aDialFailureOnlyDropsItsOwnFriend() {
        // Friends whose devices are nowhere to be found: both links wait in Connecting.
        val gone = CoopPairs(File(tmp, "g/pairs.json").toOkioPath()).apply {
            add(pairOf(saveA, CoopPeerId.of(ByteArray(32) { 0xD1.toByte() }), saveB, ticketOf(0xD1)))
            add(pairOf(saveA, CoopPeerId.of(ByteArray(32) { 0xD2.toByte() }), saveC, ticketOf(0xD2)))
        }
        val f = friends(tA, gone, saveA, tickA)
        f.startFor(saveA)
        net.pump()
        assertTrue(f.friends.value.all { it.state is CoopState.Connecting })
        f.onEvent(CoopEvent.DialFailed(6, "no connection", CoopPeerId.of(ByteArray(32) { 0xD2.toByte() })))
        val states = f.friends.value.map { it.state }
        assertTrue(states[0] is CoopState.Connecting)
        assertTrue(states[1] is CoopState.Disconnected)
    }

    @Test fun stopSaysByeToEveryoneAndAllowsNoOne() {
        all()
        fA.stop()
        net.pump()
        assertTrue(fA.friends.value.isEmpty())
        assertTrue(tA.sent.any { it.first == tB.id && it.second.type == CoopWire.BYE })
        assertTrue(tA.sent.any { it.first == tC.id && it.second.type == CoopWire.BYE })
        assertEquals(emptySet<CoopPeerId>(), tA.allowed)
        assertTrue(fB.friends.value.single().state is CoopState.Disconnected)
    }

    @Test fun aSaveWithoutFriendsHasNone() {
        val f = friends(tA, CoopPairs(File(tmp, "none/pairs.json").toOkioPath()), saveA, tickA)
        f.startFor(saveA)
        assertTrue(f.friends.value.isEmpty())
        assertNull(f.link(tB.id))
        assertEquals(0, f.broadcast(0x0100, byteArrayOf(1)))
    }

    @Test fun aStrangersConnectionIsLeftAlone() {
        // A pending (pairing) connection of someone else: the friends' links never close it (CoopPairing owns it).
        all()
        val stranger = CoopPeerId.of(ByteArray(32) { 0x5E })
        fA.onEvent(CoopEvent.Connected(stranger, pending = true, outgoing = false, direct = true))
        assertFalse(stranger in tA.disconnected)
    }
}
