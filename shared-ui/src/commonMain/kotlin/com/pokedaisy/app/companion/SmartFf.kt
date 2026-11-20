package com.pokedaisy.app.companion

import com.pokedaisy.app.companion.data.BATTLE_INPUT_ACTION_SELECT
import com.pokedaisy.app.companion.data.BATTLE_INPUT_BAG_OPEN
import com.pokedaisy.app.companion.data.BATTLE_INPUT_MOVE_SELECT
import com.pokedaisy.app.companion.data.BATTLE_INPUT_NONE
import com.pokedaisy.app.companion.data.BATTLE_INPUT_PARTY_OPEN
import com.pokedaisy.app.companion.data.BATTLE_INPUT_TARGET_SELECT

/**
 * Whether SMART fast-forward drops to 1x right now. In a battle the game's own
 * battle state decides ([battleState], BATTLE_INPUT_*, from the battle input
 * poll - NONE outside battles): 1x while it waits for the player - the action,
 * move and target menus, and the bag and party screens (the summary opened from
 * the party included) - so the Pokémon and boxes bob at the game's pace; its
 * animations, text and transitions stay fast (the user's call). A DS game tells
 * the wait itself ([battleWaiting]: MgbaCore.FIELD_BATTLE_INPUT). Outside battles,
 * and in a game that doesn't report its battle state, the menu watch ([menuOpen],
 * FfMenuWatch) and the region map ([mapOpen]) decide.
 */
fun smartSlows(battleState: Int, menuOpen: Boolean, mapOpen: Boolean, battleWaiting: Boolean = false): Boolean = when {
    battleWaiting -> true
    battleState != BATTLE_INPUT_NONE -> battleState in BATTLE_WAITS
    else -> menuOpen || mapOpen
}

private val BATTLE_WAITS = setOf(
    BATTLE_INPUT_ACTION_SELECT, BATTLE_INPUT_MOVE_SELECT, BATTLE_INPUT_TARGET_SELECT, BATTLE_INPUT_PARTY_OPEN, BATTLE_INPUT_BAG_OPEN,
)
