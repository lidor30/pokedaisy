package com.pokedaisy.app.companion.data

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Gen 3 raw Pokémon-structure decoding, shared by every native-RAM reader
 * (retail FireRed / Emerald, Unbound). Turns gPlayerParty / gBattleMons bytes
 * into Mon / BattleMon values.
 * Kotlin port of tools/telemetry-viewer/gen3mon.go.
 */
const val MON_STRUCT_SIZE = 100
const val BATTLE_POKEMON_SIZE = 0x58

// gen3SubstructOrder[personality % 24][physicalSlot] = logical substruct index
// (0=Growth, 1=Attacks, 2=EVs, 3=Misc). Classic "GAEM" permutation table.
internal val gen3SubstructOrder = arrayOf(
    intArrayOf(0, 1, 2, 3), intArrayOf(0, 1, 3, 2), intArrayOf(0, 2, 1, 3),
    intArrayOf(0, 2, 3, 1), intArrayOf(0, 3, 1, 2), intArrayOf(0, 3, 2, 1),
    intArrayOf(1, 0, 2, 3), intArrayOf(1, 0, 3, 2), intArrayOf(1, 2, 0, 3),
    intArrayOf(1, 2, 3, 0), intArrayOf(1, 3, 0, 2), intArrayOf(1, 3, 2, 0),
    intArrayOf(2, 0, 1, 3), intArrayOf(2, 0, 3, 1), intArrayOf(2, 1, 0, 3),
    intArrayOf(2, 1, 3, 0), intArrayOf(2, 3, 0, 1), intArrayOf(2, 3, 1, 0),
    intArrayOf(3, 0, 1, 2), intArrayOf(3, 0, 2, 1), intArrayOf(3, 1, 0, 2),
    intArrayOf(3, 1, 2, 0), intArrayOf(3, 2, 0, 1), intArrayOf(3, 2, 1, 0),
)

private fun u32(b: ByteArray, off: Int): Long =
    (ByteBuffer.wrap(b, off, 4).order(ByteOrder.LITTLE_ENDIAN).int.toLong()) and 0xFFFFFFFFL

private fun u16(b: ByteArray, off: Int): Int =
    (ByteBuffer.wrap(b, off, 2).order(ByteOrder.LITTLE_ENDIAN).short.toInt()) and 0xFFFF

/**
 * Where struct Pokemon keeps the party-only fields after the BoxPokemon, and
 * the box's flags byte (isBadEgg / hasSpecies / isEgg) - [size] is the whole
 * struct. Vanilla: 100 bytes, status at +0x50. SoulGold's is 96 bytes: a
 * 12-character nickname pushes the flags to +0x15, and the box ends at +0x4C
 * with status, then level / HP / max HP from +0x50 (headless: a poisoned copy
 * at +0x4C showed PSN, copies 96 bytes apart showed as party mons). Quetzal's is
 * its own too ([QUETZAL_PARTY_MON]).
 */
