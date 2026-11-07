package com.pokedaisy.app.companion.data

import java.util.concurrent.ConcurrentHashMap

/** A move a party Pokémon could learn: at [level] (a level-up), or from the bag's [tm] ("TM35", "HM03"). */
data class LearnOption(val move: Int, val level: Int, val tm: String, val advice: MoveAdvice)

/** Whether a TM / HM suits a party Pokémon, as the game's own party screen says it (ABLE / NOT ABLE / LEARNED). */
enum class TmFit { ABLE, NOT_ABLE, LEARNED }

/** A party member ([index]) for a TM: whether it can learn it and, if so, the [advice]. */
data class TmLearner(val index: Int, val fit: TmFit, val advice: MoveAdvice?)

/**
 * The move advice's data: a party Pokémon's upcoming level-up moves and the TMs / HMs in the bag it
 * can learn ([options], the summary's CAN LEARN page), and who a TM suits ([learners], the ITEMS
 * tab). Reads the ROM (learnsets, gTMHMMoves, the TM compatibility bits), so - like [DexDetails] -
 * it runs on Compose's IO dispatcher and keeps what it worked out for the UI to start from.
 * Only for the original games for now ([movesAreVanilla]): their TM items are 289..346.
 */
object MoveLearning {
    /** Upcoming level-up moves listed, at most. */
    private const val UPCOMING = 4

    private val optionsCache = ConcurrentHashMap<List<Any?>, List<LearnOption>>()
    private val learnersCache = ConcurrentHashMap<List<Any?>, List<TmLearner>>()

    /** Whether the running game gets advice at all (no ROM read). */
    fun available(t: PokedexTables?, g: GuideTables?): Boolean =
        movesAreVanilla && t != null && (hasLearnsets(t, g) || DexDetails.hasTeachable(t))

    fun isTm(itemId: Int): Boolean = movesAreVanilla && itemId in VANILLA_ITEM_TM01..VANILLA_ITEM_HM08

    /** "TM06" / "HM01" for a TM item. */
    fun tmLabel(itemId: Int): String {
        val i = itemId - VANILLA_ITEM_TM01
        return if (i < 50) "TM%02d".format(i + 1) else "HM%02d".format(i - 49)
    }

    /** The move TM / HM item [itemId] teaches; null = not a TM or the table isn't read. */
    fun tmMove(t: PokedexTables, itemId: Int): Int? =
        if (!isTm(itemId)) null else DexDetails.tmhmMoves(t)?.getOrNull(itemId - VANILLA_ITEM_TM01)?.takeIf { it > 0 }

    /** Every TM / HM item's move name ("TOXIC" for TM06), for the bag's rows; empty where the TMs aren't read. */
    fun tmMoveNames(t: PokedexTables): Map<Int, String> {
        if (!movesAreVanilla) return emptyMap()
        val moves = DexDetails.tmhmMoves(t) ?: return emptyMap()
        return moves.withIndex().filter { it.value > 0 }.associate { (i, move) -> VANILLA_ITEM_TM01 + i to lookupMove(move).name }
    }

    fun adviceMon(mon: MonView): AdviceMon = AdviceMon(
        types = mon.types.map(::typeIdOf).filter { it != TYPE_NONE },
        attack = mon.stats?.stats?.getOrNull(STAT_ATK),
        spAttack = mon.stats?.stats?.getOrNull(STAT_SPATK),
        level = mon.level,
        moves = mon.moves.map { it.id }.filter { it > 0 },
    )

    private fun hmMoves(t: PokedexTables): Set<Int> = DexDetails.tmhmMoves(t)?.drop(50)?.toSet() ?: VANILLA_HM_MOVES

    private fun optionsKey(t: PokedexTables, g: GuideTables?, mon: MonView, bag: List<ItemView>): List<Any?> =
        listOf(t, g, activeGame, mon.species, mon.level, mon.moves.map { it.id }, mon.stats?.stats, bag.map { it.itemId }.filter(::isTm).sorted())

