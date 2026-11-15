package com.pokedaisy.app.companion.data.save

import com.pokedaisy.app.companion.data.Gen4Text
import com.pokedaisy.app.companion.data.gen3ExperienceTables
import com.pokedaisy.app.companion.i18n.tr

/**
 * Where a Gen 4 game keeps things in its two save blocks: HeartGold / SoulSilver's (pret/pokeheartgold) or Platinum's
 * (pret/pokeplatinum). Offsets are into the general block, or into the PC block for the boxes.
 */
enum class Gen4Layout(
    val label: String,
    val generalSize: Int,
    /** Where the PC block starts in a half. */
    val pcOff: Int,
    val pcSize: Int,
    /** PlayerProfile: name, OT ID / secret ID, money. */
    val trainer: Int,
    /** Party { capacity, count, Pokemon[6] }. */
    val party: Int,
    /** HG / SS's aprijuice bytes after the party (5 a slot), or -1. */
    val aprijuice: Int,
    val boxes: Int, val boxStride: Int, val curBox: Int, val boxNames: Int,
    /** HG / SS's boxModifiedFlag, or -1. */
    val modifiedFlags: Int,
    val bag: Int,
    /** struct Bag's pockets: items, key items, TMs / HMs, mail, medicine, berries, balls, battle items. */
    val pocketSizes: IntArray,
    /** struct Pokedex and the fields a received Pokémon touches (-1: not in this game's). */
    val dex: Int, val dexLanguages: Int, val dexInternational: Int, val dexUnownCaught: Int, val dexPichu: Int,
    val dexRotom: Int, val dexShaymin: Int, val dexGiratina: Int,
    /** The dex's National Pokédex byte (pokedex.h's nationalDex / nationalDexObtained). */
    val dexNational: Int,
    /** The regional Pokédex's name. */
    val region: String,
) {
    HGSS(
        "HEARTGOLD / SOULSILVER", 0xF628, 0xF700, 0x12310, trainer = 0x64, party = 0x90, aprijuice = 0x98 + 6 * 0xEC,
        boxes = 0, boxStride = 0x1000, curBox = 0x12000, boxNames = 0x12008, modifiedFlags = 0x12004,
        bag = 0x644, pocketSizes = intArrayOf(165, 50, 101, 12, 40, 64, 24, 30),
        dex = 0x12B8, dexLanguages = 0x144, dexInternational = 0x335, dexUnownCaught = 0x128, dexPichu = 0x33E,
        dexRotom = 0x338, dexShaymin = 0x33C, dexGiratina = 0x33D, dexNational = 0x337, region = "JOHTO",
    ),
    PLATINUM(
        "PLATINUM", 0xCF2C, 0xCF2C, 0x121E4, trainer = 0x68, party = 0x98, aprijuice = -1,
        boxes = 4, boxStride = 30 * 0x88, curBox = 0, boxNames = 4 + 18 * 30 * 0x88, modifiedFlags = -1,
        bag = 0x630, pocketSizes = intArrayOf(165, 50, 100, 12, 40, 64, 15, 30),
        dex = 0x1328, dexLanguages = 0x128, dexInternational = -1, dexUnownCaught = -1, dexPichu = -1,
        dexRotom = 0x31C, dexShaymin = 0x320, dexGiratina = 0x321, dexNational = 0x319, region = "SINNOH",
    ),
    ;

    val partyMons: Int get() = party + 8
}

/**
 * A Gen 4 save file (512 KB: HeartGold / SoulSilver or Platinum), read and written the way the games' save code does it.
 * Both keep two copies (0x00000 and 0x40000) of their two blocks - the general one and the PC's - each ending in a footer
 * with a CRC16-CCITT of the block before it, and save into the copy they didn't load from; an edit does the same, so the
 * copy the game loaded stays as its fallback.
 *
 * - **HeartGold / SoulSilver** (pokeheartgold's save.c): a 16-byte SaveChunkFooter {count, size, magic 0x20060623 (the
 *   Korean carts 0x20070903), slot, crc}. The game loads the half whose two blocks are valid with the same count, the
 *   newer one; an edit goes into the other half, count + 1. It saves only the PC boxes it changed (boxModifiedFlag): an
 *   edited save has every box flagged, so its next save rewrites them all.
 * - **Platinum** (pokeplatinum's savedata.c): a 20-byte SaveBlockFooter {global count, block count, size, signature,
 *   block id, crc}, each block's copy picked on its own (the global count, then the block's); the two picked must share
 *   the global count. An edit is a full save: global + 1, each block's count + 1, each block into the copy it wasn't
 *   loaded from.
 *
 * Pure Kotlin (no Android): the editor, the tests and ui-preview share it.
 */
class Gen4SaveFile private constructor(
    val raw: ByteArray,
    val layout: Gen4Layout,
    /** The copy (0 / 1) the game loads the general block from, and the PC block (always [half] on HG / SS). */
    val half: Int,
    val pcHalf: Int,
    /** HG / SS's count; Platinum's global count. */
    val counter: Long,
    private val generalCount: Long,
    private val pcCount: Long,
    private val magic: Long,
    val general: ByteArray,
    val pc: ByteArray,
) {
    sealed class Parsed {
        class Ok(val save: Gen4SaveFile) : Parsed()
        class Failed(val reason: String) : Parsed()
    }

    /** The whole file with [general] and [pc] saved into the other copies (see the class doc), read back before it's handed out. */
    fun withBlocks(general: ByteArray, pc: ByteArray): ByteArray {
        val l = layout
        require(general.size == l.generalSize && pc.size == l.pcSize)
        val out = raw.copyOf()
        val gBase = (1 - half) * HALF
        val pBase = (1 - pcHalf) * HALF + l.pcOff
        val next = (counter + 1) and 0xFFFFFFFFL
        val g = general.copyOf()
        val p = pc.copyOf()
        if (l.modifiedFlags >= 0) putU32(p, l.modifiedFlags, ALL_BOXES_MODIFIED)
        if (l == Gen4Layout.HGSS) {
            hgssFooter(g, next, 0)
            hgssFooter(p, next, 1)
        } else {
            ptFooter(g, next, (generalCount + 1) and 0xFFFFFFFFL, 0)
            ptFooter(p, next, (pcCount + 1) and 0xFFFFFFFFL, 1)
        }
        g.copyInto(out, gBase)
        p.copyInto(out, pBase)
        val back = (parse(out) as? Parsed.Ok)?.save
        check(back != null && back.layout == l && back.half == 1 - half && back.pcHalf == 1 - pcHalf && back.counter == next &&
            back.general.contentEquals(g) && back.pc.contentEquals(p)) { "the written save doesn't read back" }
        // Nothing outside the two blocks written changed.
        for (i in out.indices) {
            val written = i in gBase until gBase + l.generalSize || i in pBase until pBase + l.pcSize
            if (!written) check(out[i] == raw[i]) { "byte $i changed outside the blocks" }
        }
        return out
    }

    private fun hgssFooter(block: ByteArray, count: Long, slot: Int) {
        val f = block.size - HGSS_FOOTER
        putU32(block, f, count)
        putU32(block, f + 4, block.size.toLong())
        putU32(block, f + 8, magic)
        putU16(block, f + 12, slot)
        putU16(block, f + 14, crc16(block, 0, block.size - HGSS_FOOTER))
    }

    private fun ptFooter(block: ByteArray, global: Long, count: Long, id: Int) {
        val f = block.size - PT_FOOTER
        putU32(block, f, global)
        putU32(block, f + 4, count)
        putU32(block, f + 8, block.size.toLong())
        putU32(block, f + 12, SIGNATURE)
        block[f + 16] = id.toByte()
        block[f + 17] = 0
        putU16(block, f + 18, crc16(block, 0, block.size - PT_FOOTER))
    }

    companion object {
        const val SIZE = 0x80000
        const val HALF = 0x40000
        const val SIGNATURE = 0x20060623L
        private const val HGSS_FOOTER = 16
        private const val PT_FOOTER = 20
        val MAGICS = setOf(0x20060623L, 0x20070903L)
        /** BOX_ALL_MODIFIED_FLAG (18 boxes). */
        const val ALL_BOXES_MODIFIED = (1L shl 18) - 1
        // HeartGold / SoulSilver's, kept for the tests.
        const val GENERAL_SIZE = 0xF628
        const val PC_OFF = 0xF700
        const val PC_SIZE = 0x12310
        const val PC_MODIFIED_FLAGS = 0x12004

        /** SaveCounterCompare: 1 if [a] is newer, -1 if older; 0 is newer than 0xFFFFFFFF. */
        private fun compare(a: Long, b: Long): Int = when {
            a == 0xFFFFFFFFL && b == 0L -> -1
            a == 0L && b == 0xFFFFFFFFL -> 1
            else -> a.compareTo(b).coerceIn(-1, 1)
        }

        fun parse(bytes: ByteArray): Parsed {
            if (bytes.size < SIZE) return Parsed.Failed(tr("This isn't a HeartGold, SoulSilver or Platinum save"))
            return parseHgss(bytes) ?: parsePlatinum(bytes)
                ?: Parsed.Failed(tr("This isn't a HeartGold, SoulSilver or Platinum save, or it's damaged"))
        }

        private fun parseHgss(bytes: ByteArray): Parsed? {
            val l = Gen4Layout.HGSS
            class Check(val valid: Boolean, val count: Long, val magic: Long)
            fun check(off: Int, size: Int, slot: Int): Check {
                val f = off + size - HGSS_FOOTER
                val count = u32(bytes, f)
                val magic = u32(bytes, f + 8)
                val ok = u32(bytes, f + 4) == size.toLong() && magic in MAGICS && u16(bytes, f + 12) == slot &&
                    crc16(bytes, off, size - HGSS_FOOTER) == u16(bytes, f + 14)
                return Check(ok, count, magic)
            }
            val halves = (0..1).map { h -> Triple(h, check(h * HALF, l.generalSize, 0), check(h * HALF + l.pcOff, l.pcSize, 1)) }
                .filter { (_, g, p) -> g.valid && p.valid && g.count == p.count }
            if (halves.isEmpty()) return null
            val (h, g, _) = halves.reduce { a, b -> if (compare(b.second.count, a.second.count) > 0) b else a }
            val base = h * HALF
            return Parsed.Ok(Gen4SaveFile(
                bytes.copyOf(), l, h, h, g.count, g.count, g.count, g.magic,
                bytes.copyOfRange(base, base + l.generalSize), bytes.copyOfRange(base + l.pcOff, base + l.pcOff + l.pcSize),
            ))
        }

        private fun parsePlatinum(bytes: ByteArray): Parsed? {
            val l = Gen4Layout.PLATINUM
            class Check(val valid: Boolean, val global: Long, val count: Long)
            fun check(off: Int, size: Int, id: Int): Check {
                val f = off + size - PT_FOOTER
                val ok = u32(bytes, f + 8) == size.toLong() && u32(bytes, f + 12) == SIGNATURE && (bytes[f + 16].toInt() and 0xFF) == id &&
                    crc16(bytes, off, size - PT_FOOTER) == u16(bytes, f + 18)
                return Check(ok, u32(bytes, f), u32(bytes, f + 4))
            }
            /** SaveCheckInfo_CompareSectors: the copy to load (0 primary / 1 backup), or null when neither is valid. */
            fun pick(a: Check, b: Check): Int? = when {
                a.valid && b.valid -> {
                    val gd = compare(a.global, b.global)
                    if (gd != 0) (if (gd > 0) 0 else 1) else if (compare(a.count, b.count) < 0) 1 else 0
                }
                a.valid -> 0
                b.valid -> 1
                else -> null
            }
            val g = (0..1).map { check(it * HALF, l.generalSize, 0) }
            val p = (0..1).map { check(it * HALF + l.pcOff, l.pcSize, 1) }
            val gh = pick(g[0], g[1]) ?: return null
            val ph = pick(p[0], p[1]) ?: return null
            // The two copies the game loads share the global count (LOAD_RESULT_OK); anything else it calls damaged.
            if (g[gh].global != p[ph].global) return null
            return Parsed.Ok(Gen4SaveFile(
                bytes.copyOf(), l, gh, ph, g[gh].global, g[gh].count, p[ph].count, SIGNATURE,
                bytes.copyOfRange(gh * HALF, gh * HALF + l.generalSize),
                bytes.copyOfRange(ph * HALF + l.pcOff, ph * HALF + l.pcOff + l.pcSize),
            ))
        }

        /** GF_CalcCRC16 (the SDK's MATH_CalcCRC16CCITT): poly 0x1021, from 0xFFFF, no reflection. */
        fun crc16(b: ByteArray, off: Int, len: Int): Int {
            var c = 0xFFFF
            for (i in off until off + len) {
                c = c xor ((b[i].toInt() and 0xFF) shl 8)
                repeat(8) { c = if (c and 0x8000 != 0) ((c shl 1) xor 0x1021) and 0xFFFF else (c shl 1) and 0xFFFF }
            }
            return c
        }
    }
}

