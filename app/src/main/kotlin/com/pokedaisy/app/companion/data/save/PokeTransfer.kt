package com.pokedaisy.app.companion.data.save

import com.pokedaisy.app.companion.i18n.tr
import kotlinx.datetime.LocalDate
import kotlinx.datetime.number

/**
 * POKé TRANSFER: six Pokémon from a HeartGold / SoulSilver / Platinum save's PC into a Black / White (or Black 2 /
 * White 2) save's PC, the way Route 15's Poké Transfer Lab does it. Its rules: the Gen 5 game has its National Pokédex,
 * both games are in one language, six go at once from the boxes; no egg, no Pokémon that knows an HM (the sending game's
 * HMs: Defog in Diamond / Pearl / Platinum, Whirlpool in HeartGold / SoulSilver), no Spiky-eared Pichu. A held item goes
 * back into the Gen 4 game's bag, so the Pokémon arrives holding nothing.
 *
 * Each one is the same Pokémon with what the transfer changes (PKHeX's PK4 -> PK5, with the game's own date): its
 * friendship back to 70, its PP full, the nature written in its own byte, met at Poké Transfer (30001) today at its
 * level, the HeartGold / SoulSilver ball moved to the Gen 5 ball byte, the Pt / HGSS location bytes cleared, its names in
 * Gen 5's text (UTF-16), Arceus' and Giratina's forms to their own without an item, Shedinja genderless.
 */
object PokeTransfer {
    const val COUNT = 6
    const val MET_LOCATION = 30001
    private const val FRIENDSHIP = 70
    private const val PICHU = 172
    private const val ARCEUS = 493
    private const val GIRATINA = 487
    private const val SHEDINJA = 292

    /** Diamond / Pearl / Platinum's HMs; HeartGold / SoulSilver have Whirlpool (250) instead of Defog (432). */
    private val HMS_SINNOH = setOf(15, 19, 57, 70, 432, 249, 127, 431)
    private val HMS_JOHTO = setOf(15, 19, 57, 70, 250, 249, 127, 431)

    /** Why [mon] can't be transferred from a [game] save, or null. */
    fun blocker(mon: Gen4BoxMon, game: Gen4GameData): String? {
        if (mon.isEmpty) return null
        if (mon.isBadEgg) return tr("A Bad Egg can't be transferred")
        if (mon.isEgg) return tr("An egg can't be transferred")
        if (mon.species == PICHU && mon.form == 1) return tr("This Pichu can't be transferred")
        val hms = if (game.layout == Gen4Layout.HGSS) HMS_JOHTO else HMS_SINNOH
        if (mon.moves.any { it in hms }) return tr("A Pokémon that knows an HM can't be transferred")
        return null
    }

    /** Why the two saves can't use Poké Transfer at all (the National Pokédex, the language), or null. */
    fun linkBlocker(gen4: Gen4SaveEditor, gen4Label: String, gen5: Gen5SaveEditor, gen5Label: String): String? {
        if (!gen5.data.nationalDex) return tr("{0} needs its National Pokédex for Poké Transfer", gen5Label)
        if (gen4.game.gameLanguage != gen5.game.gameLanguage) return tr("Poké Transfer only takes a game in {0}'s language", gen5Label)
        return null
    }

