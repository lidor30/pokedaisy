package com.pokedaisy.app.companion

import com.pokedaisy.app.Prefs
import com.pokedaisy.app.companion.data.GameCalls
import com.pokedaisy.app.companion.data.ShinyOdds
import com.pokedaisy.app.companion.data.shinyValue
import com.pokedaisy.app.companion.data.save.Gen3BoxMon
import kotlin.test.*

class GameShinyOddsTest {
    private val target = ShinyOdds.Target(0x02002000, 0x02001000, 0x02000000, saveBlock2Static = true)
    private class Memory : GameCalls {
        val bytes = mutableMapOf<Long, Byte>()
        val writes = mutableListOf<Long>()
        var reads = 0
        override fun call(fn: Long, a0: Int, a1: Int, a2: Int, a3: Int): Long = error("No game calls")
        override fun read(addr: Long, len: Int): ByteArray { reads++; return ByteArray(len) { bytes[addr + it] ?: 0 } }
        override fun write(addr: Long, bytes: ByteArray): Boolean {
            writes += addr; put(addr, bytes); return true
        }
        fun put(addr: Long, data: ByteArray) { data.forEachIndexed { i, b -> bytes[addr + i] = b } }
    }
    private fun creature(): ByteArray {
        val empty = Gen3BoxMon.read(ByteArray(Gen3BoxMon.SIZE), 0)
        val fields = empty.withChanges(species = 25, heldItem = 100,
            moves = intArrayOf(1, 2, 3, 4), pp = intArrayOf(10, 20, 30, 40))
        return Gen3BoxMon.read(fields, 0).withPersonality(0x12345678)
    }

    @Test fun productionRejectsDebugOddsAndSelectionSurvivesReopening() {
        val prefs = Prefs(PrefsCompanionSettingsTest.Store()).also { it.shinyOdds = 1 }
        val memory = Memory()
        val owner = GameShinyOdds(prefs, memory, false, { true })
        assertEquals(ShinyOdds.NORMAL, owner.odds)
        assertFalse(1 in owner.choices)
        owner.setOdds(128); assertEquals(128, prefs.shinyOdds)
        owner.setOdds(1); assertEquals(128, owner.odds); assertEquals(128, prefs.shinyOdds)
        assertEquals(128, GameShinyOdds(prefs, memory, false, { true }).odds)
        owner.close(); owner.setOdds(512); assertEquals(128, prefs.shinyOdds)
        assertEquals(0, memory.reads); assertTrue(memory.writes.isEmpty())
    }

    @Test fun originalRollerChangesOnlyTheNewWildFoeAndNormalKeepsItShiny() {
        val prefs = Prefs(PrefsCompanionSettingsTest.Store())
        val memory = Memory()
        val original = creature()
        memory.put(target.enemyParty, original)
        val before = Gen3BoxMon.read(original, 0)
        assertTrue(before.checksumOk)
        assertTrue(shinyValue(before.personality, before.otId) >= 8)
        var notices = 0
        val owner = GameShinyOdds(prefs, memory, true, { true }) { notices++ }
        owner.observe(target); owner.setOdds(1)
        assertTrue(owner.supported); assertTrue(owner.active)
        assertEquals(0, owner.tick(), "A half-created foe must settle first")
        assertEquals(1, owner.tick())
        val after = Gen3BoxMon.read(memory.read(target.enemyParty, Gen3BoxMon.SIZE), 0)
        assertTrue(after.checksumOk)
        assertTrue(shinyValue(after.personality, after.otId) < 8)
        assertEquals(before.personality % 25, after.personality % 25)
        assertEquals(before.personality and 255, after.personality and 255)
        assertContentEquals(before.moves, after.moves); assertContentEquals(before.pp, after.pp)
        assertContentEquals(before.ivs, after.ivs); assertContentEquals(before.evs, after.evs)
        assertEquals(before.heldItem, after.heldItem); assertEquals(before.otId, after.otId)
        owner.observe(target); repeat(5) { assertEquals(0, owner.tick()) }
        assertEquals(listOf(target.enemyParty), memory.writes)
        owner.setOdds(ShinyOdds.NORMAL)
        val reads = memory.reads
        repeat(5) { assertEquals(0, owner.tick()) }
        assertEquals(reads, memory.reads, "Normal odds require no foe reads")
        assertTrue(notices >= 3, "Host achievements state follows target and setting changes")
    }

    @Test fun unsupportedBlockedAndRetiredOwnersNeverAccessTheCore() {
        val prefs = Prefs(PrefsCompanionSettingsTest.Store()).also { it.shinyOdds = 1 }
        val memory = Memory()
        var allowed = false
        val owner = GameShinyOdds(prefs, memory, true, { allowed })
        owner.observe(target); assertFalse(owner.supported); assertEquals(0, owner.tick())
        allowed = true; owner.observe(null); assertFalse(owner.supported); assertEquals(0, owner.tick())
        owner.observe(target); assertTrue(owner.active)
        allowed = false; assertFalse(owner.active); assertEquals(0, owner.tick())
        owner.close(); allowed = true; owner.observe(target)
        assertFalse(owner.supported); assertEquals(0, owner.tick())
        assertEquals(0, memory.reads); assertTrue(memory.writes.isEmpty())
    }
}