/** A name in the Gen 4 games' text ([chars] u16s from [off]), up to its end (0xFFFF; a slot never written: 0). */
internal fun gen4String(b: ByteArray, off: Int, chars: Int): String {
    val n = (0 until chars).firstOrNull { u16(b, off + it * 2).let { c -> c == 0xFFFF || c == 0 } } ?: chars
    if (n == 0) return ""
    val copy = b.copyOfRange(off, off + chars * 2)
    for (i in n until chars) { copy[i * 2] = 0xFF.toByte(); copy[i * 2 + 1] = 0xFF.toByte() }
    return Gen4Text.name(copy, 0, chars).trim()
}

/**
 * One HeartGold / SoulSilver BoxPokemon (136 bytes: the PC's records and a party record's start): personality, flags,
 * checksum, then four 32-byte blocks shuffled by (personality >> 13) % 24 and XORed with the games' LCG seeded by the
 * checksum (pokemon.c). [encode] puts it back the same way. Blocks: A growth (species, item, OT ID, EXP, friendship,
 * ability, EVs), B moves (moves, PP, PP Ups, IVs, gender / form), C the nickname, D the OT and where it was met.
 */
class Gen4BoxMon private constructor(
    override val personality: Long,
    private val flags: Int,
    /** The four blocks decrypted, in A B C D order. */
    private val data: ByteArray,
    private val checksumOk: Boolean,
    private val original: ByteArray,
    override val game: Gen4GameData,
) : SaveMon {
    private val a = 0
    private val b = 32
    private val c = 64
    private val d = 96

    override val isEmpty: Boolean get() = checksumOk && species == 0
    override val isBadEgg: Boolean get() = !checksumOk || flags and 4 != 0
    override val species: Int get() = u16(data, a)
    override val heldItem: Int get() = u16(data, a + 2)
    val otId: Long get() = u32(data, a + 4)
    override val otTid: Int get() = (otId and 0xFFFF).toInt()
    override val otSid: Int get() = ((otId shr 16) and 0xFFFF).toInt()
    override val exp: Long get() = u32(data, a + 8)
    override val friendship: Int get() = data[a + 0x0C].toInt() and 0xFF
    override val ability: Int get() = data[a + 0x0D].toInt() and 0xFF
    val language: Int get() = data[a + 0x0F].toInt() and 0xFF
    override val evs: IntArray get() = IntArray(6) { data[a + 0x10 + it].toInt() and 0xFF }
    override val moves: IntArray get() = IntArray(4) { u16(data, b + it * 2) }
    override val pp: IntArray get() = IntArray(4) { data[b + 8 + it].toInt() and 0xFF }
    private val ppUps: IntArray get() = IntArray(4) { data[b + 0x0C + it].toInt() and 0xFF }
    override val ppBonuses: Int get() = ppUps.withIndex().sumOf { (i, v) -> (v and 3) shl (i * 2) }
    private val ivWord: Long get() = u32(data, b + 0x10)
    override val ivs: IntArray get() = IntArray(6) { ((ivWord shr (it * 5)) and 0x1F).toInt() }
    override val isEgg: Boolean get() = (ivWord shr 30) and 1L == 1L
    /** The "nicknamed" bit (IVs' bit 31): clear, a trade evolution renames it to its new species' name. */
    val nicknamed: Boolean get() = (ivWord shr 31) and 1L == 1L
    private val bFlags: Int get() = data[b + 0x18].toInt() and 0xFF
    /** Block B's form (bits 3-7 of +0x18): Unown's letter, Burmy's cloak, Spiky-eared Pichu ... */
    val form: Int get() = bFlags shr 3
    override val gender: Gen3Gender get() = when ((bFlags shr 1) and 3) { 0 -> Gen3Gender.MALE; 1 -> Gen3Gender.FEMALE; else -> Gen3Gender.GENDERLESS }
    val originGame: Int get() = data[c + 0x17].toInt() and 0xFF
    override val pokerus: Int get() = data[d + 0x1A].toInt() and 0xFF
    override val metLevel: Int get() = data[d + 0x1C].toInt() and 0x7F
    override val nature: Int get() = (personality % 25).toInt()
    override val abilityNum: Int get() = game.abilities(species).indexOf(ability).coerceAtLeast(0)
    override val isShiny: Boolean get() = Gen3Personality.isShiny(personality, otId)
    override val level: Int get() = game.levelFor(species, exp)
    override val nickname: String? get() = gen4String(data, c, 11).ifEmpty { null }
    override val otName: String get() = gen4String(data, d, 8)
    override val metGameLabel: String get() = when (originGame) {
        1 -> "SAPPHIRE"; 2 -> "RUBY"; 3 -> "EMERALD"; 4 -> "FIRERED"; 5 -> "LEAFGREEN"; 7 -> "HEARTGOLD"; 8 -> "SOULSILVER"
        10 -> "DIAMOND"; 11 -> "PEARL"; 12 -> "PLATINUM"; 15 -> "COLOSSEUM / XD"
        else -> "?"
    }

    /** This record with the given fields changed, encoded with a new checksum. Only for one that read correctly. */
    fun withChanges(
        exp: Long? = null, friendship: Int? = null, ability: Int? = null, evs: IntArray? = null, ivs: IntArray? = null,
        moves: IntArray? = null, pp: IntArray? = null, ppUps: IntArray? = null, gender: Gen3Gender? = null,
        personality: Long? = null, heldItem: Int? = null, species: Int? = null,
        /** 22 bytes: block C's u16[11]. */
        nickname: ByteArray? = null, nicknamed: Boolean? = null,
    ): ByteArray {
        require(checksumOk) { "an unreadable record is never changed" }
        val n = data.copyOf()
        heldItem?.let { putU16(n, a + 2, it) }
        species?.let { putU16(n, a, it) }
        nickname?.let { require(it.size == 22); it.copyInto(n, c) }
        nicknamed?.let { putU32(n, b + 0x10, (u32(n, b + 0x10) and 0x7FFFFFFFL) or (if (it) 1L shl 31 else 0L)) }
        exp?.let { putU32(n, a + 8, it) }
        friendship?.let { require(it in 0..255); n[a + 0x0C] = it.toByte() }
        ability?.let { n[a + 0x0D] = it.toByte() }
        evs?.let { require(it.size == 6 && it.all { v -> v in 0..255 }); for (i in 0 until 6) n[a + 0x10 + i] = it[i].toByte() }
        moves?.let { require(it.size == 4); for (i in 0 until 4) putU16(n, b + i * 2, it[i]) }
        pp?.let { require(it.size == 4); for (i in 0 until 4) n[b + 8 + i] = it[i].toByte() }
        ppUps?.let { require(it.size == 4); for (i in 0 until 4) n[b + 0x0C + i] = it[i].toByte() }
        ivs?.let {
            require(it.size == 6 && it.all { v -> v in 0..31 })
            var w = u32(n, b + 0x10) and 0x3FFFFFFFL.inv() and 0xFFFFFFFFL
            for (i in 0 until 6) w = w or (it[i].toLong() shl (i * 5))
            putU32(n, b + 0x10, w)   // (bits 30 / 31 - egg, nicknamed - kept)
        }
        gender?.let {
            val g = when (it) { Gen3Gender.MALE -> 0; Gen3Gender.FEMALE -> 1; Gen3Gender.GENDERLESS -> 2 }
            n[b + 0x18] = ((n[b + 0x18].toInt() and 0xF9) or (g shl 1)).toByte()
        }
        return encode(personality ?: this.personality, flags and 4.inv() and 3.inv(), n)
    }

    /** The 136 bytes as the game stores them. An unreadable record encodes back to itself. */
    fun encode(): ByteArray = if (!checksumOk) original.copyOf() else encode(personality, flags and 3.inv(), data)

    override fun equals(other: Any?): Boolean = other is Gen4BoxMon && other.encode().contentEquals(encode())
    override fun hashCode(): Int = personality.hashCode()

    companion object {
        const val SIZE = 0x88
        const val PARTY_SIZE = 0xEC

        fun read(b: ByteArray, off: Int, game: Gen4GameData): Gen4BoxMon {
            val original = b.copyOfRange(off, off + SIZE)
            val personality = u32(b, off)
            val flags = u16(b, off + 4)
            val checksum = u16(b, off + 6)
            // A slot never written (all zero) is empty, like ZeroBoxMonData's.
            if (original.all { it.toInt() == 0 }) return Gen4BoxMon(0, 0, ByteArray(128), true, original, game)
            val stored = crypt(b, off + 8, 128, checksum.toLong())
            val order = ORDERS[(((personality shr 13) and 0x1F) % 24).toInt()]
            val logical = ByteArray(128)
            for (slot in 0 until 4) stored.copyInto(logical, "ABCD".indexOf(order[slot]) * 32, slot * 32, slot * 32 + 32)
            var sum = 0
            for (i in 0 until 64) sum = (sum + u16(logical, i * 2)) and 0xFFFF
            return Gen4BoxMon(personality, flags, logical, sum == checksum, original, game)
        }

        private fun encode(personality: Long, flags: Int, logical: ByteArray): ByteArray {
            var sum = 0
            for (i in 0 until 64) sum = (sum + u16(logical, i * 2)) and 0xFFFF
            val order = ORDERS[(((personality shr 13) and 0x1F) % 24).toInt()]
            val stored = ByteArray(128)
            for (slot in 0 until 4) logical.copyInto(stored, slot * 32, "ABCD".indexOf(order[slot]) * 32, "ABCD".indexOf(order[slot]) * 32 + 32)
            val out = ByteArray(SIZE)
            putU32(out, 0, personality and 0xFFFFFFFFL)
            putU16(out, 4, flags)
            putU16(out, 6, sum)
            crypt(stored, 0, 128, sum.toLong()).copyInto(out, 8)
            return out
        }

        /**
         * A record's four blocks decrypted and put in A B C D order, with whether its checksum holds (Gen 5's records are
         * built the same way: [Gen5BoxMon]). Null for a slot never written.
         */
        internal fun decode(b: ByteArray, off: Int): Pair<ByteArray, Boolean>? {
            if ((0 until SIZE).all { b[off + it].toInt() == 0 }) return null
            val personality = u32(b, off)
            val checksum = u16(b, off + 6)
            val stored = crypt(b, off + 8, 128, checksum.toLong())
            val order = ORDERS[(((personality shr 13) and 0x1F) % 24).toInt()]
            val logical = ByteArray(128)
            for (slot in 0 until 4) stored.copyInto(logical, "ABCD".indexOf(order[slot]) * 32, slot * 32, slot * 32 + 32)
            var sum = 0
            for (i in 0 until 64) sum = (sum + u16(logical, i * 2)) and 0xFFFF
            return logical to (sum == checksum)
        }

        /** A whole new record from its four blocks in A B C D order (128 bytes): checksummed, shuffled and encrypted. */
        fun build(personality: Long, logical: ByteArray): ByteArray {
            require(logical.size == 128)
            return encode(personality, 0, logical)
        }

        /** [build] keeping the record's flags word (Gen 5's records: [Gen5BoxMon]). */
        internal fun build(personality: Long, flags: Int, logical: ByteArray): ByteArray {
            require(logical.size == 128)
            return encode(personality, flags, logical)
        }

        /** ZeroBoxMonData: zeroes, encrypted with checksum 0 (what an empty PC slot holds). */
        fun emptyRecord(): ByteArray = ByteArray(SIZE).also { crypt(ByteArray(128), 0, 128, 0).copyInto(it, 8) }

        /** The party fields (100 bytes after the record), XORed with the stream seeded by the personality. */
        fun cryptParty(b: ByteArray, off: Int, personality: Long): ByteArray = crypt(b, off, PARTY_SIZE - SIZE, personality)

        /** The games' LCG stream (x 0x41C64E6D + 0x6073), one u16 per step, XORed over [len] bytes from [off]. */
        fun crypt(raw: ByteArray, off: Int, len: Int, seed: Long): ByteArray {
            val out = ByteArray(len)
            var s = seed and 0xFFFFFFFFL
            var i = 0
            while (i < len) {
                s = (s * 0x41C64E6DL + 0x6073L) and 0xFFFFFFFFL
                val w = (u16(raw, off + i) xor (s ushr 16).toInt()) and 0xFFFF
                out[i] = w.toByte()
                out[i + 1] = (w shr 8).toByte()
                i += 2
            }
            return out
        }

        private val ORDERS = arrayOf(
            "ABCD", "ABDC", "ACBD", "ACDB", "ADBC", "ADCB", "BACD", "BADC", "BCAD", "BCDA", "BDAC", "BDCA",
            "CABD", "CADB", "CBAD", "CBDA", "CDAB", "CDBA", "DABC", "DACB", "DBAC", "DBCA", "DCAB", "DCBA",
        )
    }
}