data class PartyMonLayout(
    val size: Int = MON_STRUCT_SIZE,
    val status: Int = 0x50,
    val level: Int = 0x54,
    val hp: Int = 0x56,
    val maxHp: Int = 0x58,
    val flags: Int = 0x13,
    /** The species bits of a plaintext (unencrypted) box: SoulGold packs teraType above its 11. */
    val plainSpeciesMask: Int = 0xFFFF,
    /** The held item's bits in a plaintext box (growth +2): SoulGold's are expansion's 10, with bits it sets above them. */
    val plainItemMask: Int = 0xFFFF,
    /** Where a plaintext box's substructs start (growth, attacks, EVs, misc, in that order). */
    val plainBox: Int = 0x20,
    /** A plaintext box's experience is read (growth +4); the older plaintext games' never was. */
    val plainExp: Boolean = false,
    /** HP's bits in the u16 at [hp] (Quetzal: 10, packed with other fields); 16 = all of it. */
    val hpBits: Int = 16,
    /** status1 is the u32 at [status] shifted down this far, then masked. */
    val statusShift: Int = 0,
    val statusMask: Long = 0xFFFFFFFFL,
    /** The egg flag is the IV word's bit 30 (vanilla's isEgg bit), not [flags]' bit 2. */
    val eggInIvWord: Boolean = false,
    /** IVs / EVs / nature / stats come out right from these offsets (checked by recomputing the stats). */
    val hasStats: Boolean = size == MON_STRUCT_SIZE,
    /** The egg bit in the [flags] byte (vanilla's isEgg is 0x04). */
    val eggMask: Int = 0x04,
    /** Shiny when (OT ID's halves ^ PID's halves) is below this; 0 = not read. Only where the party menu draws shiny icons:
     * SoulGold (256 - its odds are 1 in 256, checked headless on v1.1.4 and v1.2: 255 shiny, 256 not). */
    val shinyBelow: Int = 0,
    /** The byte holding the newer engine's shinyModifier (`u16 hpLost:14, shinyModifier:1`: bit 14, so 0x40 of the u16's
     * high byte), which flips [shinyBelow]'s answer - the game's forced shinies (GitHub #267); 0 = none. SoulGold: +0x1F,
     * checked headless on v1.2's party menu (the bit alone = shiny; on a shiny personality = not). */
    val shinyModifier: Int = 0,
    /** The nickname's length at [nicknameOff] and where the language byte is (SoulGold: 12 letters, language +0x14; -1 =
     * none, the bit-packed structs of English-only hacks). */
    val nicknameLen: Int = 10,
    val language: Int = 0x12,
    val nicknameOff: Int = 0x08,
    /** R.O.W.E.'s bit-packed struct ([ROWE_PARTY_MON], [decodeRoweMon]) rather than a box of substructs. */
    val rowe: Boolean = false,
    /** Elite Redux's bit-packed struct ([ELITE_REDUX_PARTY_MON], [decodeEliteReduxMon]). */
    val eliteRedux: Boolean = false,
    /** Expansion's Mints: +0x12's bits above the language are a nature XORed in. Off where that byte holds other flags. */
    val natureModifier: Boolean = true,
    /** Shininess kept as bits of its own rather than worked out from the personality ([shinyBelow] then only says the
     * config is marked): the byte and its bits. Elite Redux: +0x17's 0x0C (+0x14's bits 26-27; either drew the shiny
     * colours headless, a new personality alone didn't); R.O.W.E.: +0x29's 0x80 (+0x28's bit 15: the shiny colours and
     * the star by the name). -1 = none. */
    val shinyBits: Int = -1,
    val shinyBitsMask: Int = 0,
)

val VANILLA_PARTY_MON = PartyMonLayout()

/**
 * R.O.W.E. v2.x's 0x4C-byte struct Pokemon: plaintext and bit-packed, no substructs, checksum or
 * IVs (stats recompute with IV 0). Read off its GetBoxMonData / GetMonData (a jump table over every
 * field) and checked headless by poking each one: personality, otId, a 12-byte nickname; u32 +0x14
 * = move 1 (10 bits) | experience (21) << 10; u32 +0x18 = moves 2 and 3 (10 bits each); u32 +0x1C =
 * species (12 bits, National Dex ids; a 5-bit formId above) with move 4 in the u16 at +0x1E (bits
 * 1-10); EVs +0x20; +0x27 = nature (bits 2-6, what the summary shows - not PID % 25) | isEgg (bit 7);
 * PP +0x34; status +0x38, level +0x3C, HP / max HP +0x3E / +0x40, the stats after.
 */
val ROWE_PARTY_MON = PartyMonLayout(
    size = 0x4C, status = 0x38, level = 0x3C, hp = 0x3E, maxHp = 0x40, flags = 0x27, eggMask = 0x80, hasStats = true, rowe = true,
    nicknameLen = 12, language = -1, shinyBits = 0x29, shinyBitsMask = 0x80,
)

/**
 * Pokémon Elite Redux 2.65's 0x4C-byte struct Pokemon (its include/pokemon.h, checked against the save's party menu and
 * summaries headless): plaintext and bit-packed like R.O.W.E.'s, but its own order. personality, otId; u32 +0x08 = move 1
 * (11 bits) | experience (21) << 11; u32 +0x0C = moves 2 and 3 (11 bits each) | friendship; u32 +0x10 = species (16 bits) |
 * move 4 (11) << 16; u32 +0x14 = heldItem (10) | nature (5) << 10 | isEgg << 15 | ...; EVs +0x18; a 12-byte nickname
 * +0x26; PP +0x34 (the party's, not the box's); status +0x38, level +0x3C, HP / max HP +0x3E / +0x40, the stats after.
 * No IVs are kept: every one is 31 (its options' Hell mode aside), but a Speed IV of 0 where speedDown (+0x32 bit 5) is set.
 */
val ELITE_REDUX_PARTY_MON = PartyMonLayout(
    size = 0x4C, status = 0x38, level = 0x3C, hp = 0x3E, maxHp = 0x40, flags = 0x15, eggMask = 0x80, hasStats = true,
    eliteRedux = true, nicknameOff = 0x26, nicknameLen = 12, language = -1, shinyBits = 0x17, shinyBitsMask = 0x0C,
)

