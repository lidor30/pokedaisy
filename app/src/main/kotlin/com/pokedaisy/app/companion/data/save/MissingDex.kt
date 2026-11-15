package com.pokedaisy.app.companion.data.save

import com.pokedaisy.app.companion.i18n.tr

/**
 * The SAVE EDITOR's MISSING page: what a save's Pokédex hasn't caught yet (the regional dex until its National one,
 * in the dex's own order), and for each one, the Pokémon of that species in the player's other saves - whether the
 * games would let it come over (a trade between two games of one generation, Pal Park from a GBA game's PC to a DS
 * game) and, if not, why. A trade evolution counts too: a Pokémon in another save that evolves into a missing one as
 * it arrives, and one of the save's own that would come home evolved from a trip to another save ([TradeBack]). Eggs
 * don't count: one doesn't fill the Pokédex until it hatches.
 */
object MissingDex {
    /** How a Pokémon fills the gap. */
    enum class Way {
        /** A trade between two games of one generation. */
        TRADE,
        /** Pal Park, a GBA game's PC into a DS game. */
        PAL_PARK,
        /** A trade, in which it evolves into the missing one as it arrives. */
        EVOLVES,
        /** One of the save's own Pokémon, traded to another save ([source]) and back, evolving there. */
        TRADE_BACK,
    }

    /**
     * Where one of the missing species is: another save ([source], an index into the saves given) - or, for
     * [Way.TRADE_BACK], the save's own Pokémon at [at] with [source] the save it makes the trip to - and why it can't come.
     */
    class Owned(val source: Int, val at: MonRef, val mon: SaveMon, val level: Int, val blocker: String?, val way: Way) {
        val reachable: Boolean get() = blocker == null
        val palPark: Boolean get() = way == Way.PAL_PARK
    }

    /** A species the dex hasn't caught: its number in the dex shown ([number]), its National one, and where to get it. */
    class Entry(val number: Int, val species: Int, val sources: List<Owned>) {
        /** The one to bring first: one that can come, then the highest level. */
        val best: Owned? get() = sources.firstOrNull()
    }

    /** The species [editor]'s Pokédex lists now, in its order, as (number shown, National number). */
    fun listed(editor: MonSaveEditor): List<Pair<Int, Int>> = when (editor) {
        is Gen3SaveEditor -> {
            val f = editor.file.format
            if (editor.nationalDex) (1..Gen3SaveEditor.NATIONAL_DEX_COUNT).map { it to it }
            else if (f.kanto) (1..151).map { it to it }
            else (1 until speciesHoennDex.size).mapNotNull { s ->
                val h = speciesHoennDex[s]
                val n = speciesNationalDex.getOrNull(s) ?: 0
                if (h in 1..HOENN_DEX_COUNT && n in 1..Gen3SaveEditor.NATIONAL_DEX_COUNT) h to n else null
            }.sortedBy { it.first }
        }
        is Gen4SaveEditor -> {
            val regional = !editor.nationalDex && editor.game.regional.isNotEmpty()
            editor.dexSpecies().map { if (regional) editor.game.regional[it] to it else it to it }.sortedBy { it.first }
        }
        else -> emptyList()
    }

    /** [national]'s name in [editor]'s game (a GBA game's tables go by its own species ids). */
    fun speciesName(editor: MonSaveEditor, national: Int): String? = when (editor) {
        is Gen3SaveEditor -> nationalToInternal.getOrNull(national)?.let { Gen3GameData.speciesName(it) }
        else -> editor.data.game.speciesName(national)
    }

    /** [national] is caught in [editor]'s Pokédex. */
    fun caught(editor: MonSaveEditor, national: Int): Boolean = when (editor) {
        is Gen3SaveEditor -> nationalToInternal.getOrNull(national)?.let { editor.caught(it) } == true
        is Gen4SaveEditor -> editor.caughtInDex(national)
        else -> true
    }

