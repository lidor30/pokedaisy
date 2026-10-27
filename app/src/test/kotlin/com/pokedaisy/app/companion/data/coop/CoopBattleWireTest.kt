package com.pokedaisy.app.companion.data.coop

import com.pokedaisy.app.companion.data.FixtureMemoryReader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class CoopBattleWireTest {

    private val mon: ByteArray = FixtureMemoryReader.load("emerald_vanilla")
        .readCoreMemory(0x02000000L + 0x244EC, CoopBattle.MON_SIZE)

    private val id = 0x1234ABCD
    private val name = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 0xFF.toByte())

    private fun team(n: Int) = ByteArray(n * CoopBattle.MON_SIZE).also { for (i in 0 until n) mon.copyInto(it, i * CoopBattle.MON_SIZE) }
    private fun answer(n: Int = 3, slots: IntArray = IntArray(n) { it }) = CoopBattleMsg.Answer(id, true, team(n), n, name, 1, 0x55AA, slots)

    private fun le(vararg parts: Any): ByteArray {
        val b = ByteBuffer.allocate(70_000).order(ByteOrder.LITTLE_ENDIAN)
        for (p in parts) when (p) {
            is Int -> b.putInt(p)
            is Short -> b.putShort(p)
            is Byte -> b.put(p)
            is ByteArray -> b.put(p)
        }
        return b.array().copyOf(b.position())
    }

    private val samples: List<CoopBattleMsg> = listOf(
        CoopBattleMsg.Status(free = true, ready = false),
        CoopBattleMsg.Status(free = false, ready = true, story = 166),
        CoopBattleMsg.Offer(id, 4, 265, 0, 0),
        CoopBattleMsg.Offer(id, 2, 5, 854, 300),
        CoopBattleMsg.Offer(id, 1, 0, 0, 12),
        CoopBattleMsg.Offer(id, 3, 333, 0, 25),
        answer(),
        CoopBattleMsg.Answer(id, false),
        CoopBattleMsg.Cancel(id, CoopCancel.BUSY),
        CoopBattleMsg.ResultAck(id),
        CoopBattleMsg.LinkBlock(id, 7, byteArrayOf(3, 7, 2, 0, 9, 9)),
    )

    @Test fun everyMessageRoundTrips() {
        for (m in samples) {
            val (t, p) = CoopBattleWire.encode(m)
            assertEquals(m.toString(), m, CoopBattleWire.decode(t, p))
        }
        assertEquals(6, samples.map { CoopBattleWire.encode(it).first }.toSet().size)
    }

    @Test fun wrongLengthIsNull() {
        for (m in samples) {
            val (t, p) = CoopBattleWire.encode(m)
            assertNull("$m short", CoopBattleWire.decode(t, p.copyOf(p.size - 1)))
            assertNull("$m long", CoopBattleWire.decode(t, p + 0))
        }
    }

    @Test fun unknownTypeIsNull() {
        for (type in 0x0204..0x020E) assertNull("the removed replica message $type", CoopBattleWire.decode(type, le(id, 0, 0, 0)))
        assertNull(CoopBattleWire.decode(0x0210, le(id)))
        assertNull(CoopBattleWire.decode(0x01FF, le(id)))
        assertNull(CoopBattleWire.decode(0x0202, ByteArray(0)))
    }

    @Test fun answerChecksEveryMon() {
        val bad = team(3)
        bad[CoopBattle.MON_SIZE + 40] = (bad[CoopBattle.MON_SIZE + 40].toInt() xor 1).toByte()
        val (t, p) = CoopBattleWire.encode(answer())
        val off = 4 + 1 + 1
        bad.copyInto(p, off)
        assertNull(CoopBattleWire.decode(t, p))
        // count 0 / 4
        assertNull(CoopBattleWire.decode(t, le(id, 1.toByte(), 0.toByte())))
        assertNull(CoopBattleWire.decode(t, le(id, 1.toByte(), 4.toByte(), team(4), name, 0.toByte(), 0, byteArrayOf(0, 1, 2, 3))))
        assertNull("slot 6", CoopBattleWire.decode(t, CoopBattleWire.encode(answer(2)).second.also { it[it.size - 1] = 6 }))
        assertNull("repeat", CoopBattleWire.decode(t, CoopBattleWire.encode(answer(2)).second.also { it[it.size - 1] = 0 }))
        assertNull("accept 2", CoopBattleWire.decode(t, le(id, 2.toByte())))
    }

    @Test fun offerKindAndTrainers() {
        fun offer(kind: Int, a: Int, b: Int) = CoopBattleWire.decode(CoopBattleWire.OFFER, le(id, kind.toByte(), a.toShort(), b.toShort(), 1.toShort()) + ByteArray(8) { 0xFF.toByte() } + ByteArray(5))
        assertNotNull(offer(2, 854, 1))
        assertNull(offer(0, 0, 0))
        assertNotNull(offer(3, 333, 0))
        assertNotNull(offer(3, 854, 0))
        assertNull("one trainer needs its id", offer(3, 0, 0))
        assertNull("one trainer has no second id", offer(3, 333, 1))
        assertNull(offer(3, 855, 0))
        assertNotNull("story trainer", offer(4, 265, 0))
        assertNotNull("story double", offer(4, 265, 266))
        assertNull("story needs its trainer", offer(4, 0, 0))
        assertNull(offer(5, 1, 0))
        assertNull(offer(2, 855, 1))
        assertNull(offer(2, 1, 855))
        assertNull(offer(1, 1, 0))
        assertNull(offer(1, 0, 1))
    }

    @Test fun zeroBattleIdOnlyForStatus() {
        assertNotNull(CoopBattleWire.decode(CoopBattleWire.STATUS, le(0, 3.toByte(), 166.toShort())))
        assertNull("story out of range", CoopBattleWire.decode(CoopBattleWire.STATUS, le(0, 3.toByte(), 0x400.toShort())))
        assertNull("old 1-byte status", CoopBattleWire.decode(CoopBattleWire.STATUS, le(0, 3.toByte())))
        assertNull(CoopBattleWire.decode(CoopBattleWire.STATUS, le(id, 3.toByte(), 0.toShort())))
        for (m in samples.drop(2)) {
            val (t, p) = CoopBattleWire.encode(m)
            p[0] = 0; p[1] = 0; p[2] = 0; p[3] = 0
            assertNull("$m", CoopBattleWire.decode(t, p))
        }
    }
    private fun offerWithName(n: ByteArray) = CoopBattleWire.decode(
        CoopBattleWire.OFFER, le(id, 2.toByte(), 333.toShort(), 318.toShort(), 1.toShort()) + n + ByteArray(5),
    )

    @Test fun networkNamesNeedATerminatorAndTheGamesCharacters() {
        val ok = byteArrayOf(1, 2, 3, 0xFF.toByte(), 0xFF.toByte(), 0, 0, 0)
        assertNotNull(offerWithName(ok))
        assertNotNull("an empty name is a name", offerWithName(ByteArray(8) { 0xFF.toByte() }))
        assertNotNull("seven letters then the terminator", offerWithName(byteArrayOf(1, 2, 3, 4, 5, 6, 7, 0xFF.toByte())))
        assertNull("no terminator in 8 bytes", offerWithName(byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8)))
        assertNull("a control code before the terminator", offerWithName(byteArrayOf(1, 0xFA.toByte(), 3, 0xFF.toByte(), 0, 0, 0, 0)))
        assertNull("a placeholder code", offerWithName(byteArrayOf(0xFD.toByte(), 2, 0xFF.toByte(), 0, 0, 0, 0, 0)))
        assertFalse(CoopBattleWire.validName(ByteArray(7) { 1 }))
    }


    @Test fun newMessagesRoundTripAndAreChecked() {
        for (m in listOf(CoopBattleMsg.LinkReady(id), CoopBattleMsg.StoryState(id, 3), CoopBattleMsg.StoryState(id, CoopBattleWire.STORY_GO), CoopBattleMsg.CaughtMon(id, 1), CoopBattleMsg.CaughtMon(id, 2, team(1)))) {
            val (t, p) = CoopBattleWire.encode(m)
            assertEquals(m, CoopBattleWire.decode(t, p))
        }
        assertNull(CoopBattleWire.decode(CoopBattleWire.STORY_STATE, le(id, 7.toByte())))
        assertNull("a caught Pokemon that fails the check", CoopBattleWire.decode(CoopBattleWire.CAUGHT_MON, le(id, 2.toByte()) + ByteArray(100)))
    }

    @Test fun answerNamesAreCheckedToo() {
        val (type, payload) = CoopBattleWire.encode(CoopBattleMsg.Answer(id, true, team(1), 1, name, 0, 1, intArrayOf(0)))
        assertNotNull(CoopBattleWire.decode(type, payload))
        val p = payload.copyOf()
        for (i in 106 until 114) p[i] = 1                       // id 4, accept 1, count 1, one Pokemon 100: the name follows
        assertNull(CoopBattleWire.decode(type, p))
    }
}