/**
 * Pokémon Team Rocket Edition: Dragonsden Version's struct Pokemon: vanilla's 100 bytes, encryption and substructs, but
 * the language and the flags share one byte at +0x12 (language bits 0-2, hasSpecies bit 4, isEgg bit 5 - an egg given
 * headless read 0x31, every other mon 0x12) and the OT name starts a byte early (+0x13). No Mints: nature is PID % 25
 * (the save's Crobat is Adamant by its stats; the XOR would make it Lonely).
 */
val TEAM_ROCKET_PARTY_MON = PartyMonLayout(flags = 0x12, eggMask = 0x20, natureModifier = false)

private fun decodeEliteReduxMon(raw: ByteArray, off: Int, layout: PartyMonLayout): Mon? {
    val w08 = u32(raw, off + 0x08)
    val w0c = u32(raw, off + 0x0C)
    val w10 = u32(raw, off + 0x10)
    val w14 = u32(raw, off + 0x14)
    val species = (w10 and 0xFFFF).toInt()
    if (species == 0) return null
    val speedDown = (raw[off + 0x32].toInt() and 0x20) != 0
    return Mon(
        species = species,
        level = raw[off + 0x3C].toInt() and 0xFF,
        hp = u16(raw, off + 0x3E),
        maxHp = u16(raw, off + 0x40),
        status = u32(raw, off + 0x38),
        moves = intArrayOf((w08 and 0x7FF).toInt(), (w0c and 0x7FF).toInt(), ((w0c shr 11) and 0x7FF).toInt(), ((w10 shr 16) and 0x7FF).toInt()),
        pp = IntArray(NUM_MOVES) { raw[off + 0x34 + it].toInt() and 0xFF },
        exp = (w08 shr 11) and 0x1FFFFF,
        personality = u32(raw, off),
        stats = MonStats(
            ivs = List(6) { if (it == STAT_SPEED && speedDown) 0 else MAX_IV },
            evs = List(6) { raw[off + 0x18 + it].toInt() and 0xFF },
            nature = ((w14 shr 10) and 0x1F).toInt(),
            stats = List(6) { u16(raw, off + 0x40 + 2 * it) },
        ),
        heldItem = (w14 and 0x3FF).toInt(),
        nickname = gen3Nickname(raw, off, ELITE_REDUX_PARTY_MON),
        shiny = layout.shinyBelow > 0 && raw[off + layout.shinyBits].toInt() and layout.shinyBitsMask != 0,
    )
}

private fun decodeRoweMon(raw: ByteArray, off: Int, layout: PartyMonLayout): Mon? {
    val w14 = u32(raw, off + 0x14)
    val w18 = u32(raw, off + 0x18)
    val species = (u32(raw, off + 0x1C) and 0xFFF).toInt()
    if (species == 0) return null
    return Mon(
        species = species,
        level = raw[off + 0x3C].toInt() and 0xFF,
        hp = u16(raw, off + 0x3E),
        maxHp = u16(raw, off + 0x40),
        status = u32(raw, off + 0x38),
        moves = intArrayOf((w14 and 0x3FF).toInt(), (w18 and 0x3FF).toInt(), ((w18 shr 10) and 0x3FF).toInt(), (u16(raw, off + 0x1E) shr 1) and 0x3FF),
        pp = IntArray(NUM_MOVES) { raw[off + 0x34 + it].toInt() and 0xFF },
        exp = (w14 shr 10) and 0x1FFFFF,
        personality = u32(raw, off),
        stats = MonStats(
            ivs = List(6) { 0 },
            evs = List(6) { raw[off + 0x20 + it].toInt() and 0xFF },
            nature = ((raw[off + 0x27].toInt() and 0xFF) shr 2) and 0x1F,
            stats = List(6) { u16(raw, off + 0x40 + 2 * it) },
        ),
        nickname = gen3Nickname(raw, off, ROWE_PARTY_MON),
        shiny = layout.shinyBelow > 0 && raw[off + layout.shinyBits].toInt() and layout.shinyBitsMask != 0,
    )
}

/**
 * Pokémon Quetzal's 104-byte plaintext struct Pokemon (checksum 0): vanilla's box
 * header, then the growth / attacks / EVs / misc substructs from +0x28 (species,
 * item, exp; moves +0x34, PP +0x3C (3 PP Ups on everything: Tackle 56); EVs +0x40;
 * the IV word +0x50, whose bit 30 is the egg flag), level +0x58, max HP and the
 * stats from +0x5A. Current HP is 10 bits at +0x23 and status1 the u32 at +0x24
 * shifted down 2, its low 12 bits (+0x26 is 0x80 on every Pokémon: not status). Each checked headless by poking it and looking at the party
 * menu / summary (gen_quetzal_tables.py; NATIVE_QUETZAL).
 */
