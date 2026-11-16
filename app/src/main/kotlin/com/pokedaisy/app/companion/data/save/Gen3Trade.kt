package com.pokedaisy.app.companion.data.save

import com.pokedaisy.app.companion.data.speciesNames
import com.pokedaisy.app.companion.i18n.tr

/**
 * Trading between two saves, PC box to PC box, with the games' own rules (pret's trade.c
 * `CanTradeSelectedMon` / `GetUnionRoomTradeMessageId`, link.c) and what a trade does afterwards
 * (`UpdatePokedexForReceivedMon`, trade evolutions, Ruby / Sapphire's National Dex):
 *
 * - FireRed / LeafGreen link with Ruby / Sapphire / Emerald only once `FLAG_SYS_CAN_LINK_WITH_RS` is set.
 * - Before its National Dex, FireRed / LeafGreen / Emerald neither send nor take an egg or a Pokémon outside its
 *   regional dex (Kanto's 151 / Hoenn's 202). Ruby / Sapphire's own code has no such check, and trading with
 *   FireRed / LeafGreen / Emerald is what gives them their National Dex.
 * - Mew and Deoxys trade only with their fateful-encounter bit (an event's).
 * - Mail and the e-Reader's Enigma Berry keep data in their own save, so a Pokémon holding one stays.
 * - A party Pokémon trades too, as long as another one that isn't an egg stays in that party ([partyBlocker]).
 *
 * Nothing changes until every check of a trade has passed; then both [Gen3SaveEditor]s hold the result, to be
 * written together (`SaveFileEdits.writeBoth`).
 */
class TradeSide(
    val label: String,
    val editor: MonSaveEditor,
    /** The ROM's header game code (BPRE, AXVE, IPKE, ...). */
    val gameCode: String,
) {
    /** The rules of this side's generation (two saves of one generation trade). */
    val rules: TradeRules get() = when (editor) {
        is Gen5SaveEditor -> Gen5Trade
        is Gen4SaveEditor -> Gen4Trade
        else -> Gen3Trade
    }
    internal val ed3: Gen3SaveEditor get() = editor as Gen3SaveEditor
    val format: Gen3SaveFormat get() = ed3.file.format
    /** English text: a trade evolution renames by the English species name, so only English games evolve on arrival. */
    val english: Boolean get() = gameCode.endsWith("E")
}

object Gen3Trade : TradeRules {

    /** Why [mon] can't go from [from] to [to], or null if the games would trade it. */
    override fun blocker(mon: SaveMon, from: TradeSide, to: TradeSide): String? {
        if (mon.isEmpty) return null
        mon as Gen3BoxMon
        if (mon.isBadEgg) return tr("A Bad Egg can't be traded")
        linkBlocker(from, to)?.let { return it }
        for (side in listOf(from, to)) {
            if (side.format == Gen3SaveFormat.RUBY_SAPPHIRE || side.ed3.nationalDex) continue
            if (mon.isEgg) return tr("{0} has no National Dex yet, so no eggs", side.label)
            if (!inRegionalDex(mon.species, side.format)) {
                return tr("{0} has no National Dex yet, so no {1}", side.label, speciesLabel(mon.species))
            }
        }
        if ((mon.species == SPECIES_MEW || mon.species == SPECIES_DEOXYS) && !mon.fatefulEncounter) {
            return tr("This {0} isn't from an event, so the games won't trade it", speciesLabel(mon.species))
        }
        if (mon.heldItem in MAIL_ITEMS || mon.heldItem == ITEM_ENIGMA_BERRY) {
            return tr("Mail and Enigma Berries stay with their save: take it off in the game first")
        }
        return null
    }

    /** FireRed / LeafGreen and Ruby / Sapphire / Emerald can't link before the FireRed / LeafGreen side's Sevii Islands. */
    override fun linkBlocker(a: TradeSide, b: TradeSide): String? {
        val frlg = listOf(a, b).filter { it.format == Gen3SaveFormat.FIRERED_LEAFGREEN }
        if (frlg.size != 1) return null
        val side = frlg.single()
        return if (side.ed3.flag(side.format.linkRsFlag)) null
        else tr("{0} links with Ruby, Sapphire and Emerald only after the Sevii Islands", side.label)
    }

    fun inRegionalDex(species: Int, format: Gen3SaveFormat): Boolean =
        if (format.kanto) species in 1..KANTO_COUNT
        else speciesHoennDex.getOrNull(species)?.let { it in 1..HOENN_DEX_COUNT } == true

