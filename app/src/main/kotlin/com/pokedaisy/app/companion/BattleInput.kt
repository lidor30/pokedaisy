package com.pokedaisy.app.companion

/**
 * Bridge for the bottom-screen touch battle control: BattleControlsScreen asks to
 * select an action or move, and the emulator drives the real battle menu via
 * synthetic button presses (see EmulatorEngine/BattleInputController).
 * Implemented by PokeDaisyActivity so the companion module stays
 * independent of the emulator. See PLAN.md Phase 5.
 */
interface BattleInput {
    /** The game can't be heard right now (FF with a STEADY clip, or muted): a button that presses the game's own
     * button then makes the companion's click, since the game's select sound is silent too. */
    val gameSilent: Boolean get() = false

    /** True while a queued button sequence hasn't fully drained — the UI
     * should disable further taps while this is true. */
    val busy: Boolean

    /** The game's cursor in the open battle menu (the same numbers as [selectAction] / [selectMove]); -1 unknown. */
    val menuCursor: Int get() = -1

    /** actionIndex: 0=FIGHT, 1=BAG, 2=POKEMON, 3=RUN. */
    fun selectAction(actionIndex: Int)

    /** moveIndex 0-3; opens the FIGHT menu first if it isn't already open. */
    fun selectMove(moveIndex: Int)

    /** Backs out of move-select to action-select (a single B press). */
    fun back()

    /** Whether [switchTo] works in this game (PokeDaisyActivity's switchAddrsFor knows it). */
    val canSwitch: Boolean get() = false

    /** Switches to the party mon with this personality ([com.pokedaisy.app.companion.data.MonView.personality]):
     * opens POKéMON if needed, steers the party menu's cursor to it and picks SHIFT. */
    fun switchTo(personality: Long) {}
}
