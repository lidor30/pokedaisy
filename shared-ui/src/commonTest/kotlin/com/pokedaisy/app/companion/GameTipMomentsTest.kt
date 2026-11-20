package com.pokedaisy.app.companion

import com.pokedaisy.app.GbaControls
import com.pokedaisy.app.companion.data.GameField
import com.pokedaisy.app.companion.data.SnapshotView
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class GameTipMomentsTest {
    private val field = SnapshotView(connected = true)

    @Test fun runningWithBNeedsAFullWindowOfEligibleWalkingFrames() {
        val tips = scheduler(TipId.AUTO_RUN)
        val moments = GameTipMoments()
        val keys = GbaControls.Btn.B.bit or GbaControls.Btn.UP.bit
        repeat(3600) { moments.movement(keys, GameField.KNOWN, true, autoRun = true) }
        repeat(3600) { moments.movement(keys, GameField.KNOWN or GameField.WINDOW, true, autoRun = false) }
        repeat(3599) { moments.movement(keys, GameField.KNOWN, true, autoRun = false) }
        moments.observe(field, mapOpen = false, tips)
        assertNull(tips.next())
        moments.movement(keys, GameField.KNOWN, true, autoRun = false)
        moments.observe(field, mapOpen = false, tips)
        assertEquals(TipId.AUTO_RUN, tips.next())
    }

    @Test fun threeWildBattleExitsTriggerTheEncounterTip() {
        val tips = scheduler(TipId.WILD)
        val moments = GameTipMoments()
        repeat(2) {
            moments.observe(field.copy(inBattle = true), mapOpen = false, tips)
            moments.observe(field, mapOpen = false, tips)
        }
        assertNull(tips.next())
        moments.observe(field.copy(inBattle = true), mapOpen = false, tips)
        moments.observe(field, mapOpen = false, tips)
        assertEquals(TipId.WILD, tips.next())
    }

    @Test fun mapTipStartsAfterClosingTheMap() {
        val tips = scheduler(TipId.FLY)
        val moments = GameTipMoments()
        moments.observe(field, mapOpen = true, tips)
        assertNull(tips.next())
        moments.observe(field, mapOpen = false, tips)
        assertEquals(TipId.FLY, tips.next())
    }

    private fun scheduler(tip: TipId) = TipScheduler(object : TipStore {
        override var fieldMillis = TipScheduler.FIRST_TIP_AFTER
        override var lastClosedAt = -1L
        override var records = emptyMap<TipId, TipRecord>()
    }) { it == tip }.apply { tick(TipScheduler.SESSION_SETTLE, onField = true, calm = false) }
}
