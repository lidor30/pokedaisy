package com.pokedaisy.app.companion.data

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

/** [reorderMoves]: the party summary's move drag, through the game's SetMonData. */
class MoveOrderTest {
    /** One FireRed party Pokémon: its four moves, their PP and PP Up bits, behind the fixture's function addresses. */
    private class FakeGame(val fns: FieldItemFns = FIELD_ITEMS_FIRERED_REV1) : GameCalls {
        val p = fns.party!!
        var locked = false
        var failSetMonData = false
        var personality = 0x1111L
        val moves = intArrayOf(10, 20, 30, 0)
        val pp = intArrayOf(5, 6, 7, 0)
        /** 1, 2, 3, 0 for the four slots. */
        var bonuses = 1 or (2 shl 2) or (3 shl 4)
        private val ram = mutableMapOf<Long, Byte>()

        private fun field(id: Int) = if (id >= 11) id - p.monFieldShift else id

        override fun call(fn: Long, a0: Int, a1: Int, a2: Int, a3: Int): Long = when (fn) {
            fns.fieldControlsLocked -> if (locked) 1 else 0
            p.getMonData -> when (val f = field(a1)) {
                0 -> personality
                in 13..16 -> moves[f - 13].toLong()
                in 17..20 -> pp[f - 17].toLong()
                21 -> bonuses.toLong()
                else -> -1
            }
            p.setMonData -> if (failSetMonData) -1 else {
                val v = read(a2.toLong() and 0xFFFFFFFFL, 2).let { (it[0].toInt() and 0xFF) or ((it[1].toInt() and 0xFF) shl 8) }
                val f = field(a1)
                if (f in 13..16) { moves[f - 13] = v; 0 }
                else if (f in 17..20) { pp[f - 17] = v; 0 }
                else if (f == 21) { bonuses = v; 0 }
                else -1
            }
            else -> -1
        }

        override fun read(addr: Long, len: Int): ByteArray = when (addr) {
            p.partyCount -> byteArrayOf(1)
            else -> ByteArray(len) { if (addr + it in ram) ram[addr + it]!! else 0 }
        }

        override fun write(addr: Long, bytes: ByteArray): Boolean {
            if (addr != p.scratch) return false
            bytes.forEachIndexed { i, b -> ram[addr + i] = b }
            return true
        }
    }

    @Test
    fun `a move moves down and its PP and PP Ups go with it`() {
        val g = FakeGame()
        assertEquals(MoveOrderResult.REORDERED, reorderMoves(g.fns, 0, g.personality, from = 0, to = 2, busy = false, g = g))
        assertArrayEquals(intArrayOf(20, 30, 10, 0), g.moves)
        assertArrayEquals(intArrayOf(6, 7, 5, 0), g.pp)
        assertEquals(2 or (3 shl 2) or (1 shl 4), g.bonuses)
    }

    @Test
    fun `a move moves up`() {
        val g = FakeGame()
        assertEquals(MoveOrderResult.REORDERED, reorderMoves(g.fns, 0, g.personality, from = 2, to = 0, busy = false, g = g))
        assertArrayEquals(intArrayOf(30, 10, 20, 0), g.moves)
        assertArrayEquals(intArrayOf(7, 5, 6, 0), g.pp)
        assertEquals(3 or (1 shl 2) or (2 shl 4), g.bonuses)
    }

    @Test
    fun `empty slots are not drag targets`() {
        val g = FakeGame()
        assertEquals(MoveOrderResult.FAILED, reorderMoves(g.fns, 0, g.personality, from = 0, to = 3, busy = false, g = g))
        assertEquals(MoveOrderResult.FAILED, reorderMoves(g.fns, 0, g.personality, from = 3, to = 0, busy = false, g = g))
        assertArrayEquals(intArrayOf(10, 20, 30, 0), g.moves)
    }

    @Test
    fun `not now in a battle, a locked field, or for another Pokemon`() {
        val g = FakeGame()
        assertEquals(MoveOrderResult.NOT_NOW, reorderMoves(g.fns, 0, g.personality, 0, 1, busy = true, g = g))
        g.locked = true
        assertEquals(MoveOrderResult.NOT_NOW, reorderMoves(g.fns, 0, g.personality, 0, 1, busy = false, g = g))
        g.locked = false
        assertEquals(MoveOrderResult.CHANGED, reorderMoves(g.fns, 0, 0x9999L, 0, 1, busy = false, g = g))
        assertArrayEquals(intArrayOf(10, 20, 30, 0), g.moves)
    }

    @Test
    fun `a SetMonData that doesn't take leaves the moves as they were`() {
        val g = FakeGame()
        g.failSetMonData = true
        assertEquals(MoveOrderResult.FAILED, reorderMoves(g.fns, 0, g.personality, from = 0, to = 1, busy = false, g = g))
        assertArrayEquals(intArrayOf(10, 20, 30, 0), g.moves)
        assertArrayEquals(intArrayOf(5, 6, 7, 0), g.pp)
    }
}
