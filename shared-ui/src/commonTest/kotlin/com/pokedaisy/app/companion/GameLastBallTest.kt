package com.pokedaisy.app.companion

import com.pokedaisy.app.Prefs
import com.pokedaisy.app.companion.data.ItemView
import com.pokedaisy.app.companion.data.POCKET_POKE_BALLS
import com.pokedaisy.app.companion.data.SnapshotView
import okio.Path.Companion.toPath
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class GameLastBallTest {
    private fun ball(id: Int, amount: Int) = ItemView(id, "Ball", amount, null, pocket = POCKET_POKE_BALLS)

    @Test
    fun remembersAnActualThrowAndIgnoresBagChangesAcrossAStateLoad() {
        val prefs = Prefs(PrefsCompanionSettingsTest.Store())
        val initial = SnapshotView(connected = true, inBattle = true, items = listOf(ball(3, 2), ball(4, 4)))
        val runtime = GameLastBall(prefs, { initial }, { 0 }, {}, { _, _, _ -> error("No verified throw") })
        try {
            runtime.start("test.gba".toPath(), "emerald")
            runtime.observe(initial, null)
            assertNull(prefs.lastBallFor("emerald"))

            val afterThrow = initial.copy(items = listOf(ball(3, 1), ball(4, 4)))
            runtime.observe(afterThrow, null)
            assertEquals(3, prefs.lastBallFor("emerald"))

            runtime.resetSample()
            runtime.observe(afterThrow.copy(items = listOf(ball(3, 1), ball(4, 2))), null)
            assertEquals(3, prefs.lastBallFor("emerald"))
            assertNull(runtime.current)
            runtime.throwBall()

            runtime.start("other.gba".toPath(), "firered")
            assertNull(prefs.lastBallFor("firered"))
        } finally {
            runtime.close()
        }
    }
}