    /** The trade evolution [mon] would go through on arriving in [to] (GetEvolutionTargetSpecies, EVO_MODE_TRADE), or null. */
    override fun evolution(mon: SaveMon, to: TradeSide, partner: SaveMon?): TradeEvolution? {
        if (mon.isEmpty || mon.isBadEgg || mon.isEgg || !to.english) return null
        if (mon.heldItem == ITEM_EVERSTONE) return null
        return tradeEvolutions.firstOrNull { it.species == mon.species && (it.item == 0 || it.item == mon.heldItem) }
    }

    /**
     * The game's "last Pokémon" rule for a party Pokémon about to leave [side]'s party: another one that isn't an egg
     * must stay (trade.c counts the rest of the party, not what comes in). Null for a PC Pokémon or when it can go.
     */
    override fun partyBlocker(side: TradeSide, at: MonRef): String? {
        if (at !is MonRef.Party) return null
        val others = side.ed3.data.party.withIndex().count { (i, p) -> i != at.index && !p.mon.isEgg && !p.mon.isBadEgg }
        return if (others == 0) tr("That's {0}'s last Pokémon that isn't an egg: it stays in the party", side.label) else null
    }

    /** Sends the Pokémon at [src]'s [at] to the empty slot [to] of [dest]. Throws [TradeRefused] and changes nothing if the games wouldn't. */
    fun send(src: TradeSide, at: BoxSlot, dest: TradeSide, to: BoxSlot, evolve: Boolean = true) =
        send(src, MonRef.Pc(at) as MonRef, dest, MonRef.Pc(to) as MonRef, evolve)

    /**
     * Sends the Pokémon at [src]'s [at] (a PC slot or a party Pokémon) to [dest]'s empty [to]: an empty PC slot, or a
     * party slot past its last Pokémon (the end of the party). Throws [TradeRefused] and changes nothing if the games wouldn't.
     */
    override fun send(src: TradeSide, at: MonRef, dest: TradeSide, to: MonRef, evolve: Boolean) {
        val mon = src.ed3.monAt(at) ?: throw TradeRefused(tr("There's no Pokémon there"))
        when (to) {
            is MonRef.Pc -> if (!dest.ed3.data.mon(to.at).isEmpty) throw TradeRefused(tr("That slot isn't empty"))
            is MonRef.Party -> {
                val count = dest.ed3.data.partyCount
                if (to.index < count) throw TradeRefused(tr("That slot isn't empty"))
                if (count >= Gen3SaveData.PARTY_SIZE) throw TradeRefused(tr("{0}'s party is full", dest.label))
            }
        }
        blocker(mon, src, dest)?.let { throw TradeRefused(it) }
        partyBlocker(src, at)?.let { throw TradeRefused(it) }
        val keep = (at as? MonRef.Party)?.let { src.ed3.partyRecord(it.index) }
        val arriving = arrive(mon, dest, evolve)
        dest.ed3.putIncoming(to, arriving, keep)
        src.ed3.takeOut(at)
        afterTrade(src, dest)
    }

    /** Swaps the Pokémon at [a]'s [atA] and [b]'s [atB]; each side's trade evolution unless told otherwise. */
    fun swap(a: TradeSide, atA: BoxSlot, b: TradeSide, atB: BoxSlot, evolveA: Boolean = true, evolveB: Boolean = true) =
        swap(a, MonRef.Pc(atA) as MonRef, b, MonRef.Pc(atB) as MonRef, evolveA, evolveB)

    /** Swaps the Pokémon at [a]'s [atA] and [b]'s [atB] (PC or party, either side); each side's trade evolution unless told otherwise. */
    override fun swap(a: TradeSide, atA: MonRef, b: TradeSide, atB: MonRef, evolveA: Boolean, evolveB: Boolean) {
        val monA = a.ed3.monAt(atA)
        val monB = b.ed3.monAt(atB)
        if (monA == null || monB == null) throw TradeRefused(tr("There's no Pokémon there"))
        blocker(monA, a, b)?.let { throw TradeRefused(it) }
        blocker(monB, b, a)?.let { throw TradeRefused(it) }
        partyBlocker(a, atA)?.let { throw TradeRefused(it) }
        partyBlocker(b, atB)?.let { throw TradeRefused(it) }
        val keepA = (atA as? MonRef.Party)?.let { a.ed3.partyRecord(it.index) }
        val keepB = (atB as? MonRef.Party)?.let { b.ed3.partyRecord(it.index) }
        val toB = arrive(monA, b, evolveA)
        val toA = arrive(monB, a, evolveB)
        b.ed3.putIncoming(atB, toB, keepA)
        a.ed3.putIncoming(atA, toA, keepB)
        afterTrade(a, b)
    }

