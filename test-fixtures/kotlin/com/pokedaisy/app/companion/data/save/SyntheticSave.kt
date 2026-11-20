package com.pokedaisy.app.companion.data.save

import com.pokedaisy.app.companion.data.gen3ExperienceTables
import com.pokedaisy.app.companion.data.gen3GrowthRates
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Builds Gen 3 saves and box records from scratch for the tests, written independently of the
 * code under test (its own checksum, substruct order and encryption), so a shared mistake can't
 * pass both ways. The repo is public: no real save can be checked in.
 */
object SyntheticSave {

    private const val SECTOR = 0x1000
    private const val DATA = 0xF80

    /** A 128 KB save (plus [rtc] bytes of mGBA's RTC footer) with slot 0 / 1 as given; null = an erased (0xFF) slot. */
    fun file(format: Gen3SaveFormat, slot0: Slot?, slot1: Slot?, rtc: Int = 16): ByteArray {
        val out = ByteArray(0x20000 + rtc) { 0xFF.toByte() }
        // Sectors 28-31 (Hall of Fame, ...) and the RTC footer: recognisable bytes that must survive any edit.
        for (i in 28 * SECTOR until out.size) out[i] = (i * 7 + 3).toByte()
        slot0?.let { writeSlot(out, 0, format, it) }
        slot1?.let { writeSlot(out, 1, format, it) }
        return out
    }

    class Slot(val counter: Long, val rotation: Int, val sb2: ByteArray, val sb1: ByteArray, val storage: ByteArray)

    fun blocks(format: Gen3SaveFormat, seed: Int = 1): Triple<ByteArray, ByteArray, ByteArray> {
        val sb2 = ByteArray(format.saveBlock2Size)
        // "RED" in the game's charset, then the terminator; male; TID 12345 / SID 54321; 12:34 played.
        byteArrayOf(0xCC.toByte(), 0xBF.toByte(), 0xBE.toByte(), 0xFF.toByte()).copyInto(sb2, 0)
        le16(sb2, 0x0A, 12345); le16(sb2, 0x0C, 54321); le16(sb2, 0x0E, 12); sb2[0x10] = 34
        if (format.encryptionKeyOff >= 0) le32(sb2, format.encryptionKeyOff, 0xA5A5_1234L + seed)
        val sb1 = ByteArray(format.saveBlock1Size)
        val key = if (format.encryptionKeyOff >= 0) 0xA5A5_1234L + seed else 0L
        le32(sb1, format.moneyOff, 3000L xor key)
        sb1[format.partyCountOff] = 2
        mon(personality = 0x1234_5678L, otId = 0x0BAD_F00DL, species = 6, exp = 125_000).copyInto(sb1, format.partyOff)
        sb1[format.partyOff + 0x54] = 50
        partyHp(sb1, format.partyOff, 150)
        mon(personality = 0x0000_0017L, otId = 0x0BAD_F00DL, species = 25, exp = 1000, item = 13).copyInto(sb1, format.partyOff + 100)
        sb1[format.partyOff + 100 + 0x54] = 10
        partyHp(sb1, format.partyOff + 100, 30)
        val storage = ByteArray(format.storageSize)
        storage[0] = 1
        // Box 1: three mons; box 2: one. Every personality % 24 shows up across the tests' records.
        for (i in 0 until 3) mon(personality = 0x100L + i * 5 + seed, otId = 0x0BAD_F00DL, species = 1 + i * 3, exp = 500L + i).copyInto(storage, 4 + i * 80)
        mon(personality = 0xFFFF_FFF0L, otId = 0x1L, species = 150, exp = 1_250_000, item = 1).copyInto(storage, 4 + 30 * 80)
        // Box names: "BOX1", "BOX2", ...
        for (b in 0 until 14) {
            val o = 4 + 14 * 30 * 80 + b * 9
            byteArrayOf(0xBC.toByte(), 0xC9.toByte(), 0xD2.toByte()).copyInto(storage, o)
            if (b < 9) { storage[o + 3] = (0xA2 + b).toByte(); storage[o + 4] = 0xFF.toByte() }
            else { storage[o + 3] = 0xA2.toByte(); storage[o + 4] = (0xA1 + b - 9).toByte(); storage[o + 5] = 0xFF.toByte() }
        }
        return Triple(sb2, sb1, storage)
    }

