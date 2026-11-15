package com.pokedaisy.app.companion.data.save

import com.pokedaisy.app.companion.i18n.tr

/**
 * The event items Nintendo handed out by Mystery Gift, Mystery Event or the e-Reader, which the games' own ports honour
 * (pret's LilycoveCity_Harbor / VermilionCity scripts): the key item plus the flag that lets the ferry sail there. Giving
 * one does what its distribution script did (gift_aurora_ticket.inc & co.: giveitem, setflag the ship, setflag "received"),
 * and only while that script would have: not if the save holds the item, got it before, or has met that Pokémon.
 */
enum class Gen3Event(
    val item: Int,
    /** Per game family: the flags the distribution sets (the ship's first), and the flags that mean it's been done. */
    private val flags: Map<Gen3SaveFormat, Flags>,
) {
    EON_TICKET(
        275, mapOf(
            // pokeruby: FLAG_SYS_HAS_EON_TICKET. pokeemerald: FLAG_ENABLE_SHIP_SOUTHERN_ISLAND (CableClub_EventScript_DistributeEonTicket).
            Gen3SaveFormat.RUBY_SAPPHIRE to Flags(set = listOf(0x853)),
            Gen3SaveFormat.EMERALD to Flags(set = listOf(0x8B3), done = listOf(0x1C9)),   // FLAG_CAUGHT_LATIAS_OR_LATIOS
        ),
    ),
    AURORA_TICKET(
        371, mapOf(
            // FLAG_ENABLE_SHIP_BIRTH_ISLAND, FLAG_RECEIVED_AURORA_TICKET; done: FLAG_BATTLED_DEOXYS / FLAG_FOUGHT_DEOXYS.
            Gen3SaveFormat.EMERALD to Flags(set = listOf(0x8D5, 0x13A), done = listOf(0x1AD)),
            Gen3SaveFormat.FIRERED_LEAFGREEN to Flags(set = listOf(0x84B, 0x2A7), done = listOf(0x2E4)),
        ),
    ),
    MYSTIC_TICKET(
        370, mapOf(
            // FLAG_ENABLE_SHIP_NAVEL_ROCK, FLAG_RECEIVED_MYSTIC_TICKET; done: Lugia / Ho-Oh caught (Emerald) or fought (FR / LG).
            Gen3SaveFormat.EMERALD to Flags(set = listOf(0x8E0, 0x13B), done = listOf(0x91, 0x92)),
            Gen3SaveFormat.FIRERED_LEAFGREEN to Flags(set = listOf(0x84A, 0x2A8), done = listOf(0x2F2, 0x2F3)),
        ),
    ),
    OLD_SEA_MAP(
        376, mapOf(
            // FLAG_ENABLE_SHIP_FARAWAY_ISLAND, FLAG_RECEIVED_OLD_SEA_MAP; done: FLAG_CAUGHT_MEW.
            Gen3SaveFormat.EMERALD to Flags(set = listOf(0x8D6, 0x13C), done = listOf(0x1CA)),
        ),
    ),
    ;

    class Flags(val set: List<Int>, val done: List<Int> = emptyList())

    enum class State {
        /** The save can be given it. */
        AVAILABLE,
        /** It holds the item (or the ship's flag): the trip is open. */
        HAS,
        /** It got it once (the received flag) or met the Pokémon already. */
        DONE,
    }

    val formats: Set<Gen3SaveFormat> get() = flags.keys

    internal fun setFlags(f: Gen3SaveFormat): List<Int> = flags.getValue(f).set

    internal fun state(f: Gen3SaveFormat, data: Gen3SaveData): State {
        val fl = flags.getValue(f)
        if (data.hasKeyItem(item) || data.flag(fl.set.first())) return State.HAS
        if (fl.set.drop(1).any(data::flag) || fl.done.any(data::flag)) return State.DONE
        return State.AVAILABLE
    }

    /** When the port's ferry takes it: after the Hall of Fame (FireRed / LeafGreen's Seagallop: with the Rainbow Pass). */
    fun note(f: Gen3SaveFormat): String =
        if (f == Gen3SaveFormat.FIRERED_LEAFGREEN) tr("Show it at the port once you have the Rainbow Pass")
        else tr("Show it at the port after the Hall of Fame")

    companion object {
        fun forFormat(f: Gen3SaveFormat): List<Gen3Event> = entries.filter { f in it.formats }
    }
}