    /**
     * [mon]'s record as it lands in [to]: evolved if it trade-evolves (the item it needed used up, its name changed
     * when it was the species' own - EvolutionRenameMon), and [to]'s Pokédex told about it. Mutates [to]'s Pokédex.
     */
    private fun arrive(mon: Gen3BoxMon, to: TradeSide, evolve: Boolean): ByteArray {
        if (!mon.isEgg) to.ed3.markCaught(mon.species, mon.personality)
        val evo = evolution(mon, to)?.takeIf { evolve } ?: return mon.encode()
        val oldName = englishName(mon.species)
        val renamed = oldName != null && nicknameText(mon.nicknameBytes) == nicknameText(oldName)
        val record = mon.withChanges(
            species = evo.into,
            heldItem = if (evo.item != 0) 0 else null,
            nickname = if (renamed) englishName(evo.into) else null,
        )
        to.ed3.markCaught(evo.into, mon.personality)
        return record
    }

    /** Ruby / Sapphire get their National Dex from any trade with FireRed / LeafGreen / Emerald (pokeruby trade.c). */
    private fun afterTrade(a: TradeSide, b: TradeSide) {
        for ((side, partner) in listOf(a to b, b to a)) {
            if (side.format == Gen3SaveFormat.RUBY_SAPPHIRE && partner.format != Gen3SaveFormat.RUBY_SAPPHIRE && !side.ed3.nationalDex) {
                side.ed3.enableNationalDex()
            }
        }
    }

    /** The species' English name in the games' charset as gSpeciesNames stores it (caps, EOS, zero padding), or null. */
    internal fun englishName(species: Int): ByteArray? {
        val name = speciesNames[species]?.uppercase() ?: return null
        val out = ByteArray(Gen3BoxMon.NICKNAME_BYTES)
        if (name.length > out.size) return null
        name.forEachIndexed { i, c -> out[i] = (WESTERN[c] ?: return null).toByte() }
        if (name.length < out.size) out[name.length] = EOS.toByte()
        return out
    }

    /** A name as the game compares it: up to its EOS. */
    private fun nicknameText(b: ByteArray): List<Byte> = b.takeWhile { it != EOS.toByte() }

    private fun speciesLabel(species: Int) = speciesNames[species]?.uppercase() ?: "#$species"

    private const val EOS = 0xFF
    private const val KANTO_COUNT = 151

    // The species names' characters in the Western charset (charmap.txt).
    private val WESTERN: Map<Char, Int> = buildMap {
        put(' ', 0x00); put('É', 0x1B); put('.', 0xAD); put('-', 0xAE); put('♂', 0xB5); put('♀', 0xB6); put('\'', 0xB4)
        for (i in 0..9) put('0' + i, 0xA1 + i)
        for (i in 0 until 26) put('A' + i, 0xBB + i)
    }
}

/** What a generation's trades follow: the games' own rules and what a trade does after ([Gen3Trade], [Gen4Trade]). */
interface TradeRules {
    /** Why [mon] can't go from [from] to [to], or null if the games would trade it. */
    fun blocker(mon: SaveMon, from: TradeSide, to: TradeSide): String?
    /** Why the two games can't link at all, or null. */
    fun linkBlocker(a: TradeSide, b: TradeSide): String?
    /** The "last Pokémon" rule for a party Pokémon about to leave [side]'s party. */
    fun partyBlocker(side: TradeSide, at: MonRef): String?
    /** The evolution [mon] would go through on arriving in [to] (traded for [partner], when it's a swap), or null. */
    fun evolution(mon: SaveMon, to: TradeSide, partner: SaveMon? = null): TradeEvolution?
    /** [src]'s Pokémon at [at] to [dest]'s empty [to]; throws [TradeRefused] and changes nothing if the games wouldn't. */
    fun send(src: TradeSide, at: MonRef, dest: TradeSide, to: MonRef, evolve: Boolean = true)
    fun swap(a: TradeSide, atA: MonRef, b: TradeSide, atB: MonRef, evolveA: Boolean = true, evolveB: Boolean = true)
}

/** A trade the games wouldn't allow, with the reason to show. */
class TradeRefused(message: String) : SaveEditRefused(message)