val QUETZAL_PARTY_MON = PartyMonLayout(
    size = 0x68, status = 0x24, level = 0x58, hp = 0x23, maxHp = 0x5A,
    plainBox = 0x28, plainExp = true, hpBits = 10, statusShift = 2, statusMask = 0xFFF, eggInIvWord = true, hasStats = true,
)
val SOULGOLD_PARTY_MON = PartyMonLayout(
    size = 96, status = 0x4C, level = 0x50, hp = 0x52, maxHp = 0x54, flags = 0x15, plainSpeciesMask = 0x07FF,
    nicknameLen = 12, language = 0x14, shinyBelow = 256, shinyModifier = 0x1F,
    plainItemMask = 0x03FF,   // heldItem:10 - Cyndaquil's 0x0400 read as an "Item#1024" tag on its party slot
)

/**
 * Decodes one struct Pokemon at [off]. Tries the retail Gen 3 format
 * (substructs encrypted with key = PID ^ OTID, ordered by PID % 24, gated on the
 * checksum), falling back to Unbound's variant (plaintext, fixed order, no
 * checksum). Returns null for an empty slot.
 */
fun decodePartyMon(raw: ByteArray, off: Int, layout: PartyMonLayout = VANILLA_PARTY_MON): Mon? {
    if (raw.size < off + layout.size) return null
    if (layout.rowe) return decodeRoweMon(raw, off, layout)
    if (layout.eliteRedux) return decodeEliteReduxMon(raw, off, layout)

    var species: Int
    var heldItem = 0
    val moves: IntArray
    val pp: IntArray
    var exp: Long? = null
    val evs: List<Int>
    val ivWord: Long

    val dec = decryptGen3Substructs(raw, off)
    if (dec != null) {
        species = dec.species
        heldItem = dec.heldItem
        moves = dec.moves
        pp = dec.pp
        exp = dec.exp
        evs = dec.evs
        ivWord = dec.ivWord
    } else {
        val box = off + layout.plainBox
        species = u16(raw, box) and layout.plainSpeciesMask
        if (species == 0 || species > 4000) return null
        heldItem = u16(raw, box + 2) and layout.plainItemMask
        moves = IntArray(NUM_MOVES) { u16(raw, box + 0x0C + it * 2) }
        pp = IntArray(NUM_MOVES) { raw[box + 0x0C + 8 + it].toInt() and 0xFF }
        // Plaintext boxes keep the substructs in G-A-E-M order: EVs at +0x18, IVs at Misc +4.
        evs = List(6) { raw[box + 0x18 + it].toInt() and 0xFF }
        ivWord = u32(raw, box + 0x28)
        if (layout.plainExp) exp = u32(raw, box + 4)
    }
    if (species == 0) return null

    return Mon(
        species = species,
        level = raw[off + layout.level].toInt() and 0xFF,
        hp = u16(raw, off + layout.hp) and ((1 shl layout.hpBits) - 1),
        maxHp = u16(raw, off + layout.maxHp),
        status = (u32(raw, off + layout.status) shr layout.statusShift) and layout.statusMask,
        moves = moves,
        pp = pp,
        exp = exp,
        personality = u32(raw, off),
        stats = if (layout.hasStats) monStats(raw, off, layout, evs, ivWord) else null,
        heldItem = heldItem,
        nickname = gen3Nickname(raw, off, layout),
        shiny = layout.shinyBelow > 0 &&
            (shinyValue(u32(raw, off), u32(raw, off + 4)) < layout.shinyBelow) !=
            (layout.shinyModifier > 0 && raw[off + layout.shinyModifier].toInt() and 0x40 != 0),
    )
}

/** sizeof(struct BoxPokemon): a PC box slot, the first 80 bytes of a party struct. */
const val BOX_MON_SIZE = 80

/** How a PC box slot is kept ([decodeBoxMon]). */
enum class BoxRecordFormat {
    /** Vanilla's 80-byte BoxPokemon: substructs encrypted and shuffled by the personality, a checksum. */
    ENCRYPTED,
    /** A plaintext box (SoulGold's 76 bytes): the substructs in order from [PartyMonLayout.plainBox], no checksum. */
    PLAIN,
    /** CFRU's 58-byte CompressedPokemon: the box's first 0x1C bytes, then growth (no unknown byte), four 10-bit moves
     * (no PP), the EVs, then misc's first 8 bytes (pokérus, met, the IV word). */
    CFRU,
}

