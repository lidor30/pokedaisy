package com.pokedaisy.app.companion.data.save

import com.pokedaisy.app.companion.data.vanillaMoveStats

/**
 * What a save of a [Gen3SaveFormat] may hold, from the decomp tables scripts/gen_save_tables.py generates: the save editor
 * offers only these, so an edit never puts something in a save the game itself couldn't have.
 *
 * - Bag items: every item that game files in a pocket (key items aren't offered, nor the e-Reader's Enigma Berry).
 * - Moves: what the species - or what it evolved from, at that level or below - learns by level, TM / HM or the
 *   game's move tutors, plus FireRed / LeafGreen's Cape Brink moves for their three starters. Egg moves and event
 *   moves aren't listed: a Pokémon keeps the ones it already knows, they just aren't offered to others. Smeargle
 *   can Sketch any move but Struggle, so it gets every other one.
 */
object Gen3Legal {
    private class Tables(
        val bagItems: IntArray, val levelUp: List<String>, val tmHm: List<String>, val tutorMoves: IntArray, val tutors: List<String>,
    )

    private fun tables(f: Gen3SaveFormat): Tables = when (f) {
        Gen3SaveFormat.RUBY_SAPPHIRE -> rs
        Gen3SaveFormat.EMERALD -> e
        Gen3SaveFormat.FIRERED_LEAFGREEN -> frlg
    }

    private val rs by lazy { Tables(bagItemsRS, levelUpRS, tmHmRS, tutorMovesRS, tutorsRS) }
    private val e by lazy { Tables(bagItemsE, levelUpE, tmHmE, tutorMovesE, tutorsE) }
    private val frlg by lazy { Tables(bagItemsFRLG, levelUpFRLG, tmHmFRLG, tutorMovesFRLG, tutorsFRLG) }

    /** The last move id the Gen 3 games have (Psycho Boost). */
    const val LAST_MOVE = 354
    private const val MOVE_STRUGGLE = 165
    private const val SPECIES_SMEARGLE = 235

    /** FireRed / LeafGreen's Cape Brink tutor: Venusaur, Charizard, Blastoise (CanLearnTutorMove). */
    private val capeBrink = mapOf(3 to 338, 6 to 307, 9 to 308)

    /** The items [f]'s bag may hold in [pocket], by id. KEY is never offered. */
    fun bagItems(f: Gen3SaveFormat, pocket: BagPocket): List<Int> {
        val t = tables(f).bagItems
        return (0 until t.size / 2).filter { t[it * 2 + 1] == pocket.id }.map { t[it * 2] }
    }

    /** The pocket [f] files [item] in, or null (a key item, or not an item of that game). */
    fun pocketOf(f: Gen3SaveFormat, item: Int): BagPocket? {
        val t = tables(f).bagItems
        for (i in 0 until t.size / 2) if (t[i * 2] == item) return BagPocket.entries.first { it.id == t[i * 2 + 1] }
        return null
    }

    /** What a Pokémon may be given to hold: bag items but HMs (they can't be given) and mail (it needs a letter). */
    fun holdable(f: Gen3SaveFormat): List<Int> =
        listOf(BagPocket.ITEMS, BagPocket.BALLS, BagPocket.TMHM, BagPocket.BERRIES).flatMap { bagItems(f, it) }
            .filter { it < Gen3SaveFormat.FIRST_HM && it !in MAIL_ITEMS }.sorted()

    /** Every move [species] could know at [level] in a game of [f] (see the class doc). */
    fun learnable(f: Gen3SaveFormat, species: Int, level: Int): Set<Int> {
        if (species == SPECIES_SMEARGLE) return (1..LAST_MOVE).filter { it != MOVE_STRUGGLE }.toSet()
        val t = tables(f)
        val out = HashSet<Int>()
        var s = species
        var guard = 0
        while (s in 1 until SPECIES_EGG && guard++ < 4) {
            val lu = t.levelUp[s]
            for (i in 0 until lu.length / 5) {
                val lv = lu.substring(i * 5, i * 5 + 2).toInt(16)
                if (lv <= level) out += lu.substring(i * 5 + 2, i * 5 + 5).toInt(16)
            }
            val tm = t.tmHm[s].toLong(16)
            for (b in tmHmMoves.indices) if ((tm shr b) and 1L == 1L) out += tmHmMoves[b]
            val tu = t.tutors[s].toLong(16)
            for (b in t.tutorMoves.indices) if ((tu shr b) and 1L == 1L) out += t.tutorMoves[b]
            if (f == Gen3SaveFormat.FIRERED_LEAFGREEN) capeBrink[s]?.let { out += it }
            s = preEvolution[s]
        }
        return out
    }

    /** Mail: a Pokémon holding a letter keeps it (the letter lives in the save beside it). */
    fun isMail(item: Int): Boolean = item in MAIL_ITEMS

    /** The move a TM / HM item teaches (TM01 = 289 .. HM08 = 346), or null. */
    fun tmHmMove(item: Int): Int? = tmHmMoves.getOrNull(item - FIRST_TM)

    private const val FIRST_TM = 289

    /** A move's PP with no PP Ups (gBattleMoves.pp); 0 for an id the games don't have. */
    fun basePp(move: Int): Int = if (move in 1..LAST_MOVE) vanillaMoveStats.getOrNull(move)?.pp ?: 0 else 0

    /** CalculatePPWithBonus: base PP + a fifth per PP Up. */
    fun ppWithBonus(move: Int, ups: Int): Int = basePp(move).let { it + it * 20 * ups / 100 }
}
