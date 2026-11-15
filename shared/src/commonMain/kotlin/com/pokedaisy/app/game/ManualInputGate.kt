package com.pokedaisy.app.game

/** Manual game keys withheld by UI owners; a held key needs a release before it can return to the game. */
class ManualInputGate {
    enum class Owner { COMPANION, EDITOR, BINDING, BACKGROUND }
    private val owners = mutableSetOf<Owner>()
    private var suppressed = 0
    val blocked get() = owners.isNotEmpty()

    /** Snapshot held keys at both edges, including an open/close entirely between two game frames. */
    fun setBlocked(owner: Owner, on: Boolean, held: Int) {
        val wasBlocked = blocked
        if (on) owners.add(owner) else owners.remove(owner)
        if (wasBlocked || blocked) suppressed = suppressed or held
    }

    /** Pass the sustained held mask here, before applying turbo's alternating frames. */
    fun filter(held: Int): Int {
        suppressed = suppressed and held
        if (blocked) {
            suppressed = suppressed or held
            return 0
        }
        return held and suppressed.inv()
    }
}
