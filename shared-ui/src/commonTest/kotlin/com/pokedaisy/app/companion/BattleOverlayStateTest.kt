package com.pokedaisy.app.companion

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.unit.dp
import com.pokedaisy.app.Prefs
import com.pokedaisy.app.companion.ui.battleButtonsColumnWidth
import com.pokedaisy.app.companion.data.*
import kotlin.test.*

class BattleOverlayStateTest {
    @Test fun readinessSurvivesQuietReadsButNeverTheEndOfBattle() {
        val state = BattleOverlayState()
        state.observe(true, BATTLE_INPUT_NONE); assertFalse(state.read)
        state.observe(true, BATTLE_INPUT_ACTION_SELECT); assertTrue(state.read)
        state.observe(true, BATTLE_INPUT_NONE); assertTrue(state.read)
        state.observe(false, BATTLE_INPUT_ACTION_SELECT); assertFalse(state.read)
        state.observe(true, BATTLE_INPUT_NONE); assertFalse(state.read)
        state.observe(true, BATTLE_INPUT_MOVE_SELECT); assertTrue(state.read)
    }

    @Test fun onlySupportedSingleBattlesOnLandscapeSingleScreensAreEligible() {
        val battle = SnapshotView(connected = true, game = GameKind.EMERALD, inBattle = true,
            location = LocationView("Test", null, 0, 0, 0, 0))
        assertTrue(battleButtonsWanted(true, true, false, battle))
        assertFalse(battleButtonsWanted(false, true, false, battle))
        assertFalse(battleButtonsWanted(true, false, false, battle))
        assertFalse(battleButtonsWanted(true, true, true, battle))
        for (snapshot in listOf(battle.copy(inBattle = false), battle.copy(connected = false),
            battle.copy(unsupported = true), battle.copy(isDoubleBattle = true), battle.copy(game = null))) {
            assertFalse(battleButtonsWanted(true, true, false, snapshot))
        }
    }

    @Test fun tabletKeepsBattleButtonsWhenTheLandscapePictureFillsTheWidth() {
        val unit = 1.5.dp
        assertEquals(96.dp, battleButtonsColumnWidth(0.dp, unit, allowPictureOverlap = true))
        assertEquals(96.dp, battleButtonsColumnWidth(18.dp, unit, allowPictureOverlap = true))
        assertNull(battleButtonsColumnWidth(18.dp, unit, allowPictureOverlap = false))
        assertEquals(100.dp, battleButtonsColumnWidth(100.dp, unit, allowPictureOverlap = false))
    }

    @Test fun actionLaneMovesOffShouldersAndFaceButtonsAndDeclinesAnObstructedScreen() {
        val box = Rect(0f, 0f, 1000f, 500f)
        val controls = listOf(Rect(880f, 10f, 970f, 60f),
            Rect(850f, 200f, 990f, 330f))
        assertEquals(Rect(750f, 0f, 850f, 500f), battleButtonsLane(box, 100f, controls))
        assertEquals(Rect(900f, 0f, 1000f, 500f), battleButtonsLane(box, 100f, emptyList()))
        assertNull(battleButtonsLane(box, 100f, listOf(box)))
        assertEquals(battleButtonsLane(box, 100f, emptyList()), battleButtonsLane(box, 100f,
            listOf(Rect(0f, 600f, 1000f, 700f))))
    }

    @Test fun actionLaneStaysAtTheEdgeBetweenShoulderAndFaceButtons() {
        val rows = Rect(0f, 96f, 1272f, 392f)
        val shoulder = Rect(1168f, 52f, 1264f, 90f)
        val faceButtons = Rect(1116f, 644f, 1264f, 788f)
        assertEquals(Rect(1148f, 96f, 1272f, 392f), battleButtonsLane(rows, 124f, listOf(shoulder, faceButtons)))

        val raisedButton = Rect(1170f, 214f, 1238f, 272f)
        assertEquals(Rect(1046f, 96f, 1170f, 392f), battleButtonsLane(rows, 124f, listOf(shoulder, faceButtons, raisedButton)))
    }

    @Test fun compactActionRowFitsBetweenThePhoneSideControls() {
        val area = Rect(0f, 12f, 2376f, 1068f)
        val controls = listOf(
            Rect(2090f, 38f, 2320f, 137f),
            Rect(2118f, 263f, 2292f, 347f),
            Rect(2031f, 474f, 2379f, 822f),
            Rect(2107f, 958f, 2302f, 1042f),
        )
        assertEquals(Rect(1544f, 137f, 2376f, 263f), battleButtonsRow(area, 832f, 126f, controls))
        assertNull(battleButtonsRow(area, 832f, 126f, controls + Rect(1544f, 0f, 2400f, 1080f)))
    }

    @Test fun settingsSurviveHostRecreationWithoutChangingHudChoices() {
        val store = PrefsCompanionSettingsTest.Store()
        val settings = PrefsCompanionSettingsTest.Settings(Prefs(store))
        settings.setFieldHud(false); settings.setBattleButtons(false)
        val reopened = PrefsCompanionSettingsTest.Settings(Prefs(store))
        assertFalse(reopened.battleButtons); assertFalse(reopened.fieldHud)
        reopened.setBattleButtons(true)
        assertTrue(settings.battleButtons); assertFalse(settings.fieldHud)
    }
}