    fun cachedOptions(t: PokedexTables, g: GuideTables?, mon: MonView, bag: List<ItemView>): List<LearnOption>? =
        optionsCache[optionsKey(t, g, mon, bag)]

    /**
     * [mon]'s next few level-up moves it doesn't know (its own level's too: that's the move the game is asking about while
     * it wants another forgotten), then the TMs / HMs in [bag] it can learn, each
     * with its [MoveAdvice] - the bag's best first. Empty for an egg or where nothing applies.
     */
    fun options(t: PokedexTables, g: GuideTables?, mon: MonView, bag: List<ItemView>): List<LearnOption> {
        val key = optionsKey(t, g, mon, bag)
        optionsCache[key]?.let { return it }
        if (mon.isEgg || mon.species <= 0 || !available(t, g)) return emptyList()
        val am = adviceMon(mon)
        val hms = hmMoves(t)
        fun advise(move: Int) = MoveAdvisor.advise(am, move, hms)
        val levelUps = upcoming(DexDetails.levelUp(t, g, mon.species).orEmpty(), mon.level, am.moves)
            .map { LearnOption(it.move, it.level, "", advise(it.move)) }
        val teach = DexDetails.teachable(t, g, mon.species).orEmpty().map { it.move }.toSet()
        val tms = bag.map { it.itemId }.filter(::isTm).distinct().sorted().mapNotNull { item ->
            val move = tmMove(t, item) ?: return@mapNotNull null
            if (move !in teach || move in am.moves) null else LearnOption(move, 0, tmLabel(item), advise(move))
        }.sortedWith(compareBy<LearnOption> { it.advice.verdict == Verdict.SKIP }.thenByDescending { it.advice.gain })
        return (levelUps + tms).also { if (optionsCache.size > 64) optionsCache.clear(); optionsCache[key] = it }
    }

    /** The level-up moves still to come at [level] (its own too), [known] left out. */
    internal fun upcoming(moves: List<LevelMove>, level: Int, known: Collection<Int>): List<LevelMove> =
        moves.filter { it.level >= level && it.move !in known }.distinctBy { it.move }.take(UPCOMING)

    private fun learnersKey(t: PokedexTables, g: GuideTables?, party: List<MonView>, itemId: Int): List<Any?> =
        listOf(t, g, activeGame, itemId, party.map { listOf(it.species, it.level, it.isEgg, it.moves.map { m -> m.id }, it.stats?.stats) })

    fun cachedLearners(t: PokedexTables, g: GuideTables?, party: List<MonView>, itemId: Int): List<TmLearner>? =
        learnersCache[learnersKey(t, g, party, itemId)]

    /** Who in [party] can learn TM / HM [itemId], and the advice for each who can; null = not a TM here. */
    fun learners(t: PokedexTables, g: GuideTables?, party: List<MonView>, itemId: Int): List<TmLearner>? {
        val key = learnersKey(t, g, party, itemId)
        learnersCache[key]?.let { return it }
        val move = tmMove(t, itemId) ?: return null
        val hms = hmMoves(t)
        return party.mapIndexed { i, mon ->
            val am = adviceMon(mon)
            when {
                mon.isEgg -> TmLearner(i, TmFit.NOT_ABLE, null)
                move in am.moves -> TmLearner(i, TmFit.LEARNED, null)
                DexDetails.teachable(t, g, mon.species).orEmpty().none { it.move == move } -> TmLearner(i, TmFit.NOT_ABLE, null)
                else -> TmLearner(i, TmFit.ABLE, MoveAdvisor.advise(am, move, hms))
            }
        }.also { if (learnersCache.size > 64) learnersCache.clear(); learnersCache[key] = it }
    }

    /** The party member a TM suits best: the biggest gain among those it's worth teaching. */
    fun best(learners: List<TmLearner>): Int? = learners
        .filter { it.advice != null && it.advice.verdict != Verdict.SKIP }
        .maxByOrNull { it.advice!!.gain }?.index
}
