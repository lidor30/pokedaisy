package com.pokedaisy.app.companion.data.save

import com.pokedaisy.app.companion.data.gen3ExperienceTables

/**
 * A Black / White or Black 2 / White 2 save file (512 KB), as POKé TRANSFER needs it: the PC boxes and the Pokédex read
 * and written the games' way. The data lives twice, at 0 and right after it (0x24000 / 0x26000), the two copies equal.
 * It's cut into blocks, each with a CRC16-CCITT (from 0xFFFF, no reflection: [Gen4SaveFile.crc16]) stored twice: right
 * after the block's own write counter (offset + length + 2) and in the checksum block's table (+ 2 x the block's index),
 * whose own CRC follows its footer (counter, size, magic 0x31053527). An edit rewrites the changed blocks' CRCs and the
 * table's, then copies the main data over the backup. Checked against real saves of both games (block CRCs, footer).
 */
enum class Gen5Layout(
    val label: String,
    /** One copy of the data. */
    val size: Int,
    val dex: Int, val dexLen: Int, val dexIndex: Int,
    /** The checksum block: its table and its length. */
    val checksums: Int, val checksumsLen: Int,
    /** The Pokédex's form areas' size, language flags and Spinda's personality (inside the dex block). */
    val formLen: Int, val dexLanguages: Int, val dexSpinda: Int,
) {
    BW("Black / White", 0x24000, 0x21600, 0x4D4, 55, 0x23F00, 0x8C, 9, 0x320, 0x4D0),
    B2W2("Black 2 / White 2", 0x26000, 0x21400, 0x4DC, 54, 0x25F00, 0x94, 11, 0x328, 0x4D8);

    /** The checksum block's own CRC (after its footer). */
    val checksumsCrc: Int get() = checksums + checksumsLen + 0x0E
    val footerMagic: Int get() = checksums + checksumsLen + 8

    companion object {
        const val FILE_SIZE = 0x80000
        const val MAGIC = 0x31053527L
        const val BOXES = 24
        const val BOX_SLOTS = 30
        const val BOX_STRIDE = 0x1000
        const val BOX_LEN = 0xFF0
        const val FIRST_BOX = 0x400
        const val BOX_NAMES = 0x0
        const val PARTY = 0x18E00
        const val TRAINER = 0x19400
        const val DEX_MAGIC = 0xBEEFCAFEL
    }
}

class Gen5SaveFile private constructor(val bytes: ByteArray, val layout: Gen5Layout) {
    /** The main copy of the data. */
    val data: ByteArray get() = bytes.copyOfRange(0, layout.size)

    /** The file with [edited] as both copies: the changed boxes' and the Pokédex's CRCs rewritten, then the table's. */
    fun withData(edited: ByteArray): ByteArray {
        require(edited.size == layout.size)
        val out = bytes.copyOf()
        edited.copyInto(out, 0)
        fun block(off: Int, len: Int, index: Int) {
            if ((off until off + len).all { out[it] == bytes[it] }) return
            val c = Gen4SaveFile.crc16(out, off, len)
            putU16(out, off + len + 2, c)
            putU16(out, layout.checksums + 2 * index, c)
        }
        for (n in 0 until Gen5Layout.BOXES) block(Gen5Layout.FIRST_BOX + n * Gen5Layout.BOX_STRIDE, Gen5Layout.BOX_LEN, n + 1)
        block(layout.dex, layout.dexLen, layout.dexIndex)
        putU16(out, layout.checksumsCrc, Gen4SaveFile.crc16(out, layout.checksums, layout.checksumsLen))
        out.copyInto(out, layout.size, 0, layout.size)
        return out
    }

    sealed class Parsed {
        class Ok(val save: Gen5SaveFile) : Parsed()
        class Failed(val reason: String) : Parsed()
    }

    companion object {
        fun parse(bytes: ByteArray): Parsed {
            if (bytes.size < Gen5Layout.FILE_SIZE) return Parsed.Failed("not a Black / White save (${bytes.size} bytes)")
            val layout = Gen5Layout.entries.firstOrNull { u32(bytes, it.footerMagic) == Gen5Layout.MAGIC }
                ?: return Parsed.Failed("not a Black / White save (no footer)")
            if (Gen4SaveFile.crc16(bytes, layout.checksums, layout.checksumsLen) != u16(bytes, layout.checksumsCrc)) {
                return Parsed.Failed("its checksums don't match")
            }
            if (u32(bytes, layout.dex) != Gen5Layout.DEX_MAGIC) return Parsed.Failed("its Pokédex isn't where it should be")
            return Parsed.Ok(Gen5SaveFile(bytes.copyOf(), layout))
        }
    }
}