    /** An encrypted 80-byte BoxPokemon. */
    fun mon(
        personality: Long, otId: Long, species: Int, exp: Long, item: Int = 0,
        moves: IntArray = intArrayOf(33, 45, 0, 0), ivs: IntArray = intArrayOf(31, 0, 15, 7, 1, 30),
        isEgg: Boolean = false, fateful: Boolean = false, nickname: String = "PIKA", ot: String = "ASH", metGame: Int = 4,
    ): ByteArray {
        val growth = ByteArray(12).also { le16(it, 0, species); le16(it, 2, item); le32(it, 4, exp); it[9] = 70 }
        val attacks = ByteArray(12).also { a -> moves.forEachIndexed { i, m -> le16(a, i * 2, m); if (m != 0) a[8 + i] = 35 } }
        val evs = ByteArray(12).also { it[0] = 4; it[5] = 252.toByte() }
        var ivWord = 0L
        ivs.forEachIndexed { i, v -> ivWord = ivWord or (v.toLong() shl (i * 5)) }
        if (isEgg) ivWord = ivWord or (1L shl 30)
        val misc = ByteArray(12).also {
            it[1] = 88; le16(it, 2, 5 or (metGame shl 7) or (4 shl 11)); le32(it, 4, ivWord)
            if (fateful) le32(it, 8, 1L shl 31)
        }
        val logical = arrayOf(growth, attacks, evs, misc)
        var sum = 0
        for (s in logical) for (i in 0 until 6) sum = (sum + ((s[i * 2].toInt() and 0xFF) or ((s[i * 2 + 1].toInt() and 0xFF) shl 8))) and 0xFFFF
        val out = ByteArray(80)
        le32(out, 0, personality); le32(out, 4, otId)
        text(nickname, 10).copyInto(out, 8)
        out[0x12] = 2; out[0x13] = 2   // English; hasSpecies
        text(ot, 7).copyInto(out, 0x14)
        le16(out, 0x1C, sum)
        val order = ORDERS[(personality % 24).toInt()]
        val key = personality xor otId
        order.forEachIndexed { slot, name ->
            val sub = logical["GAEM".indexOf(name)]
            for (w in 0 until 3) le32(out, 0x20 + slot * 12 + w * 4, (rd32(sub, w * 4) xor key) and 0xFFFFFFFFL)
        }
        return out
    }

    // Bulbapedia-style order strings, independent of the app's index table.
    private val ORDERS = listOf(
        "GAEM", "GAME", "GEAM", "GEMA", "GMAE", "GMEA", "AGEM", "AGME", "AEGM", "AEMG", "AMGE", "AMEG",
        "EGAM", "EGMA", "EAGM", "EAMG", "EMGA", "EMAG", "MGAE", "MGEA", "MAGE", "MAEG", "MEGA", "MEAG",
    )

    private fun writeSlot(out: ByteArray, slot: Int, format: Gen3SaveFormat, s: Slot) {
        for (id in 0 until 14) {
            val base = (slot * 14 + (id + s.rotation) % 14) * SECTOR
            out.fill(0, base, base + SECTOR)
            val (block, start, size) = when (id) {
                0 -> Triple(s.sb2, 0, s.sb2.size)
                in 1..4 -> Triple(s.sb1, (id - 1) * DATA, minOf(DATA, s.sb1.size - (id - 1) * DATA))
                else -> Triple(s.storage, (id - 5) * DATA, minOf(DATA, s.storage.size - (id - 5) * DATA).coerceAtLeast(0))
            }
            System.arraycopy(block, start, out, base, size)
            var sum = 0L
            for (i in 0 until size / 4) sum += rd32(out, base + i * 4)
            sum = sum and 0xFFFFFFFFL
            le16(out, base + 0xFF4, id)
            le16(out, base + 0xFF6, (((sum shr 16) + sum) and 0xFFFF).toInt())
            le32(out, base + 0xFF8, 0x08012025L)
            le32(out, base + 0xFFC, s.counter)
        }
    }

