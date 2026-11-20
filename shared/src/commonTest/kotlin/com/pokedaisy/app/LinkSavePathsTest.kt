package com.pokedaisy.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import okio.Path.Companion.toPath

class LinkSavePathsTest {
    @Test fun duplicateDestinationsNeverOverwriteAnotherCartridgesOwnBattery() {
        val originals = listOf("/saves/Game.srm", "/saves/Game.srm", "/saves/Game (link 2).srm", "/saves/Game.srm").map { it.toPath() }
        val assigned = LinkSavePaths.forGames(originals).map { it.toString() }
        assertEquals(listOf("/saves/Game.srm", "/saves/Game (link 3).srm", "/saves/Game (link 2).srm", "/saves/Game (link 4).srm"), assigned)
        assertEquals(listOf("/Game.sav", "/Game (link 2).sav"), LinkSavePaths.forGames(List(2) { "/Game.sav".toPath() }).map { it.toString() })
    }

    @Test fun recoveryFailureLeavesBatteriesUntouched() {
        val result = LinkSavePaths.prepare(
            listOf("Game"), recover = { false },
            saveFor = { error("Resolved a save before recovery") },
            prepareBattery = { error("Prepared a save before recovery") },
        )
        assertEquals(ClosedSaveEdits.recoveryError(), assertIs<LinkSavePaths.Preparation.Failed>(result).reason)
    }

    @Test fun everyAssignedBatteryIsPreparedButOnlyThePrimarySlotsOwnResumes() {
        val prepared = mutableListOf<String>()
        val result = LinkSavePaths.prepare(
            listOf("Game", "Game", "Game (link 2)"), recover = { true },
            saveFor = { "/saves/$it.sav".toPath() },
            prepareBattery = {
                prepared += it.name
                null
            },
        )
        val ready = assertIs<LinkSavePaths.Preparation.Ready>(result)
        assertEquals(listOf("Game.sav", "Game (link 3).sav", "Game (link 2).sav"), prepared)
        assertEquals(prepared, ready.paths.map { it.name })
        assertEquals(listOf(0, 2), ready.primarySlots)
    }

    @Test fun batteryPreparationCompletesAndReportsTheFirstFailure() {
        val prepared = mutableListOf<String>()
        val result = LinkSavePaths.prepare(
            listOf("First", "Second"), recover = { true }, saveFor = { "/$it.sav".toPath() },
            prepareBattery = {
                prepared += it.name
                it.name
            },
        )
        assertEquals(listOf("First.sav", "Second.sav"), prepared)
        assertEquals("First.sav", assertIs<LinkSavePaths.Preparation.Failed>(result).reason)
    }
}
