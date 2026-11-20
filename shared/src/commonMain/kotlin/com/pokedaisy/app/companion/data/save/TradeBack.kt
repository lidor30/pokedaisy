package com.pokedaisy.app.companion.data.save

import com.pokedaisy.app.companion.i18n.tr

/**
 * TRADE AND BACK: what two players do for a Pokémon that only evolves by trading. It goes to the partner's save, evolves
 * there as it arrives (the item it needed used up), and comes straight back to its own save. Both legs are ordinary
 * trades with the games' own rules ([TradeRules.send]), so the partner's Pokédex registers it and both forms, and its
 * own save registers the evolved one. It waits in the partner's PC between the two, so the partner needs a free box slot.
 * A party Pokémon returns to its old place in the party. Both saves are put back if either leg is refused.
 */
object TradeBack {

    /** The evolution the trip would give the Pokémon at [home]'s [at], or null when the trip changes nothing. */
    fun evolution(home: TradeSide, at: MonRef, partner: TradeSide): TradeEvolution? {
        val mon = home.editor.monAt(at) ?: return null
        return home.rules.evolution(mon, partner)
    }

    /** Why the Pokémon at [home]'s [at] can't make the trip, or null (checked before anything changes). */
    fun blocker(home: TradeSide, at: MonRef, partner: TradeSide): String? {
        val mon = home.editor.monAt(at) ?: return tr("There's no Pokémon there")
        val rules = home.rules
        rules.linkBlocker(home, partner)?.let { return it }
        rules.blocker(mon, home, partner)?.let { return it }
        rules.partyBlocker(home, at)?.let { return it }
        if (evolution(home, at, partner) == null) return tr("{0} wouldn't evolve in {1}", monName(mon), partner.label)
        if (freeSlot(partner) == null) return tr("{0}'s PC is full", partner.label)
        return null
    }

    /** Sends the Pokémon at [home]'s [at] to [partner] and back, evolved. Throws [TradeRefused], changing nothing, if either leg can't happen. */
    fun run(home: TradeSide, at: MonRef, partner: TradeSide): TradeEvolution {
        blocker(home, at, partner)?.let { throw TradeRefused(it) }
        val evo = evolution(home, at, partner)!!
        val waiting = MonRef.Pc(freeSlot(partner)!!)
        val keepHome = home.editor.snapshot()
        val keepPartner = partner.editor.snapshot()
        try {
            home.rules.send(home, at, partner, waiting, evolve = true)
            when (at) {
                is MonRef.Pc -> home.rules.send(partner, waiting, home, at, evolve = false)
                is MonRef.Party -> {
                    // It left a gap the party closed up; it comes back at the end, then steps back to its old place.
                    val end = home.editor.data.partyCount
                    home.rules.send(partner, waiting, home, MonRef.Party(end), evolve = false)
                    for (i in end downTo at.index + 1) home.editor.move(MonRef.Party(i), MonRef.Party(i - 1))
                }
            }
        } catch (e: SaveEditRefused) {
            home.editor.restore(keepHome)
            partner.editor.restore(keepPartner)
            throw e as? TradeRefused ?: TradeRefused(e.message ?: "")
        }
        return evo
    }

    /** The partner's first empty PC slot, from the box its PC is on. */
    private fun freeSlot(side: TradeSide): BoxSlot? {
        val d = side.editor.data
        val n = d.boxCount
        for (k in 0 until n) {
            val box = (d.currentBox.coerceIn(0, n - 1) + k) % n
            val i = d.boxMons(box).indexOfFirst { it.isEmpty }
            if (i >= 0) return BoxSlot(box, i)
        }
        return null
    }

    private fun monName(mon: SaveMon): String = mon.nickname ?: mon.game.speciesName(mon.species) ?: "#${mon.species}"
}