/**
 * One PC box slot (a BoxPokemon at [off], kept as [format] says) as the summary shows it: a box record keeps no level,
 * HP or stats, so the level comes from the experience on its species' growth rate ([growthRate], else vanilla's table)
 * and the stats are worked out the game's way (CalculateMonStats) from [baseStats] (HP, ATTACK, DEFENSE, SPEED,
 * SP. ATK, SP. DEF; null = unknown, then no stats and no HP). A box Pokémon is always healed: HP is its max. CFRU's
 * records keep no PP: full PP from [movePp] (a move's base PP; null = unknown, 0). Null for an empty slot or one whose
 * checksum fails (the game's Bad Egg).
 */
fun decodeBoxMon(
    raw: ByteArray, off: Int, layout: PartyMonLayout = VANILLA_PARTY_MON, baseStats: (Int) -> IntArray? = { null },
    format: BoxRecordFormat = BoxRecordFormat.ENCRYPTED, growthRate: (Int) -> Int? = { null }, movePp: (Int) -> Int? = { null },
): Mon? {
    val dec = when (format) {
        BoxRecordFormat.ENCRYPTED -> if (raw.size < off + BOX_MON_SIZE) null else decryptGen3Substructs(raw, off)
        BoxRecordFormat.PLAIN -> plainBoxSubstructs(raw, off, layout)
        BoxRecordFormat.CFRU -> cfruBoxSubstructs(raw, off, movePp)
    } ?: return null
    if (dec.species == 0) return null
    val egg = (dec.ivWord shr 30) and 1L == 1L
    val rate = growthRate(dec.species)?.takeIf { it in gen3ExperienceTables.indices } ?: gen3GrowthRates.getOrNull(dec.species)?.toInt()
    val level = boxMonLevel(rate, dec.exp)
    val ivs = List(6) { ((dec.ivWord shr (5 * it)) and 0x1F).toInt() }
    val nature = (u32(raw, off) % 25).toInt()
    val stats = if (egg) null else baseStats(dec.species)?.takeIf { it.size >= 6 }?.let { gen3Stats(dec.species, it, ivs, dec.evs, nature, level) }
    return Mon(
        species = dec.species,
        level = level,
        hp = stats?.get(STAT_HP) ?: 0,
        maxHp = stats?.get(STAT_HP) ?: 0,
        status = 0,
        moves = dec.moves,
        pp = dec.pp,
        exp = dec.exp,
        personality = u32(raw, off),
        stats = stats?.let { MonStats(ivs = ivs, evs = dec.evs, nature = nature, stats = it) },
        heldItem = dec.heldItem,
        nickname = gen3Nickname(raw, off, layout),
        shiny = layout.shinyBelow > 0 &&
            (shinyValue(u32(raw, off), u32(raw, off + 4)) < layout.shinyBelow) !=
            (format == BoxRecordFormat.PLAIN && layout.shinyModifier > 0 && raw[off + layout.shinyModifier].toInt() and 0x40 != 0),
    )
}

/** The level [exp] makes on growth rate [rate] (gExperienceTables), at least 1; 1 when the rate isn't known. */
private fun boxMonLevel(rate: Int?, exp: Long): Int {
    val table = rate?.let { gen3ExperienceTables.getOrNull(it) } ?: return 1
    var level = 1
    while (level < 100 && exp >= table[level + 1]) level++
    return level
}

/** A plaintext box ([BoxRecordFormat.PLAIN]): growth, attacks, EVs and misc in that order from [PartyMonLayout.plainBox],
 * the box ending where the party fields start ([PartyMonLayout.status]). */
private fun plainBoxSubstructs(raw: ByteArray, off: Int, layout: PartyMonLayout): Gen3Substructs? {
    if (raw.size < off + layout.status) return null
    val box = off + layout.plainBox
    val species = u16(raw, box) and layout.plainSpeciesMask
    if (species > 4000) return null
    return Gen3Substructs(
        species = species,
        exp = u32(raw, box + 4),
        moves = IntArray(NUM_MOVES) { u16(raw, box + 0x0C + it * 2) },
        pp = IntArray(NUM_MOVES) { raw[box + 0x0C + 8 + it].toInt() and 0xFF },
        evs = List(6) { raw[box + 0x18 + it].toInt() and 0xFF },
        ivWord = u32(raw, box + 0x28),
        heldItem = u16(raw, box + 2) and layout.plainItemMask,
    )
}

private const val CFRU_RECORD_SIZE = 58

/** CFRU's CompressedPokemon ([BoxRecordFormat.CFRU]); the PP the game gives a Pokémon coming out of the box, from
 * [movePp] and the PP Ups (CalculatePPWithBonus: a fifth more of the base for each). */
