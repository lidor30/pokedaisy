package com.pokedaisy.app.companion.data

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Black / White's battle input state ([readGen5BattleInput]) over memory laid out as White's headless captures had it
 * (its Route 1 wild battle: the GameSystem's proc, the battle's main module, client 0, its adapter, btlv_core, btlv_scd
 * and btlv_input at their heap addresses), each byte the read looks at set as it was at that moment.
 */
class Gen5BattleInputTest {
    private val mem = HashMap<Long, Byte>()
    private val reader = object : MemoryReader {
        override fun readCoreMemory(addr: Long, size: Int) = ByteArray(size) { mem[addr + it] ?: 0 }
    }

    private fun w32(a: Long, v: Long) { for (i in 0 until 4) mem[a + i] = (v ushr (8 * i)).toByte() }
    private fun w8(a: Long, v: Int) { mem[a] = v.toByte() }

    private val b = GEN5_WHITE.battle!!
    private val gs = 0x0224F76CL
    private val ps = 0x02250000L
    private val proc = 0x022594B8L
    private val main = 0x0226978CL
    private val client = 0x02272044L
    private val adapter = 0x02272228L
    private val core = 0x022961E4L
    private val scd = 0x022A4BACL
    private val input = 0x022D6ACCL

    private fun battle(command: Int, panel: Int, up: Int, sub: Int, slide: Long = 0) {
        w32(b.gameSystemPtr, gs); w32(gs + b.offProcSys, ps); w32(ps + b.offCurrentProc, proc)
        w32(proc + b.offProcOverlay, b.battleOverlay.toLong()); w32(proc + b.offProcWork, main)
        w32(main + b.offClient, client); w32(client + b.offAdapter, adapter); w8(adapter + b.offAdapterCommand, command)
        w32(client + b.offView, core); w32(core + b.offScd, scd); w32(scd + b.offInput, input)
        w8(input + b.offPanel, panel); w32(input + b.offSlide, slide); w8(scd + b.offWaiting, up); w8(scd + b.offSubscreen, sub)
    }

    private fun state() = readGen5BattleInput(reader, b)

    @Test
    fun theCommandsAndTheMovesOnceTheirPanelHasSlidIn() {
        battle(command = 3, panel = 1, up = 1, sub = 0)
        assertEquals(BATTLE_INPUT_ACTION_SELECT, state())
        battle(command = 3, panel = 2, up = 1, sub = 0)
        assertEquals(BATTLE_INPUT_MOVE_SELECT, state())
        // Still sliding (its task set): a tap wouldn't land.
        battle(command = 3, panel = 2, up = 1, sub = 0, slide = 0x022D6E84)
        assertEquals(BATTLE_INPUT_BUSY, state())
        // FIGHT's press: the panel down for a moment.
        battle(command = 3, panel = 1, up = 0, sub = 1)
        assertEquals(BATTLE_INPUT_BUSY, state())
    }

    @Test
    fun theBagAndThePartyOnlyWhileThePlayerChooses() {
        battle(command = 3, panel = 0, up = 0, sub = 2)
        assertEquals(BATTLE_INPUT_BAG_OPEN, state())
        battle(command = 3, panel = 0, up = 0, sub = 3)
        assertEquals(BATTLE_INPUT_PARTY_OPEN, state())
        // The turn playing: the screen the panel opened is left as it was.
        battle(command = 8, panel = 0, up = 0, sub = 3)
        assertEquals(BATTLE_INPUT_BUSY, state())
        // A fainted Pokémon's party.
        battle(command = 5, panel = 0, up = 0, sub = 0)
        assertEquals(BATTLE_INPUT_PARTY_OPEN, state())
        // "Use next Pokémon?": the touch screen's (BUSY for the buttons).
        battle(command = 7, panel = 4, up = 0, sub = 0)
        assertEquals(BATTLE_INPUT_BUSY, state())
    }

    @Test
    fun noBattleNoState() {
        battle(command = 3, panel = 1, up = 1, sub = 0)
        w32(proc + b.offProcOverlay, 21)
        assertEquals(BATTLE_INPUT_NONE, state())
        // A config without the touch screen's chain reads nothing.
        battle(command = 3, panel = 1, up = 1, sub = 0)
        assertEquals(BATTLE_INPUT_NONE, readGen5BattleInput(reader, b.copy(offView = 0)))
    }
}
