package com.pokedaisy.app.companion.data

// struct PokemonSummaryScreenData's mode for "pick a move to forget" (SUMMARY_MODE_SELECT_MOVE).
internal const val SUMMARY_MODE_SELECT_MOVE = 3

/**
 * Tells when the game's summary screen is up asking which move to forget, for the HUD card and the companion's LEARN
 * page.
 *
 * The screen's state (sMonSummaryScreen) outlives it: the game frees the struct without clearing it, and
 * gMoveToLearn / gPartyMenu.data1 keep the last move too. So the bytes alone would show "learning" under any
 * later menu. A move counts as a new event when gMoveToLearn or gPartyMenu.data1 changes, or when the select-move
 * screen comes up; a panel already showing stays until the screen's mode changes or the page goes away.
 */
object MoveLearningWatch {
    private var started = false
    private var lastLevelMove = 0
    private var lastMenuMove = 0
    private var fresh = false
    private var onScreen = false
    private var summaryWasUp = false

    fun reset() {
        synchronized(this) {
            started = false
            lastLevelMove = 0
            lastMenuMove = 0
            fresh = false
            onScreen = false
            summaryWasUp = false
        }
    }

    /**
     * [levelMove] gMoveToLearn, [menuMove] gPartyMenu.data1, and the summary's [mode] / [newMove] / [monIndex] /
     * [partySize]. Null unless the SELECT_MOVE summary is asking about a move being learned.
     */
    @Synchronized
    fun check(
        levelMove: Int,
        menuMove: Int,
        mode: Int,
        newMove: Int,
        monIndex: Int,
        partySize: Int,
    ): MoveLearningPrompt? {
        val promptMove = when {
            levelMove > 0 && newMove == levelMove -> levelMove
            menuMove > 0 && newMove == menuMove -> menuMove
            else -> 0
        }
        val summaryUp = mode == SUMMARY_MODE_SELECT_MOVE && promptMove > 0 && monIndex in 0 until partySize
        if (!started) {
            started = true
            lastLevelMove = levelMove
            lastMenuMove = menuMove
        }
        // Entering the select-move screen is the event, even when the move itself didn't change (the companion
        // started after the level-up did, say).
        if (summaryUp && !summaryWasUp) fresh = true
        summaryWasUp = summaryUp
        if (levelMove != lastLevelMove || menuMove != lastMenuMove) {
            val changed = when {
                levelMove != lastLevelMove && levelMove > 0 -> levelMove
                menuMove != lastMenuMove && menuMove > 0 -> menuMove
                else -> 0
            }
            lastLevelMove = levelMove
            lastMenuMove = menuMove
            if (changed > 0) fresh = true
        }
        if (!summaryUp) {
            onScreen = false
            fresh = false
            return null
        }
        if (!(fresh || onScreen)) return null
        fresh = false
        onScreen = true
        return MoveLearningPrompt(monIndex, promptMove)
    }
}