class CoopLinkBlockWireTest {
    private fun block(kind: Int, seq: Int, payload: ByteArray, size: Int = payload.size) =
        byteArrayOf(kind.toByte(), seq.toByte(), (size and 0xFF).toByte(), (size shr 8).toByte()) + payload
    private fun payloadOf(b: ByteArray) = byteArrayOf(0x44, 0, 0, 1) + b

    @Test fun sizeFieldMustMatchPayloadLength() {
        assertNotNull(CoopBattleWire.decode(CoopBattleWire.LINK_BLOCK, payloadOf(block(1, 2, ByteArray(10)))))
        assertNull(CoopBattleWire.decode(CoopBattleWire.LINK_BLOCK, payloadOf(block(1, 2, ByteArray(10), size = 9))))
        assertNull(CoopBattleWire.decode(CoopBattleWire.LINK_BLOCK, payloadOf(block(1, 2, ByteArray(10), size = 11))))
        assertNull(CoopBattleWire.decode(CoopBattleWire.LINK_BLOCK, payloadOf(byteArrayOf(1, 2, 0))))
    }

    @Test fun payloadOverTheCapIsRefused() {
        val big = CoopBattleWire.MAX_LINK_PAYLOAD
        assertNotNull(CoopBattleWire.decode(CoopBattleWire.LINK_BLOCK, payloadOf(block(1, 2, ByteArray(big)))))
        assertNull(CoopBattleWire.decode(CoopBattleWire.LINK_BLOCK, payloadOf(block(1, 2, ByteArray(big + 1)))))
    }

    @Test fun seqComesFromTheBlock() {
        val m = CoopBattleWire.decode(CoopBattleWire.LINK_BLOCK, payloadOf(block(1, 200, ByteArray(3)))) as CoopBattleMsg.LinkBlock
        assertEquals(200, m.seq)
    }
}
