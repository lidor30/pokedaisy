package com.pokedaisy.app.companion.data.save

import com.pokedaisy.app.companion.i18n.tr
import java.time.LocalDate

/**
 * PAL PARK: six Pokémon from a GBA game's PC brought into HeartGold / SoulSilver / Platinum, the way the DS games' own
 * migration and Pal Park do it (pret/pokeplatinum's gba_migrator.c / gba_pokemon.c, scrcmd_catching_show.c; HeartGold's
 * scrcmd_12.c does the same):
 *
 * - The DS game offers it once it has the National Pokédex, and only with a GBA game in its own language
 *   (main_menu.c); the GBA game's PC must hold at least six Pokémon.
 * - Exactly six go, from the PC boxes (never the party). An egg can't, nor one that knows an HM, holds mail or a key
 *   item ([palParkRefusedItems]), or is no real species.
 * - Each is made the migration's way ([BoxMonGBAToBoxMon][convert]): the same personality, OT, EXP, IVs, EVs, moves (full
 *   PP), contest stats, markings, ribbons and ball, friendship back to 70, the item and the names in the DS game's codes,
 *   its ability by its Gen 3 ability bit (the species that got a second ability in Gen 4 keep their first). Caught in the
 *   park, it's then stored with "met at Pal Park" today at its current level (UpdateMonStatusAndTrainerInfo's case 2),
 *   in the first free PC slot from the box the PC is on, and registered as caught.
 * - The GBA game's six are gone from its PC, so both saves are written together.
 *
 * What PokeDaisy leaves out: the day's wait between two sixes from one GBA game, catching them in the park (all six are
 * always caught there in the end), and the DS game's own counts (trainer score, the Pokétch's history).
 */
object PalPark {
    const val COUNT = 6
    const val MET_LOCATION = 55

    /** Why [mon] can't migrate, or null. */
    fun blocker(mon: Gen3BoxMon): String? = when {
        mon.isEmpty -> null
        mon.isBadEgg -> tr("A Bad Egg can't go to Pal Park")
        mon.isEgg -> tr("An egg can't go to Pal Park")
        mon.moves.any { it in palParkHms } -> tr("A Pokémon that knows an HM can't go to Pal Park")
        mon.heldItem in palParkRefusedItems -> tr("A Pokémon holding mail or a key item can't go to Pal Park")
        Gen3Species.nationalDex(mon.species) == null -> tr("This Pokémon can't go to Pal Park")
        else -> null
    }

    /** Why the two games can't use Pal Park at all, or null. */
    fun linkBlocker(gba: Gen3SaveEditor, gbaGameCode: String, gbaLabel: String, ds: Gen4SaveEditor, dsLabel: String): String? {
        if (!ds.nationalDex) return tr("{0} needs its National Pokédex for Pal Park", dsLabel)
        if (languageOf(gbaGameCode) != ds.game.gameLanguage) return tr("Pal Park only takes a GBA game in {0}'s language", dsLabel)
        val inPc = (0 until gba.data.boxCount).sumOf { b -> gba.data.boxMons(b).count { !it.isEmpty } }
        if (inPc < COUNT) return tr("{0}'s PC needs at least six Pokémon", gbaLabel)
        return null
    }

    /** A GBA cart's language (LANGUAGE_*) from the last letter of its game code. */
    fun languageOf(gameCode: String): Int = when (gameCode.lastOrNull()) {
        'J' -> 1; 'E' -> 2; 'F' -> 3; 'I' -> 4; 'D' -> 5; 'S' -> 7
        else -> 2
    }

    /** The GBA cart's version (Deoxys' form follows it): Ruby, Sapphire, Emerald, FireRed, LeafGreen. */
    fun versionOf(gameCode: String): Int = when (gameCode.take(3)) {
        "AXV" -> 2; "AXP" -> 1; "BPE" -> 3; "BPR" -> 4; "BPG" -> 5
        else -> 2
    }

    /** The DS PC slots the six go to: the first free ones from the box the PC is on, wrapping (PCBoxes_TryStoreBoxMon). */
    fun destinations(ds: Gen4SaveEditor): List<BoxSlot> {
        val d = ds.data
        val start = d.currentBox
        return (0 until Gen4SaveData.BOXES).flatMap { i ->
            val box = (start + i) % Gen4SaveData.BOXES
            (0 until Gen4SaveData.SLOTS).map { BoxSlot(box, it) }
        }.filter { d.mon(it).isEmpty }
    }