private fun cfruBoxSubstructs(raw: ByteArray, off: Int, movePp: (Int) -> Int?): Gen3Substructs? {
    if (raw.size < off + CFRU_RECORD_SIZE) return null
    val species = u16(raw, off + 0x1C)
    if (species > 4000) return null
    // Four 10-bit moves packed into the 5 bytes from +0x27.
    var packed = 0L
    for (i in 0 until 5) packed = packed or ((raw[off + 0x27 + i].toLong() and 0xFF) shl (8 * i))
    val moves = IntArray(NUM_MOVES) { ((packed shr (10 * it)) and 0x3FF).toInt() }
    val bonuses = raw[off + 0x24].toInt() and 0xFF
    return Gen3Substructs(
        species = species,
        exp = u32(raw, off + 0x20),
        moves = moves,
        pp = IntArray(NUM_MOVES) { i ->
            val base = if (moves[i] == 0) 0 else movePp(moves[i]) ?: 0
            base + base * 20 * ((bonuses shr (2 * i)) and 3) / 100
        },
        evs = List(6) { raw[off + 0x2C + it].toInt() and 0xFF },
        ivWord = u32(raw, off + 0x36),
        heldItem = u16(raw, off + 0x1E),
    )
}

/** CalculateMonStats: HP, ATTACK, DEFENSE, SPEED, SP. ATK, SP. DEF from base stats, IVs, EVs, nature and level. */
internal fun gen3Stats(species: Int, base: IntArray, ivs: List<Int>, evs: List<Int>, nature: Int, level: Int): List<Int> = List(6) { s ->
    val n = (2 * base[s] + ivs[s] + evs[s] / 4) * level / 100
    if (s == STAT_HP) {
        // Shedinja's HP is always 1.
        if (species == SPECIES_SHEDINJA) 1 else n + level + 10
    } else {
        val v = n + 5
        when (s) {
            natureRaises(nature) -> v * 110 / 100
            natureLowers(nature) -> v * 90 / 100
            else -> v
        }
    }
}

private const val SPECIES_SHEDINJA = 303

/** Gen 3's shiny value: the OT ID's and the personality's halves XORed together. */
internal fun shinyValue(personality: Long, otId: Long): Int =
    ((otId ushr 16) xor (otId and 0xFFFF) xor (personality ushr 16) xor (personality and 0xFFFF)).toInt()

/**
 * The nickname at [PartyMonLayout.nicknameOff] (+8 but in Elite Redux), as the party menu prints it; null when it can't be read as the game's text: a
 * Japanese Pokémon (language 1) in a Western game or the other way round (kana and letters share bytes),
 * or a byte the table doesn't know.
 */
internal fun gen3Nickname(raw: ByteArray, off: Int, layout: PartyMonLayout): String? {
    if (layout.language >= 0) {
        val japanese = (raw[off + layout.language].toInt() and 0xFF) == 1
        if (japanese != (romLanguage == 'J')) return null
    }
    val s = Gen3Text.decode(raw, off + layout.nicknameOff, layout.nicknameLen).trim()
    return s.takeIf { it.isNotEmpty() && Gen3Text.UNKNOWN !in it }
}

/**
 * IVs (5 bits each, HP first), EVs and nature - checked against every fixture
 * by recomputing its stats from the ROM's base stats. Not for SoulGold's
 * shorter struct: its stats didn't add up from these offsets.
 */
private fun monStats(raw: ByteArray, off: Int, layout: PartyMonLayout, evs: List<Int>, ivWord: Long): MonStats {
    val pidNature = (u32(raw, off) % 25).toInt()
    // pokeemerald-expansion's Mints: hiddenNatureModifier sits above the 3
    // language bits at +0x12 (0 in every other game) and is XORed in.
    val nature = if (!layout.natureModifier) pidNature
        else (pidNature xor ((raw[off + 0x12].toInt() and 0xFF) ushr 3)).takeIf { it < 25 } ?: pidNature
    return MonStats(
        ivs = List(6) { ((ivWord shr (5 * it)) and 0x1F).toInt() },
        evs = evs,
        nature = nature,
        stats = List(6) { u16(raw, off + layout.maxHp + 2 * it) },
    )
}

private class Gen3Substructs(
    val species: Int, val exp: Long, val moves: IntArray, val pp: IntArray,
    val evs: List<Int>, val ivWord: Long, val heldItem: Int,
)

