package com.pokedaisy.app.companion

import com.pokedaisy.app.Prefs
import com.pokedaisy.app.companion.data.MonView
import com.pokedaisy.app.companion.data.SnapshotView
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GameBattleRetryTest {
    private val field = SnapshotView(connected = true)
    private val mon = MonView(species = 25, name = "A", level = 5, hp = 10, maxHp = 10,
        status = "", types = emptyList(), iconAsset = null)
    private val trainer = field.copy(inBattle = true, isTrainerBattle = true, party = listOf(mon))
    private val lost = trainer.copy(party = listOf(mon.copy(hp = 0)))

    @Test
    fun offersOnceAfterALostTrainerBattleWithASavedStart() {
        val retry = GameBattleRetry(Prefs(PrefsCompanionSettingsTest.Store()))
        retry.set(true)
        var saves = 0
        val capture = { saves++; true }
        retry.observe(trainer, true, capture)
        retry.observe(trainer, true, capture)
        retry.observe(lost, true, capture)
        assertFalse(retry.asking)
        retry.observe(field, true, capture)
        assertEquals(1, saves)
        assertTrue(retry.asking)
        assertTrue(retry.answer(true))
        retry.observe(field, true, capture)
        assertFalse(retry.answer(true))
    }

    @Test
    fun failedOrInvalidatedCapturesAndWildBattlesNeverOfferAStaleRetry() {
        val retry = GameBattleRetry(Prefs(PrefsCompanionSettingsTest.Store()))
        retry.set(true)
        retry.observe(trainer, true) { false }
        retry.observe(lost, true) { error("Do not recapture a losing battle.") }
        retry.observe(field, true) { error("The loss has ended.") }
        assertFalse(retry.asking)
        retry.observe(trainer, true) { true }
        retry.observe(lost, true) { true }
        retry.reset()
        retry.observe(field, true) { error("State was replaced.") }
        assertFalse(retry.asking)
        retry.observe(lost.copy(isTrainerBattle = false), true) { error("Wild battle") }
        retry.observe(field, true) { error("Battle has ended.") }
        assertFalse(retry.asking)
    }
}
