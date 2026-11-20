package com.pokedaisy.app.companion

import com.pokedaisy.app.Prefs
import com.pokedaisy.app.companion.data.GameCalls
import com.pokedaisy.app.companion.data.GameKind
import com.pokedaisy.app.companion.data.ItemUseOutcome
import com.pokedaisy.app.companion.data.ItemUseResult
import com.pokedaisy.app.companion.data.ItemView
import com.pokedaisy.app.companion.data.SnapshotView
import com.pokedaisy.app.companion.data.activeGame
import com.pokedaisy.app.game.GameSession
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

class GameAutoRefillTest {
    private var previousGame = GameKind.EMERALD
    @BeforeTest fun rememberGame() { previousGame = activeGame; activeGame = GameKind.EMERALD }
    @AfterTest fun restoreGame() { activeGame = previousGame }

    @Test fun keepsTheActiveKindAndInvalidatesWorkQueuedBeforeAStateChange() {
        val calls = object : GameCalls {
            override fun call(fn: Long, a0: Int, a1: Int, a2: Int, a3: Int) = 0L
            override fun read(addr: Long, len: Int) = ByteArray(len)
            override fun write(addr: Long, bytes: ByteArray) = false
        }
        val session = GameSession(calls, { true }) { _, error -> throw error }
        val refill = GameAutoRefill(Prefs(PrefsCompanionSettingsTest.Store()), session)
        val bag = listOf(ItemView(86, "Repel", 3, null), ItemView(83, "Super Repel", 3, null))
        val snapshot = SnapshotView(connected = true, items = bag)
        val attempted = mutableListOf<Int>()
        val notices = mutableListOf<Int>()

        fun observe(steps: Int, snap: SnapshotView = snapshot) = refill.observe(snap, steps, prepare = { (id, _) ->
            { _, _ -> attempted += id; ItemUseOutcome(ItemUseResult.USED) }
        }, used = { notices += it })

        refill.set(true)
        observe(80)
        observe(10)
        observe(9)
        session.serviceField(true, true)
        assertEquals(listOf(86), attempted)

        observe(110) // The larger sum includes the previous Repel's remaining steps.
        observe(8)
        session.serviceField(true, true)
        assertEquals(listOf(86, 86), attempted)

        observe(1)
        refill.reset()
        observe(8, snapshot.copy(inBattle = true))
        session.serviceField(true, true)
        assertEquals(listOf(86, 86), attempted)
        assertEquals(attempted, notices)
    }
}
