package com.pokedaisy.app.companion

import com.pokedaisy.app.companion.data.*
import com.pokedaisy.app.game.GameSession
import org.junit.Assert.*
import org.junit.Test

class ClassicFlyActionTest {
    private class Game : GameCalls {
        private val zero = java.util.zip.CRC32().apply { update(ByteArray(32)) }.value
        val fly = FLY_FIRERED_REV1.copy(codeCrcs = List(7) { zero })
        val fns = FIELD_ITEMS_FIRERED_REV1.copy(codeCrcs = List(5) { zero }, fly = fly,
            party = FIELD_ITEMS_FIRERED_REV1.party!!.let { it.copy(codeCrcs = List(it.functions.size) { zero }) })
        val fields = FieldItemSupport(fns, emptySet(), canFly = true)
        var locked = false
        var warped = -1
        val events = mutableListOf<String>()
        override fun call(fn: Long, a0: Int, a1: Int, a2: Int, a3: Int): Long = when (fn) {
            fns.fieldControlsLocked -> if (locked) 1 else 0
            fly.flagGet, fly.setUpFieldMoveFly -> 1
            fly.getMapsecType -> if (a0 == fly.firstMapsec) 2 else 1
            fly.lockControls -> { locked = true; events += "lock"; 0 }
            fly.unlockControls -> { locked = false; events += "unlock"; 0 }
            fly.fadeScreen -> { events += "fade $a0"; 0 }
            fly.setFlyWarpDestination -> { warped = a0; events += "warp"; 0 }
            fns.party!!.getMonData -> when (a1) { 0 -> 0x123; 13 -> 19; else -> 0 }
            else -> 0
        }
        override fun read(addr: Long, len: Int) = when (addr) {
            fns.party!!.partyCount -> byteArrayOf(1)
            fly.destinations -> ByteArray(len) { 1 }
            else -> ByteArray(len)
        }
        override fun write(addr: Long, bytes: ByteArray) = true
    }
    private val game = Game()
    private var current: GameSession? = GameSession(game, { true }) { _, e -> throw e }
    private var battle = false
    private var handedOver = 0
    private val items = FieldItemUse({ game.fields }, { current }, { battle }) { it() }
    private fun action() = ClassicFlyAction(items, { game.fields }, { current }) { handedOver++ }
    private fun target() = FlyTargets(FlyResult.OK, listOf(game.fly.firstMapsec), 0, 0x123)

    @Test fun targetsAndWarpUseTheSameQueueAndWaitForTheFade() {
        var targets: FlyTargets? = null
        action().targets { targets = it }
        assertNull(targets)
        current!!.serviceField(true, true)
        assertEquals(FlyResult.OK, targets?.result)
        var result: FlyResult? = null
        action().fly(game.fly.firstMapsec, target()) { result = it }
        current!!.serviceField(true, true)
        assertEquals(FlyResult.OK, result)
        assertEquals(1, handedOver)
        assertEquals(listOf("lock", "fade 1"), game.events)
        repeat(FLY_FADE_FRAMES) { current!!.serviceField(true, true) }
        assertEquals(-1, game.warped)
        current!!.serviceField(true, true)
        assertEquals(game.fly.firstMapsec, game.warped)
    }

    @Test fun aSceneChangeDuringTheFadeNeverWarpsOnTopOfTheNewScene() {
        action().fly(game.fly.firstMapsec, target()) {}
        current!!.serviceField(true, true)
        current!!.observeScreens(false, dsMenu = true)
        repeat(FLY_FADE_FRAMES + 1) { current!!.serviceField(true, true) }
        assertEquals(-1, game.warped)
        assertEquals(listOf("lock", "fade 1"), game.events)
    }

    @Test fun closeCancelsTheDelayedWarpAndBattleRefusalsKeepTheirReason() {
        battle = true
        var targets: FlyTargets? = null
        var result: FlyResult? = null
        action().targets { targets = it }
        action().fly(game.fly.firstMapsec, target()) { result = it }
        current!!.serviceField(true, true)
        assertEquals(FlyResult.IN_BATTLE, targets?.result)
        assertEquals(FlyResult.IN_BATTLE, result)
        assertEquals(0, handedOver)
        battle = false
        action().fly(game.fly.firstMapsec, target()) {}
        current!!.serviceField(true, true)
        current!!.close()
        repeat(FLY_FADE_FRAMES + 1) { current!!.serviceField(true, true) }
        assertEquals(-1, game.warped)
    }

    @Test fun missingGamesFailAndScenicOnlyGamesDoNotAdvertiseClassicFly() {
        current = null
        var result: FlyResult? = null
        action().fly(game.fly.firstMapsec, target()) { result = it }
        assertEquals(FlyResult.FAILED, result)
        val scenic = game.fields.let { FieldItemSupport(it.fns.copy(classicFly = false), emptySet(), canFly = true) }
        assertFalse(ClassicFlyAction(items, { scenic }, { current }).canFly())
    }
}
