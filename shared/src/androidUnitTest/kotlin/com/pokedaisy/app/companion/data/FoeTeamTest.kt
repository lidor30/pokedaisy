package com.pokedaisy.app.companion.data

import org.junit.Assert.assertEquals
import org.junit.Test

/** SnapshotView.enemyNext: only the trainer's pick once the foe that's out has fainted. */
class FoeTeamTest {
    private fun mon(species: Int, hp: Int) = Mon(species, 10, hp, 30, 0, IntArray(4), IntArray(4))
    private val noBattler = BattleMon(0, 0, 0, 0, 0, 0, 0L, IntArray(4), IntArray(4))

    private fun snapshot(active: Int, next: Int, activeHp: Int, nextHp: Int = 30): SnapshotView {
        activeGame = GameKind.FIRERED
        val party = listOf(mon(74, if (active == 0) activeHp else 30), mon(95, if (active == 1) activeHp else nextHp), mon(111, nextHp))
        return buildSnapshotView(
            Telemetry(
                frameCounter = 0, inBattle = true, isDoubleBattle = false, mapGroup = 0, mapNum = 0,
                regionMapSectionId = 0, x = 0, y = 0, facingDirection = 0, partyCount = 0, party = emptyList(),
                battleMons = List(4) { noBattler }, itemCount = 0, items = emptyList(),
                enemyParty = party, enemyActive = active, enemyNext = next,
            ),
        )
    }

    @Test fun announcedAfterFaint() = assertEquals(2, snapshot(active = 1, next = 2, activeHp = 0).enemyNext)

    /** monToSwitchIntoId still holds an old pick while the foe that's out is fine. */
    @Test fun staleWhileActiveUp() = assertEquals(-1, snapshot(active = 1, next = 2, activeHp = 12).enemyNext)

    @Test fun staleSameAsActive() = assertEquals(-1, snapshot(active = 1, next = 1, activeHp = 0).enemyNext)

    @Test fun faintedPickIgnored() = assertEquals(-1, snapshot(active = 1, next = 2, activeHp = 0, nextHp = 0).enemyNext)

    /** RHYHORN (GROUND / ROCK) in reserve: 4x to WATER and GRASS, like the active foe's own matchups. */
    @Test fun matchupsForReserves() = assertEquals(
        listOf("Water", "Grass"),
        snapshot(active = 0, next = -1, activeHp = 30).enemyParty[2].weaknesses.filter { it.pct == 400 }.map { it.type },
    )
}