    /** A made-up FireRed save for PC BOXES' screenshots: a full party, a box and a half of Pokémon and an egg. */
    fun demoFireRed(): Gen3SaveFile {
        val f = Gen3SaveFormat.FIRERED_LEAFGREEN
        val (sb2, sb1, storage) = blocks(f)
        fun exp(species: Int, level: Int) = gen3ExperienceTables[gen3GrowthRates[species].toInt()][level].toLong()
        fun mon(i: Int, species: Int, level: Int, name: String, item: Int = 0, egg: Boolean = false, game: Int = 4) = mon(
            personality = 0x2468_0000L + i * 7919L, otId = (54321L shl 16) or 12345L, species = species, exp = exp(species, level), item = item,
            moves = intArrayOf(53, 17, 89, 0), isEgg = egg, nickname = if (egg) "EGG" else name, ot = "RED", metGame = game,
        )
        val names = mapOf(
            1 to "BULBASAUR", 4 to "CHARMANDER", 6 to "CHARIZARD", 7 to "SQUIRTLE", 16 to "PIDGEY", 19 to "RATTATA",
            25 to "PIKACHU", 35 to "CLEFAIRY", 39 to "JIGGLYPUFF", 41 to "ZUBAT", 52 to "MEOWTH", 54 to "PSYDUCK",
            63 to "ABRA", 65 to "ALAKAZAM", 66 to "MACHOP", 74 to "GEODUDE", 92 to "GASTLY", 94 to "GENGAR",
            95 to "ONIX", 129 to "MAGIKARP", 131 to "LAPRAS", 133 to "EEVEE", 143 to "SNORLAX", 147 to "DRATINI",
            277 to "TREECKO", 280 to "TORCHIC", 283 to "MUDKIP",
        )
        val party = listOf(6 to 52, 25 to 38, 131 to 45, 143 to 47, 94 to 44, 65 to 46)
        sb1[f.partyCountOff] = party.size.toByte()
        party.forEachIndexed { i, (s, l) ->
            val o = f.partyOff + i * 100
            sb1.fill(0, o, o + 100)
            mon(i, s, l, names.getValue(s), item = if (i == 0) 200 else 0).copyInto(sb1, o)
            sb1[o + 0x54] = l.toByte()
            partyHp(sb1, o, 40 + l)
        }
        storage.fill(0, 4, 4 + 14 * 30 * 80)
        listOf(1, 4, 7, 16, 19, 35, 39, 41, 52, 54, 63, 66, 74, 92, 95, 129, 133, 147, 277, 280, 283).forEachIndexed { i, s ->
            mon(100 + i, s, 5 + (i * 3) % 30, names.getValue(s), game = if (s >= 277) 3 else 4).copyInto(storage, 4 + i * 80)
        }
        mon(200, 25, 5, "PIKACHU", egg = true).copyInto(storage, 4 + 23 * 80)
        listOf(16, 19, 41, 129).forEachIndexed { i, s -> mon(300 + i, s, 12 + i, names.getValue(s)).copyInto(storage, 4 + (30 + i) * 80) }
        storage[0] = 0
        // Items, key items, balls, TM case, berry pouch (FireRed's SaveBlock1 offsets).
        bag(sb2, sb1, 0xF20, 0x310, listOf(13 to 12, 22 to 5, 19 to 3, 17 to 9, 23 to 2, 26 to 4, 84 to 6, 110 to 1, 200 to 1))
        bag(sb2, sb1, 0xF20, 0x3B8, listOf(260 to 1, 262 to 1, 361 to 1, 364 to 1))
        bag(sb2, sb1, 0xF20, 0x430, listOf(4 to 23, 3 to 10, 2 to 4))
        bag(sb2, sb1, 0xF20, 0x464, listOf(294 to 1, 316 to 2, 323 to 1, 339 to 1, 340 to 1, 341 to 1))
        bag(sb2, sb1, 0xF20, 0x54C, listOf(133 to 4, 138 to 7, 139 to 2))
        le16(sb1, 0x294, 350 xor (rd32(sb2, 0xF20) and 0xFFFF).toInt())
        val bytes = file(f, Slot(57, 4, sb2, sb1, storage), null)
        return (Gen3SaveFile.parse(bytes, f) as Gen3SaveFile.Parsed.Ok).save
    }