    /**
     * Transfers the six Pokémon at [picks] of [gen4]'s PC into [gen5]'s first free places, [today]: their items into
     * [gen4]'s bag, the Pokédex told, the Pokémon gone from [gen4]. Checks everything first: throws [SaveEditRefused] and
     * changes nothing if the game wouldn't. Returns where they landed.
     */
    fun transfer(
        gen4: Gen4SaveEditor, gen4Label: String, picks: List<BoxSlot>, gen5: Gen5SaveEditor, gen5Label: String, today: LocalDate,
    ): List<BoxSlot> {
        linkBlocker(gen4, gen4Label, gen5, gen5Label)?.let { throw SaveEditRefused(it) }
        if (picks.size != COUNT || picks.toSet().size != COUNT) throw SaveEditRefused(tr("Pick six Pokémon"))
        val mons = picks.map { gen4.data.mon(it) }
        if (mons.any { it.isEmpty }) throw SaveEditRefused(tr("There's no Pokémon there"))
        mons.firstNotNullOfOrNull { blocker(it, gen4.game) }?.let { throw SaveEditRefused(it) }
        val to = gen5.freePlaces(COUNT)
        if (to.size < COUNT) throw SaveEditRefused(tr("{0}'s PC has no room for six more", gen5Label))
        val records = mons.map { convert(it, gen4.game, today) }
        val keep4 = gen4.snapshot()
        val keep5 = gen5.snapshot()
        try {
            for (mon in mons) if (mon.heldItem != 0 && !gen4.returnToBag(mon.heldItem)) {
                throw SaveEditRefused(tr("{0}'s bag has no room for the items they hold", gen4Label))
            }
            records.zip(to).forEach { (record, at) ->
                gen5.putRecord(at, record)
                gen5.registerCaught(Gen5BoxMon.read(record, 0, gen5.game))
            }
            picks.forEach { gen4.takeOut(MonRef.Pc(it)) }
        } catch (e: SaveEditRefused) {
            gen4.restore(keep4)
            gen5.restore(keep5)
            throw e
        }
        return to
    }

    /** [mon]'s 136-byte Gen 5 record, as the transfer makes it. */
    fun convert(mon: Gen4BoxMon, game: Gen4GameData, today: LocalDate): ByteArray {
        val (n, ok) = Gen4BoxMon.decode(mon.encode(), 0) ?: error("an empty slot")
        require(ok) { "an unreadable record" }
        val a = 0; val b = 32; val c = 64; val d = 96
        n[a + 0x0C] = FRIENDSHIP.toByte()
        putU16(n, a + 2, 0)   // its item went back to the bag
        // Arceus' type and Giratina's Origin Forme come from what they hold: without it, their own form.
        if (mon.species == ARCEUS || mon.species == GIRATINA) n[b + 0x18] = (n[b + 0x18].toInt() and 0x07).toByte()
        if (mon.species == SHEDINJA) n[b + 0x18] = ((n[b + 0x18].toInt() and 0xF9) or (2 shl 1)).toByte()
        for (i in 0 until 4) {
            val move = u16(n, b + i * 2)
            n[b + 8 + i] = (if (move == 0) 0 else game.ppWithBonus(move, n[b + 0x0C + i].toInt() and 3)).toByte()
        }
        n[b + 0x19] = (mon.personality % 25).toByte()   // the nature's own byte
        for (i in 0x1A..0x1F) n[b + i] = 0              // hidden ability / sparkle flags, Pt / HGSS locations
        // Met at Poké Transfer, today, at its level; the HeartGold / SoulSilver ball byte moved to Gen 5's.
        putU16(n, d + 0x18, MET_LOCATION)
        n[d + 0x13] = (today.year - 2000).toByte(); n[d + 0x14] = today.month.number.toByte(); n[d + 0x15] = today.day.toByte()
        n[d + 0x1C] = ((n[d + 0x1C].toInt() and 0x80) or mon.level).toByte()
        val ball = maxOf(n[d + 0x1B].toInt() and 0xFF, n[d + 0x1E].toInt() and 0xFF)
        n[d + 0x1B] = ball.toByte()
        n[d + 0x1E] = 0; n[d + 0x1F] = 0
        writeName(n, c, 11, gen4String(n, c, 11))
        writeName(n, d, 8, gen4String(n, d, 8))
        return Gen4BoxMon.build(mon.personality, n)
    }

    /** [text] as Gen 5 writes a name: UTF-16, the 0xFFFF end, zeroes after; ♂ / ♀ as 0x246D / 0x246E. */
    private fun writeName(n: ByteArray, off: Int, chars: Int, text: String) {
        for (i in 0 until chars) putU16(n, off + i * 2, 0)
        val codes = text.take(chars - 1).map { ch -> when (ch) { '♂' -> 0x246D; '♀' -> 0x246E; else -> ch.code } }
        codes.forEachIndexed { i, ch -> putU16(n, off + i * 2, ch) }
        putU16(n, off + codes.size * 2, 0xFFFF)
    }
}