/**
 * HeartGold / SoulSilver's data the editor needs, read from the player's own cart ([com.pokedaisy.app.companion.data.NdsArt.readSaveTables]):
 * names, each species' personal record (base stats, gender ratio, growth rate, abilities, TM / HM bits), level-up
 * moves, pre-evolutions and each move's PP. Species are National Dex numbers.
 */
class Gen4GameData(
    private val species: List<String>,
    private val moveNames: List<String>,
    private val items: List<String>,
    private val abilityNames: List<String>,
    /** Each species' personal record (by id), 0x2C bytes. */
    private val personal: List<ByteArray?>,
    /** Each species' level-up moves as (level, move). */
    private val levelUp: List<List<Pair<Int, Int>>>,
    /** Each species' pre-evolution (0 = none). */
    private val preEvolution: IntArray,
    /** TM01..TM92 then HM01..HM08: the move each teaches. */
    private val tmMoves: IntArray,
    /** Each move's base PP. */
    private val movePp: IntArray,
    /** Each item's field pocket (POCKET_ITEMS 0 .. POCKET_KEY_ITEMS 7, item.h's numbers), -1 for none. */
    private val itemPockets: IntArray = IntArray(0),
    /** Each species' evolutions as (method, param, target) (the cart's evo data). */
    private val evolutions: List<List<Triple<Int, Int, Int>>> = emptyList(),
    /** The cart's language (LANGUAGE_*: 1 Japanese, 2 English, 3 French, 4 Italian, 5 German, 7 Spanish, 8 Korean). */
    val gameLanguage: Int = 2,
    /** Which save layout the cart's saves have. */
    val layout: Gen4Layout = Gen4Layout.HGSS,
    /** Each species' regional Pokédex number (0: not in it), from the cart. */
    val regional: IntArray = IntArray(0),
) : SaveGameData {
    /** TM01..HM08's item ids (328..427): the move each teaches. */
    fun tmMove(item: Int): Int? = tmMoves.getOrNull(item - FIRST_TM)?.takeIf { it != 0 }

    /** Whether this game has [item] at all (Platinum lacks HG / SS's newer ones). */
    fun hasItem(item: Int): Boolean = item == 0 || itemPockets.isEmpty() || (itemPockets.getOrNull(item) ?: -1) >= 0

    /** GetMonEvolution with EVOCTX_TRADE: EVO_TRADE (5), or EVO_TRADE_ITEM (6) holding its item; an Everstone stops it. */
    fun tradeEvolution(species: Int, heldItem: Int): TradeEvolution? {
        if (heldItem == EVERSTONE) return null
        for ((method, param, target) in evolutions.getOrNull(species).orEmpty()) {
            if (method == 5) return TradeEvolution(species, 0, target)
            if (method == 6 && param == heldItem) return TradeEvolution(species, param, target)
        }
        return null
    }

    /** The pocket the bag files [item] in (GetItemAttr ITEMATTR_FIELD_POCKET), or null. */
    fun fieldPocket(item: Int): Int? = itemPockets.getOrNull(item)?.takeIf { it in 0..7 }

    override fun speciesName(species: Int): String? = this.species.getOrNull(species)?.takeIf { it.isNotBlank() }
    override fun moveName(move: Int): String? = moveNames.getOrNull(move)?.takeIf { it.isNotBlank() }
    override fun itemName(item: Int): String? = items.getOrNull(item)?.takeIf { it.isNotBlank() }
    override fun abilityName(ability: Int): String = abilityNames.getOrNull(ability)?.takeIf { it.isNotBlank() } ?: "#$ability"
    override fun nationalDex(species: Int): Int? = species.takeIf { it in 1..LAST_SPECIES }
    private fun p(species: Int): ByteArray? = personal.getOrNull(species)?.takeIf { it.size >= 0x2C }
    override fun abilities(species: Int): List<Int> {
        val r = p(species) ?: return listOf(0)
        val x = r[0x16].toInt() and 0xFF
        val y = r[0x17].toInt() and 0xFF
        return if (y == 0 || y == x) listOf(x) else listOf(x, y)
    }
    override fun genderRatio(species: Int): Int = p(species)?.let { it[0x10].toInt() and 0xFF } ?: 255
    override fun baseStats(species: Int): IntArray = p(species)?.let { r -> IntArray(6) { r[it].toInt() and 0xFF } } ?: IntArray(6)
    override fun expForLevel(species: Int, level: Int): Long {
        val rate = p(species)?.let { it[0x13].toInt() and 0xFF } ?: return 0
        return gen3ExperienceTables.getOrNull(rate)?.getOrNull(level)?.toLong() ?: 0
    }
    override fun basePp(move: Int): Int = movePp.getOrElse(move) { 0 }
    override fun isMail(item: Int): Boolean = item in MAIL
    override val unown: Int get() = 201
    override val wurmpleLine: IntRange get() = 265..269
    override val shedinja: Int get() = 292

    /** What [species] (or what it evolved from) could know at [level]: level-up moves to then, and its TMs / HMs. */
    fun learnable(species: Int, level: Int): Set<Int> {
        if (species == SMEARGLE) return (1 until movePp.size).filter { it != STRUGGLE && movePp[it] > 0 }.toSet()
        val out = HashSet<Int>()
        var s = species
        var guard = 0
        while (s in 1..LAST_SPECIES && guard++ < 4) {
            levelUp.getOrNull(s)?.forEach { (lv, m) -> if (lv <= level) out += m }
            p(s)?.let { r -> for (bit in tmMoves.indices) if ((r[0x1C + bit / 8].toInt() shr (bit % 8)) and 1 == 1) out += tmMoves[bit] }
            s = preEvolution.getOrElse(s) { 0 }
        }
        return out
    }

    companion object {
        const val LAST_SPECIES = 493
        /** TM01's item id; HM01 is 420. */
        const val FIRST_TM = 328
        private const val SMEARGLE = 235
        private const val STRUGGLE = 165
        private const val EVERSTONE = 229
        /** Gen 4's mail items (GRASS MAIL .. BRICK MAIL). */
        val MAIL = 137..148
    }
}

