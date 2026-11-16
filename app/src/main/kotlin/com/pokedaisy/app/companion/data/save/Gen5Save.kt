package com.pokedaisy.app.companion.data.save

import com.pokedaisy.app.companion.data.Gen4Text
import com.pokedaisy.app.companion.data.gen3ExperienceTables
import com.pokedaisy.app.companion.data.tmMovesBlack
import com.pokedaisy.app.companion.i18n.tr
import kotlin.random.Random

/**
 * A Black / White or Black 2 / White 2 save file (512 KB), read and written the games' way: the SAVE EDITOR's pages and
 * POKé TRANSFER. The data lives twice, at 0 and right after it (0x24000 / 0x26000), the two copies equal. It's cut into
 * blocks, each with a CRC16-CCITT (from 0xFFFF, no reflection: [Gen4SaveFile.crc16]) stored twice: right after the block's
 * own write counter (offset + length + 2) and in the checksum block's table (+ 2 x the block's index), whose own CRC follows
 * its footer (counter, size, magic 0x31053527). An edit rewrites the changed blocks' CRCs and the table's, then copies the
 * main data over the backup. Every block below was found by its CRC in the player's own saves of both games.
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
    /** The bag's and the trainer's blocks' lengths (both at fixed places, [ITEMS] / [TRAINER]). */
    val itemsLen: Int, val trainerLen: Int,
    /** The block that starts with the money. */
    val money: Int, val moneyLen: Int,
    /** The personal NARC's member that numbers the regional dex (a u16 per species, 999 = not in it). */
    val regionalMember: Int,
) {
    BW("Black / White", 0x24000, 0x21600, 0x4D4, 55, 0x23F00, 0x8C, 9, 0x320, 0x4D0, 0x9C0, 0x68, 0x21200, 0xEC, 668),
    B2W2("Black 2 / White 2", 0x26000, 0x21400, 0x4DC, 54, 0x25F00, 0x94, 11, 0x328, 0x4D8, 0x9EC, 0xB0, 0x21100, 0xF0, 709);

    /** The checksum block's own CRC (after its footer). */
    val checksumsCrc: Int get() = checksums + checksumsLen + 0x0E
    val footerMagic: Int get() = checksums + checksumsLen + 8

    /** The blocks an edit can touch, as (offset, length, index in the checksum table). */
    val editedBlocks: List<Triple<Int, Int, Int>> get() =
        listOf(Triple(BOX_NAMES, BOX_NAMES_LEN, 0)) +
            (0 until BOXES).map { Triple(FIRST_BOX + it * BOX_STRIDE, BOX_LEN, it + 1) } +
            listOf(
                Triple(ITEMS, itemsLen, 25), Triple(PARTY, PARTY_LEN, 26), Triple(TRAINER, trainerLen, 27),
                Triple(money, moneyLen, 52), Triple(dex, dexLen, dexIndex),
            )

    companion object {
        const val FILE_SIZE = 0x80000
        const val MAGIC = 0x31053527L
        const val BOXES = 24
        const val BOX_SLOTS = 30
        const val BOX_STRIDE = 0x1000
        const val BOX_LEN = 0xFF0
        const val FIRST_BOX = 0x400
        const val BOX_NAMES = 0x0
        const val BOX_NAMES_LEN = 0x3E0
        const val ITEMS = 0x18400
        const val PARTY = 0x18E00
        const val PARTY_LEN = 0x534
        const val TRAINER = 0x19400
        const val DEX_MAGIC = 0xBEEFCAFEL
    }
}

class Gen5SaveFile private constructor(val bytes: ByteArray, val layout: Gen5Layout) {
    /** The main copy of the data. */
    val data: ByteArray get() = bytes.copyOfRange(0, layout.size)

