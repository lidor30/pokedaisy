package com.pokedaisy.app.companion

import com.pokedaisy.app.companion.i18n.tk

// SETTINGS > HUD's choices (the HUD itself is companion.ui.FieldHud): here, beside the other settings types,
// so Prefs and CompanionSettings can hold them.

/** Where the HUD's pieces sit on the game screen. */
enum class FieldHudLayout(val label: String) {
    /** A modern open-world game's corners: minimap top-right (the games' area names slide in top-left), party bottom-left, balls and buttons bottom-right. */
    CORNERS(tk("CORNERS")),

    /** CORNERS with the right side swapped: the minimap bottom right, the balls and buttons top right. */
    CORNERS_2(tk("CORNERS 2")),

    /** Two columns at the screen's sides, beside the GBA's own 3:2 (the black bars at ORIGINAL, the margins at EXPAND). */
    RAILS(tk("RAILS")),

    /** One bar along the bottom (party, balls, buttons), the minimap top-right. */
    DOCK(tk("DOCK")),
}

/** How the HUD's windows are drawn. */
enum class FieldHudLook(val label: String) {
    /** The running game's own look (its companion skin's HUD: HeartGold's green menu window, Black's navy panels...);
     * GLASS for a game without one. The default. */
    GAME(tk("GAME")),

    /** See-through dark panels with light text: as little of the game hidden as can be. */
    GLASS(tk("GLASS")),

    /** [GLASS] in white: see-through light panels with dark text. */
    WHITE_GLASS(tk("WHITE GLASS")),

    /** The OPTION screen's white title windows, like the rest of the app. */
    WINDOW(tk("WINDOW")),
}

/** How big the HUD is: its GBA pixel one whole screen pixel smaller or bigger than the companion's (then all drawn 12.5%
 * bigger, FieldHud's HUD_SCALE). */
enum class FieldHudSize(val label: String, val step: Int) {
    SMALL(tk("SMALL"), -1),
    NORMAL(tk("NORMAL"), 0),
    LARGE(tk("LARGE"), 1),
}

/** The parts a player can switch off (SETTINGS > HUD > SHOW), stored by name ([com.pokedaisy.app.Prefs.fieldHudStyle]). */
enum class FieldHudPart(val label: String) {
    PARTY(tk("PARTY")),
    MAP(tk("MINIMAP")),
    BALLS(tk("POKé BALLS")),
    /** The steps left on a REPEL in effect, counting down, beside the balls. */
    REPEL(tk("REPEL STEPS")),
    /** The game's own PC on the spot (ItemUse.openPc), where the BAG tab has PC. */
    PC(tk("PC")),
    BAG(tk("BAG")),
    WILD(tk("WILD POKéMON")),
    /** The card over the game while its summary asks which move to forget (MoveLearningHudCard). */
    MOVE_ADVICE(tk("MOVE ADVICE")),
}

/** The player's choices for the HUD (SETTINGS > HUD). */
data class FieldHudStyle(
    val layout: FieldHudLayout = FieldHudLayout.CORNERS,
    val look: FieldHudLook = FieldHudLook.GAME,
    val size: FieldHudSize = FieldHudSize.NORMAL,
    val hidden: Set<FieldHudPart> = emptySet(),
) {
    fun shows(part: FieldHudPart) = part !in hidden
}