/** A Gen 4 save's trainer, party and PC, read from its two blocks ([layout]'s offsets). */
class Gen4SaveData(
    override val game: Gen4GameData, private val general: ByteArray, private val pc: ByteArray,
    val layout: Gen4Layout = Gen4Layout.HGSS,
) : MonSave {
    private val l get() = layout
    override val trainerName: String get() = gen4String(general, l.trainer, 8)
    override val trainerId: Int get() = u16(general, l.trainer + 0x10)
    override val secretId: Int get() = u16(general, l.trainer + 0x12)
    val money: Long get() = u32(general, l.trainer + 0x14)
    override val partyCount: Int get() = u32(general, l.party + 4).toInt()
    override val partyMons: List<PartyEntry> get() = (0 until partyCount.coerceIn(0, 6)).map { i ->
        val off = l.partyMons + i * Gen4BoxMon.PARTY_SIZE
        val mon = Gen4BoxMon.read(general, off, game)
        val ext = Gen4BoxMon.cryptParty(general, off + Gen4BoxMon.SIZE, mon.personality)
        PartyEntry(mon, ext[4].toInt() and 0xFF, u16(ext, 6), IntArray(6) { u16(ext, 8 + it * 2) })
    }
    override val boxCount: Int get() = BOXES
    override val currentBox: Int get() = u32(pc, l.curBox).toInt().coerceIn(0, BOXES - 1)
    override fun boxName(box: Int): String = gen4String(pc, l.boxNames + box * BOX_NAME_BYTES, BOX_NAME_BYTES / 2)
    override fun boxMons(box: Int): List<SaveMon> = List(SLOTS) { s -> mon(BoxSlot(box, s)) }
    fun mon(at: BoxSlot): Gen4BoxMon = Gen4BoxMon.read(pc, recordOff(at, l), game)
    override fun mon(at: MonRef): Pair<Gen4BoxMon, Int>? = when (at) {
        is MonRef.Party -> partyMons.getOrNull(at.index)?.let { (it.mon as Gen4BoxMon) to it.level }
        is MonRef.Pc -> mon(at.at).takeIf { !it.isEmpty }?.let { it to it.level }
    }

    /** Why these blocks don't read as this game's (a hack's own layout), or null. */
    fun mismatch(): String? {
        val count = partyCount
        if (count !in 0..6) return "party count $count"
        partyMons.forEachIndexed { i, p ->
            if (p.mon.isBadEgg) return "party Pokémon ${i + 1} doesn't decode"
            if (p.mon.species !in 1..Gen4GameData.LAST_SPECIES) return "party Pokémon ${i + 1} has species ${p.mon.species}"
            if (!p.mon.isEgg && p.level !in 1..100) return "party Pokémon ${i + 1} is level ${p.level}"
        }
        val records = (0 until BOXES * SLOTS).map { mon(BoxSlot(it / SLOTS, it % SLOTS)) }
        val used = records.count { !it.isEmpty }
        val bad = records.count { !it.isEmpty && (it.isBadEgg || it.species !in 1..Gen4GameData.LAST_SPECIES) }
        if (used >= 4 && bad * 2 > used) return "$bad of $used PC records don't decode"
        return null
    }

    companion object {
        /** HG / SS's aprijuice bytes a party slot. */
        const val APRIJUICE_SIZE = 5
        const val BOXES = 18
        const val SLOTS = 30
        const val BOX_NAME_BYTES = 40

        fun recordOff(at: BoxSlot, layout: Gen4Layout = Gen4Layout.HGSS): Int {
            require(at.box in 0 until BOXES && at.slot in 0 until SLOTS) { "no box slot $at" }
            return layout.boxes + at.box * layout.boxStride + at.slot * Gen4BoxMon.SIZE
        }
    }
}

/**
 * Edits a HeartGold / SoulSilver save's working copies: the party and PC (the PC's rules), a Pokémon's IVs, EVs, level,
 * friendship, moves and the traits its personality decides - each value one the game could hold. Names and held items
 * stay as they are for now.
 */
class Gen4SaveEditor(val file: Gen4SaveFile, val game: Gen4GameData) : MonSaveEditor, TrainerBagEditor {
    private val l: Gen4Layout get() = file.layout
    private val general = file.general.copyOf()
    private val pc = file.pc.copyOf()

    private fun refuseIf(condition: Boolean, why: () -> String) { if (condition) throw SaveEditRefused(why()) }

    override val data: Gen4SaveData get() = Gen4SaveData(game, general, pc, file.layout)
    override val changed: Boolean get() = !general.contentEquals(file.general) || !pc.contentEquals(file.pc)
    override fun fresh(): MonSaveEditor = Gen4SaveEditor(file, game)
    override fun snapshot() = EditorSnapshot(listOf(general.copyOf(), pc.copyOf()))
    override fun restore(snapshot: EditorSnapshot) {
        snapshot.parts.zip(listOf(general, pc)).forEach { (from, to) -> from.copyInto(to) }
    }
    override fun build(): ByteArray = file.withBlocks(general, pc)
    override val canWriteNames: Boolean get() = true
    override val canEditItems: Boolean get() = true