    /** The file with [edited] as both copies: the changed blocks' CRCs rewritten, then the table's. */
    fun withData(edited: ByteArray): ByteArray {
        require(edited.size == layout.size)
        val out = bytes.copyOf()
        edited.copyInto(out, 0)
        for ((off, len, index) in layout.editedBlocks) {
            if ((off until off + len).all { out[it] == bytes[it] }) continue
            val c = Gen4SaveFile.crc16(out, off, len)
            putU16(out, off + len + 2, c)
            putU16(out, layout.checksums + 2 * index, c)
        }
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
 * One Gen 5 BoxPokemon (136 bytes): Gen 4's record ([Gen4BoxMon.decode]) with its names in UTF-16, a nature of its own
 * (block B +0x19, any nature with any personality) and a hidden-ability bit (B +0x1A bit 0); where it was met is only the
 * DP fields (block D +0x16 egg, +0x18 met), the ball at D +0x1B.
 */
class Gen5BoxMon private constructor(
    override val personality: Long,
    private val flags: Int,
    private val data: ByteArray,
    private val checksumOk: Boolean,
    private val original: ByteArray,
    override val game: SaveGameData,
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
    override val ppBonuses: Int get() = (0 until 4).sumOf { ((data[b + 0x0C + it].toInt() and 3) shl (it * 2)) }
    private val ivWord: Long get() = u32(data, b + 0x10)
    override val ivs: IntArray get() = IntArray(6) { ((ivWord shr (it * 5)) and 0x1F).toInt() }
    override val isEgg: Boolean get() = (ivWord shr 30) and 1L == 1L
    /** It carries a nickname (the IV word's top bit): a trade evolution keeps it, else it takes the new species' name. */
    val nicknamed: Boolean get() = (ivWord shr 31) and 1L == 1L
    val form: Int get() = (data[b + 0x18].toInt() and 0xFF) shr 3
    override val gender: Gen3Gender get() = when (((data[b + 0x18].toInt() and 0xFF) shr 1) and 3) {
        0 -> Gen3Gender.MALE; 1 -> Gen3Gender.FEMALE; else -> Gen3Gender.GENDERLESS
    }
    override val nature: Int get() = data[b + 0x19].toInt() and 0xFF
    /** Its ability is the species' hidden one (a Dream World or Hidden Grotto Pokémon). */
    val hiddenAbility: Boolean get() = data[b + 0x1A].toInt() and 1 != 0
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

    /** This record with the given fields changed, encoded with a new checksum. Only for one that read correctly. */
    fun withChanges(
        exp: Long? = null, friendship: Int? = null, ability: Int? = null, evs: IntArray? = null, ivs: IntArray? = null,
        moves: IntArray? = null, pp: IntArray? = null, ppUps: IntArray? = null, gender: Gen3Gender? = null,
        personality: Long? = null, heldItem: Int? = null, nature: Int? = null, species: Int? = null,
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
        nature?.let { require(it in 0..24); n[b + 0x19] = it.toByte() }
        return Gen4BoxMon.build(personality ?: this.personality, flags and 7.inv(), n)
    }

    /** The 136 bytes as the game stores them. An unreadable record encodes back to itself. */
    fun encode(): ByteArray = if (!checksumOk) original.copyOf() else Gen4BoxMon.build(personality, flags and 7.inv(), data)

    companion object {
        const val SIZE = 0x88

        fun read(b: ByteArray, off: Int, game: SaveGameData): Gen5BoxMon {
            val original = b.copyOfRange(off, off + SIZE)
            val (logical, ok) = Gen4BoxMon.decode(b, off) ?: return Gen5BoxMon(0, 0, ByteArray(128), true, original, game)
            return Gen5BoxMon(u32(b, off), u16(b, off + 4), logical, ok, original, game)
        }

        /** [n] chars of UTF-16 from [off], up to the 0xFFFF end. Gen 5 prints ♂ / ♀ as 0x246D / 0x246E. */
        internal fun gen5String(b: ByteArray, off: Int, n: Int): String = buildString {
            for (i in 0 until n) {
                val ch = u16(b, off + i * 2)
                if (ch == 0xFFFF || ch == 0) break
                append(when (ch) { 0x246D -> '♂'; 0x246E -> '♀'; else -> ch.toChar() })
            }
        }

        /** [text] as Gen 5 writes a name in [chars] u16s: the characters, the 0xFFFF end, zeroes after. */
        internal fun gen5Codes(text: String, chars: Int): ByteArray {
            require(text.length < chars) { "too long" }
            val out = ByteArray(chars * 2)
            text.forEachIndexed { i, ch -> putU16(out, i * 2, when (ch) { '♂' -> 0x246D; '♀' -> 0x246E; else -> ch.code }) }
            putU16(out, text.length * 2, 0xFFFF)
            return out
        }
    }
}

/**
 * Black / White's (or their sequels') data the editor needs, from the player's own cart: names, each species' personal
 * record (a/0/1/6: base stats, gender ratio 0x12, growth rate 0x15, abilities 0x18-0x1A, machine bits from 0x28), level-up
 * moves (a/0/1/8), evolutions (a/0/1/9), each move's PP (a/0/2/1, byte 5), each item's bag pocket (a/0/2/4) and the
 * regional dex.
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
    /** Each species' level-up moves as (level, move). */
    private val levelUp: List<List<Pair<Int, Int>>> = emptyList(),
    /** Each species' evolutions as (method, param, target). */
    private val evolutions: List<List<Triple<Int, Int, Int>>> = emptyList(),
    /** Each move's base PP. */
    private val movePp: IntArray = IntArray(0),
    /** Each item's bag pocket (0 items, 1 medicine, 2 TMs / HMs, 3 berries, 4 key items), -1 for none. */
    private val itemPockets: IntArray = IntArray(0),
    /** Each species' regional Pokédex place (-1: not in it). */
    val regional: IntArray = IntArray(0),
) : SaveGameData {
    private fun p(s: Int): ByteArray? = personal.getOrNull(s)?.takeIf { it.size >= 0x1A }
    override fun speciesName(species: Int): String? = this.species.getOrNull(species)?.takeIf { species in 1..LAST_SPECIES && it.isNotBlank() }
    override fun moveName(move: Int): String? = moveNames.getOrNull(move)?.takeIf { it.isNotBlank() }
    override fun itemName(item: Int): String? = items.getOrNull(item)?.takeIf { it.isNotBlank() && it != "???" }
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
    override fun basePp(move: Int): Int = movePp.getOrElse(move) { 0 }
    override fun isMail(item: Int): Boolean = item in MAIL
    override val unown: Int get() = 201
    override val wurmpleLine: IntRange get() = 265..269
    override val shedinja: Int get() = 292

    /** The species' hidden ability (personal +0x1A), or 0. */
    fun hiddenAbility(species: Int): Int = p(species)?.let { it[0x1A].toInt() and 0xFF } ?: 0

    /** Whether this game has [item] at all (Black / White lack their sequels' newer ones). */
    fun hasItem(item: Int): Boolean = item == 0 || itemPockets.isEmpty() || pocketOf(item) != null

    /**
     * The evolution a trade sets off (the cart's evolution table): method 5 (trade), 6 (trade holding its item, used up),
     * 7 (traded for [partner], Karrablast and Shelmet's way). An Everstone stops any of them.
     */
    fun tradeEvolution(species: Int, heldItem: Int, partner: Int? = null): TradeEvolution? {
        if (heldItem == EVERSTONE) return null
        for ((method, param, target) in evolutions.getOrNull(species).orEmpty()) {
            if (method == 5) return TradeEvolution(species, 0, target)
            if (method == 6 && param == heldItem) return TradeEvolution(species, param, target)
            if (method == 7 && partner != null && param == partner) return TradeEvolution(species, 0, target)
        }
        return null
    }

    /** The bag pocket the game files [item] in (0 items ... 4 key items), or null. */
    fun pocketOf(item: Int): Int? = itemPockets.getOrNull(item)?.takeIf { it in 0..4 && itemName(item) != null }
    val itemCount: Int get() = itemPockets.size

    /** The move TM01..TM95 / HM01..HM06 teaches (their item ids: 328..419 TM01-92, 420..425 the HMs, 618..620 TM93-95). */
    fun tmMove(item: Int): Int? = machineIndex(item)?.let { tmMovesBlack.getOrNull(it) }

    /** Whether the species' evolution chain is in this game's data (the editor's learnsets need it). */
    val hasLearnsets: Boolean get() = levelUp.isNotEmpty()

    /** Each species' pre-evolution, from the evolution table. */
    private val preEvolution: IntArray by lazy {
        IntArray(LAST_SPECIES + 1).also { pre ->
            evolutions.forEachIndexed { from, list -> if (from in 1..LAST_SPECIES) list.forEach { (_, _, to) -> if (to in 1..LAST_SPECIES && pre[to] == 0) pre[to] = from } }
        }
    }

    /** What [species] (or what it evolved from) could know at [level]: level-up moves to then, and its TMs / HMs. */
    fun learnable(species: Int, level: Int): Set<Int> {
        if (species == SMEARGLE) return (1 until movePp.size).filter { it != STRUGGLE && movePp[it] > 0 }.toSet()
        val out = HashSet<Int>()
        var s = species
        var guard = 0
        while (s in 1..LAST_SPECIES && guard++ < 4) {
            levelUp.getOrNull(s)?.forEach { (lv, m) -> if (lv <= level) out += m }
            personal.getOrNull(s)?.takeIf { it.size >= 0x28 + 13 }?.let { r ->
                for (bit in tmMovesBlack.indices) if ((r[0x28 + bit / 8].toInt() shr (bit % 8)) and 1 == 1) out += tmMovesBlack[bit]
            }
            s = preEvolution.getOrElse(s) { 0 }
        }
        return out
    }

    companion object {
        const val LAST_SPECIES = 649
        private const val SMEARGLE = 235
        private const val EVERSTONE = 229
        private const val STRUGGLE = 165
        /** Gen 5's mail items. */
        val MAIL = 137..148
        /** HM01 .. HM06: never held or taken away. */
        val HMS = 420..425

        /** [item]'s place in [tmMovesBlack]: TM01..TM95, then HM01..HM06. */
        fun machineIndex(item: Int): Int? = when (item) {
            in 328..419 -> item - 328
            in 618..620 -> 92 + item - 618
            in HMS -> 95 + item - 420
            else -> null
        }
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
        val off = partyOff(i)
        val mon = Gen5BoxMon.read(data, off, game)
        val ext = Gen4BoxMon.crypt(data, off + Gen5BoxMon.SIZE, PARTY_SIZE - Gen5BoxMon.SIZE, mon.personality)
        PartyEntry(mon, ext[4].toInt() and 0xFF, u16(ext, 6), IntArray(6) { u16(ext, 8 + it * 2) })
    }
    override val boxCount: Int get() = Gen5Layout.BOXES
    /** The boxes the PC has opened so far (it adds more as they fill). */
    val unlockedBoxes: Int get() = (data[Gen5Layout.BOX_NAMES + 0x3DD].toInt() and 0xFF).coerceIn(1, Gen5Layout.BOXES)
    override val currentBox: Int get() = (data[Gen5Layout.BOX_NAMES].toInt() and 0xFF).coerceIn(0, Gen5Layout.BOXES - 1)
    override fun boxName(box: Int): String = Gen5BoxMon.gen5String(data, Gen5Layout.BOX_NAMES + 4 + 0x28 * box, 10)
    override fun boxMons(box: Int): List<SaveMon> = (0 until Gen5Layout.BOX_SLOTS).map { mon(BoxSlot(box, it)) }
    fun mon(at: BoxSlot): Gen5BoxMon = Gen5BoxMon.read(data, recordOff(at), game)
    override fun mon(at: MonRef): Pair<Gen5BoxMon, Int>? = when (at) {
        is MonRef.Party -> partyMons.getOrNull(at.index)?.let { (it.mon as Gen5BoxMon) to it.level }
        is MonRef.Pc -> mon(at.at).takeIf { !it.isEmpty }?.let { it to it.level }
    }
    /** The National Pokédex (the dex's packed word, bit 0). */
    val nationalDex: Boolean get() = u32(data, game.layout.dex + 4) and 1L == 1L

    /** Why this doesn't read as the cart's save (a hack's own layout, another game's save), or null. */
    fun mismatch(): String? {
        val count = u32(data, Gen5Layout.PARTY + 4)
        if (count !in 0L..6L) return "party count $count"
        partyMons.forEachIndexed { i, p ->
            if (p.mon.isBadEgg) return "party Pokémon ${i + 1} doesn't decode"
            if (p.mon.species !in 1..Gen5GameData.LAST_SPECIES) return "party Pokémon ${i + 1} has species ${p.mon.species}"
            if (!p.mon.isEgg && p.level !in 1..100) return "party Pokémon ${i + 1} is level ${p.level}"
        }
        val records = (0 until Gen5Layout.BOXES * Gen5Layout.BOX_SLOTS).map { mon(BoxSlot(it / Gen5Layout.BOX_SLOTS, it % Gen5Layout.BOX_SLOTS)) }
        val used = records.count { !it.isEmpty }
        val bad = records.count { !it.isEmpty && (it.isBadEgg || it.species !in 1..Gen5GameData.LAST_SPECIES) }
        if (used >= 4 && bad * 2 > used) return "$bad of $used PC records don't decode"
        return null
    }

    companion object {
        const val PARTY_SIZE = 220
        fun recordOff(at: BoxSlot): Int {
            require(at.box in 0 until Gen5Layout.BOXES && at.slot in 0 until Gen5Layout.BOX_SLOTS) { "no box slot $at" }
            return Gen5Layout.FIRST_BOX + at.box * Gen5Layout.BOX_STRIDE + at.slot * Gen5BoxMon.SIZE
        }
        fun partyOff(i: Int): Int = Gen5Layout.PARTY + 8 + i * PARTY_SIZE
    }
}

/**
 * A Gen 5 save's working copy: the SAVE EDITOR's pages (the party and PC by the PC's rules, a Pokémon's traits, the bag,
 * the money, the Pokédex - each value one the game could hold) and POKé TRANSFER's Pokémon put into its PC.
 */
class Gen5SaveEditor(val file: Gen5SaveFile, val game: Gen5GameData) : MonSaveEditor, TrainerBagEditor {
    private val work = file.data
    private val l: Gen5Layout get() = file.layout

