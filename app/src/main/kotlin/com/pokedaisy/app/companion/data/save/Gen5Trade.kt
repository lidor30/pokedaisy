package com.pokedaisy.app.companion.data.save

import com.pokedaisy.app.companion.i18n.tr

/**
 * Trading between two Black / White / Black 2 / White 2 saves, the way their link trade does it: the received Pokémon
 * registered in the Pokédex ([Gen5SaveEditor.registerCaught]) and the trade evolutions the cart lists (a trade, a trade
 * holding its item, which is used up, and Karrablast and Shelmet traded for each other; an Everstone stops one), renamed to
 * the new species' name when it wasn't nicknamed. An item Black / White don't have (their sequels' newer ones) can't go
 * there; a Pokémon holding mail keeps its letter, so it can only go into the other party. Nothing changes until every
 * check has passed.
 */
object Gen5Trade : TradeRules {
    private val TradeSide.ed5: Gen5SaveEditor get() = editor as Gen5SaveEditor

    override fun blocker(mon: SaveMon, from: TradeSide, to: TradeSide): String? {
        if (mon.isEmpty) return null
        if (mon.isBadEgg) return tr("A Bad Egg can't be traded")
        if (!to.ed5.game.hasItem(mon.heldItem)) return tr("{0} doesn't have the item it holds", to.label)
        return null
    }

    override fun linkBlocker(a: TradeSide, b: TradeSide): String? = null

    override fun partyBlocker(side: TradeSide, at: MonRef): String? {
        if (at !is MonRef.Party) return null
        val others = side.ed5.data.partyMons.withIndex().count { (i, p) -> i != at.index && !p.mon.isEgg && !p.mon.isBadEgg }
        return if (others == 0) tr("That's {0}'s last Pokémon that isn't an egg: it stays in the party", side.label) else null
    }

    override fun evolution(mon: SaveMon, to: TradeSide, partner: SaveMon?): TradeEvolution? {
        if (mon.isEmpty || mon.isBadEgg || mon.isEgg) return null
        val other = partner?.takeIf { !it.isEmpty && !it.isEgg && !it.isBadEgg }?.species
        return to.ed5.game.tradeEvolution(mon.species, mon.heldItem, other)
    }

    private fun mailBlocker(mon: SaveMon, to: MonRef): String? =
        if (to is MonRef.Pc && mon.game.isMail(mon.heldItem)) tr("A Pokémon holding mail can't go in the PC") else null

    override fun send(src: TradeSide, at: MonRef, dest: TradeSide, to: MonRef, evolve: Boolean) {
        val mon = src.ed5.monAt(at) ?: throw TradeRefused(tr("There's no Pokémon there"))
        when (to) {
            is MonRef.Pc -> if (!dest.ed5.data.mon(to.at).isEmpty) throw TradeRefused(tr("That slot isn't empty"))
            is MonRef.Party -> {
                val count = dest.ed5.data.partyCount
                if (to.index < count) throw TradeRefused(tr("That slot isn't empty"))
                if (count >= 6) throw TradeRefused(tr("{0}'s party is full", dest.label))
            }
        }
        blocker(mon, src, dest)?.let { throw TradeRefused(it) }
        partyBlocker(src, at)?.let { throw TradeRefused(it) }
        mailBlocker(mon, to)?.let { throw TradeRefused(it) }
        val keep = (at as? MonRef.Party)?.let { src.ed5.partyRecord(it.index) }
        val arriving = arrive(mon, dest, evolve, partner = null)
        dest.ed5.putIncoming(to, arriving, keep)
        src.ed5.takeOut(at)
    }

    override fun swap(a: TradeSide, atA: MonRef, b: TradeSide, atB: MonRef, evolveA: Boolean, evolveB: Boolean) {
        val monA = a.ed5.monAt(atA)
        val monB = b.ed5.monAt(atB)
        if (monA == null || monB == null) throw TradeRefused(tr("There's no Pokémon there"))
        blocker(monA, a, b)?.let { throw TradeRefused(it) }
        blocker(monB, b, a)?.let { throw TradeRefused(it) }
        partyBlocker(a, atA)?.let { throw TradeRefused(it) }
        partyBlocker(b, atB)?.let { throw TradeRefused(it) }
        mailBlocker(monA, atB)?.let { throw TradeRefused(it) }
        mailBlocker(monB, atA)?.let { throw TradeRefused(it) }
        val keepA = (atA as? MonRef.Party)?.let { a.ed5.partyRecord(it.index) }
        val keepB = (atB as? MonRef.Party)?.let { b.ed5.partyRecord(it.index) }
        val toB = arrive(monA, b, evolveA, partner = monB)
        val toA = arrive(monB, a, evolveB, partner = monA)
        b.ed5.putIncoming(atB, toB, keepA)
        a.ed5.putIncoming(atA, toA, keepB)
    }

    /**
     * [mon]'s record as it lands in [to]: registered in its Pokédex, then evolved if the trade sets one off (the Pokédex
     * told again). The evolved one keeps its ability slot (personality bit 16; a hidden one becomes the new species'
     * hidden one), its gender read against the new species' ratio.
     */
    private fun arrive(mon: Gen5BoxMon, to: TradeSide, evolve: Boolean, partner: SaveMon?): ByteArray {
        val e = to.ed5
        if (!mon.isEgg) e.registerCaught(mon)
        val evo = evolution(mon, to, partner)?.takeIf { evolve } ?: return mon.encode()
        val g = e.game
        val abilities = g.abilities(evo.into)
        val ability = when {
            mon.hiddenAbility -> g.hiddenAbility(evo.into).takeIf { it != 0 } ?: abilities[0]
            abilities.size == 2 -> abilities[((mon.personality shr 16) and 1L).toInt()]
            else -> abilities[0]
        }
        val gender = when (val r = g.genderRatio(evo.into)) {
            0 -> Gen3Gender.MALE; 254 -> Gen3Gender.FEMALE; 255 -> Gen3Gender.GENDERLESS
            else -> if ((mon.personality and 0xFF) < r) Gen3Gender.FEMALE else Gen3Gender.MALE
        }
        val record = mon.withChanges(
            species = evo.into,
            heldItem = if (evo.item != 0) 0 else null,
            ability = ability,
            gender = gender,
            nickname = if (!mon.nicknamed) g.speciesName(evo.into)?.let { e.nameCodesFor(it) } else null,
        )
        e.registerCaught(Gen5BoxMon.read(record, 0, g))
        return record
    }
}