private fun decryptGen3Substructs(raw: ByteArray, off: Int): Gen3Substructs? {
    val personality = u32(raw, off + 0)
    val otId = u32(raw, off + 4)
    val storedChecksum = u16(raw, off + 28)
    val key = personality xor otId

    val block = ByteArray(48)
    var sum = 0
    for (i in 0 until 12) {
        val w = (u32(raw, off + 0x20 + i * 4) xor key) and 0xFFFFFFFFL
        block[i * 4] = (w and 0xFF).toByte()
        block[i * 4 + 1] = ((w shr 8) and 0xFF).toByte()
        block[i * 4 + 2] = ((w shr 16) and 0xFF).toByte()
        block[i * 4 + 3] = ((w shr 24) and 0xFF).toByte()
        sum = (sum + (w.toInt() and 0xFFFF) + ((w ushr 16).toInt() and 0xFFFF)) and 0xFFFF
    }
    if (sum != storedChecksum || storedChecksum == 0) return null

    val order = gen3SubstructOrder[(personality % 24).toInt()]
    var growthOff = 0
    var attacksOff = 0
    var evsOff = 0
    var miscOff = 0
    for (slot in 0 until 4) {
        when (order[slot]) {
            0 -> growthOff = slot * 12
            1 -> attacksOff = slot * 12
            2 -> evsOff = slot * 12
            3 -> miscOff = slot * 12
        }
    }
    // Growth: species u16, held item u16, experience u32.
    val species = u16(block, growthOff)
    val heldItem = u16(block, growthOff + 2)
    val exp = u32(block, growthOff + 4)
    val moves = IntArray(NUM_MOVES) { u16(block, attacksOff + it * 2) }
    val pp = IntArray(NUM_MOVES) { block[attacksOff + 8 + it].toInt() and 0xFF }
    // EVs: HP, ATK, DEF, SPEED, SP.ATK, SP.DEF bytes. Misc: pokerus, met location, origin, then the IV word.
    val evs = List(6) { block[evsOff + it].toInt() and 0xFF }
    val ivWord = u32(block, miscOff + 4)
    return Gen3Substructs(species, exp, moves, pp, evs, ivWord, heldItem)
}

/**
 * struct BattlePokemon's size and field offsets. Vanilla FireRed/Emerald (and
 * CFRU) share [VANILLA_BATTLE_MON]; pokeemerald-expansion reshuffles and grows
 * it, so each expansion hack whose battle was actually captured gets its own.
 */
data class BattleMonLayout(
    val size: Int,
    val moves: Int,
    val type1: Int,
    val type2: Int,
    val pp: Int,
    val hp: Int,
    val level: Int,
    val maxHp: Int,
    val status1: Int,
    /** Species bits: R.O.W.E. keeps a 5-bit formId above its 11. */
    val speciesMask: Int = 0xFFFF,
    /** The ability, a byte (vanilla, CFRU) or a u16 ([abilityU16], expansion). */
    val ability: Int = 0x20,
    val abilityU16: Boolean = false,
    /** The held item (u16). */
    val item: Int = 0x2E,
    /** The species (u16): every layout but Elite Redux's keeps it first. */
    val species: Int = 0x00,
    /** Attack, Defense, Speed, Sp. Atk, Sp. Def (u16 each). */
    val stats: Int = 0x02,
    /** statStages[8] (HP, Atk, Def, Speed, Sp. Atk, Sp. Def, accuracy, evasion; 6 = unchanged). */
    val statStages: Int = 0x18,
    /** personality and otId (u32 each), for the SHINY marks; -1 = not mapped. */
    val personality: Int = -1,
    val otId: Int = -1,
)

val VANILLA_BATTLE_MON = BattleMonLayout(
    size = BATTLE_POKEMON_SIZE, moves = 0x0C, type1 = 0x21, type2 = 0x22, pp = 0x24,
    hp = 0x28, level = 0x2A, maxHp = 0x2C, status1 = 0x4C,
    // Read headless in a FireRed rev 1 and an Emerald wild battle: gBattleMons[1]'s matched gEnemyParty[0]'s.
    personality = 0x48, otId = 0x54,
)

/**
 * pokeemerald-expansion's 0x60-byte struct BattlePokemon (Emerald Rogue's,
 * from its headers compiled for ARM; Lazarus's matches it in memory).
 */
val EXPANSION_BATTLE_MON = BattleMonLayout(
    size = 0x60, moves = 0x0C, type1 = 0x22, type2 = 0x23, pp = 0x25,
    hp = 0x2A, level = 0x2C, maxHp = 0x2E, status1 = 0x50,
    ability = 0x20, abilityU16 = true, item = 0x30,
    // After the nickname, ppBonuses, OT name and experience (+0x48), as Team Rocket Edition's dump shows.
    personality = 0x4C, otId = 0x58,
)

/** Too Many Types 2's: expansion's, but PP a byte later (a third type byte at +0x24). */
val TMT2_BATTLE_MON = EXPANSION_BATTLE_MON.copy(pp = 0x26)

