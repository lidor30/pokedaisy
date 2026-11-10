package com.pokedaisy.app.companion

import com.pokedaisy.app.companion.data.isDsGame

import com.pokedaisy.app.companion.i18n.tk

/**
 * The QUICK MENU's actions (companion.ui.QuickMenu, held open by the QUICK MENU hotkey, L3 by default), in the order it
 * shows them. Which ones it shows is the player's (SETTINGS > QUICK MENU, [com.pokedaisy.app.Prefs.quickMenuActions]);
 * one the running game can't do isn't offered at all (no PC code, a layout the BIKE / ROD register isn't known in), and one it
 * can't do right now shows dimmed, saying why.
 */
enum class QuickAction(val label: String) {
    PC(tk("PC")),
    FLY(tk("FLY")),
    /** FREE FLIGHT (Flight.kt): up on the FLY bird, over anything, until LAND (or B). */
    FLIGHT(tk("TAKE OFF")),
    /** The field move, where a party Pokémon knows it: a dark cave lit (companion.data.Flash). */
    FLASH(tk("FLASH")),
    REPEL(tk("REPEL")),
    BIKE(tk("BIKE")),
    ROD(tk("ROD")),
    /** WILD ENCOUNTERS off, or back to what it was. */
    WILD(tk("WILD ENCOUNTERS")),
    SAVE_STATE(tk("SAVE STATE")),
    LOAD_STATE(tk("LOAD STATE")),
    FF(tk("FF")),
    /** One screen: opens the companion (its window or side panel). */
    COMPANION(tk("COMPANION")),
}

/** How the QUICK MENU lays its tiles out (SETTINGS > QUICK MENU > STYLE, [com.pokedaisy.app.Prefs.quickMenuStyle]): a tap
 * flips it. */
enum class QuickMenuStyle(val label: String) {
    /** The default. A circle, like a modern game's weapon wheel: the left stick points at a tile. */
    RING(tk("RING")),

    /** Rows of tiles: the D-pad steps through them. */
    GRID(tk("GRID")),
}

/** REPEL's kinds, weakest first: item id and the steps one lasts (the games' holdEffectParam, the same in every Gen 3 game;
 * a DS game's own ids, [com.pokedaisy.app.companion.data.GEN4_REPEL_KINDS]). */
val REPEL_KINDS: List<Pair<Int, Int>>
    get() = when {
        com.pokedaisy.app.companion.data.activeGame.isDsGame -> com.pokedaisy.app.companion.data.GEN4_REPEL_KINDS
        // SoulGold renumbered its items (expansion): its own 114..116 (GitHub #242).
        com.pokedaisy.app.companion.data.activeGame == com.pokedaisy.app.companion.data.GameKind.SOULGOLD -> SOULGOLD_REPEL_KINDS
        else -> GBA_REPEL_KINDS
    }

private val GBA_REPEL_KINDS = listOf(86 to 100, 83 to 200, 84 to 250)
private val SOULGOLD_REPEL_KINDS = listOf(114 to 100, 115 to 200, 116 to 250)
