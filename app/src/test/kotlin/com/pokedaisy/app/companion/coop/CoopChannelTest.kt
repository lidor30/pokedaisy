package com.pokedaisy.app.companion.coop

import java.security.SecureRandom
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class CoopChannelTest {
    private class Log : CoopChannel.Listener {
        val events = mutableListOf<String>()
        val frames = mutableListOf<ByteArray>()
        var remote: CoopPeerId? = null
        val closes = mutableListOf<Pair<Int, Boolean>>()
        override fun opened(channel: CoopChannel, remote: CoopPeerId) { this.remote = remote; events += "opened" }
        override fun frame(channel: CoopChannel, bytes: ByteArray) { frames += bytes; events += "frame" }
        override fun closed(channel: CoopChannel, code: Int, beforeOpen: Boolean) { closes += code to beforeOpen; events += "closed" }
    }

    private class Rig(
        decide: (CoopPeerId) -> Int = { 0 },
        initPrologue: ByteArray = PROLOGUE,
        respPrologue: ByteArray = PROLOGUE,
        initTarget: ByteArray? = null,
    ) {
        var now = 1_000L
        val initSecret = secret()
        val respSecret = secret()
        val toResp = ArrayDeque<ByteArray>()
        val toInit = ArrayDeque<ByteArray>()
        val initLog = Log()
        val respLog = Log()
        val timings = CoopTimings()
        val init = CoopChannel(
            true, initSecret, initTarget ?: CoopKeys.publicKey(respSecret), initPrologue,
            { toResp.addLast(it); true }, { now }, timings, { 0 }, initLog,
        )
        val resp = CoopChannel(
            false, respSecret, null, respPrologue,
            { toInit.addLast(it); true }, { now }, timings, decide, respLog,
        )

        fun pump(tamper: ((ByteArray) -> ByteArray)? = null) {
            var guard = 0
            while ((toResp.isNotEmpty() || toInit.isNotEmpty()) && guard++ < 100) {
                while (toResp.isNotEmpty()) resp.receive(toResp.removeFirst().let { tamper?.invoke(it) ?: it })
                while (toInit.isNotEmpty()) init.receive(toInit.removeFirst())
            }
        }

        fun open() { init.begin(); pump() }
    }

    companion object {
        val PROLOGUE = CoopChannel.prologue(CoopChannel.PATH_TCP, null)
        fun secret() = ByteArray(32).also { SecureRandom().nextBytes(it) }
    }

    @Test fun prologueIsTheContractedBytes() {
        val room = "0123456789abcdef0123456789abcdef"
        assertArrayEquals("PokeDaisy co-op 1".toByteArray() + byteArrayOf(0) + room.toByteArray(), CoopChannel.prologue(CoopChannel.PATH_INVITE, room))
        assertArrayEquals("PokeDaisy co-op 1".toByteArray() + byteArrayOf(1) + room.toByteArray(), CoopChannel.prologue(CoopChannel.PATH_PAIR, room))
        assertArrayEquals("PokeDaisy co-op 1".toByteArray() + byteArrayOf(2), CoopChannel.prologue(CoopChannel.PATH_TCP, null))
    }

    @Test fun opensBothSidesAndCarriesFrames() {
        val r = Rig()
        r.open()
        assertTrue(r.init.isOpen && r.resp.isOpen)
        assertEquals(listOf("opened"), r.initLog.events)
        assertTrue(r.init.send(byteArrayOf(1, 2, 3)))
        assertTrue(r.resp.send(byteArrayOf(9)))
        r.pump()
        assertArrayEquals(byteArrayOf(1, 2, 3), r.respLog.frames.single())
        assertArrayEquals(byteArrayOf(9), r.initLog.frames.single())
        assertArrayEquals(r.init.handshakeHash, r.resp.handshakeHash)
        assertEquals(1_000L, r.init.openedAt)
    }

    @Test fun responderLearnsInitiatorKey() {
        val r = Rig()
        r.open()
        assertEquals(CoopPeerId.of(CoopKeys.publicKey(r.initSecret)), r.respLog.remote)
        assertEquals(CoopPeerId.of(CoopKeys.publicKey(r.respSecret)), r.initLog.remote)
        assertEquals(r.respLog.remote, r.resp.remote)
    }

    @Test fun refusalReachesInitiatorBeforeOpen() {
        val r = Rig(decide = { CoopFrames.NOT_ALLOWED })
        r.open()
        assertEquals(listOf(CoopFrames.NOT_ALLOWED to true), r.initLog.closes)
        assertFalse(r.resp.isOpen)
        assertFalse(r.respLog.events.contains("opened"))
        assertFalse(r.initLog.events.contains("opened"))
    }

    @Test fun versionMismatchRefusedWithVersion() {
        val rig = Rig()
        val noise = CoopNoise.initiator(rig.initSecret, CoopKeys.publicKey(rig.respSecret), PROLOGUE)
        rig.resp.receive(noise.writeMessage(byteArrayOf(0, 2)))
        val msg2 = rig.toInit.removeFirst()
        assertEquals(CoopFrames.VERSION, noise.readMessage(msg2)!![0].toInt())
        assertFalse(rig.resp.isOpen)
        assertEquals(CoopFrames.VERSION to true, rig.respLog.closes.single())
    }

    @Test fun wrongResponderKeyNeverOpens() {
        val r = Rig(initTarget = CoopKeys.publicKey(secret()))
        r.open()
        assertFalse(r.init.isOpen)
        assertFalse(r.resp.isOpen)
        assertFalse(r.respLog.events.contains("opened"))
        assertEquals(CoopFrames.ERROR to true, r.respLog.closes.single())
    }

    @Test fun prologueMismatchNeverOpens() {
        val a = CoopChannel.prologue(CoopChannel.PATH_PAIR, "0".repeat(32))
        val b = CoopChannel.prologue(CoopChannel.PATH_PAIR, "1".repeat(32))
        val r = Rig(initPrologue = a, respPrologue = b)
        r.open()
        assertFalse(r.init.isOpen || r.resp.isOpen)
        assertNull(r.init.handshakeHash)
    }

    @Test fun tamperedMessageClosesWithError() {
        val r = Rig()
        r.open()
        r.init.send(byteArrayOf(5))
        r.pump { it.copyOf().also { c -> c[c.size - 1] = (c[c.size - 1].toInt() xor 1).toByte() } }
        assertEquals(listOf(CoopFrames.ERROR to false), r.respLog.closes)
        assertTrue(r.respLog.frames.isEmpty())
        assertEquals(listOf(CoopFrames.ERROR to false), r.initLog.closes)
    }

    @Test fun replayedMessageClosesWithError() {
        val r = Rig()
        r.open()
        r.init.send(byteArrayOf(5))
        val wire = r.toResp.first().copyOf()
        r.pump()
        assertEquals(1, r.respLog.frames.size)
        r.resp.receive(wire)
        assertEquals(listOf(CoopFrames.ERROR to false), r.respLog.closes)
        assertEquals(1, r.respLog.frames.size)
    }

    @Test fun closeSendsReasonToPeer() {
        val r = Rig()
        r.open()
        r.init.close(CoopFrames.DENIED)
        r.pump()
        assertEquals(listOf(CoopFrames.DENIED to false), r.respLog.closes)
        assertTrue(r.initLog.closes.isEmpty())
        assertFalse(r.init.send(byteArrayOf(1)))
        r.init.tick()
        r.init.pipeClosed()
        assertTrue(r.initLog.closes.isEmpty())
    }

    @Test fun pingKeepsAliveAndSilenceTimesOut() {
        val r = Rig()
        r.open()
        repeat(8) { // 40 s of quiet, kept up by pings both ways
            r.now += 5_000
            r.init.tick()
            r.resp.tick()
            r.pump()
        }
        assertTrue(r.init.isOpen && r.resp.isOpen)
        assertTrue(r.initLog.closes.isEmpty() && r.respLog.closes.isEmpty())
        r.toResp.clear()
        r.toInit.clear()
        r.now += 20_000
        r.init.tick()
        assertEquals(listOf(CoopFrames.TIMEOUT to false), r.initLog.closes)
        assertFalse(r.init.isOpen)
    }

    @Test fun handshakeTimesOut() {
        val r = Rig()
        r.init.begin()
        r.now += 4_999
        r.init.tick()
        assertTrue(r.initLog.closes.isEmpty())
        r.now += 1
        r.init.tick()
        r.resp.tick()
        assertEquals(listOf(CoopFrames.TIMEOUT to true), r.initLog.closes)
        assertEquals(listOf(CoopFrames.TIMEOUT to true), r.respLog.closes)
    }

    @Test fun oversizeFrameRefusedOnSend() {
        val r = Rig()
        r.open()
        assertTrue(r.init.send(ByteArray(65_536) { 1 }))
        try { r.init.send(ByteArray(65_537)); fail() } catch (_: IllegalArgumentException) {}
        try { r.init.send(ByteArray(0)); fail() } catch (_: IllegalArgumentException) {}
        assertTrue(r.init.isOpen)
    }

    @Test fun oversizeFrameReceivedClosesOverflowAndUnknownKindError() {
        for ((body, code) in listOf(ByteArray(1 + 65_537) to CoopFrames.OVERFLOW, byteArrayOf(9) to CoopFrames.ERROR)) {
            val initSecret = secret()
            val respSecret = secret()
            val log = Log()
            val out = ArrayDeque<ByteArray>()
            val ch = CoopChannel(false, respSecret, null, PROLOGUE, { out.addLast(it); true }, { 0L }, CoopTimings(), { 0 }, log)
            val hand = CoopNoise.initiator(initSecret, CoopKeys.publicKey(respSecret), PROLOGUE)
            ch.receive(hand.writeMessage(byteArrayOf(0, 1)))
            hand.readMessage(out.removeFirst())
            val (send, receive) = hand.split()
            ch.receive(send.encrypt(body))
            assertEquals(listOf(code to false), log.closes)
            val told = receive.decrypt(out.removeFirst())!!
            assertEquals(CoopFrames.CLOSE, told[0].toInt())
            assertEquals(code, told[1].toInt())
        }
    }

    @Test fun pipeClosedReportsError() {
        val r = Rig()
        r.open()
        r.resp.pipeClosed()
        assertEquals(listOf(CoopFrames.ERROR to false), r.respLog.closes)
        val early = Rig()
        early.init.begin()
        early.init.pipeClosed()
        assertEquals(listOf(CoopFrames.ERROR to true), early.initLog.closes)
    }
    @Test fun decideRunsWithTheLockReleased() {
        lateinit var resp: CoopChannel
        var touched = false
        val r = Rig(decide = {
            // another thread needs the channel's lock: it would hang if decide ran under it
            val t = Thread { resp.isOpen; touched = true }
            t.start()
            t.join(2_000)
            if (touched) 0 else CoopFrames.ERROR
        })
        resp = r.resp
        r.open()
        assertTrue(touched)
        assertTrue(r.init.isOpen && r.resp.isOpen)
    }

    @Test fun decideThatThrowsRefusesWithError() {
        val r = Rig(decide = { throw IllegalStateException("boom") })
        r.open()
        assertEquals(listOf(CoopFrames.ERROR to true), r.initLog.closes)
        assertEquals(listOf(CoopFrames.ERROR to true), r.respLog.closes)
        assertFalse(r.resp.isOpen)
    }

    @Test fun initiatorIgnoresInputBeforeBeginAndSecondBeginIsANoOp() {
        val r = Rig()
        r.init.receive(ByteArray(80))
        assertTrue(r.initLog.closes.isEmpty())
        r.init.begin()
        r.init.begin()
        assertEquals(1, r.toResp.size)
        r.pump()
        assertTrue(r.init.isOpen && r.resp.isOpen)
    }

    @Test fun aThrowingListenerDoesNotStopTheRest() {
        val calls = mutableListOf<String>()
        val secretA = secret()
        val secretB = secret()
        val out = ArrayDeque<ByteArray>()
        val ch = CoopChannel(false, secretB, null, PROLOGUE, { out.addLast(it); true }, { 0L }, CoopTimings(), { 0 }, object : CoopChannel.Listener {
            override fun opened(channel: CoopChannel, remote: CoopPeerId) { calls += "opened"; throw IllegalStateException("x") }
            override fun frame(channel: CoopChannel, bytes: ByteArray) { calls += "frame" }
            override fun closed(channel: CoopChannel, code: Int, beforeOpen: Boolean) { calls += "closed" }
        })
        val hand = CoopNoise.initiator(secretA, CoopKeys.publicKey(secretB), PROLOGUE)
        ch.receive(hand.writeMessage(byteArrayOf(0, 1)))
        hand.readMessage(out.removeFirst())
        val (send, _) = hand.split()
        ch.receive(send.encrypt(byteArrayOf(0, 7)))
        assertEquals(listOf("opened", "frame"), calls)
    }
}