/**
 * Elite Redux 2.65's 0x68-byte struct BattlePokemon, its own order (its source's, checked on both builds' battle dumps):
 * experience, personality, status1 +0x08, status2, otId, species +0x14, the five stats +0x16, moves +0x20, abilities[4]
 * +0x28 (the ability, then its three innates), HP / max HP +0x36 / +0x38, item +0x3A, statStages +0x3C, types +0x44 /
 * +0x45 (a third at +0x46), PP +0x47, level +0x4B.
 */
val ELITE_REDUX_BATTLE_MON = BattleMonLayout(
    size = 0x68, moves = 0x20, type1 = 0x44, type2 = 0x45, pp = 0x47,
    hp = 0x36, level = 0x4B, maxHp = 0x38, status1 = 0x08,
    ability = 0x28, abilityU16 = true, item = 0x3A,
    species = 0x14, stats = 0x16, statStages = 0x3C,
)

/**
 * Pokémon Team Rocket Edition: Dragonsden Version's 0x5C-byte struct BattlePokemon: expansion's fields where
 * [EXPANSION_BATTLE_MON] has them (a third type byte at +0x24, PP from +0x25, HP +0x2A, level +0x2C, item +0x30), then the
 * nickname, OT name, experience +0x48, personality +0x4C, status1 +0x50, status2, otId +0x58 (its battle dump's lead
 * matched its party struct field for field).
 */
val TEAM_ROCKET_BATTLE_MON = EXPANSION_BATTLE_MON.copy(size = 0x5C)

/**
 * Pokémon Wishes of Tomorrow's (pokeemerald-expansion 1.15.3) 0x88-byte struct BattlePokemon: expansion's fields where
 * [EXPANSION_BATTLE_MON] has them (types +0x22 / +0x23 and a third at +0x24, PP +0x25, HP +0x2A, level +0x2C, max HP +0x2E,
 * item +0x30, status1 +0x50), its volatiles grown to 0x88 (the reference build's sizeof; its wild and trainer battles
 * read the foe at +0x88, Heart and Soul's size).
 */
val WISHES_BATTLE_MON = EXPANSION_BATTLE_MON.copy(size = 0x88)

/** R.O.W.E.'s: expansion's layout, the species' form id in its top 5 bits. */
val ROWE_BATTLE_MON = EXPANSION_BATTLE_MON.copy(speciesMask = 0x7FF)

/**
 * SoulGold's 0x98-byte struct BattlePokemon: expansion's fields up to max HP
 * where [EXPANSION_BATTLE_MON] has them, then a longer nickname (+0x34) that
 * pushes experience / personality to +0x4C / +0x50 and status1 to +0x54
 * (headless: poison poked there hurt the mon at the end of the turn); otId +0x88 (both battlers' held the player's).
 */
val SOULGOLD_BATTLE_MON = EXPANSION_BATTLE_MON.copy(size = 0x98, status1 = 0x54, personality = 0x50, otId = 0x88)

/** Decodes one struct BattlePokemon (a gBattleMons entry) at [off]; shiny under [shinyBelow] (0 = never marked). */
fun decodeBattleMon(raw: ByteArray, off: Int, layout: BattleMonLayout = VANILLA_BATTLE_MON, shinyBelow: Int = 0): BattleMon? {
    if (raw.size < off + layout.size) return null
    val species = u16(raw, off + layout.species) and layout.speciesMask
    if (species == 0) return null
    return BattleMon(
        species = species,
        level = raw[off + layout.level].toInt() and 0xFF,
        type1 = raw[off + layout.type1].toInt() and 0xFF,
        type2 = raw[off + layout.type2].toInt() and 0xFF,
        hp = u16(raw, off + layout.hp),
        maxHp = u16(raw, off + layout.maxHp),
        status1 = u32(raw, off + layout.status1),
        moves = IntArray(NUM_MOVES) { u16(raw, off + layout.moves + it * 2) },
        pp = IntArray(NUM_MOVES) { raw[off + layout.pp + it].toInt() and 0xFF },
        ability = if (layout.abilityU16) u16(raw, off + layout.ability) else raw[off + layout.ability].toInt() and 0xFF,
        item = u16(raw, off + layout.item),
        stats = IntArray(5) { u16(raw, off + layout.stats + it * 2) },
        // Attack .. evasion (the HP slot isn't a stage the game uses).
        statStages = IntArray(7) { raw[off + layout.statStages + 1 + it].toInt() },
        shiny = shinyBelow > 0 && layout.personality >= 0 && layout.otId >= 0 &&
            shinyValue(u32(raw, off + layout.personality), u32(raw, off + layout.otId)) < shinyBelow,
    )
}
