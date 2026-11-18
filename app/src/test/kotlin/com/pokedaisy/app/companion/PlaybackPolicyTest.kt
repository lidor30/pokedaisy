package com.pokedaisy.app.companion

import com.pokedaisy.app.companion.data.*
import com.pokedaisy.app.companion.PlaybackPolicy.Scene
import org.junit.Assert.assertEquals
import org.junit.Test

class PlaybackPolicyTest {
    private fun scene(battle: Int = BATTLE_INPUT_NONE, menu: Boolean = false, map: Boolean = false,
                      battling: Boolean = false, field: Int = 0) = PlaybackPolicy.scene(battle, menu, map, battling, field)
    @Test fun menusWinOverBattleTextAndRiding() {
        assertEquals(Scene.MENU, scene(BATTLE_INPUT_BAG_OPEN, field = GameField.TEXT))
        assertEquals(Scene.MENU, scene(BATTLE_INPUT_PARTY_OPEN))
        assertEquals(Scene.MENU, scene(BATTLE_INPUT_BUSY, map = true))
        assertEquals(Scene.MENU, scene(field = GameField.MENU or GameField.BATTLE))
    }
    @Test fun battleWinsOverTextAndRiding() {
        assertEquals(Scene.BATTLE, scene(BATTLE_INPUT_BUSY, field = GameField.TEXT))
        assertEquals(Scene.BATTLE, scene(battling = true))
        assertEquals(Scene.BATTLE, scene(field = GameField.BATTLE))
    }
    @Test fun ridingNeedsKnownAvatarAndFreeControls() {
        assertEquals(Scene.FIELD, scene(field = GameField.BIKE))
        assertEquals(Scene.FIELD, scene(field = GameField.AVATAR or GameField.BIKE or GameField.LOCKED))
        assertEquals(Scene.BIKE, scene(field = GameField.AVATAR or GameField.BIKE))
        assertEquals(Scene.SURF, scene(field = GameField.AVATAR or GameField.SURF or GameField.BIKE))
        assertEquals(Scene.TEXT, scene(field = GameField.TEXT or GameField.AVATAR or GameField.SURF))
    }
    @Test fun sameFallsBackToGameSpeedAndSceneSpeedsStayIndependent() {
        fun speed(scene: Scene) = PlaybackPolicy.sceneSpeed(scene, .5f, 2f, 0f, 3f, 4f, 1f)
        assertEquals(.5f, speed(Scene.FIELD))
        assertEquals(.5f, speed(Scene.TEXT))
        assertEquals(2f, speed(Scene.BATTLE))
        assertEquals(3f, speed(Scene.MENU))
        assertEquals(4f, speed(Scene.BIKE))
        assertEquals(1f, speed(Scene.SURF))
    }
    @Test fun smartCapsUncappedAndSceneSpeedsButKeepsSlowMotionAndNormal() {
        fun speed(rate: Float, smart: Boolean = true) = PlaybackPolicy.smartSpeed(rate, smart, BATTLE_INPUT_NONE, true, false)
        assertEquals(1f, speed(Float.POSITIVE_INFINITY))
        assertEquals(1f, speed(4f))
        assertEquals(.5f, speed(.5f))
        assertEquals(4f, speed(4f, false))
        assertEquals(4f, PlaybackPolicy.smartSpeed(4f, true, BATTLE_INPUT_BUSY, true, true))
    }
    @Test fun ffWinsOverSlowmoAndSlowmoWinsOverSceneSpeed() {
        assertEquals(Float.POSITIVE_INFINITY, PlaybackPolicy.requestedSpeed(true, 0f, true, 4f))
        assertEquals(3f, PlaybackPolicy.requestedSpeed(true, 3f, true, .25f))
        assertEquals(.5f, PlaybackPolicy.requestedSpeed(false, 3f, true, 4f))
        assertEquals(.25f, PlaybackPolicy.requestedSpeed(false, 3f, false, .25f))
    }

    @Test fun cycleWrapsAt4xAndLeavesBothSlowStepsForTheSpeedPicker() {
        assertEquals(listOf(1.5f, 2f, 3f, 4f, 1f, 1f, 1f, 1f),
            listOf(1f, 1.5f, 2f, 3f, 4f, .5f, .25f, 9f).map(PlaybackPolicy::cycleSpeed))
    }

}