    private fun refuseIf(condition: Boolean, why: () -> String) { if (condition) throw SaveEditRefused(why()) }

    override val data: Gen5SaveData get() = Gen5SaveData(game, work)
    override val changed: Boolean get() = !work.contentEquals(file.data)
    override fun fresh(): Gen5SaveEditor = Gen5SaveEditor(file, game)
    override fun build(): ByteArray = file.withData(work)
    override fun snapshot() = EditorSnapshot(listOf(work.copyOf()))
    override fun restore(snapshot: EditorSnapshot) { snapshot.parts[0].copyInto(work) }

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

    private fun record(at: BoxSlot): ByteArray = Gen5SaveData.recordOff(at).let { work.copyOfRange(it, it + Gen5BoxMon.SIZE) }

    // ---- Names ----

    override val canWriteNames: Boolean get() = true
    override val canEditItems: Boolean get() = true
    /** NICKNAME's length: five for a Japanese or Korean Pokémon (language 1 / 8), ten for the others. */
    override fun nicknameMax(at: MonRef): Int = when (monAt(at)?.language) { 1, 8 -> 5; else -> 10 }
    override val boxNameMax: Int get() = 8
    /** The DS font's characters (Gen 4's set, which Gen 5 keeps) and ♂ / ♀. */
    override fun canWrite(text: String): Boolean = Gen4Text.encode(text) != null