    /**
     * Migrates the six at [picks] in [gba]'s PC into [ds], both editors changed only if every check passes. The DS PC
     * slots they went to come back, in the picks' order.
     */
    fun migrate(
        gba: Gen3SaveEditor, gbaGameCode: String, gbaLabel: String, picks: List<BoxSlot>,
        ds: Gen4SaveEditor, dsLabel: String, today: LocalDate = LocalDate.now(),
    ): List<BoxSlot> {
        linkBlocker(gba, gbaGameCode, gbaLabel, ds, dsLabel)?.let { throw SaveEditRefused(it) }
        if (picks.size != COUNT || picks.toSet().size != COUNT) throw SaveEditRefused(tr("Pick six Pokémon"))
        val mons = picks.map { at -> gba.data.mon(at).also { if (it.isEmpty) throw SaveEditRefused(tr("There's no Pokémon there")) } }
        mons.forEach { m -> blocker(m)?.let { throw SaveEditRefused(it) } }
        val to = destinations(ds).take(COUNT)
        if (to.size < COUNT) throw SaveEditRefused(tr("{0}'s PC has no room for six more", dsLabel))
        val version = versionOf(gbaGameCode)
        val records = mons.map { convert(it, version, ds.game, today) }
        records.forEachIndexed { i, r ->
            ds.putIncoming(MonRef.Pc(to[i]), r)
            ds.registerCaught(Gen4BoxMon.read(r, 0, ds.game))
        }
        picks.forEach { gba.takeOut(MonRef.Pc(it)) }
        return to
    }

    /** [mon]'s record as it lands in a DS game's PC ([game]: its species' data and language) from a [gbaVersion] cart. */
    fun convert(mon: Gen3BoxMon, gbaVersion: Int, game: Gen4GameData, today: LocalDate): ByteArray {
        require(!mon.isEmpty && !mon.isBadEgg && !mon.isEgg)
        val species = requireNotNull(Gen3Species.nationalDex(mon.species)) { "species ${mon.species}" }
        val pid = mon.personality
        val d = ByteArray(128)
        val a = 0
        val b = 32
        val c = 64
        val dd = 96
        // Block A
        putU16(d, a, species)
        putU16(d, a + 2, if (mon.heldItem != 0) palParkItems.getOrElse(mon.heldItem) { 0 } else 0)
        putU32(d, a + 4, mon.otId)
        putU32(d, a + 8, mon.exp)
        d[a + 0x0C] = BASE_FRIENDSHIP.toByte()
        d[a + 0x0D] = ability(species, mon.abilityNum, game).toByte()
        d[a + 0x0E] = mon.markings.toByte()
        d[a + 0x0F] = mon.language.toByte()
        mon.evs.forEachIndexed { i, v -> d[a + 0x10 + i] = v.toByte() }
        mon.contest.forEachIndexed { i, v -> d[a + 0x16 + i] = v.toByte() }
        // Block B: moves at full PP with their PP Ups.
        for (i in 0 until 4) {
            val move = mon.moves[i]
            val ups = (mon.ppBonuses shr (i * 2)) and 3
            putU16(d, b + i * 2, move)
            d[b + 0x0C + i] = ups.toByte()
            d[b + 8 + i] = (if (move == 0) 0 else game.ppWithBonus(move, ups)).toByte()
        }
        var ivWord = 0L
        mon.ivs.forEachIndexed { i, v -> ivWord = ivWord or (v.toLong() shl (i * 5)) }
        val name = convertName(mon.nicknameBytes, 10, mon.language)
        // MON_DATA_NICKNAME_AND_FLAG: a nickname unless it's the species' own name in the DS game's text; one from a GBA
        // game in another language counts as one whatever it says.
        val own = game.speciesName(species)?.let { com.pokedaisy.app.companion.data.Gen4Text.encode(it)?.toList() }
        val nicknamed = (own?.let { it != name.codeList } ?: (name.text != game.speciesName(species))) || mon.language != game.gameLanguage
        if (nicknamed) ivWord = ivWord or (1L shl 31)
        putU32(d, b + 0x10, ivWord)
        putU32(d, b + 0x14, ribbons(mon.ribbons))
        val gender = when (val r = game.genderRatio(species)) {
            0 -> 0; 254 -> 1; 255 -> 2
            else -> if ((pid and 0xFF) < r) 1 else 0
        }
        val form = when (species) {
            UNOWN -> Gen3Personality.unownLetter(pid)
            DEOXYS -> when (gbaVersion) { 4 -> 1; 5 -> 2; 3 -> 3; else -> 0 }
            else -> 0
        }
        d[b + 0x18] = ((if (mon.fatefulEncounter) 1 else 0) or (gender shl 1) or (form shl 3)).toByte()
        putU16(d, b + 0x1E, MET_LOCATION)   // MetLocation_PtHGSS (egg location: none)
        // Block C
        name.codes.copyInto(d, c)
        d[c + 0x17] = mon.metGame.toByte()
        // Block D
        convertName(mon.otNameBytes, 7, mon.language).codes.copyInto(d, dd, 0, 16)
        d[dd + 0x13] = (today.year - 2000).toByte()
        d[dd + 0x14] = today.monthValue.toByte()
        d[dd + 0x15] = today.dayOfMonth.toByte()
        putU16(d, dd + 0x18, MET_LOCATION)  // MetLocation_DP
        d[dd + 0x1A] = mon.pokerus.toByte()
        d[dd + 0x1B] = mon.ball.toByte()
        d[dd + 0x1C] = ((game.levelFor(species, mon.exp) and 0x7F) or (mon.otGender shl 7)).toByte()
        // HeartGold / SoulSilver keep the ball a second time (MON_DATA_POKEBALL sets HGSS_Pokeball too).
        if (game.layout == Gen4Layout.HGSS) d[dd + 0x1E] = mon.ball.toByte()
        return Gen4BoxMon.build(pid, d)
    }