    /** NICKNAME's length: five for a Japanese or Korean Pokémon (language 1 / 8), ten for the others. */
    override fun nicknameMax(at: MonRef): Int = when (monAt(at)?.language) { 1, 8 -> 5; else -> 10 }
    override val boxNameMax: Int get() = 8
    override fun canWrite(text: String): Boolean = Gen4Text.encode(text) != null

    /** The codes of [name] in [chars] u16s: the characters, the 0xFFFF end, zeroes after (as the games leave them). */
    /** [name] as a nickname's 22 bytes (a trade evolution's new name). */
    fun nameCodesFor(name: String): ByteArray = nameCodes(name, 11)

    private fun nameCodes(name: String, chars: Int): ByteArray {
        val codes = requireNotNull(Gen4Text.encode(name)) { "can't write \"$name\"" }
        require(codes.size < chars) { "too long" }
        val out = ByteArray(chars * 2)
        codes.forEachIndexed { i, c -> putU16(out, i * 2, c) }
        putU16(out, codes.size * 2, 0xFFFF)
        return out
    }

    override fun renameBox(box: Int, name: String) {
        require(box in 0 until Gen4SaveData.BOXES)
        require(name.isNotBlank() && name.length <= boxNameMax) { "box name $name" }
        nameCodes(name, Gen4SaveData.BOX_NAME_BYTES / 2).copyInto(pc, l.boxNames + box * Gen4SaveData.BOX_NAME_BYTES)
    }

    /** A new nickname for a Pokémon this trainer caught; its "nicknamed" bit set unless it's the species' own name. */
    override fun setNickname(at: MonRef, name: String) {
        val (mon, _) = editable(at)
        require(isOwn(mon)) { "only its OT renames it" }
        require(name.isNotBlank() && name.length <= nicknameMax(at)) { "name $name" }
        val nicknamed = name != game.speciesName(mon.species)
        put(at, mon.withChanges(nickname = nameCodes(name, 11), nicknamed = nicknamed))
    }

    // ---- The bag (bag.c): GIVE takes one from its stack, TAKE puts it back ----

    /** Struct Bag's pockets in the save, and the field pocket each one is (item.h's POCKET_*). */
    private val pocketOffsets: IntArray = run {
        var off = l.bag
        IntArray(l.pocketSizes.size) { i -> off.also { off += l.pocketSizes[i] * 4 } }
    }

    private fun pocketOf(item: Int): Int? = game.fieldPocket(item)?.let { POCKET_ORDER.indexOf(it) }?.takeIf { it >= 0 }

    private fun slots(p: Int): MutableList<IntArray> =
        MutableList(l.pocketSizes[p]) { i -> intArrayOf(u16(general, pocketOffsets[p] + i * 4), u16(general, pocketOffsets[p] + i * 4 + 2)) }

    private fun writeSlots(p: Int, s: List<IntArray>) =
        s.forEachIndexed { i, (id, q) -> putU16(general, pocketOffsets[p] + i * 4, id); putU16(general, pocketOffsets[p] + i * 4 + 2, q) }

    /** PocketCompaction: an emptied slot swapped toward the end, as the game does it (not a stable sort). */
    private fun compact(s: MutableList<IntArray>) {
        for (i in 0 until s.size - 1) for (j in i + 1 until s.size) if (s[i][1] == 0) { val t = s[i]; s[i] = s[j]; s[j] = t }
    }

    /** SortPocket: TMs / HMs and berries by id, empties last. */
    private fun sort(s: MutableList<IntArray>) {
        for (i in 0 until s.size - 1) for (j in i + 1 until s.size) {
            if (s[i][1] == 0 || (s[j][1] != 0 && s[i][0] > s[j][0])) { val t = s[i]; s[i] = s[j]; s[j] = t }
        }
    }

    /** What may be given to hold: one of each item the bag holds outside the key items, mail and HMs. */
    override fun holdable(): List<Int> = POCKET_ORDER.indices
        .filter { POCKET_ORDER[it] != FIELD_KEY_ITEMS && POCKET_ORDER[it] != FIELD_MAIL }
        .flatMap { p -> slots(p).filter { it[0] != 0 && it[1] > 0 }.map { it[0] } }
        .filter { it !in HMS }
        .distinct()

    /** Bag_TakeItem (one). False when the bag has none. */
    private fun takeOne(item: Int): Boolean {
        val p = pocketOf(item) ?: return false
        val s = slots(p)
        val slot = s.firstOrNull { it[0] == item && it[1] >= 1 } ?: return false
        slot[1]--
        if (slot[1] == 0) slot[0] = 0
        compact(s)
        writeSlots(p, s)
        return true
    }

    /** Bag_AddItem (one): onto its stack, else the first empty slot; TMs / HMs and berries sorted after. False if no room. */
    private fun addOne(item: Int): Boolean {
        val p = pocketOf(item) ?: return false
        val max = if (POCKET_ORDER[p] == FIELD_TMHMS) 99 else 999
        val s = slots(p)
        val have = s.firstOrNull { it[0] == item }
        val slot = when {
            have != null -> have.takeIf { it[1] + 1 <= max } ?: return false
            else -> s.firstOrNull { it[0] == 0 && it[1] == 0 } ?: return false
        }
        slot[0] = item
        slot[1]++
        if (POCKET_ORDER[p] == FIELD_TMHMS || POCKET_ORDER[p] == FIELD_BERRIES) sort(s)
        writeSlots(p, s)
        return true
    }

    // ---- BAG and TRAINER ([TrainerBagEditor]) ----

    override val money: Long get() = u32(general, l.trainer + 0x14)
    override val maxMoney: Long get() = MAX_MONEY
    override val playTime: String? get() = null

    override fun setMoney(amount: Long) {
        require(amount in 0..MAX_MONEY) { "money $amount" }
        putU32(general, l.trainer + 0x14, amount)
    }

    /** The struct pocket holding field pocket [kind] (PocketKind's order is item.h's POCKET_* numbers). */
    private fun structPocket(kind: PocketKind): Int = POCKET_ORDER.indexOf(kind.ordinal)

    override val pockets: List<SavePocket> get() = PocketKind.entries.map { kind ->
        val p = structPocket(kind)
        SavePocket(kind, l.pocketSizes[p], slots(p).filter { it[0] != 0 && it[1] > 0 }.map { ItemStack(it[0], it[1]) })
    }

    override fun stackMax(kind: PocketKind, item: Int): Int = when {
        item in HMS -> 1
        kind == PocketKind.TMHM -> 99
        else -> 999
    }

    override fun fixedStack(kind: PocketKind, item: Int): Boolean = kind == PocketKind.KEY || item in HMS

    override fun addable(kind: PocketKind): List<Int> =
        if (kind == PocketKind.KEY) emptyList()
        else (1 until 1000).filter { game.fieldPocket(it) == kind.ordinal && it !in HMS && game.itemName(it) != null }

    override fun tmMove(item: Int): Int? = game.tmMove(item)

    override fun setQuantity(kind: PocketKind, index: Int, quantity: Int) {
        val p = structPocket(kind)
        val s = slots(p)
        val filled = s.filter { it[0] != 0 && it[1] > 0 }
        require(index in filled.indices) { "no stack $index in $kind" }
        val slot = filled[index]
        if (quantity == 0) {
            require(!fixedStack(kind, slot[0])) { "item ${slot[0]} stays" }
            slot[0] = 0; slot[1] = 0
            compact(s)
        } else {
            require(!fixedStack(kind, slot[0]) && quantity in 1..stackMax(kind, slot[0])) { "$quantity of item ${slot[0]}" }
            slot[1] = quantity
        }
        writeSlots(p, s)
    }

    override fun addItem(kind: PocketKind, item: Int, quantity: Int) {
        require(item in addable(kind) && quantity in 1..stackMax(kind, item)) { "$quantity of item $item in $kind" }
        val p = structPocket(kind)
        val s = slots(p)
        require(s.none { it[0] == item && it[1] > 0 }) { "$item is in the bag already" }
        val slot = s.firstOrNull { it[0] == 0 || it[1] == 0 } ?: throw IllegalArgumentException("$kind is full")
        slot[0] = item; slot[1] = quantity
        if (kind == PocketKind.TMHM || kind == PocketKind.BERRIES) sort(s)
        writeSlots(p, s)
    }

    // ---- The Pokédex ----

    private fun dexBit(off: Int, species: Int) = (general[l.dex + off + (species - 1) / 8].toInt() shr ((species - 1) % 8)) and 1 == 1
    private val hasNational: Boolean get() = general[l.dex + l.dexNational].toInt() != 0
    /** The save has the National Pokédex (what Pal Park waits for). */
    val nationalDex: Boolean get() = hasNational
    /** [species] (a National Dex number) is caught in this save's Pokédex. */
    fun caughtInDex(species: Int): Boolean = species in 1..Gen4GameData.LAST_SPECIES && dexBit(DEX_CAUGHT, species)
    /** The species the Pokédex lists now: the regional ones until the National Pokédex. */
    fun dexSpecies(): List<Int> =
        (1..Gen4GameData.LAST_SPECIES).filter { hasNational || game.regional.isEmpty() || inRegional(it) }
    private fun inRegional(species: Int) = (game.regional.getOrNull(species) ?: 0) != 0

