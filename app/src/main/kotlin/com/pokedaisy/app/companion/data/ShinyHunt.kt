package com.pokedaisy.app.companion.data

/**
 * SHINY ALERT (SETTINGS > GAMEPLAY): counts the wild battles of a hunt and says so the moment a shiny foe is out. Fed one
 * sample at a time ([sample]): a battle is counted once, on the first sample that reads its foes (the intro's first ones
 * have none yet), and a shiny among them is reported once a battle. A trainer's battle counts for nothing ([wild]: the
 * reader's [SnapshotView.isTrainerBattle], else no FOE TEAM). The count itself is the app's (per game), this only says
 * when it moves. The activity stops FF and turbo on a shiny: a wild battle waits at its menu, so the player can't miss it.
 */
class ShinyHunt {
    /** What one sample did: [counted] a new wild battle; [shiny] = the shiny foe it found (null = none). */
    data class Step(val counted: Boolean, val shiny: MonView?)

    private var counted = false
    private var alerted = false

    fun sample(inBattle: Boolean, wild: Boolean, foes: List<MonView>): Step {
        if (!inBattle) {
            counted = false
            alerted = false
            return NONE
        }
        if (!wild || foes.isEmpty()) return NONE
        val count = !counted
        counted = true
        val shiny = if (alerted) null else foes.firstOrNull { it.shiny }
        if (shiny != null) alerted = true
        return if (count || shiny != null) Step(count, shiny) else NONE
    }

    companion object {
        private val NONE = Step(false, null)

        /** Whether [snap]'s battle is a wild one, as far as the reader knows. */
        fun wild(snap: SnapshotView): Boolean = snap.isTrainerBattle?.not() ?: snap.enemyParty.isEmpty()
    }
}