    override fun renameBox(box: Int, name: String) {
        require(box in 0 until Gen5Layout.BOXES)
        require(name.isNotBlank() && name.length <= boxNameMax && canWrite(name)) { "box name $name" }
        Gen5BoxMon.gen5Codes(name, 20).copyInto(work, Gen5Layout.BOX_NAMES + 4 + 0x28 * box)
    }

    override fun isOwn(mon: SaveMon): Boolean {
        val d = data
        return mon.otTid == d.trainerId && mon.otSid == d.secretId && mon.otName == d.trainerName
    }

    override fun setNickname(at: MonRef, name: String) {
        val (mon, _) = editable(at)
        require(isOwn(mon)) { "only its OT renames it" }
        require(name.isNotBlank() && name.length <= nicknameMax(at) && canWrite(name)) { "name $name" }
        put(at, mon.withChanges(nickname = Gen5BoxMon.gen5Codes(name, 11), nicknamed = name != game.speciesName(mon.species)))
    }

    // ---- The party ----

    private val partyCount: Int get() = u32(work, Gen5Layout.PARTY + 4).toInt()
    private fun partyOff(i: Int) = Gen5SaveData.partyOff(i)
    private fun partyBytes(i: Int) = work.copyOfRange(partyOff(i), partyOff(i) + Gen5SaveData.PARTY_SIZE)
    private val extSize = Gen5SaveData.PARTY_SIZE - Gen5BoxMon.SIZE

    override fun monAt(at: MonRef): Gen5BoxMon? = data.mon(at)?.first

