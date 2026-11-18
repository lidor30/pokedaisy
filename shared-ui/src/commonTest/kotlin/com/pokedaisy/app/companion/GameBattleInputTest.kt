package com.pokedaisy.app.companion

import com.pokedaisy.app.ScriptInput
import com.pokedaisy.app.companion.data.*
import kotlin.test.*

class GameBattleInputTest {
    private val reader = object : MemoryReader {
        override fun readCoreMemory(addr: Long, size: Int) = ByteArray(size)
    }

    @Test fun scriptedPicksOwnBothInputPathsAndCancellationRestoresManualKeys() {
        var bits = 0; var exclusive = false
        val battle = GameBattleInput(TelemetryStore(), null, 0,
            ScriptInput { b, e -> bits = b; exclusive = e }, reader)
        val held = CoreKey.START
        assertEquals(held, battle.keys(held))
        battle.controller.onState(0, BATTLE_INPUT_MOVE_SELECT)
        battle.selectMove(3)
        assertTrue(battle.busy)
        repeat(8) {
            val mixed = battle.keys(held)
            assertTrue(exclusive)
            assertEquals(bits, mixed)
            assertEquals(0, mixed and held)
        }
        battle.cancel()
        assertFalse(battle.busy)
        assertEquals(held, battle.keys(held))
        assertFalse(exclusive); assertEquals(0, bits)
    }

    @Test fun samplesUseTheDetectedMenuFamilyAndUnknownGamesCannotSwitch() {
        val telemetry = TelemetryStore()
        val battle = GameBattleInput(telemetry, null, 0, reader = reader, silent = { true })
        val mon = MonView(1, "Diagnostic", 5, 10, 10, "", emptyList(), null)
        val oldGame = activeGame; val oldCode = romGameCode; val oldLanguage = romLanguage
        val oldTables = DecompIconSource.tables
        try {
            telemetry.showCached(SnapshotView(connected = true, game = GameKind.YELLOW, party = listOf(mon)))
            battle.sampled(); assertTrue(battle.controller.gen1)
            assertFalse(battle.canSwitch); assertTrue(battle.gameSilent)
            telemetry.showCached(SnapshotView(connected = true, game = GameKind.EMERALD, party = listOf(mon)))
            battle.sampled(); assertFalse(battle.controller.gen1)
            assertFalse(battle.canSwitch)
        } finally {
            battle.cancel(); activeGame = oldGame; romGameCode = oldCode; romLanguage = oldLanguage
            DecompIconSource.tables = oldTables
        }
    }
}