/**
 * One Gen 5 BoxPokemon (136 bytes): Gen 4's record ([Gen4BoxMon.decode]) with its names in UTF-16 and a nature of its own
 * (block B +0x19); where it was met is only the DP fields (block D +0x16 egg, +0x18 met), the ball at D +0x1B.
 */
class Gen5BoxMon private constructor(
    override val personality: Long,
    private val data: ByteArray,
    private val checksumOk: Boolean,
    override val game: SaveGameData,
) : SaveMon {
    private val a = 0
    private val b = 32
    private val c = 64
    private val d = 96

    override val isEmpty: Boolean get() = checksumOk && species == 0
    override val isBadEgg: Boolean get() = !checksumOk
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
    override val ppBonuses: Int get() = (0 until 4).sumOf { ((data[b + 0x0C + it].toInt() and 3) shl (it * 2)) }
    private val ivWord: Long get() = u32(data, b + 0x10)
    override val ivs: IntArray get() = IntArray(6) { ((ivWord shr (it * 5)) and 0x1F).toInt() }
    override val isEgg: Boolean get() = (ivWord shr 30) and 1L == 1L
    val form: Int get() = (data[b + 0x18].toInt() and 0xFF) shr 3
    override val gender: Gen3Gender get() = when (((data[b + 0x18].toInt() and 0xFF) shr 1) and 3) {
        0 -> Gen3Gender.MALE; 1 -> Gen3Gender.FEMALE; else -> Gen3Gender.GENDERLESS
    }
    override val nature: Int get() = data[b + 0x19].toInt() and 0xFF
    override val pokerus: Int get() = data[d + 0x1A].toInt() and 0xFF
    override val metLevel: Int get() = data[d + 0x1C].toInt() and 0x7F
    val metLocation: Int get() = u16(data, d + 0x18)
    val ball: Int get() = data[d + 0x1B].toInt() and 0xFF
    override val abilityNum: Int get() = game.abilities(species).indexOf(ability).coerceAtLeast(0)
    override val isShiny: Boolean get() = Gen3Personality.isShiny(personality, otId)
    override val level: Int get() = game.levelFor(species, exp)
    override val nickname: String? get() = gen5String(data, c, 11).ifEmpty { null }
    override val otName: String get() = gen5String(data, d, 8)
    override val metGameLabel: String get() = when (data[c + 0x17].toInt() and 0xFF) {
        1 -> "SAPPHIRE"; 2 -> "RUBY"; 3 -> "EMERALD"; 4 -> "FIRERED"; 5 -> "LEAFGREEN"; 7 -> "HEARTGOLD"; 8 -> "SOULSILVER"
        10 -> "DIAMOND"; 11 -> "PEARL"; 12 -> "PLATINUM"; 15 -> "COLOSSEUM / XD"
        20 -> "WHITE"; 21 -> "BLACK"; 22 -> "WHITE 2"; 23 -> "BLACK 2"
        else -> "?"
    }

    companion object {
        const val SIZE = 0x88

        fun read(b: ByteArray, off: Int, game: SaveGameData): Gen5BoxMon {
            val (logical, ok) = Gen4BoxMon.decode(b, off) ?: return Gen5BoxMon(0, ByteArray(128), true, game)
            return Gen5BoxMon(u32(b, off), logical, ok, game)
        }

        /** [n] chars of UTF-16 from [off], up to the 0xFFFF end. Gen 5 prints ♂ / ♀ as 0x246D / 0x246E. */
        internal fun gen5String(b: ByteArray, off: Int, n: Int): String = buildString {
            for (i in 0 until n) {
                val ch = u16(b, off + i * 2)
                if (ch == 0xFFFF || ch == 0) break
                append(when (ch) { 0x246D -> '♂'; 0x246E -> '♀'; else -> ch.toChar() })
            }
        }
    }
}

/**
 * Black / White's (or their sequels') data the PC needs, from the player's own cart: names, each species' personal
 * record (a/0/1/6: base stats, gender ratio 0x12, growth rate 0x15, abilities 0x18-0x19) and ability names.
 */
