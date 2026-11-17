package com.pokedaisy.app.game

/** Manual game keys withheld by UI owners; a held key needs a release before it can return to the game. */
class ManualInputGate {
    enum class Owner { COMPANION, EDITOR, BINDING, BACKGROUND }
    private val owners = mutableSetOf<Owner>()
    private var suppressed = 0
    var awaitingNeutral = false
        private set
    val blocked get() = owners.isNotEmpty()
    /** A modal may handle its own shortcut, while every other input owner still wins. */
    fun blockedExcept(owner: Owner) = owners.any { it != owner }
    /** A menu's aiming stick must return to centre, even if it changes direction after closing. */
    fun quarantineUntilNeutral() { awaitingNeutral = true }

    /** Snapshot held keys at both edges, including an open/close entirely between two game frames. */
    fun setBlocked(owner: Owner, on: Boolean, held: Int) {
        val wasBlocked = blocked
        if (on) owners.add(owner) else owners.remove(owner)
        if (wasBlocked || blocked) suppressed = suppressed or held
    }

    /** Pass the sustained held mask here, before applying turbo's alternating frames. */
    fun filter(held: Int, neutral: Boolean = held == 0): Int {
        suppressed = suppressed and held
        if (awaitingNeutral) {
            if (neutral) awaitingNeutral = false
            else { suppressed = suppressed or held; return 0 }
        }
        if (blocked) {
            suppressed = suppressed or held
            return 0
        }
        return held and suppressed.inv()
    }
}