    /** ConvertBoxMonGBAtoDSAbility. */
    private fun ability(species: Int, abilityNum: Int, game: Gen4GameData): Int {
        val abilities = game.abilities(species)
        if (abilities.size < 2) return abilities[0]
        return if (species in palParkNewAbilitySpecies || abilityNum and 1 == 0) abilities[0] else abilities[1]
    }

    /** The GBA ribbons as the DS games keep them: a contest rank of N sets that category's first N bits (4 each). */
    private fun ribbons(gba: Long): Long {
        var out = 0L
        for (cat in 0 until 5) {
            val rank = ((gba shr (cat * 3)) and 7).toInt()
            if (rank <= 4) for (i in 0 until rank) out = out or (1L shl (cat * 4 + i))
        }
        // Champion .. World: GBA bits 15-26 to DS bits 20-31.
        for (i in 0 until 12) if ((gba shr (15 + i)) and 1L == 1L) out = out or (1L shl (20 + i))
        return out and 0xFFFFFFFFL
    }

    /** A converted name: its [codes] as stored (end code, zeroes), the characters alone ([codeList]) and as text. */
    class Name(val codes: ByteArray, val codeList: List<Int>, val text: String)

    /**
     * GBA_ConvertStringToDS: [max] GBA bytes (up to 0xFF) as DS codes, the space and double quotes as [language] writes
     * them; a byte past the table turns the name into ten question marks. 11 codes for a nickname, 8 for an OT's name: the
     * end code after the characters, zeroes after it.
     */
    fun convertName(bytes: ByteArray, max: Int, language: Int): Name {
        val table = if (language == 1) palParkCharsJapanese else palParkCharsInternational
        val codes = ArrayList<Int>()
        for (i in 0 until max.coerceAtMost(bytes.size)) {
            val byte = bytes[i].toInt() and 0xFF
            if (byte == 0xFF) break
            if (byte >= table.size) {
                codes.clear()
                repeat(max.coerceAtMost(10)) { codes += PalParkChars.QUESTION }
                break
            }
            codes += when (val ch = table[byte]) {
                PalParkChars.WIDE_SPACE -> if (language == 1) PalParkChars.WIDE_SPACE else PalParkChars.SPACE
                PalParkChars.JP_DOUBLE_QUOTE_OPEN -> when (language) {
                    2, 4, 7 -> PalParkChars.DOUBLE_QUOTE_OPEN; 3 -> PalParkChars.DOUBLE_PAREN_OPEN
                    5 -> PalParkChars.DOUBLE_QUOTE_CLOSE_INVERTED; else -> PalParkChars.JP_DOUBLE_QUOTE_OPEN
                }
                PalParkChars.JP_DOUBLE_QUOTE_CLOSE -> when (language) {
                    2, 4, 7 -> PalParkChars.DOUBLE_QUOTE_CLOSE; 3 -> PalParkChars.DOUBLE_PAREN_CLOSE
                    5 -> PalParkChars.DOUBLE_QUOTE_OPEN; else -> PalParkChars.JP_DOUBLE_QUOTE_CLOSE
                }
                else -> ch
            }
        }
        val slots = if (max == 7) 8 else 11
        val out = ByteArray(slots * 2)
        codes.forEachIndexed { i, c -> putU16(out, i * 2, c) }
        if (codes.size < slots) putU16(out, codes.size * 2, PalParkChars.EOS)
        return Name(out, codes.toList(), gen4String(out, 0, slots))
    }

    private const val BASE_FRIENDSHIP = 70
    private const val UNOWN = 201
    private const val DEOXYS = 386
}
