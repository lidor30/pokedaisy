package com.pokedaisy.app.companion.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The DS POKéMON switch's place on the party screen ([gen4SwitchSlot]): a battle laid out in RAM the way HeartGold keeps it. */
class Gen4SwitchSlotTest {
    private val b = GEN4_HEARTGOLD_US.battle!!
    private val ram = ByteArray(0x400000)
    private val r = object : MemoryReader {
        override fun readCoreMemory(addr: Long, size: Int) = ByteArray(size) { ram[(addr - 0x02000000L).toInt() + it] }
    }
    private fun put32(addr: Long, v: Long) { for (i in 0 until 4) ram[(addr - 0x02000000L).toInt() + i] = (v shr (8 * i)).toByte() }
    private fun put8(addr: Long, v: Int) { ram[(addr - 0x02000000L).toInt()] = v.toByte() }

    private val pids = listOf(0x11111111L, 0x22222222L, 0x33333333L, 0x44444444L)
    private val bs = 0x022C025CL
    private val ctx = 0x022C3328L

    private fun battle(order: List<Int>, type: Long = 0) {
        val fs = 0x022A023CL; val pm = 0x022A0384L; val child = 0x022A6B54L; val party = 0x02280000L
        put32(b.fieldSystemPtr, fs); put32(fs, pm); put32(pm + b.offChild, child)
        put32(child, 0x0221ABCDL); put32(b.battleTemplate, 0x0221ABCDL)   // the battle's ApplicationManager
        put32(child + b.offAppData, bs)
        put32(bs + b.offBattleType, type); put32(bs + b.offBattleCtx, ctx); put32(bs + b.offParties, party)
        put32(party + 4, pids.size.toLong())
        pids.forEachIndexed { i, p -> put32(party + 8 + i * 0xEC, p) }
        order.forEachIndexed { i, v -> put8(ctx + b.offPartyOrder + i, v) }
    }

    @Test fun eachPokemonWhereTheBattleShowsIt() {
        battle(listOf(0, 1, 2, 3, 4, 5))
        assertEquals(listOf(null, 1, 2, 3), pids.map { gen4SwitchSlot(r, b, it) })
        // After a SHIFT to the second: it's out (first), the old lead in its place.
        battle(listOf(1, 0, 2, 3, 4, 5))
        assertEquals(listOf(1, null, 2, 3), pids.map { gen4SwitchSlot(r, b, it) })
        assertNull(gen4SwitchSlot(r, b, 0x55555555L))
    }

    @Test fun notInADouble() {
        battle(listOf(0, 1, 2, 3, 4, 5), type = 0x2)
        assertNull(gen4SwitchSlot(r, b, pids[2]))
    }
}
