package com.pokedaisy.app.companion.data

/**
 * AUTO REFILL (SETTINGS > GAMEPLAY): a REPEL that runs low gets another of the same kind on top, as Black / White's "use
 * another?" would, without the stop. It goes on while [REFILL_AT] steps or fewer are left (the companion reads the count
 * once a second, and a bike covers up to ~15 steps in that), its steps added to what's left, so the walking it covers is
 * exactly the two one after the other's; the game's "wore off" message never comes. Field only, controls free, no battle.
 *
 * Which kind: the one working ([kindOf]: the game doesn't keep it, so the smallest whose steps cover the first count seen
 * after none was working), else the smallest the bag has. The calls are [refillRepel] (GBA, the game's own VarSet /
 * RemoveBagItem) and [useGen4Repel] / [useGen5Repel] with `topUp` (DS: the bag and the step byte written directly).
 */
object RepelRefill {
    /** The most steps left that a refill goes on at. */
    const val REFILL_AT = 20

    /** The kind (an id of [kinds]: id to steps) that a count of [steps] just after none was working came from. */
    fun kindOf(kinds: List<Pair<Int, Int>>, steps: Int): Int? =
        kinds.sortedBy { it.second }.firstOrNull { it.second >= steps }?.first

    /** What goes on next: [running] if [inBag] has it, else the smallest kind [inBag] has; null = none left. */
    fun pick(kinds: List<Pair<Int, Int>>, inBag: (Int) -> Boolean, running: Int?): Pair<Int, Int>? =
        kinds.firstOrNull { it.first == running && inBag(it.first) }
            ?: kinds.sortedBy { it.second }.firstOrNull { inBag(it.first) }
}