class Gen5GameData(
    private val species: List<String>,
    private val moveNames: List<String>,
    private val items: List<String>,
    private val abilityNames: List<String>,
    private val personal: List<ByteArray?>,
    /** The PK language byte of the cart's language (2 English, 3 French ...). */
    val gameLanguage: Int,
    val layout: Gen5Layout,
) : SaveGameData {
    private fun p(s: Int): ByteArray? = personal.getOrNull(s)?.takeIf { it.size >= 0x1A }
    override fun speciesName(species: Int): String? = this.species.getOrNull(species)?.takeIf { species in 1..LAST_SPECIES && it.isNotBlank() }
    override fun moveName(move: Int): String? = moveNames.getOrNull(move)?.takeIf { it.isNotBlank() }
    override fun itemName(item: Int): String? = items.getOrNull(item)?.takeIf { it.isNotBlank() }
    override fun abilityName(ability: Int): String = abilityNames.getOrNull(ability).orEmpty()
    override fun nationalDex(species: Int): Int? = species.takeIf { it in 1..LAST_SPECIES }
    override fun abilities(species: Int): List<Int> =
        p(species)?.let { r -> listOf(r[0x18].toInt() and 0xFF, r[0x19].toInt() and 0xFF).filter { it != 0 }.distinct() }.orEmpty().ifEmpty { listOf(0) }
    override fun genderRatio(species: Int): Int = p(species)?.let { it[0x12].toInt() and 0xFF } ?: 127
    override fun baseStats(species: Int): IntArray = p(species)?.let { r -> IntArray(6) { r[it].toInt() and 0xFF } } ?: IntArray(6)
    override fun expForLevel(species: Int, level: Int): Long {
        val rate = p(species)?.let { it[0x15].toInt() and 0xFF }?.takeIf { it in gen3ExperienceTables.indices } ?: 0
        return gen3ExperienceTables[rate][level.coerceIn(1, 100)].toLong()
    }
    override fun basePp(move: Int): Int = 0
    override fun isMail(item: Int): Boolean = item in 137..148
    override val unown: Int get() = 201
    override val wurmpleLine: IntRange get() = 265..269
    override val shedinja: Int get() = 292

    companion object {
        const val LAST_SPECIES = 649
    }
}

/** A Gen 5 save's trainer, party and PC, read. */
class Gen5SaveData(override val game: Gen5GameData, private val data: ByteArray) : MonSave {
    override val trainerName: String get() = Gen5BoxMon.gen5String(data, Gen5Layout.TRAINER + 4, 8)
    override val trainerId: Int get() = u16(data, Gen5Layout.TRAINER + 0x14)
    override val secretId: Int get() = u16(data, Gen5Layout.TRAINER + 0x16)
    /** The trainer's language (the PK byte: 2 English ...). */
    val language: Int get() = data[Gen5Layout.TRAINER + 0x1E].toInt() and 0xFF
    override val partyCount: Int get() = u32(data, Gen5Layout.PARTY + 4).toInt().coerceIn(0, 6)
    override val partyMons: List<PartyEntry> get() = (0 until partyCount).map { i ->
        val mon = Gen5BoxMon.read(data, Gen5Layout.PARTY + 8 + i * PARTY_SIZE, game)
        PartyEntry(mon, mon.level, 0, game.stats(mon, mon.level))
    }
    override val boxCount: Int get() = Gen5Layout.BOXES
    /** The boxes the PC has opened so far (it adds more as they fill). */
    val unlockedBoxes: Int get() = (data[Gen5Layout.BOX_NAMES + 0x3DD].toInt() and 0xFF).coerceIn(1, Gen5Layout.BOXES)
    override val currentBox: Int get() = (data[Gen5Layout.BOX_NAMES].toInt() and 0xFF).coerceIn(0, Gen5Layout.BOXES - 1)
    override fun boxName(box: Int): String = Gen5BoxMon.gen5String(data, Gen5Layout.BOX_NAMES + 4 + 0x28 * box, 10)
    override fun boxMons(box: Int): List<SaveMon> = (0 until Gen5Layout.BOX_SLOTS).map { mon(BoxSlot(box, it)) }
    fun mon(at: BoxSlot): Gen5BoxMon = Gen5BoxMon.read(data, recordOff(at), game)
    override fun mon(at: MonRef): Pair<SaveMon, Int>? = when (at) {
        is MonRef.Party -> partyMons.getOrNull(at.index)?.let { it.mon to it.level }
        is MonRef.Pc -> mon(at.at).takeIf { !it.isEmpty }?.let { it to it.level }
    }
    /** The National Pokédex (the dex's packed word, bit 0). */
    val nationalDex: Boolean get() = u32(data, game.layout.dex + 4) and 1L == 1L

    companion object {
        const val PARTY_SIZE = 220
        fun recordOff(at: BoxSlot): Int = Gen5Layout.FIRST_BOX + at.box * Gen5Layout.BOX_STRIDE + at.slot * Gen5BoxMon.SIZE
    }
}