    /** What [target]'s Pokédex is missing, each with the Pokémon of [others] that could fill it. */
    fun find(target: TradeSide, others: List<TradeSide>): List<Entry> {
        val missing = listed(target.editor).filter { !caught(target.editor, it.second) }
        val wanted = missing.map { it.second }.toSet()
        val bySpecies = HashMap<Int, MutableList<Owned>>()
        fun add(n: Int, o: Owned) { bySpecies.getOrPut(n) { ArrayList() } += o }
        others.forEachIndexed { i, src ->
            for ((at, mon, level) in owned(src.editor)) {
                if (mon.isEgg || mon.isBadEgg) continue
                val n = mon.game.nationalDex(mon.species) ?: continue
                if (n in wanted) {
                    val (why, palPark) = canCome(src, at, mon, target) ?: continue
                    add(n, Owned(i, at, mon, level, why, if (palPark) Way.PAL_PARK else Way.TRADE))
                }
                // It evolves into a missing one as it arrives (and the Pokédex registers that one).
                if (!sameGeneration(src, target)) continue
                val into = src.rules.evolution(mon, target)?.let { mon.game.nationalDex(it.into) } ?: continue
                if (into in wanted) add(into, Owned(i, at, mon, level, canCome(src, at, mon, target)?.first, Way.EVOLVES))
            }
        }
        // The save's own Pokémon that would come back evolved from a trip to another save: the first save that can take it.
        for ((at, mon, level) in owned(target.editor)) {
            if (mon.isEgg || mon.isBadEgg) continue
            val trips = others.withIndex().filter { (_, partner) -> sameGeneration(target, partner) }.mapNotNull { (i, partner) ->
                val into = TradeBack.evolution(target, at, partner)?.let { mon.game.nationalDex(it.into) } ?: return@mapNotNull null
                if (into !in wanted) null else Triple(i, into, TradeBack.blocker(target, at, partner))
            }
            val (i, into, why) = trips.firstOrNull { it.third == null } ?: trips.firstOrNull() ?: continue
            add(into, Owned(i, at, mon, level, why, Way.TRADE_BACK))
        }
        return missing.map { (number, n) ->
            Entry(number, n, bySpecies[n].orEmpty().sortedWith(compareBy<Owned>({ !it.reachable }, { it.way != Way.TRADE && it.way != Way.PAL_PARK }, { -it.level })))
        }
    }

    private fun sameGeneration(a: TradeSide, b: TradeSide) = (a.editor is Gen4SaveEditor) == (b.editor is Gen4SaveEditor)

    private fun owned(e: MonSaveEditor): List<Triple<MonRef, SaveMon, Int>> {
        val d = e.data
        val party = d.partyMons.mapIndexed { i, p -> Triple(MonRef.Party(i) as MonRef, p.mon, p.level) }
        val pc = (0 until d.boxCount).flatMap { b ->
            d.boxMons(b).mapIndexedNotNull { s, m -> if (m.isEmpty) null else Triple(MonRef.Pc(BoxSlot(b, s)) as MonRef, m, m.level) }
        }
        return party + pc
    }

    /** Why [mon] at [at] in [src] can't come to [to] (null: it can) and whether Pal Park brings it; null when it never could. */
    private fun canCome(src: TradeSide, at: MonRef, mon: SaveMon, to: TradeSide): Pair<String?, Boolean>? {
        val a = src.editor
        val b = to.editor
        return when {
            a is Gen3SaveEditor && b is Gen3SaveEditor ->
                (Gen3Trade.blocker(mon, src, to) ?: Gen3Trade.partyBlocker(src, at)) to false
            a is Gen4SaveEditor && b is Gen4SaveEditor ->
                (Gen4Trade.blocker(mon, src, to) ?: Gen4Trade.partyBlocker(src, at)) to false
            a is Gen3SaveEditor && b is Gen4SaveEditor -> {
                val why = if (at is MonRef.Party) tr("Pal Park takes Pokémon from the PC: put it in a box first")
                    else PalPark.blocker(mon as Gen3BoxMon) ?: PalPark.linkBlocker(a, src.gameCode, src.label, b, to.label)
                why to true
            }
            else -> null   // nothing goes back from a DS game to a GBA one
        }
    }

    /** A National Dex number's Gen 3 internal species id. */
    private val nationalToInternal: IntArray by lazy {
        IntArray(Gen3SaveEditor.NATIONAL_DEX_COUNT + 1).also { out ->
            speciesNationalDex.forEachIndexed { s, n -> if (n in 1..Gen3SaveEditor.NATIONAL_DEX_COUNT && out[n] == 0) out[n] = s }
        }
    }
}
