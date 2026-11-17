package com.pokedaisy.app.companion.data.save

import com.pokedaisy.app.companion.data.gen3ExperienceTables
import com.pokedaisy.app.companion.data.gen3GrowthRates
import com.pokedaisy.app.companion.data.gen3SubstructOrder

/**
 * One struct BoxPokemon (80 bytes: the PC's records, and the first 80 bytes of a party mon), with
 * its four 12-byte substructs decrypted (key = personality ^ OT id) and put in logical order:
 * Growth, Attacks, EVs / condition, Misc. [encode] puts it back the way the game would - checksum
 * over the decrypted words, then reordered by personality % 24 and encrypted - so a record that
 * was never changed encodes to its own bytes.
 */
class Gen3BoxMon private constructor(
    private val header: ByteArray,
    private val data: ByteArray,
    val checksumOk: Boolean,
    /** The record as read: what an unreadable one [encode]s back to. */
    private val original: ByteArray,
) : SaveMon {

    override val game: SaveGameData get() = Gen3GameData
    override val personality: Long get() = u32(header, 0)
    val otId: Long get() = u32(header, 4)
    val nicknameBytes: ByteArray get() = header.copyOfRange(0x08, 0x12)
    val language: Int get() = header[0x12].toInt() and 0xFF
    private val flags: Int get() = header[0x13].toInt() and 0xFF
    /** The game's own "Bad Egg" bit (set when it found the checksum wrong). */
    override val isBadEgg: Boolean get() = flags and 1 != 0 || !checksumOk
    val otNameBytes: ByteArray get() = header.copyOfRange(0x14, 0x1B)

    // Growth
    override val species: Int get() = u16(data, 0)
    override val heldItem: Int get() = u16(data, 2)
    override val exp: Long get() = u32(data, 4)
    override val ppBonuses: Int get() = data[8].toInt() and 0xFF
    override val friendship: Int get() = data[9].toInt() and 0xFF
    // Attacks
    override val moves: IntArray get() = IntArray(4) { u16(data, 12 + it * 2) }
    override val pp: IntArray get() = IntArray(4) { data[20 + it].toInt() and 0xFF }
    // EVs / condition
    override val evs: IntArray get() = IntArray(6) { data[24 + it].toInt() and 0xFF }
    // Misc
    val metLocation: Int get() = data[37].toInt() and 0xFF
    private val origins: Int get() = u16(data, 38)
    override val metLevel: Int get() = origins and 0x7F
    /** GAME_VERSION the mon was met in (1 Sapphire, 2 Ruby, 3 Emerald, 4 FireRed, 5 LeafGreen, 15 Colosseum / XD). */
    val metGame: Int get() = (origins shr 7) and 0xF
    val ball: Int get() = (origins shr 11) and 0xF
    private val ivWord: Long get() = u32(data, 40)
    override val ivs: IntArray get() = IntArray(6) { ((ivWord shr (it * 5)) and 0x1F).toInt() }
    override val isEgg: Boolean get() = (ivWord shr 30) and 1L == 1L
    override val abilityNum: Int get() = ((ivWord shr 31) and 1L).toInt()
    /** The ribbons word: five contest ranks (3 bits each), the twelve others' bits, then the fateful-encounter bit. */
    val ribbons: Long get() = u32(data, 44)
    /** The top ribbon bit: the "fateful encounter" the trade code checks on Mew and Deoxys (obedience). */
    val fatefulEncounter: Boolean get() = (ribbons shr 31) and 1L == 1L
    /** The summary's marks (circle, square, triangle, heart). */
    val markings: Int get() = header[0x1B].toInt() and 0xFF
    /** Condition: cool, beauty, cute, smart, tough, then sheen. */
    val contest: IntArray get() = IntArray(6) { data[30 + it].toInt() and 0xFF }
    /** The original trainer's gender (1 = female). */
    val otGender: Int get() = (origins shr 15) and 1

    override val isEmpty: Boolean get() = checksumOk && species == 0
    override val nature: Int get() = (personality % 25).toInt()

    /** The trainer ID / secret ID halves of [otId]. */
    override val otTid: Int get() = (otId and 0xFFFF).toInt()
    override val otSid: Int get() = ((otId shr 16) and 0xFFFF).toInt()

    /** IsShinyOtIdPersonality: OT ID ^ secret ID ^ the personality's halves under 8. */
    override val isShiny: Boolean get() = Gen3Personality.isShiny(personality, otId)

    /** The species' ability this one has (its ability bit; a species with one ability always has that). */
    override val ability: Int get() = Gen3Species.ability(species, abilityNum)

    /** GetGenderFromSpeciesAndPersonality. */
    override val gender: Gen3Gender get() = Gen3Personality.gender(species, personality)

    /** The nickname in the games' Western text; a Japanese game's (language 1) is in kana the Western table can't read. */
    override val nickname: String? get() =
        if (language == 1) null else com.pokedaisy.app.companion.data.Gen3Text.decode(nicknameBytes, 0, 10).trim().ifEmpty { null }

    override val otName: String get() = if (language == 1) "-" else com.pokedaisy.app.companion.data.Gen3Text.decode(otNameBytes, 0, 7).trim()

    override val metGameLabel: String get() = when (metGame) {
        1 -> "SAPPHIRE"; 2 -> "RUBY"; 3 -> "EMERALD"; 4 -> "FIRERED"; 5 -> "LEAFGREEN"; 15 -> "COLOSSEUM / XD"
        else -> "?"
    }

    /** Pokérus (strain / days) as stored: 0 = never had it. */
    override val pokerus: Int get() = data[36].toInt() and 0xFF

    /** Level from experience and the species' growth rate (box records keep no level byte). */
    override val level: Int get() = levelFor(species, exp)

    /**
     * This record with [species], [heldItem] and / or [nickname] (10 bytes, the game's charset) changed - what a trade
     * evolution does to it (EvolutionScene's SetMonData + EvolutionRenameMon) - or its moves, encoded with a new
     * checksum. Only for a record that read correctly.
     */
    fun withChanges(
        species: Int? = null, heldItem: Int? = null, nickname: ByteArray? = null,
        /** Four move ids, their PP and the PP Up bits (2 per move) - set together. */
        moves: IntArray? = null, pp: IntArray? = null, ppBonuses: Int? = null,
        exp: Long? = null, friendship: Int? = null,
        /** Six values in the game's stat order (HP, Attack, Defense, Speed, Sp. Atk, Sp. Def). */
        evs: IntArray? = null, ivs: IntArray? = null,
        /** The ability bit (which of the species' two abilities). */
        abilityNum: Int? = null,
    ): ByteArray {
        require(checksumOk) { "an unreadable record is never changed" }
        val h = header.copyOf()
        val d = data.copyOf()
        species?.let { putU16(d, 0, it) }
        heldItem?.let { putU16(d, 2, it) }
        exp?.let { putU32(d, 4, it) }
        ppBonuses?.let { d[8] = it.toByte() }
        friendship?.let { require(it in 0..255); d[9] = it.toByte() }
        moves?.let { require(it.size == 4); for (i in 0 until 4) putU16(d, 12 + i * 2, it[i]) }
        pp?.let { require(it.size == 4); for (i in 0 until 4) d[20 + i] = it[i].toByte() }
        evs?.let { require(it.size == 6 && it.all { v -> v in 0..255 }); for (i in 0 until 6) d[24 + i] = it[i].toByte() }
        if (ivs != null || abilityNum != null) {
            var word = u32(d, 40)
            ivs?.let {
                require(it.size == 6 && it.all { v -> v in 0..31 })
                word = word and 0x3FFFFFFFL.inv() and 0xFFFFFFFFL
                for (i in 0 until 6) word = word or (it[i].toLong() shl (i * 5))
            }
            abilityNum?.let { word = (word and 0x7FFFFFFFL) or ((it.toLong() and 1L) shl 31) }
            putU32(d, 40, word)
        }
        nickname?.let { require(it.size == NICKNAME_BYTES); it.copyInto(h, 0x08) }
        return Gen3BoxMon(h, d, true, original).encode()
    }

    /** This record under another [personality] (SHINY ODDS): its substructs reordered and encrypted with the new key. */
    fun withPersonality(personality: Long): ByteArray {
        require(checksumOk) { "an unreadable record is never changed" }
        val h = header.copyOf()
        putU32(h, 0, personality and 0xFFFFFFFFL)
        return Gen3BoxMon(h, data.copyOf(), true, original).encode()
    }

    /** The record's 80 bytes, checksum recomputed and encrypted as the game stores it. */
    fun encode(): ByteArray {
        val out = header.copyOf(SIZE)
        if (!checksumOk) return original.copyOf()   // never re-encode a record we couldn't read
        var sum = 0
        for (i in 0 until 24) sum = (sum + u16(data, i * 2)) and 0xFFFF
        putU16(out, 0x1C, sum)
        val order = gen3SubstructOrder[(personality % 24).toInt()]
        val key = personality xor otId
        for (slot in 0 until 4) {
            val logical = order[slot]
            for (w in 0 until 3) putU32(out, 0x20 + slot * 12 + w * 4, u32(data, logical * 12 + w * 4) xor key)
        }
        return out
    }

    companion object {
        const val SIZE = 80
        const val NICKNAME_BYTES = 10

        fun read(b: ByteArray, off: Int): Gen3BoxMon {
            val header = b.copyOfRange(off, off + 0x20)
            val personality = u32(b, off)
            val key = personality xor u32(b, off + 4)
            val order = gen3SubstructOrder[(personality % 24).toInt()]
            val data = ByteArray(48)
            var sum = 0
            for (slot in 0 until 4) {
                val logical = order[slot]
                for (w in 0 until 3) {
                    val word = u32(b, off + 0x20 + slot * 12 + w * 4) xor key
                    putU32(data, logical * 12 + w * 4, word)
                    sum = (sum + (word and 0xFFFF).toInt() + ((word shr 16) and 0xFFFF).toInt()) and 0xFFFF
                }
            }
            return Gen3BoxMon(header, data, sum == u16(b, off + 0x1C), b.copyOfRange(off, off + SIZE))
        }

        fun levelFor(species: Int, exp: Long): Int {
            val rate = gen3GrowthRates.getOrNull(species)?.toInt() ?: return 0
            val table = gen3ExperienceTables.getOrNull(rate) ?: return 0
            var level = 1
            while (level < 100 && exp >= table[level + 1]) level++
            return level
        }
    }
}