    /** CountPartyAliveNonEggMonsExcept. */
    private fun aliveExcept(except: Int): Int = data.partyMons.withIndex().count { (i, p) -> i != except && !p.mon.isEgg && !p.mon.isBadEgg && p.hp > 0 }

    /** A blank letter as another party Pokémon of this save carries it (the game's language and version in it). */
    private fun blankMail(): ByteArray? = data.partyMons.withIndex().firstOrNull { (_, p) -> !game.isMail(p.mon.heldItem) }?.let { (i, p) ->
        Gen4BoxMon.crypt(work, partyOff(i) + Gen5BoxMon.SIZE, extSize, p.mon.personality).copyOfRange(MAIL, MAIL + MAIL_SIZE)
    }

    /**
     * Writes [record] as party slot [index]: no status, a blank letter, its level and stats, full HP - [keep] instead keeps a
     * party Pokémon's own status, HP and letter.
     */
    private fun writePartyMon(index: Int, record: ByteArray, keep: ByteArray? = null) {
        val off = partyOff(index)
        val mon = Gen5BoxMon.read(record, 0, game)
        val ext = if (keep != null) Gen4BoxMon.crypt(keep, Gen5BoxMon.SIZE, extSize, u32(keep, 0))
            else ByteArray(extSize).also { e -> blankMail()?.copyInto(e, MAIL) }
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
        record.copyInto(work, off, 0, Gen5BoxMon.SIZE)
        Gen4BoxMon.crypt(ext, 0, extSize, mon.personality).copyInto(work, off + Gen5BoxMon.SIZE)
    }

