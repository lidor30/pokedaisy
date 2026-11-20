package com.pokedaisy.app.companion.data.coop

import com.pokedaisy.app.companion.data.VANILLA_PARTY_MON
import com.pokedaisy.app.companion.data.decodePartyMon
import com.pokedaisy.app.companion.data.vanillaMoveStats
import com.pokedaisy.app.companion.data.save.Gen3BoxMon

/** Whether a partner's Pokémon may join a co-op battle, and why not. */
sealed interface CoopMonCheck {
    object Ok : CoopMonCheck
    data class Refused(val reason: String) : CoopMonCheck
}

/**
 * The Kotlin side of an Emerald co-op battle: the partner's Pokémon checked before they go into the link battle, and the
 * party slots a team is picked from. Pokémon are retail Emerald's 100-byte struct Pokemon, read with the save editor's [Gen3BoxMon] (checksum, egg bit) and [decodePartyMon].
 */
object CoopBattle {
    const val MON_SIZE = 100

    /** The highest species id Emerald has (SPECIES_CHIMECHO, after the 25 unused slots). */
    private const val MAX_SPECIES = 411
    /** Emerald's unused "?" species slots (SPECIES_OLD_UNOWN_B..Z, 25 of them, between Celebi and Treecko). */
    private val UNUSED_SPECIES = 252..276
    /** The last item id Emerald has (ITEMS_COUNT - 1). */
    private const val MAX_ITEM = 376
    private const val PARTNER_SLOTS = 3

    /**
     * The only gate for a partner's untrusted bytes: the checksum holds, it isn't an egg, species 1..411 and not one of
     * the unused slots, a held item up to 376, every move a real Emerald move (id up to 354) with PP no higher than its
     * base PP raised by the PP Ups (base * (5 + bonus) / 5, bonus 0..3), level 1..100 and HP no more than max HP. The
     * stats are not recomputed from the species' base stats: no helper for that is in the repo for a struct Pokemon.
     */
    fun check(mon: ByteArray): CoopMonCheck {
        if (mon.size < MON_SIZE) return CoopMonCheck.Refused("short record")
        val box = Gen3BoxMon.read(mon, 0)
        if (box.isBadEgg) return CoopMonCheck.Refused("checksum")
        if (isEgg(mon, box)) return CoopMonCheck.Refused("egg")
        if (box.species !in 1..MAX_SPECIES) return CoopMonCheck.Refused("species ${box.species}")
        if (box.species in UNUSED_SPECIES) return CoopMonCheck.Refused("species ${box.species}")
        if (box.heldItem > MAX_ITEM) return CoopMonCheck.Refused("item ${box.heldItem}")
        for (i in 0 until 4) {
            val move = box.moves[i]
            if (move >= vanillaMoveStats.size) return CoopMonCheck.Refused("move $move")
            val bonus = (box.ppBonuses shr (2 * i)) and 3
            val maxPp = vanillaMoveStats[move].pp * (5 + bonus) / 5
            if (box.pp[i] > maxPp) return CoopMonCheck.Refused("PP ${box.pp[i]} over $maxPp for move $move")
        }
        val m = decodePartyMon(mon, 0) ?: return CoopMonCheck.Refused("empty")
        if (m.level !in 1..100) return CoopMonCheck.Refused("level ${m.level}")
        if (m.hp > m.maxHp) return CoopMonCheck.Refused("HP ${m.hp} over ${m.maxHp}")
        return CoopMonCheck.Ok
    }

    /**
     * The first three of [count] party mons that can fight (HP above 0, [check] passes: a bad checksum, an egg or an
     * out-of-range species is skipped, never sent), packed [MON_SIZE] bytes each. The team the partner answers a join offer with
     * (see [armArguments]).
     */
    fun firstThreeAble(party: ByteArray, count: Int): ByteArray {
        val slots = ableSlots(party, count)
        val out = ByteArray(slots.size * MON_SIZE)
        slots.forEachIndexed { i, slot -> party.copyInto(out, i * MON_SIZE, slot * MON_SIZE, (slot + 1) * MON_SIZE) }
        return out
    }

    /** The party indexes [firstThreeAble] packs, in order (at most three). */
    fun ableSlots(party: ByteArray, count: Int): IntArray =
        (0 until count.coerceIn(0, 6))
            .filter { (it + 1) * MON_SIZE <= party.size }
            .filter { slot ->
                val mon = party.copyOfRange(slot * MON_SIZE, (slot + 1) * MON_SIZE)
                check(mon) == CoopMonCheck.Ok && (decodePartyMon(mon, 0)?.hp ?: 0) > 0
            }
            .take(PARTNER_SLOTS)
            .toIntArray()

    /** The team an ANSWER carries: the packed [partner] Pokémon (100 bytes each) and their count (= size / [MON_SIZE]). */
    class ArmArguments(val partner: ByteArray, val count: Int)

    /** The team for the first three able Pokémon of [party], or null when none can fight. */
    fun armArguments(party: ByteArray, count: Int): ArmArguments? {
        val packed = firstThreeAble(party, count)
        return if (packed.isEmpty()) null else ArmArguments(packed, packed.size / MON_SIZE)
    }

    /** The Misc substruct's isEgg, or the box flags' copy of it (the game keeps both). */
    private fun isEgg(mon: ByteArray, box: Gen3BoxMon): Boolean =
        box.isEgg || (mon[VANILLA_PARTY_MON.flags].toInt() and VANILLA_PARTY_MON.eggMask) != 0
}
