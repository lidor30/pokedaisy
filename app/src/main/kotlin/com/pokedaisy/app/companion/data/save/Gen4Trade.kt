package com.pokedaisy.app.companion.data.save

import com.pokedaisy.app.companion.i18n.tr

/**
 * Trading between two HeartGold / SoulSilver / Platinum saves with the games' rules (pret/pokeheartgold's trade code) and what a
 * trade does: the received Pokémon registered in the Pokédex ([Gen4SaveEditor.registerCaught]) and trade evolutions
 * (EVO_TRADE, EVO_TRADE_ITEM with its item used up; an Everstone stops one), renamed to its new species' name when it
 * wasn't nicknamed. The games trade any species and eggs; the Spiky-eared Pichu stays. A Pokémon holding mail keeps its
 * letter, so it can only go into the other party (the PC takes none). Nothing changes until every check has passed.
 */
object Gen4Trade : TradeRules {
    private val TradeSide.ed4: Gen4SaveEditor get() = editor as Gen4SaveEditor

    private const val PICHU = 172

    override fun blocker(mon: SaveMon, from: TradeSide, to: TradeSide): String? {
        if (mon.isEmpty) return null
        if (mon.isBadEgg) return tr("A Bad Egg can't be traded")
        if (mon.species == PICHU && (mon as Gen4BoxMon).form == 1) return tr("This Pichu can't be traded")
        // HeartGold / SoulSilver's newer items (Apricorns, the Pokéwalker's) don't exist in Platinum.
        if (!to.ed4.game.hasItem(mon.heldItem)) return tr("{0} doesn't have the item it holds", to.label)
        return null
    }

    override fun linkBlocker(a: TradeSide, b: TradeSide): String? = null

    override fun partyBlocker(side: TradeSide, at: MonRef): String? {
        if (at !is MonRef.Party) return null
        val others = side.ed4.data.partyMons.withIndex().count { (i, p) -> i != at.index && !p.mon.isEgg && !p.mon.isBadEgg }
        return if (others == 0) tr("That's {0}'s last Pokémon that isn't an egg: it stays in the party", side.label) else null
    }

    override fun evolution(mon: SaveMon, to: TradeSide, partner: SaveMon?): TradeEvolution? {
        if (mon.isEmpty || mon.isBadEgg || mon.isEgg) return null
        return to.ed4.game.tradeEvolution(mon.species, mon.heldItem)
    }

    private fun mailBlocker(mon: SaveMon, to: MonRef): String? =
        if (to is MonRef.Pc && (mon as Gen4BoxMon).game.isMail(mon.heldItem)) tr("A Pokémon holding mail can't go in the PC") else null

    override fun send(src: TradeSide, at: MonRef, dest: TradeSide, to: MonRef, evolve: Boolean) {
        val mon = src.ed4.monAt(at) ?: throw TradeRefused(tr("There's no Pokémon there"))
        when (to) {
            is MonRef.Pc -> if (!dest.ed4.data.mon(to.at).isEmpty) throw TradeRefused(tr("That slot isn't empty"))
            is MonRef.Party -> {
                val count = dest.ed4.data.partyCount
                if (to.index < count) throw TradeRefused(tr("That slot isn't empty"))
                if (count >= 6) throw TradeRefused(tr("{0}'s party is full", dest.label))
            }
        }
        blocker(mon, src, dest)?.let { throw TradeRefused(it) }
        partyBlocker(src, at)?.let { throw TradeRefused(it) }
        mailBlocker(mon, to)?.let { throw TradeRefused(it) }
        val keep = (at as? MonRef.Party)?.let { src.ed4.partyRecord(it.index) }
        val arriving = arrive(mon, dest, evolve)
        dest.ed4.putIncoming(to, arriving, keep)
        src.ed4.takeOut(at)
    }

    override fun swap(a: TradeSide, atA: MonRef, b: TradeSide, atB: MonRef, evolveA: Boolean, evolveB: Boolean) {
        val monA = a.ed4.monAt(atA)
        val monB = b.ed4.monAt(atB)
        if (monA == null || monB == null) throw TradeRefused(tr("There's no Pokémon there"))
        blocker(monA, a, b)?.let { throw TradeRefused(it) }
        blocker(monB, b, a)?.let { throw TradeRefused(it) }
        partyBlocker(a, atA)?.let { throw TradeRefused(it) }
        partyBlocker(b, atB)?.let { throw TradeRefused(it) }
        mailBlocker(monA, atB)?.let { throw TradeRefused(it) }
        mailBlocker(monB, atA)?.let { throw TradeRefused(it) }
        val keepA = (atA as? MonRef.Party)?.let { a.ed4.partyRecord(it.index) }
        val keepB = (atB as? MonRef.Party)?.let { b.ed4.partyRecord(it.index) }
        val toB = arrive(monA, b, evolveA)
        val toA = arrive(monB, a, evolveB)
        b.ed4.putIncoming(atB, toB, keepA)
        a.ed4.putIncoming(atA, toA, keepB)
    }

    /** [mon]'s record as it lands in [to]: registered in its Pokédex, then evolved if it trade-evolves (the Pokédex told
     * again). The evolved one keeps its ability slot, its gender read against the new species' ratio. */
    private fun arrive(mon: Gen4BoxMon, to: TradeSide, evolve: Boolean): ByteArray {
        val e = to.ed4
        if (!mon.isEgg) e.registerCaught(mon)
        val evo = evolution(mon, to)?.takeIf { evolve } ?: return mon.encode()
        val g = e.game
        val abilities = g.abilities(evo.into)
        val gender = when (val r = g.genderRatio(evo.into)) {
            0 -> Gen3Gender.MALE; 254 -> Gen3Gender.FEMALE; 255 -> Gen3Gender.GENDERLESS
            else -> if ((mon.personality and 0xFF) < r) Gen3Gender.FEMALE else Gen3Gender.MALE
        }
        val renamed = !mon.nicknamed
        val record = mon.withChanges(
            species = evo.into,
            heldItem = if (evo.item != 0) 0 else null,
            ability = if (abilities.size == 2) abilities[(mon.personality and 1L).toInt()] else abilities[0],
            gender = gender,
            nickname = if (renamed) g.speciesName(evo.into)?.let { name -> runCatching { e.nameCodesFor(name) }.getOrNull() } else null,
        )
        e.registerCaught(Gen4BoxMon.read(record, 0, g))
        return record
    }
}
