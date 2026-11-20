package com.pokedaisy.app.companion.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The BATTLE tab shows only for a battle that read something ([showsBattle]). */
class BattleTabTest {
    private val mon = MonView(25, "Pikachu", 5, 19, 19, "", listOf("Electric"), null)

    @Test fun anEmptyBattleHasNoTab() {
        // In battle, but no battler, foe party or input state read (battle memory not mapped).
        assertFalse(SnapshotView(connected = true, inBattle = true).showsBattle)
    }

    @Test fun anyBattleDataShowsTheTab() {
        assertTrue(SnapshotView(connected = true, inBattle = true, battleOpponent = listOf(mon)).showsBattle)
        assertTrue(SnapshotView(connected = true, inBattle = true, battlePlayer = listOf(mon)).showsBattle)
        assertTrue(SnapshotView(connected = true, inBattle = true, battleInputState = BATTLE_INPUT_BUSY).showsBattle)
        assertFalse(SnapshotView(connected = true, inBattle = false, battlePlayer = listOf(mon)).showsBattle)
    }

    @Test fun seaglassBattleNowReadsData() {
        activeGame = GameKind.EMERALD_SEAGLASS
        val t = readNativeTelemetry(FixtureMemoryReader.load("emerald_seaglass_battle"), NATIVE_EMERALD_SEAGLASS)
        assertTrue(buildSnapshotView(t).showsBattle)
    }
}
