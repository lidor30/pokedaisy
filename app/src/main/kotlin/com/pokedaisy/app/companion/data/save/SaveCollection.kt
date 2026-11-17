package com.pokedaisy.app.companion.data.save

/**
 * TOOLS > COLLECTION: every Pokémon in every save the editor can read, party and PC, in one list - where each one is
 * and what can be done with it from there (open its save, trade it, send it through Pal Park).
 */
object SaveCollection {
    /** One Pokémon: which save ([source], an index into the saves given), where in it, and its National Dex number. */
    class Item(val source: Int, val at: MonRef, val mon: SaveMon, val level: Int, val national: Int) {
        /** A GBA game's PC Pokémon: what Pal Park takes. */
        val palParkable: Boolean get() = at is MonRef.Pc && !mon.isEgg && mon is Gen3BoxMon
    }

    enum class Sort { DEX, LEVEL, GAME }

    /** Every readable Pokémon of [sides], party first, then box by box (Bad Eggs left out). */
    fun gather(sides: List<TradeSide>): List<Item> = sides.flatMapIndexed { i, side ->
        val d = side.editor.data
        val party = d.partyMons.mapIndexed { p, e -> Triple(MonRef.Party(p) as MonRef, e.mon, e.level) }
        val pc = (0 until d.boxCount).flatMap { b ->
            d.boxMons(b).mapIndexedNotNull { s, m -> if (m.isEmpty) null else Triple(MonRef.Pc(BoxSlot(b, s)) as MonRef, m, m.level) }
        }
        (party + pc).mapNotNull { (at, mon, level) ->
            if (mon.isBadEgg) null else Item(i, at, mon, level, mon.game.nationalDex(mon.species) ?: 0)
        }
    }

    /** [items] in [sort]'s order: by Pokédex number (then level), by level (highest first), or by save (then where). */
    fun sorted(items: List<Item>, sort: Sort): List<Item> = when (sort) {
        Sort.DEX -> items.sortedWith(compareBy<Item>({ it.mon.isEgg }, { it.national }, { -it.level }))
        Sort.LEVEL -> items.sortedWith(compareBy<Item>({ it.mon.isEgg }, { -it.level }, { it.national }))
        Sort.GAME -> items   // gathered save by save, party then boxes
    }

    /** How many different species [items] hold (eggs don't count). */
    fun species(items: List<Item>): Int = items.filter { !it.mon.isEgg && it.national > 0 }.map { it.national }.toSet().size
}
