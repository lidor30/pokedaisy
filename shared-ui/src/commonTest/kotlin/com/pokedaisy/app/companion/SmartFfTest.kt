package com.pokedaisy.app.companion

import com.pokedaisy.app.companion.data.BATTLE_INPUT_ACTION_SELECT
import com.pokedaisy.app.companion.data.BATTLE_INPUT_BAG_OPEN
import com.pokedaisy.app.companion.data.BATTLE_INPUT_BUSY
import com.pokedaisy.app.companion.data.BATTLE_INPUT_MOVE_SELECT
import com.pokedaisy.app.companion.data.BATTLE_INPUT_NONE
import com.pokedaisy.app.companion.data.BATTLE_INPUT_PARTY_OPEN
import com.pokedaisy.app.companion.data.BATTLE_INPUT_TARGET_SELECT
import kotlin.test.*

/** [smartSlows]: battles stay fast but where they wait for the player; the field's menus slow down. */
class SmartFfTest {
    @Test fun aBattleStaysFastWhateverTheMenuWatchThinks() {
        assertFalse(smartSlows(BATTLE_INPUT_BUSY, menuOpen = true, mapOpen = true))
    }

    @Test fun aBattleWaitingForThePlayerSlowsDown() {
        for (state in listOf(BATTLE_INPUT_ACTION_SELECT, BATTLE_INPUT_MOVE_SELECT, BATTLE_INPUT_TARGET_SELECT,
            BATTLE_INPUT_PARTY_OPEN, BATTLE_INPUT_BAG_OPEN)) {
            assertTrue(smartSlows(state, menuOpen = false, mapOpen = false))
        }
        // A DS game says so itself.
        assertTrue(smartSlows(BATTLE_INPUT_NONE, menuOpen = false, mapOpen = false, battleWaiting = true))
    }

    @Test fun outsideBattlesTheMenuWatchAndMapDecide() {
        assertTrue(smartSlows(BATTLE_INPUT_NONE, menuOpen = true, mapOpen = false))
        assertTrue(smartSlows(BATTLE_INPUT_NONE, menuOpen = false, mapOpen = true))
        assertFalse(smartSlows(BATTLE_INPUT_NONE, menuOpen = false, mapOpen = false))
    }
}