/** A Gen 5 save's working copy for POKé TRANSFER: Pokémon put into its PC, its Pokédex told. */
class Gen5SaveEditor(val file: Gen5SaveFile, val game: Gen5GameData) {
    private val work = file.data
    private val l: Gen5Layout get() = file.layout

    val data: Gen5SaveData get() = Gen5SaveData(game, work)
    val changed: Boolean get() = !work.contentEquals(file.data)
    fun fresh(): Gen5SaveEditor = Gen5SaveEditor(file, game)
    fun build(): ByteArray = file.withData(work)
    fun snapshot(): ByteArray = work.copyOf()
    fun restore(copy: ByteArray) = copy.copyInto(work)

    /** The first [n] empty PC places from the box the PC is on, among the boxes it has opened; fewer when it's full. */
    fun freePlaces(n: Int): List<BoxSlot> {
        val d = data
        val boxes = d.unlockedBoxes
        val out = ArrayList<BoxSlot>()
        for (k in 0 until boxes) {
            val box = (d.currentBox.coerceAtMost(boxes - 1) + k) % boxes
            for (slot in 0 until Gen5Layout.BOX_SLOTS) {
                if (out.size == n) return out
                if (d.mon(BoxSlot(box, slot)).isEmpty) out += BoxSlot(box, slot)
            }
        }
        return out
    }

    fun putRecord(at: BoxSlot, record: ByteArray) {
        require(record.size == Gen5BoxMon.SIZE)
        record.copyInto(work, Gen5SaveData.recordOff(at))
    }

    private fun bit(off: Int, n: Int) = (work[off + (n shr 3)].toInt() shr (n and 7)) and 1 == 1
    private fun setBit(off: Int, n: Int) { work[off + (n shr 3)] = (work[off + (n shr 3)].toInt() or (1 shl (n and 7))).toByte() }

    /**
     * What the game records for a Pokémon it receives (the Pokédex's caught / seen / displayed bits, its language, its form,
     * Spinda's first personality): seen in its gender / shiny region, caught, displayed if nothing of it is yet, and the
     * same for its form.
     */
    fun registerCaught(mon: Gen5BoxMon) {
        val species = mon.species
        if (species !in 1..Gen5GameData.LAST_SPECIES || mon.isEgg) return
        val dex = l.dex
        val n = species - 1
        val region = (if (mon.isShiny) 2 else 0) or (if (mon.gender == Gen3Gender.FEMALE) 1 else 0)
        fun seen(r: Int) = dex + 0x5C + r * 0x54
        fun shown(r: Int) = dex + 0x1AC + r * 0x54
        if (species == SPINDA && (0 until 4).none { bit(seen(it), n) }) putU32(work, dex + l.dexSpinda, mon.personality)
        setBit(seen(region), n)
        setBit(dex + 0x08, n)
        if ((0 until 4).none { bit(shown(it), n) }) setBit(shown(region), n)
        if (species <= 493) {
            val lang = when (mon.language) { 1 -> 0; 2 -> 1; 3 -> 2; 4 -> 3; 5 -> 4; 7 -> 5; 8 -> 6; else -> -1 }
            if (lang >= 0) setBit(dex + l.dexLanguages, n * 7 + lang)
        }
        val (base, count) = formIndex(species) ?: return
        val form = mon.form.takeIf { it < count } ?: return
        fun area(a: Int) = dex + 0x2FC + a * l.formLen
        setBit(area(if (mon.isShiny) 1 else 0), base + form)
        val shownArea = area(if (mon.isShiny) 3 else 2)
        if ((0 until count).none { bit(area(2), base + it) || bit(area(3), base + it) }) setBit(shownArea, base + form)
    }

    /** The species' first bit in the form areas and how many forms it has. */
    private fun formIndex(species: Int): Pair<Int, Int>? = when (species) {
        201 -> 0 to 28; 386 -> 28 to 4; 492 -> 32 to 2; 487 -> 34 to 2; 479 -> 36 to 6; 422 -> 42 to 2; 423 -> 44 to 2
        412 -> 46 to 3; 413 -> 49 to 3; 351 -> 52 to 4; 421 -> 56 to 2; 585 -> 58 to 4; 586 -> 62 to 4; 648 -> 66 to 2
        555 -> 68 to 2; 550 -> 70 to 2
        else -> if (l == Gen5Layout.B2W2) when (species) {
            646 -> 72 to 3; 647 -> 75 to 2; 642 -> 77 to 2; 641 -> 79 to 2; 645 -> 81 to 2
            else -> null
        } else null
    }

    fun caughtInDex(species: Int): Boolean = species in 1..Gen5GameData.LAST_SPECIES && bit(l.dex + 0x08, species - 1)

    private companion object {
        const val SPINDA = 327
    }
}