    /** The party's rest moved up, the last slot cleared, the count down. */
    private fun removePartyMon(index: Int) {
        val count = partyCount
        for (i in index until count - 1) work.copyInto(work, partyOff(i), partyOff(i + 1), partyOff(i + 1) + Gen5SaveData.PARTY_SIZE)
        val empty = ByteArray(Gen5SaveData.PARTY_SIZE)
        Gen4BoxMon.emptyRecord().copyInto(empty)
        Gen4BoxMon.crypt(ByteArray(extSize), 0, extSize, 0).copyInto(empty, Gen5BoxMon.SIZE)
        empty.copyInto(work, partyOff(count - 1))
        putU32(work, Gen5Layout.PARTY + 4, (count - 1).toLong())
    }

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
                partyBytes(target).copyInto(work, partyOff(from.index))
                a.copyInto(work, partyOff(target))
            }
            from is MonRef.Pc && to is MonRef.Party -> {
                val mon = data.mon(from.at)
                refuseIf(mon.isEmpty || mon.isBadEgg) { "nothing movable there" }
                val count = partyCount
                if (to.index >= count) {
                    refuseIf(count >= 6) { tr("Your party is full") }
                    writePartyMon(count, record(from.at))
                    putU32(work, Gen5Layout.PARTY + 4, (count + 1).toLong())
                    putRecord(from.at, Gen4BoxMon.emptyRecord())
                } else {
                    val leaving = data.partyMons[to.index].mon
                    refuseIf(game.isMail(leaving.heldItem)) { tr("A Pokémon holding mail can't go in the PC") }
                    refuseIf(mon.isEgg && aliveExcept(to.index) == 0) { tr("That's your last Pokémon able to battle") }
                    val boxed = partyBytes(to.index).copyOf(Gen5BoxMon.SIZE)
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
                val boxed = partyBytes(from.index).copyOf(Gen5BoxMon.SIZE)
                if (target.isEmpty) removePartyMon(from.index) else writePartyMon(from.index, record(to.at))
                putRecord(to.at, boxed)
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
        val slots = (0 until Gen5Layout.BOX_SLOTS).map { BoxSlot(box, it) }
        val mons = slots.map { record(it) to data.mon(it) }.filter { !it.second.isEmpty }
        val (good, bad) = mons.partition { !it.second.isBadEgg }
        val order = when (by) {
            BoxSort.DEX -> good.sortedWith(compareBy({ it.second.isEgg }, { it.second.species }, { -it.second.level }))
            BoxSort.LEVEL -> good.sortedWith(compareBy({ it.second.isEgg }, { -it.second.level }, { it.second.species }))
            BoxSort.NAME -> good.sortedWith(compareBy({ it.second.isEgg }, { name(it.second) }))
        } + bad
        slots.forEachIndexed { i, at -> putRecord(at, order.getOrNull(i)?.first ?: Gen4BoxMon.emptyRecord()) }
    }

    // ---- A Pokémon's page ----

    private fun editable(at: MonRef): Pair<Gen5BoxMon, Int> {
        val (mon, level) = requireNotNull(data.mon(at)) { "nothing at $at" }
        require(!mon.isBadEgg && !mon.isEgg) { "eggs and Bad Eggs aren't edited" }
        return mon to level
    }

    /** Writes a changed record back; a party Pokémon keeps its own party fields, its level and stats recalculated. */
    private fun put(at: MonRef, record: ByteArray) {
        when (at) {
            is MonRef.Pc -> putRecord(at.at, record)
            is MonRef.Party -> writePartyMon(at.index, record, keep = partyBytes(at.index))
        }
    }

    override fun levelAsRead(at: MonRef): Int {
        val mon = monAt(at) ?: return 1
        val read = Gen5SaveData(game, file.data)
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

    /**
     * Gen 5 keeps the nature on its own (any nature fits any personality), so a new nature is just written. Shininess, the
     * gender and which of two abilities (bit 16) still come from the personality: a new one keeps Unown's letter, Wurmple's
     * branch and the parity Gen 5 gives a personality against the trainer's IDs. A hidden ability stays as it is.
     */
    override fun reroll(at: MonRef, nature: Int?, shiny: Boolean?, gender: Gen3Gender?, abilityNum: Int?) {
        val (mon, _) = editable(at)
        require(nature == null || nature in 0..24) { "nature $nature" }
        val abilities = game.abilities(mon.species)
        require(abilityNum == null || abilityNum in abilities.indices) { "ability $abilityNum" }
        refuseIf(abilityNum != null && mon.hiddenAbility && abilities.getOrNull(abilityNum) != mon.ability) { tr("No Pokémon like that can exist") }
        if (shiny == null && gender == null && abilityNum == null) {
            nature?.let { put(at, mon.withChanges(nature = it)) }
            return
        }
        val p = findPersonality(mon, shiny, gender, if (mon.hiddenAbility) null else abilityNum)
            ?: throw SaveEditRefused(tr("No Pokémon like that can exist"))
        val newGender = genderOf(mon.species, p)
        val ability = if (!mon.hiddenAbility && abilities.size == 2) abilities[((p shr 16) and 1L).toInt()] else mon.ability
        put(at, mon.withChanges(personality = p, gender = newGender, ability = ability, nature = nature))
    }

    private fun genderOf(species: Int, p: Long): Gen3Gender = when (val ratio = game.genderRatio(species)) {
        0 -> Gen3Gender.MALE
        254 -> Gen3Gender.FEMALE
        255 -> Gen3Gender.GENDERLESS
        else -> if ((p and 0xFF) < ratio) Gen3Gender.FEMALE else Gen3Gender.MALE
    }

    private fun findPersonality(mon: Gen5BoxMon, shiny: Boolean?, gender: Gen3Gender?, abilityNum: Int?, random: Random = Random.Default): Long? {
        val species = mon.species
        val wantShiny = shiny ?: mon.isShiny
        val wantGender = gender ?: mon.gender
        if (wantGender != genderOf(species, mon.personality) && !game.hasBothGenders(species)) return null
        val twoAbilities = !mon.hiddenAbility && game.abilities(species).size == 2
        val wantBit = abilityNum ?: ((mon.personality shr 16) and 1L).toInt()
        val otId = mon.otId
        fun parity(p: Long) = ((p ushr 31) xor p xor otId xor (otId shr 16)) and 1L
        val keepParity = parity(mon.personality)
        val letter = Gen3Personality.unownLetter(mon.personality)
        val silcoon = Gen3Personality.wurmpleSilcoon(mon.personality)
        fun fits(p: Long): Boolean =
            Gen3Personality.isShiny(p, otId) == wantShiny &&
                genderOf(species, p) == wantGender &&
                (!twoAbilities || ((p shr 16) and 1L).toInt() == wantBit) &&
                parity(p) == keepParity &&
                (species != game.unown || Gen3Personality.unownLetter(p) == letter) &&
                (species !in game.wurmpleLine || Gen3Personality.wurmpleSilcoon(p) == silcoon)
        val ids = ((otId shr 16) xor (otId and 0xFFFF)) and 0xFFFF
        repeat(4_000_000) {
            val low = random.nextInt(0x10000).toLong()
            val high = if (wantShiny) (ids xor low xor random.nextInt(8).toLong()) and 0xFFFF else random.nextInt(0x10000).toLong()
            val p = (high shl 16) or low
            if (fits(p)) return p
        }
        return null
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

    // ---- The bag: five pockets of (item u16, quantity u16), kept in the order things went in ----

    private class Pocket(val kind: PocketKind, val off: Int, val slots: Int, val cart: Int)

    private val pocketList = listOf(
        Pocket(PocketKind.ITEMS, 0x000, 310, 0), Pocket(PocketKind.MEDICINE, 0x7D8, 48, 1),
        Pocket(PocketKind.TMHM, 0x624, 109, 2), Pocket(PocketKind.BERRIES, 0x898, 64, 3), Pocket(PocketKind.KEY, 0x4D8, 83, 4),
    )

    private fun pocketFor(kind: PocketKind) = pocketList.first { it.kind == kind }
    private fun pocketForItem(item: Int) = game.pocketOf(item)?.let { c -> pocketList.first { it.cart == c } }

    private fun slots(p: Pocket): MutableList<IntArray> = MutableList(p.slots) { i ->
        val o = Gen5Layout.ITEMS + p.off + i * 4
        intArrayOf(u16(work, o), u16(work, o + 2))
    }

    private fun writeSlots(p: Pocket, s: List<IntArray>) = s.forEachIndexed { i, (id, q) ->
        val o = Gen5Layout.ITEMS + p.off + i * 4
        putU16(work, o, id); putU16(work, o + 2, q)
    }

    /** An emptied stack taken out, the rest moved up in order. */
    private fun compact(s: MutableList<IntArray>) {
        val kept = s.filter { it[0] != 0 && it[1] > 0 }
        for (i in s.indices) s[i] = kept.getOrNull(i) ?: intArrayOf(0, 0)
    }

    override fun holdable(): List<Int> = pocketList.filter { it.kind != PocketKind.KEY && it.kind != PocketKind.TMHM }
        .flatMap { p -> slots(p).filter { it[0] != 0 && it[1] > 0 }.map { it[0] } }
        .distinct()

    private fun takeOne(item: Int): Boolean {
        val p = pocketForItem(item) ?: return false
        val s = slots(p)
        val slot = s.firstOrNull { it[0] == item && it[1] >= 1 } ?: return false
        slot[1]--
        if (slot[1] == 0) { slot[0] = 0; compact(s) }
        writeSlots(p, s)
        return true
    }

    private fun addOne(item: Int): Boolean {
        val p = pocketForItem(item) ?: return false
        val s = slots(p)
        val have = s.firstOrNull { it[0] == item && it[1] > 0 }
        val slot = when {
            have != null -> have.takeIf { it[1] + 1 <= stackMax(p.kind, item) } ?: return false
            else -> s.firstOrNull { it[0] == 0 || it[1] == 0 } ?: return false
        }
        slot[0] = item
        slot[1]++
        writeSlots(p, s)
        return true
    }

    // ---- What a trade puts in or takes out ----

    /** Party Pokémon [index]'s 220 bytes (a trade from the party keeps its HP, status and letter), or null. */
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
                    putU32(work, Gen5Layout.PARTY + 4, (count + 1).toLong())
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

    /** [name] as a nickname's 22 bytes (a trade evolution's new name), or null when it can't be written. */
    fun nameCodesFor(name: String): ByteArray? = name.takeIf { it.length < 11 }?.let { Gen5BoxMon.gen5Codes(it, 11) }

    /** An item a Pokémon held, back into the bag (Poké Transfer's way); false when the bag has no room. */
    internal fun returnToBag(item: Int): Boolean = addOne(item)

    /** GIVE / TAKE as the party menu does them: the new item out of the bag, the old one back in. Mail stays with its letter. */
    override fun setHeldItem(at: MonRef, item: Int) {
        val (mon, _) = editable(at)
        if (item == mon.heldItem) return
        refuseIf(game.isMail(mon.heldItem)) { tr("Mail stays with its letter") }
        require(item == 0 || item in holdable()) { "item $item isn't in the bag" }
        val before = work.copyOf()
        if (item != 0 && !takeOne(item)) { before.copyInto(work); throw IllegalArgumentException("item $item isn't in the bag") }
        if (mon.heldItem != 0 && !addOne(mon.heldItem)) {
            before.copyInto(work)
            throw SaveEditRefused(tr("The bag has no room for it"))
        }
        put(at, mon.withChanges(heldItem = item))
    }

    override val money: Long get() = u32(work, l.money)
    override val maxMoney: Long get() = MAX_MONEY
    override val playTime: String? get() {
        val h = u16(work, Gen5Layout.TRAINER + 0x24)
        val m = work[Gen5Layout.TRAINER + 0x26].toInt() and 0xFF
        return "$h:${m.toString().padStart(2, '0')}"
    }

    override fun setMoney(amount: Long) {
        require(amount in 0..MAX_MONEY) { "money $amount" }
        putU32(work, l.money, amount)
    }

    override val pockets: List<SavePocket> get() = pocketList.map { p ->
        SavePocket(p.kind, p.slots, slots(p).filter { it[0] != 0 && it[1] > 0 }.map { ItemStack(it[0], it[1]) })
    }

    /** 999 a stack; a TM or HM is one (Gen 5's machines aren't used up). */
    override fun stackMax(kind: PocketKind, item: Int): Int = when (kind) {
        PocketKind.TMHM, PocketKind.KEY -> 1
        else -> 999
    }

    override fun fixedStack(kind: PocketKind, item: Int): Boolean = kind == PocketKind.KEY || item in Gen5GameData.HMS

    override fun addable(kind: PocketKind): List<Int> {
        if (kind == PocketKind.KEY) return emptyList()
        val cart = pocketList.firstOrNull { it.kind == kind }?.cart ?: return emptyList()
        return (1 until game.itemCount).filter { game.pocketOf(it) == cart && it !in Gen5GameData.HMS }
    }

    override fun tmMove(item: Int): Int? = game.tmMove(item)

    override fun setQuantity(kind: PocketKind, index: Int, quantity: Int) {
        val p = pocketFor(kind)
        val s = slots(p)
        val filled = s.filter { it[0] != 0 && it[1] > 0 }
        require(index in filled.indices) { "no stack $index in $kind" }
        val slot = filled[index]
        require(!fixedStack(kind, slot[0])) { "item ${slot[0]} stays" }
        if (quantity == 0) {
            slot[0] = 0; slot[1] = 0
            compact(s)
        } else {
            require(quantity in 1..stackMax(kind, slot[0])) { "$quantity of item ${slot[0]}" }
            slot[1] = quantity
        }
        writeSlots(p, s)
    }

    override fun addItem(kind: PocketKind, item: Int, quantity: Int) {
        require(item in addable(kind) && quantity in 1..stackMax(kind, item)) { "$quantity of item $item in $kind" }
        val p = pocketFor(kind)
        val s = slots(p)
        require(s.none { it[0] == item && it[1] > 0 }) { "$item is in the bag already" }
        val slot = s.firstOrNull { it[0] == 0 || it[1] == 0 } ?: throw IllegalArgumentException("$kind is full")
        slot[0] = item; slot[1] = quantity
        writeSlots(p, s)
    }

    // ---- The Pokédex ----

    private fun bit(off: Int, n: Int) = (work[off + (n shr 3)].toInt() shr (n and 7)) and 1 == 1
    private fun setBit(off: Int, n: Int) { work[off + (n shr 3)] = (work[off + (n shr 3)].toInt() or (1 shl (n and 7))).toByte() }
    private fun seenArea(r: Int) = l.dex + 0x5C + r * 0x54
    private fun shownArea(r: Int) = l.dex + 0x1AC + r * 0x54
    private fun seen(species: Int) = (0 until 4).any { bit(seenArea(it), species - 1) }
    private val hasNational: Boolean get() = u32(work, l.dex + 4) and 1L == 1L
    private fun inRegional(species: Int) = (game.regional.getOrNull(species) ?: -1) >= 0

    override val dex: DexSummary get() {
        var s = 0; var c = 0; var rs = 0; var rc = 0
        for (sp in 1..Gen5GameData.LAST_SPECIES) {
            val sn = seen(sp)
            val ct = caughtInDex(sp)
            if (sn) s++
            if (ct) c++
            if (inRegional(sp)) { if (sn) rs++; if (ct) rc++ }
        }
        return DexSummary("UNOVA".takeIf { game.regional.isNotEmpty() }, rs, rc, s, c, hasNational)
    }

    /** The species the Pokédex lists now: the regional ones until the National Pokédex. */
    fun dexSpecies(): List<Int> = (1..Gen5GameData.LAST_SPECIES).filter { hasNational || game.regional.isEmpty() || inRegional(it) }

    override fun registerOwned() {
        val d = data
        val all = d.partyMons.map { it.mon as Gen5BoxMon } +
            (0 until Gen5Layout.BOXES).flatMap { b -> (0 until Gen5Layout.BOX_SLOTS).map { d.mon(BoxSlot(b, it)) } }
        for (mon in all) if (!mon.isEmpty && !mon.isEgg && !mon.isBadEgg) registerCaught(mon)
    }

    /**
     * Every species the dex lists marked the way meeting one does: seen with its usual gender (male where it has both), not
     * shiny, its first form, displayed so; when [caught], caught in the game's own language too. What's there stays.
     */
    override fun markAllDex(caught: Boolean) {
        for (sp in dexSpecies()) {
            val n = sp - 1
            val region = if (game.genderRatio(sp) == 254) 1 else 0
            if (!seen(sp)) {
                if (sp == SPINDA) putU32(work, l.dex + l.dexSpinda, 0)
                setBit(seenArea(region), n)
                if ((0 until 4).none { bit(shownArea(it), n) }) setBit(shownArea(region), n)
                formIndex(sp)?.let { (base, count) ->
                    setBit(formArea(0), base)
                    if ((0 until count).none { bit(formArea(2), base + it) || bit(formArea(3), base + it) }) setBit(formArea(2), base)
                }
            }
            if (caught && !caughtInDex(sp)) {
                languageBit(game.gameLanguage)?.let { if (sp <= 493) setBit(l.dex + l.dexLanguages, n * 7 + it) }
                setBit(l.dex + 0x08, n)
            }
        }
    }

    private fun formArea(a: Int) = l.dex + 0x2FC + a * l.formLen
    private fun languageBit(language: Int): Int? = when (language) { 1 -> 0; 2 -> 1; 3 -> 2; 4 -> 3; 5 -> 4; 7 -> 5; 8 -> 6; else -> null }

    /**
     * What the game records for a Pokémon it receives (the Pokédex's caught / seen / displayed bits, its language, its form,
     * Spinda's first personality): seen in its gender / shiny region, caught, displayed if nothing of it is yet, and the
     * same for its form.
     */
    fun registerCaught(mon: Gen5BoxMon) {
        val species = mon.species
        if (species !in 1..Gen5GameData.LAST_SPECIES || mon.isEgg) return
        val n = species - 1
        val region = (if (mon.isShiny) 2 else 0) or (if (mon.gender == Gen3Gender.FEMALE) 1 else 0)
        if (species == SPINDA && !seen(species)) putU32(work, l.dex + l.dexSpinda, mon.personality)
        setBit(seenArea(region), n)
        setBit(l.dex + 0x08, n)
        if ((0 until 4).none { bit(shownArea(it), n) }) setBit(shownArea(region), n)
        if (species <= 493) languageBit(mon.language)?.let { setBit(l.dex + l.dexLanguages, n * 7 + it) }
        val (base, count) = formIndex(species) ?: return
        val form = mon.form.takeIf { it < count } ?: return
        setBit(formArea(if (mon.isShiny) 1 else 0), base + form)
        if ((0 until count).none { bit(formArea(2), base + it) || bit(formArea(3), base + it) }) setBit(formArea(if (mon.isShiny) 3 else 2), base + form)
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

    /** The save has the National Pokédex. */
    val nationalDex: Boolean get() = hasNational

    companion object {
        /** Gen 5's money cap. */
        const val MAX_MONEY = 9_999_999L
        private const val SPINDA = 327
        /** A party slot's letter (after the 136-byte record) and its size. */
        private const val MAIL = 0x14
        private const val MAIL_SIZE = 0x38
    }
}