    override val dex: DexSummary get() {
        var seen = 0; var caught = 0; var rSeen = 0; var rCaught = 0
        for (sp in 1..Gen4GameData.LAST_SPECIES) {
            val sn = dexBit(DEX_SEEN, sp)
            val c = dexBit(DEX_CAUGHT, sp)
            if (sn) seen++
            if (c) caught++
            if (inRegional(sp)) { if (sn) rSeen++; if (c) rCaught++ }
        }
        return DexSummary(l.region.takeIf { game.regional.isNotEmpty() }, rSeen, rCaught, seen, caught, hasNational)
    }

    override fun registerOwned() {
        val d = data
        for (mon in d.partyMons.map { it.mon as Gen4BoxMon } + (0 until Gen4SaveData.BOXES).flatMap { b -> (0 until Gen4SaveData.SLOTS).map { d.mon(BoxSlot(b, it)) } }) {
            if (mon.isEmpty || mon.isEgg || mon.isBadEgg) continue
            registerCaught(mon)
        }
    }

    /**
     * Every species the dex lists marked the way meeting one does (Pokedex_Encounter / _Capture): seen with its usual gender
     * (male where it has both) and its first form, and when [caught], caught in the game's own language. Species already
     * seen keep what they have.
     */
    override fun markAllDex(caught: Boolean) {
        val national = hasNational || game.regional.isEmpty()
        for (sp in 1..Gen4GameData.LAST_SPECIES) {
            if (!national && !inRegional(sp)) continue
            val dex = l.dex
            fun setBit(off: Int, state: Boolean) {
                val o = dex + off + (sp - 1) / 8
                val m = 1 shl ((sp - 1) % 8)
                general[o] = ((general[o].toInt() and m.inv()) or (if (state) m else 0)).toByte()
            }
            if (!dexBit(DEX_SEEN, sp)) {
                val ratio = game.genderRatio(sp)
                val gender = if (ratio == 254) 1 else 0   // genderless is recorded as 0 too
                setBit(DEX_SEEN_GENDER_0, gender == 1)
                setBit(DEX_SEEN_GENDER_1, gender == 1)
                when (sp) {
                    UNOWN -> appendUnown(0, caught = false)
                    BURMY, WORMADAM, SHELLOS, GASTRODON, SHAYMIN, GIRATINA, DEOXYS, ROTOM, PICHU ->
                        appendSeenFormOf(sp, 0, if (gender == 1) Gen3Gender.FEMALE else Gen3Gender.MALE)
                }
                setBit(DEX_SEEN, true)
            }
            if (caught && !dexBit(DEX_CAUGHT, sp)) {
                if (sp == UNOWN && l.dexUnownCaught >= 0) appendUnown(0, caught = true)
                val shift = listOf(1, 2, 3, 5, 4, 7).indexOf(game.gameLanguage)
                if (shift >= 0) general[dex + l.dexLanguages + sp] = (general[dex + l.dexLanguages + sp].toInt() or (1 shl shift)).toByte()
                setBit(DEX_CAUGHT, true)
            }
        }
    }

    /** GIVE / TAKE as the party menu does them: the new item out of the bag, the old one back in (refused, with nothing
     * changed, when the bag hasn't the item or has no room for the old one). Mail stays with its letter. */
    override fun setHeldItem(at: MonRef, item: Int) {
        val (mon, _) = editable(at)
        if (item == mon.heldItem) return
        refuseIf(game.isMail(mon.heldItem)) { tr("Mail stays with its letter") }
        require(item == 0 || item in holdable()) { "item $item isn't in the bag" }
        val before = general.copyOf()
        if (item != 0 && !takeOne(item)) { before.copyInto(general); throw IllegalArgumentException("item $item isn't in the bag") }
        if (mon.heldItem != 0 && !addOne(mon.heldItem)) {
            before.copyInto(general)
            throw SaveEditRefused(tr("The bag has no room for it"))
        }
        put(at, mon.withChanges(heldItem = item))
    }

    /** An item a Pokémon held, back into the bag (Poké Transfer's way); false when the bag has no room. */
    internal fun returnToBag(item: Int): Boolean = addOne(item)

    private val partyCount: Int get() = u32(general, l.party + 4).toInt()
    private fun partyOff(i: Int) = l.partyMons + i * Gen4BoxMon.PARTY_SIZE

    override fun monAt(at: MonRef): Gen4BoxMon? = data.mon(at)?.first

    private fun record(at: BoxSlot): ByteArray = Gen4SaveData.recordOff(at, l).let { pc.copyOfRange(it, it + Gen4BoxMon.SIZE) }
    private fun putRecord(at: BoxSlot, bytes: ByteArray) = bytes.copyInto(pc, Gen4SaveData.recordOff(at, l))

    /** CountPartyAliveNonEggMonsExcept. */
    private fun aliveExcept(except: Int): Int = data.partyMons.withIndex().count { (i, p) -> i != except && !p.mon.isEgg && !p.mon.isBadEgg && p.hp > 0 }

    /**
     * Writes [record] as party slot [index]: CopyBoxPokemonToPokemon (no status, a blank letter, no ball capsule, its level
     * and stats, full HP - [keep] instead keeps a party Pokémon's own status, HP and letter) and its aprijuice bytes reset.
     */
    private fun writePartyMon(index: Int, record: ByteArray, keep: ByteArray? = null) {
        val off = partyOff(index)
        val mon = Gen4BoxMon.read(record, 0, game)
        val ext = if (keep != null) {
            Gen4BoxMon.cryptParty(keep, Gen4BoxMon.SIZE, u32(keep, 0))
        } else ByteArray(Gen4BoxMon.PARTY_SIZE - Gen4BoxMon.SIZE).also { e ->
            // The blank letter (Mail_Init) as another party Pokémon of this save carries it.
            blankMail()?.copyInto(e, MAIL, 0, MAIL_SIZE)
        }
        val level = mon.level.coerceIn(1, 100)
        val stats = game.stats(mon, level)
        val oldMax = u16(ext, 8)
        val hp = u16(ext, 6)
        ext[4] = level.toByte()
        val newHp = when {
            mon.isEgg -> 0
            mon.species == game.shedinja -> if (keep != null && hp == 0) 0 else 1
            keep == null || (hp == 0 && oldMax == 0) -> stats[0]
            hp == 0 -> 0
            else -> (hp + stats[0] - oldMax).coerceIn(1, stats[0])
        }
        putU16(ext, 6, newHp)
        for (i in 0 until 6) putU16(ext, 8 + i * 2, if (mon.isEgg) 0 else stats[i])
        record.copyInto(general, off, 0, Gen4BoxMon.SIZE)
        Gen4BoxMon.cryptParty(ext, 0, mon.personality).copyInto(general, off + Gen4BoxMon.SIZE)
        // Party_SafeCopyMonToSlot_ResetAprijuiceModifiers: a Pokémon new to the slot starts without them.
        if (keep == null && l.aprijuice >= 0) for (b in 0 until Gen4SaveData.APRIJUICE_SIZE) general[l.aprijuice + index * Gen4SaveData.APRIJUICE_SIZE + b] = 0
    }

    /** A party Pokémon's letter that's blank (no mail held), to copy: Mail_Init writes the game's language and version. */
    private fun blankMail(): ByteArray? = data.partyMons.withIndex().firstOrNull { (_, p) -> !game.isMail(p.mon.heldItem) }?.let { (i, p) ->
        Gen4BoxMon.cryptParty(general, partyOff(i) + Gen4BoxMon.SIZE, p.mon.personality).copyOfRange(MAIL, MAIL + MAIL_SIZE)
    }

    /** Party_RemoveMon: the rest moved up with their aprijuice bytes, the last slot ZeroMonData'd, the count down. */
    private fun removePartyMon(index: Int) {
        val count = partyCount
        val aj = l.aprijuice
        val ajs = Gen4SaveData.APRIJUICE_SIZE
        for (i in index until count - 1) {
            general.copyInto(general, partyOff(i), partyOff(i + 1), partyOff(i + 1) + Gen4BoxMon.PARTY_SIZE)
            general.copyInto(general, aj + i * ajs, aj + (i + 1) * ajs, aj + (i + 2) * ajs)
        }
        val last = count - 1
        val empty = ByteArray(Gen4BoxMon.PARTY_SIZE)
        Gen4BoxMon.emptyRecord().copyInto(empty)
        Gen4BoxMon.crypt(ByteArray(Gen4BoxMon.PARTY_SIZE - Gen4BoxMon.SIZE), 0, Gen4BoxMon.PARTY_SIZE - Gen4BoxMon.SIZE, 0).copyInto(empty, Gen4BoxMon.SIZE)
        empty.copyInto(general, partyOff(last))
        for (b in 0 until ajs) general[aj + last * ajs + b] = 0
        putU32(general, l.party + 4, (count - 1).toLong())
    }

    private fun partyBytes(i: Int) = general.copyOfRange(partyOff(i), partyOff(i) + Gen4BoxMon.PARTY_SIZE)

