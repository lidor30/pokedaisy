package com.pokedaisy.app.companion.data

/** The same field input rules on both hosts: B means walking while auto-run is on, and never closes a menu. */
object AutoRunPolicy {
    const val BLOCKERS = GameField.KNOWN or GameField.LOCKED or GameField.BATTLE or GameField.BIKE or GameField.SURF or GameField.WINDOW
    private const val DIRECTIONS = CoreKey.UP or CoreKey.DOWN or CoreKey.LEFT or CoreKey.RIGHT
    fun walking(keys: Int, field: Int, onField: Boolean): Boolean =
        keys and DIRECTIONS != 0 && field and BLOCKERS == GameField.KNOWN && onField

    /** A keeps the game's own sneaking/DexNav behavior; a game's built-in auto-run also stays in charge. */
    fun apply(keys: Int, field: Int, onField: Boolean, builtIn: Boolean = false): Int =
        if (!builtIn && keys and CoreKey.A == 0 && walking(keys, field, onField)) keys xor CoreKey.B else keys
}
