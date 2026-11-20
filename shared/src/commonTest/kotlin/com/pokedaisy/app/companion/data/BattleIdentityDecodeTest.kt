package com.pokedaisy.app.companion.data

import kotlin.test.*

class BattleIdentityDecodeTest {
    @Test fun mappedLayoutsRetainTheUnsignedPersonalityAlreadyReadForShininess() {
        for (layout in listOf(VANILLA_BATTLE_MON, EXPANSION_BATTLE_MON, SOULGOLD_BATTLE_MON)) {
            val raw = ByteArray(layout.size)
            raw[layout.species] = 64
            val identity = 0xDEADBEEFL
            repeat(4) { raw[layout.personality + it] = (identity shr (8*it)).toByte() }
            assertEquals(identity, assertNotNull(decodeBattleMon(raw, 0, layout)).personality)
        }
        val layout = VANILLA_BATTLE_MON.copy(personality = -1, otId = -1)
        val raw = ByteArray(layout.size); raw[layout.species] = 64
        assertEquals(0L, assertNotNull(decodeBattleMon(raw, 0, layout)).personality)
    }

    private val firstStats = MonStats(List(6) { 1 }, List(6) { 0 }, 0, List(6) { 20 })
    private val secondStats = firstStats.copy(nature = 1)
    private val first = Mon(64, 36, 81, 81, 0, IntArray(4), IntArray(4), personality = 11, stats = firstStats)
    private val second = first.copy(personality = 12, stats = secondStats)
    private val empty = BattleMon(0, 0, 0, 0, 0, 0, 0, IntArray(4), IntArray(4))
    private fun snapshot(identity: Long): SnapshotView {
        val battler = empty.copy(species = 64, level = 36, hp = 81, maxHp = 81, personality = identity)
        return buildSnapshotView(Telemetry(frameCounter = 1, inBattle = true, isDoubleBattle = false,
            mapGroup = 0, mapNum = 0, regionMapSectionId = 0, x = 0, y = 0, facingDirection = 0,
            partyCount = 2, party = listOf(first, second), battleMons = listOf(battler, battler, empty, empty),
            itemCount = 0, items = emptyList()))
    }

    @Test fun snapshotsKeepIdentityAndFindTheCorrectPartyStatsForIdenticalBattlers() {
        val s = snapshot(12)
        assertEquals(12L, s.battlePlayer.single().personality)
        assertEquals(12L, s.battleOpponent.single().personality)
        assertEquals(secondStats, s.battlePlayer.single().stats)
    }

    @Test fun unknownIdentityKeepsLegacyMatchingButKnownUnmatchedIdentityDoesNotBorrowStats() {
        assertEquals(firstStats, snapshot(0).battlePlayer.single().stats)
        assertNull(snapshot(13).battlePlayer.single().stats)
    }
}