    override fun move(from: MonRef, to: MonRef) {
        if (from == to) return
        when {
            from is MonRef.Pc && to is MonRef.Pc -> {
                val a = data.mon(from.at)
                val b = data.mon(to.at)
                refuseIf(a.isEmpty || a.isBadEgg) { "nothing movable there" }
                refuseIf(!b.isEmpty && b.isBadEgg) { "a Bad Egg sits there" }
                val ra = record(from.at)
                putRecord(from.at, record(to.at))
                putRecord(to.at, ra)
            }
            from is MonRef.Party && to is MonRef.Party -> {
                val count = partyCount
                refuseIf(from.index >= count) { "nothing there" }
                val target = to.index.coerceAtMost(count - 1)
                if (target == from.index) return
                val a = partyBytes(from.index)
                partyBytes(target).copyInto(general, partyOff(from.index))
                a.copyInto(general, partyOff(target))
                val aj = l.aprijuice
                val ajs = Gen4SaveData.APRIJUICE_SIZE
                val ja = general.copyOfRange(aj + from.index * ajs, aj + from.index * ajs + ajs)
                general.copyInto(general, aj + from.index * ajs, aj + target * ajs, aj + target * ajs + ajs)
                ja.copyInto(general, aj + target * ajs)
            }
            from is MonRef.Pc && to is MonRef.Party -> {
                val mon = data.mon(from.at)
                refuseIf(mon.isEmpty || mon.isBadEgg) { "nothing movable there" }
                val count = partyCount
                if (to.index >= count) {
                    refuseIf(count >= 6) { tr("Your party is full") }
                    writePartyMon(count, record(from.at))
                    putU32(general, l.party + 4, (count + 1).toLong())
                    putRecord(from.at, Gen4BoxMon.emptyRecord())
                } else {
                    val leaving = data.partyMons[to.index].mon
                    refuseIf(game.isMail(leaving.heldItem)) { tr("A Pokémon holding mail can't go in the PC") }
                    refuseIf(mon.isEgg && aliveExcept(to.index) == 0) { tr("That's your last Pokémon able to battle") }
                    val boxed = partyBytes(to.index).copyOf(Gen4BoxMon.SIZE)
                    writePartyMon(to.index, record(from.at))
                    putRecord(from.at, boxed)
                }
            }
            from is MonRef.Party && to is MonRef.Pc -> {
                val leaving = requireNotNull(data.partyMons.getOrNull(from.index)) { "nothing there" }.mon
                refuseIf(game.isMail(leaving.heldItem)) { tr("A Pokémon holding mail can't go in the PC") }
                val target = data.mon(to.at)
                refuseIf(!target.isEmpty && target.isBadEgg) { "a Bad Egg sits there" }
                val incomingAlive = !target.isEmpty && !target.isEgg
                refuseIf(!incomingAlive && aliveExcept(from.index) == 0) { tr("That's your last Pokémon able to battle") }
                val boxed = partyBytes(from.index).copyOf(Gen4BoxMon.SIZE)
                if (target.isEmpty) removePartyMon(from.index) else writePartyMon(from.index, record(to.at))
                putRecord(to.at, boxed)
            }
        }
    }

    // ---- What a trade puts in or takes out ----

    /** Party Pokémon [index]'s 236 bytes (a trade from the party keeps its HP, status and letter), or null. */
    fun partyRecord(index: Int): ByteArray? = if (index < partyCount) partyBytes(index) else null

    /** Puts a traded-in [record] at [at] (a party slot past the last = the end of the party); [partyMon] = its party bytes. */
    fun putIncoming(at: MonRef, record: ByteArray, partyMon: ByteArray? = null) {
        when (at) {
            is MonRef.Pc -> putRecord(at.at, record)
            is MonRef.Party -> {
                val count = partyCount
                if (at.index >= count) {
                    refuseIf(count >= 6) { tr("Your party is full") }
                    writePartyMon(count, record, partyMon)
                    putU32(general, l.party + 4, (count + 1).toLong())
                } else writePartyMon(at.index, record, partyMon)
            }
        }
    }

    /** Takes the Pokémon at [at] out (a trade sent it away). */
    fun takeOut(at: MonRef) {
        when (at) {
            is MonRef.Pc -> putRecord(at.at, Gen4BoxMon.emptyRecord())
            is MonRef.Party -> removePartyMon(at.index)
        }
    }

    /**
     * Pokedex_SetMonCaughtFlag (pokedex.c), what the game does for a Pokémon received in a trade: seen and caught, the
     * genders seen, the forms' order (Unown's letters, Burmy / Wormadam / Pichu, Shellos / Gastrodon / Shaymin /
     * Giratina, Deoxys, Rotom), Spinda's first personality, the language it was caught in.
     */
    fun registerCaught(mon: Gen4BoxMon) {
        val species = mon.species
        if (species !in 1..Gen4GameData.LAST_SPECIES) return
        val dex = l.dex
        fun bit(off: Int, id: Int) = (general[dex + off + (id - 1) / 8].toInt() shr ((id - 1) % 8)) and 1 == 1
        fun setBit(off: Int, id: Int, state: Int) {
            val o = dex + off + (id - 1) / 8
            val m = 1 shl ((id - 1) % 8)
            general[o] = ((general[o].toInt() and m.inv()) or (if (state != 0) m else 0)).toByte()
        }
        fun seenGender(state0: Int, num: Int) {
            val state = if (state0 == 2) 0 else state0
            if (num == 0) setBit(DEX_SEEN_GENDER_1, species, state)
            setBit(if (num == 0) DEX_SEEN_GENDER_0 else DEX_SEEN_GENDER_1, species, state)
        }
        val gender = when (mon.gender) { Gen3Gender.MALE -> 0; Gen3Gender.FEMALE -> 1; Gen3Gender.GENDERLESS -> 2 }
        val seen = bit(DEX_SEEN, species)
        if (!seen) {
            if (species == SPINDA) putU32(general, dex + DEX_SPINDA, mon.personality)
            seenGender(gender, 0)
        } else if ((if (bit(DEX_SEEN_GENDER_0, species)) 1 else 0) != gender) {
            seenGender(gender, 1)
        }
        appendSeenForm(mon, seen)
        if (species == UNOWN && l.dexUnownCaught >= 0) appendUnown(mon.form, caught = true)
        val shift = listOf(1, 2, 3, 5, 4, 7).indexOf(mon.language).let { if (it < 0) 6 else it }
        if (shift != 6) general[dex + l.dexLanguages + species] = (general[dex + l.dexLanguages + species].toInt() or (1 shl shift)).toByte()
        if (l.dexInternational >= 0 && mon.language != game.gameLanguage) general[dex + l.dexInternational] = 1
        setBit(DEX_CAUGHT, species, 1)
        setBit(DEX_SEEN, species, 1)
    }

    private fun appendUnown(letter: Int, caught: Boolean) {
        val base = l.dex + if (caught) l.dexUnownCaught else DEX_UNOWN_SEEN
        val list = (0 until 28).map { general[base + it].toInt() and 0xFF }
        if (letter in list) return
        val n = list.indexOfFirst { it == 0xFF }.let { if (it < 0) 28 else it }
        if (n < 28) general[base + n] = letter.toByte()
    }

    /** Pokedex_TryAppendSeenForm; [seen] = the species' seen bit before this Pokémon. */
    private fun appendSeenForm(mon: Gen4BoxMon, seen: Boolean) = appendSeenForm(mon.species, mon.form, mon.gender, seen)

    /** The first form of a species not seen yet. */
    private fun appendSeenFormOf(species: Int, form: Int, gender: Gen3Gender) = appendSeenForm(species, form, gender, seen = false)

    private fun appendSeenForm(species: Int, form: Int, gender: Gen3Gender, seen: Boolean) {
        val dex = l.dex
        fun twoMax(off: Int, state: Int) {
            val o = dex + off
            val v = general[o].toInt() and 0xFF
            val n = if (!seen) 0 else if ((v and 1) == ((v shr 1) and 1)) 1 else 2
            if ((0 until n).any { ((v shr it) and 1) == state }) return
            if (n < 2) {
                var w = (v and (1 shl n).inv()) or (state shl n)
                if (n == 0) w = (w and 2.inv()) or (state shl 1)
                general[o] = w.toByte()
            }
        }
        fun threeMax(off: Int, state: Int) {
            val o = dex + off
            val v = general[o].toInt() and 0xFF
            if (seen && (0 until 3).any { ((v shr (2 * it)) and 3) == state }) return
            val n = if (!seen) 0 else (0 until 3).firstOrNull { ((v shr (2 * it)) and 3) == 3 } ?: 3
            if (n < 3) general[o] = ((v and (3 shl (2 * n)).inv()) or (state shl (2 * n))).toByte()
        }
        when (species) {
            UNOWN -> appendUnown(form, caught = false)
            BURMY -> threeMax(DEX_BURMY, form)
            WORMADAM -> threeMax(DEX_WORMADAM, form)
            PICHU -> if (l.dexPichu >= 0) threeMax(l.dexPichu, if (form == 0) (if (gender == Gen3Gender.MALE) 0 else 1) else 2)
            SHELLOS -> twoMax(DEX_SHELLOS, form)
            GASTRODON -> twoMax(DEX_GASTRODON, form)
            SHAYMIN -> twoMax(l.dexShaymin, form)
            GIRATINA -> twoMax(l.dexGiratina, form)
            DEOXYS -> {
                // Four nibbles: caughtSpecies' and seenSpecies' last word, bits 24-31 each; 15 = none yet.
                fun nib(i: Int): Int = (u32(general, dex + (if (i < 2) DEX_CAUGHT else DEX_SEEN) + 60) shr (24 + 4 * (i % 2))).toInt() and 15
                if ((0 until 4).none { nib(it) == form }) {
                    val i = (0 until 4).firstOrNull { nib(it) == 15 } ?: 4
                    if (i < 4) {
                        val o = dex + (if (i < 2) DEX_CAUGHT else DEX_SEEN) + 60
                        val w = u32(general, o) and (15L shl (24 + 4 * (i % 2))).inv() and 0xFFFFFFFFL
                        putU32(general, o, w or (form.toLong() shl (24 + 4 * (i % 2))))
                    }
                }
            }
            ROTOM -> {
                val o = dex + l.dexRotom
                val v = u32(general, o)
                val n = if (!seen) 0 else (0 until 6).firstOrNull { ((v shr (3 * it)) and 7).toInt() == 7 } ?: 6
                if ((0 until n).none { ((v shr (3 * it)) and 7).toInt() == form } && n < 6) {
                    putU32(general, o, (v and (7L shl (3 * n)).inv() and 0xFFFFFFFFL) or (form.toLong() shl (3 * n)))
                }
            }
        }
    }