    /** TRADE's screenshots: [demoFireRed] with its National Dex and Sevii Islands, beside a made-up Emerald save. */
    fun demoTrade(): Pair<Gen3SaveFile, Gen3SaveFile> {
        val fr = demoFireRed()
        val frSb2 = fr.saveBlock2.copyOf()
        val frSb1 = fr.saveBlock1.copyOf()
        progress(fr.format, frSb2, frSb1)
        val e = Gen3SaveFormat.EMERALD
        val (sb2, sb1, storage) = blocks(e, seed = 3)
        storage.fill(0, 4, 4 + 14 * 30 * 80)
        listOf(277 to "TREECKO", 280 to "TORCHIC", 283 to "MUDKIP", 64 to "KADABRA", 95 to "ONIX", 151 to "MEW").forEachIndexed { i, (sp, n) ->
            mon(0x5150_0000L + i * 131, 0x1234_0007L, sp, exp = 8000, nickname = n, ot = "MAY", metGame = 3).copyInto(storage, 4 + i * 80)
        }
        storage[0] = 0   // the PC opens on BOX 1
        progress(e, sb2, sb1)
        fun parse(f: Gen3SaveFormat, bytes: ByteArray) = (Gen3SaveFile.parse(bytes, f) as Gen3SaveFile.Parsed.Ok).save
        return parse(fr.format, file(fr.format, Slot(fr.counter, fr.rotation, frSb2, frSb1, fr.storage), null)) to
            parse(e, file(e, Slot(12, 5, sb2, sb1, storage), null))
    }

    /** A party Pokémon's HP and max HP (the rest of its stats left 0): enough to count as able to battle. */
    fun partyHp(sb1: ByteArray, off: Int, hp: Int) { le16(sb1, off + 0x56, hp); le16(sb1, off + 0x58, hp); sb1[off + 0x55] = 0xFF.toByte() }

    /** The National Dex (and FireRed's Sevii Islands) in a save's blocks. */
    fun progress(f: Gen3SaveFormat, sb2: ByteArray, sb1: ByteArray) {
        sb2[f.nationalMagicOff] = f.nationalMagic.toByte()
        le16(sb1, f.varsOff + (f.nationalVar - 0x4000) * 2, f.nationalVarValue)
        for (id in listOf(f.nationalFlag, f.linkRsFlag).filter { it >= 0 }) {
            sb1[f.flagsOff + id / 8] = (sb1[f.flagsOff + id / 8].toInt() or (1 shl (id % 8))).toByte()
        }
    }

    /**
     * Fills one bag pocket of [sb1] at [off] (global.h's offsets, given by the test, not the format) with (item, quantity)
     * pairs, quantities XORed with the low half of the key in [sb2] at [keyOff] (-1: Ruby / Sapphire, no key).
     */
    fun bag(sb2: ByteArray, sb1: ByteArray, keyOff: Int, off: Int, items: List<Pair<Int, Int>>) {
        val key = if (keyOff >= 0) (rd32(sb2, keyOff) and 0xFFFF).toInt() else 0
        items.forEachIndexed { i, (item, n) -> le16(sb1, off + i * 4, item); le16(sb1, off + i * 4 + 2, n xor key) }
    }

    /** [s] in the games' Western charset (capitals, digits, space), 0xFF-terminated / padded to [len]. */
    fun text(s: String, len: Int): ByteArray = ByteArray(len) { 0xFF.toByte() }.also { out ->
        s.take(len).forEachIndexed { i, c ->
            out[i] = when (c) {
                in 'A'..'Z' -> 0xBB + (c - 'A')
                in 'a'..'z' -> 0xD5 + (c - 'a')
                in '0'..'9' -> 0xA1 + (c - '0')
                else -> 0x00
            }.toByte()
        }
    }

    fun rd32(b: ByteArray, o: Int): Long = ByteBuffer.wrap(b, o, 4).order(ByteOrder.LITTLE_ENDIAN).int.toLong() and 0xFFFFFFFFL
    fun le16(b: ByteArray, o: Int, v: Int) { b[o] = v.toByte(); b[o + 1] = (v shr 8).toByte() }
    fun le32(b: ByteArray, o: Int, v: Long) { for (i in 0 until 4) b[o + i] = (v shr (8 * i)).toByte() }
}