    override fun release(at: BoxSlot) {
        val mon = data.mon(at)
        require(!mon.isEmpty && !mon.isBadEgg) { "nothing to release at $at" }
        refuseIf(mon.isEgg) { tr("An egg can't be released") }
        putRecord(at, Gen4BoxMon.emptyRecord())
    }

    override fun sortBox(box: Int, by: BoxSort, name: (SaveMon) -> String) {
        val slots = (0 until Gen4SaveData.SLOTS).map { BoxSlot(box, it) }
        val mons = slots.map { record(it) to data.mon(it) }.filter { !it.second.isEmpty }
        val (good, bad) = mons.partition { !it.second.isBadEgg }
        val order = when (by) {
            BoxSort.DEX -> good.sortedWith(compareBy({ it.second.isEgg }, { it.second.species }, { -it.second.level }))
            BoxSort.LEVEL -> good.sortedWith(compareBy({ it.second.isEgg }, { -it.second.level }, { it.second.species }))
            BoxSort.NAME -> good.sortedWith(compareBy({ it.second.isEgg }, { name(it.second) }))
        } + bad
        slots.forEachIndexed { i, at -> putRecord(at, order.getOrNull(i)?.first ?: Gen4BoxMon.emptyRecord()) }
    }

    override fun isOwn(mon: SaveMon): Boolean {
        val d = data
        return mon.otTid == d.trainerId && mon.otSid == d.secretId && mon.otName == d.trainerName
    }

    private fun editable(at: MonRef): Pair<Gen4BoxMon, Int> {
        val (mon, level) = requireNotNull(data.mon(at)) { "nothing at $at" }
        require(!mon.isBadEgg && !mon.isEgg) { "eggs and Bad Eggs aren't edited" }
        return mon to level
    }

    /** Writes a changed record back; a party Pokémon keeps its own party fields, its level and stats recalculated. */
    private fun put(at: MonRef, record: ByteArray) {
        when (at) {
            is MonRef.Pc -> putRecord(at.at, record)
            is MonRef.Party -> {
                val old = partyBytes(at.index)
                writePartyMon(at.index, record, keep = old)
            }
        }
    }

    override fun levelAsRead(at: MonRef): Int {
        val mon = monAt(at) ?: return 1
        val read = Gen4SaveData(game, file.general, file.pc, file.layout)
        val was = read.partyMons.map { it.mon }.firstOrNull { it.personality == mon.personality && it.otTid == mon.otTid }
            ?: (0 until read.boxCount).asSequence().flatMap { read.boxMons(it) }.firstOrNull { !it.isEmpty && it.personality == mon.personality && it.otTid == mon.otTid }
        return was?.level ?: mon.level
    }

    override fun setLevel(at: MonRef, level: Int) {
        val (mon, _) = editable(at)
        require(level in levelAsRead(at)..100) { "level $level" }
        put(at, mon.withChanges(exp = game.expForLevel(mon.species, level)))
    }

    override fun setIvs(at: MonRef, ivs: IntArray) {
        val (mon, _) = editable(at)
        require(ivs.size == 6 && ivs.all { it in 0..Gen3StatLimits.IV_MAX }) { "IVs ${ivs.toList()}" }
        put(at, mon.withChanges(ivs = ivs))
    }

    override fun setEvs(at: MonRef, evs: IntArray) {
        val (mon, _) = editable(at)
        require(evs.size == 6 && evs.all { it in 0..Gen3StatLimits.EV_MAX } && evs.sum() <= Gen3StatLimits.EV_TOTAL) { "EVs ${evs.toList()}" }
        put(at, mon.withChanges(evs = evs))
    }

    override fun setFriendship(at: MonRef, value: Int) {
        val (mon, _) = editable(at)
        require(value in 0..255) { "friendship $value" }
        put(at, mon.withChanges(friendship = value))
    }

    /** As in Gen 3, the personality decides the nature, gender, shininess and (for a species with two) the ability; Gen 4
     * keeps the ability's id and the gender in the record too, so they're written with it. */
    override fun reroll(at: MonRef, nature: Int?, shiny: Boolean?, gender: Gen3Gender?, abilityNum: Int?) {
        val (mon, _) = editable(at)
        require(nature == null || nature in 0..24) { "nature $nature" }
        val abilities = game.abilities(mon.species)
        require(abilityNum == null || abilityNum in abilities.indices) { "ability $abilityNum" }
        val p = Gen3Personality.find(mon, nature, shiny, gender, abilityNum) ?: throw SaveEditRefused(tr("No Pokémon like that can exist"))
        val newGender = when (val r = game.genderRatio(mon.species)) {
            0 -> Gen3Gender.MALE
            254 -> Gen3Gender.FEMALE
            255 -> Gen3Gender.GENDERLESS
            else -> if ((p and 0xFF) < r) Gen3Gender.FEMALE else Gen3Gender.MALE
        }
        val ability = if (abilities.size == 2) abilities[(p and 1L).toInt()] else mon.ability
        put(at, mon.withChanges(personality = p, gender = newGender, ability = ability))
    }

    override fun learnable(at: MonRef): Set<Int> = data.mon(at)?.let { (mon, level) -> game.learnable(mon.species, level) } ?: emptySet()

    override fun setMoves(at: MonRef, moves: List<Int>) {
        val (mon, level) = editable(at)
        require(moves.size in 1..4 && moves.all { it > 0 && game.basePp(it) > 0 } && moves.toSet().size == moves.size) { "moves $moves" }
        val known = mon.moves
        val learnable by lazy { game.learnable(mon.species, level) }
        require(moves.all { it in known || it in learnable }) { "a move ${mon.species} can't learn" }
        val ids = IntArray(4)
        val pp = IntArray(4)
        val ups = IntArray(4)
        moves.forEachIndexed { i, m ->
            ids[i] = m
            val was = known.indexOf(m)
            if (was >= 0) {
                pp[i] = mon.pp[was]
                ups[i] = (mon.ppBonuses shr (was * 2)) and 3
            } else {
                pp[i] = game.basePp(m)
            }
        }
        put(at, mon.withChanges(moves = ids, pp = pp, ppUps = ups))
    }

    companion object {
        /** PlayerProfile's MAX_MONEY. */
        const val MAX_MONEY = 999_999L
        /** struct Pokedex's fields both games keep at the same place (pokedex.h). */
        private const val DEX_CAUGHT = 0x04
        private const val DEX_SEEN = 0x44
        private const val DEX_SEEN_GENDER_0 = 0x84
        private const val DEX_SEEN_GENDER_1 = 0xC4
        private const val DEX_SPINDA = 0x104
        private const val DEX_SHELLOS = 0x108
        private const val DEX_GASTRODON = 0x109
        private const val DEX_BURMY = 0x10A
        private const val DEX_WORMADAM = 0x10B
        private const val DEX_UNOWN_SEEN = 0x10C
        private const val UNOWN = 201
        private const val SPINDA = 327
        private const val PICHU = 172
        private const val BURMY = 412
        private const val WORMADAM = 413
        private const val SHELLOS = 422
        private const val GASTRODON = 423
        private const val DEOXYS = 386
        private const val ROTOM = 479
        private const val SHAYMIN = 492
        private const val GIRATINA = 487
        /** Each of them as item.h's field pocket: items, key items, TMs / HMs, mail, medicine, berries, balls, battle items. */
        private val POCKET_ORDER = intArrayOf(0, 7, 3, 5, 1, 4, 2, 6)
        private const val FIELD_TMHMS = 3
        private const val FIELD_BERRIES = 4
        private const val FIELD_MAIL = 5
        private const val FIELD_KEY_ITEMS = 7
        /** HM01 .. HM08: never held. */
        private val HMS = 420..427
        /** PartyPokemon's Mail (0x9C - 0x88) and its size. */
        private const val MAIL = 0x14
        private const val MAIL_SIZE = 0x38
    }
}
