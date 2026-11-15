package com.pokedaisy.app.companion.data.save

import com.pokedaisy.app.companion.data.gen3ExperienceTables
import com.pokedaisy.app.companion.data.gen3GrowthRates
import kotlin.random.Random

enum class Gen3Gender { MALE, FEMALE, GENDERLESS }

/** What the games know about a species that a save doesn't hold (SaveSpeciesGen.kt, from the decomps). */
object Gen3Species {
    const val SHEDINJA = 303
    const val UNOWN = 201
    /** Wurmple and the four it becomes: its personality picks Silcoon / Cascoon. */
    private val WURMPLE_LINE = 265..269

    fun baseStats(species: Int): IntArray = IntArray(6) { saveBaseStats.getOrElse(species * 6 + it) { 0 } }

    /** The species' abilities: one or two ids. */
    fun abilities(species: Int): List<Int> {
        val a = saveAbilities.getOrElse(species * 2) { 0 }
        val b = saveAbilities.getOrElse(species * 2 + 1) { 0 }
        return if (b == 0 || b == a) listOf(a) else listOf(a, b)
    }

    /** GetAbilityBySpecies: the second ability when the bit is set and the species has one, else the first. */
    fun ability(species: Int, abilityNum: Int): Int = abilities(species).let { list -> list.getOrElse(abilityNum) { list[0] } }

    /** The species' National Pokédex number, or null (an egg, an unused slot). */
    fun nationalDex(species: Int): Int? = speciesNationalDex.getOrNull(species)?.takeIf { it in 1..386 }

    fun abilityName(id: Int): String = saveAbilityNames.getOrElse(id) { "#$id" }

    fun genderRatio(species: Int): Int = saveGenderRatios.getOrElse(species) { 255 }

    /** Whether a [species]' gender can be either, i.e. picked by the personality. */
    fun hasBothGenders(species: Int): Boolean = genderRatio(species) !in setOf(0, 254, 255)

    internal fun wurmpleLine(species: Int) = species in WURMPLE_LINE

    /** The experience a Pokémon of [species] needs for [level] (gExperienceTables by growth rate). */
    fun expForLevel(species: Int, level: Int): Long {
        val rate = gen3GrowthRates.getOrNull(species)?.toInt() ?: return 0
        return gen3ExperienceTables.getOrNull(rate)?.getOrNull(level)?.toLong() ?: 0
    }
}

/**
 * What the games read off a personality value (pokemon.c): its nature (% 25), gender (the low byte against the species'
 * ratio), shininess (with the OT's IDs), the ability bit a new Pokémon gets (bit 0), Unown's letter and Wurmple's
 * branch. A save editor that changes one of these picks another personality that keeps all the others, the way a
 * Pokémon with those traits could have been met.
 */
object Gen3Personality {
    fun isShiny(personality: Long, otId: Long): Boolean {
        val v = ((otId shr 16) xor (otId and 0xFFFF) xor (personality shr 16) xor (personality and 0xFFFF)) and 0xFFFF
        return v < 8
    }

    fun gender(species: Int, personality: Long): Gen3Gender = when (val ratio = Gen3Species.genderRatio(species)) {
        0 -> Gen3Gender.MALE
        254 -> Gen3Gender.FEMALE
        255 -> Gen3Gender.GENDERLESS
        else -> if ((personality and 0xFF) < ratio) Gen3Gender.FEMALE else Gen3Gender.MALE
    }

    /** GetUnownLetterByPersonality: 0 = A .. 25 = Z, 26 = !, 27 = ?. */
    fun unownLetter(personality: Long): Int = (
        ((personality and 0x3000000) shr 18) or ((personality and 0x30000) shr 12) or
            ((personality and 0x300) shr 6) or (personality and 0x3)
        ).toInt() % 28

    /** Wurmple's branch: Silcoon when the upper half % 10 is under 5. */
    fun wurmpleSilcoon(personality: Long): Boolean = ((personality shr 16) and 0xFFFF) % 10 < 5

    /**
     * A personality for [mon] with the [nature], [shiny], [gender] and [abilityNum] asked for (null = keep its own),
     * keeping its Unown letter and Wurmple branch. Null when there's none (asking a one-gender species for the other).
     */
    fun find(
        mon: SaveMon, nature: Int? = null, shiny: Boolean? = null, gender: Gen3Gender? = null, abilityNum: Int? = null,
        random: Random = Random.Default,
    ): Long? {
        val g = mon.game
        val species = mon.species
        val wantNature = nature ?: mon.nature
        val wantShiny = shiny ?: mon.isShiny
        val wantGender = gender ?: mon.gender
        fun genderOf(p: Long): Gen3Gender = when (val ratio = g.genderRatio(species)) {
            0 -> Gen3Gender.MALE
            254 -> Gen3Gender.FEMALE
            255 -> Gen3Gender.GENDERLESS
            else -> if ((p and 0xFF) < ratio) Gen3Gender.FEMALE else Gen3Gender.MALE
        }
        if (wantGender != genderOf(mon.personality) && !g.hasBothGenders(species)) return null
        val twoAbilities = g.abilities(species).size == 2
        val wantBit = abilityNum ?: mon.abilityNum
        val otId = (mon.otSid.toLong() shl 16) or mon.otTid.toLong()
        val letter = unownLetter(mon.personality)
        val silcoon = wurmpleSilcoon(mon.personality)
        fun fits(p: Long): Boolean =
            p % 25 == wantNature.toLong() &&
                isShiny(p, otId) == wantShiny &&
                genderOf(p) == wantGender &&
                (!twoAbilities || (p and 1L).toInt() == wantBit) &&
                (species != g.unown || unownLetter(p) == letter) &&
                (species !in g.wurmpleLine || wurmpleSilcoon(p) == silcoon)
        if (fits(mon.personality) && nature == null && shiny == null && gender == null && abilityNum == null) return mon.personality
        val ids = ((otId shr 16) xor (otId and 0xFFFF)) and 0xFFFF
        repeat(4_000_000) {
            val low = random.nextInt(0x10000).toLong()
            val high = if (wantShiny) (ids xor low xor random.nextInt(8).toLong()) and 0xFFFF else random.nextInt(0x10000).toLong()
            val p = (high shl 16) or low
            if (fits(p)) return p
        }
        return null
    }
}

/** Gen 3's data for the editor (SaveSpeciesGen.kt, the app's name tables). */
object Gen3GameData : SaveGameData {
    override fun speciesName(species: Int): String? = com.pokedaisy.app.companion.data.speciesNames[species]
    override fun moveName(move: Int): String? = com.pokedaisy.app.companion.data.moveData[move]?.name
    override fun itemName(item: Int): String? = com.pokedaisy.app.companion.data.itemNames[item]
    override fun abilityName(ability: Int): String = Gen3Species.abilityName(ability)
    override fun nationalDex(species: Int): Int? = Gen3Species.nationalDex(species)
    override fun abilities(species: Int): List<Int> = Gen3Species.abilities(species)
    override fun genderRatio(species: Int): Int = Gen3Species.genderRatio(species)
    override fun baseStats(species: Int): IntArray = Gen3Species.baseStats(species)
    override fun expForLevel(species: Int, level: Int): Long = Gen3Species.expForLevel(species, level)
    override fun basePp(move: Int): Int = Gen3Legal.basePp(move)
    override fun isMail(item: Int): Boolean = Gen3Legal.isMail(item)
    override val unown: Int get() = Gen3Species.UNOWN
    override val wurmpleLine: IntRange get() = 265..269
    override val shedinja: Int get() = Gen3Species.SHEDINJA
}

/**
 * CalculateMonStats (pokemon.c): a Pokémon's six stats from its species' base stats, IVs, EVs, level and nature, in the
 * game's order (HP, Attack, Defense, Speed, Sp. Atk, Sp. Def).
 */
object Gen3StatCalc {
    fun stats(species: Int, level: Int, ivs: IntArray, evs: IntArray, nature: Int): IntArray {
        val base = Gen3Species.baseStats(species)
        val out = IntArray(6)
        out[0] = if (species == Gen3Species.SHEDINJA) 1 else (2 * base[0] + ivs[0] + evs[0] / 4) * level / 100 + level + 10
        for (i in 1 until 6) {
            val n = (2 * base[i] + ivs[i] + evs[i] / 4) * level / 100 + 5
            // gNatureStatTable: nature / 5 raises, nature % 5 lowers, over Attack, Defense, Speed, Sp. Atk, Sp. Def.
            out[i] = when {
                nature / 5 == nature % 5 -> n
                i - 1 == nature / 5 -> n * 110 / 100
                i - 1 == nature % 5 -> n * 90 / 100
                else -> n
            }
        }
        return out
    }

    fun stats(mon: Gen3BoxMon, level: Int): IntArray = stats(mon.species, level, mon.ivs, mon.evs, mon.nature)
}

/** The editor's limits (the games' own): EVs 255 a stat and 510 in all, IVs 0-31. */
object Gen3StatLimits {
    const val EV_MAX = 255
    const val EV_TOTAL = 510
    const val IV_MAX = 31
}

/**
 * A nickname in the Western games' charset (charmap.txt): letters, digits and the punctuation the naming screen offers.
 * Ten characters at most, 0xFF-terminated and padded like the naming screen leaves it.
 */
object Gen3Names {
    const val NICKNAME_MAX = 10
    const val BOX_NAME_MAX = 8

    private val chars: Map<Char, Int> = buildMap {
        put(' ', 0x00)
        for (i in 0..9) put('0' + i, 0xA1 + i)
        for (i in 0 until 26) { put('A' + i, 0xBB + i); put('a' + i, 0xD5 + i) }
        put('!', 0xAB); put('?', 0xAC); put('.', 0xAD); put('-', 0xAE); put('…', 0xB0)
        put('“', 0xB1); put('”', 0xB2); put('‘', 0xB3); put('’', 0xB4); put('\'', 0xB4); put('"', 0xB2)
        put('♂', 0xB5); put('♀', 0xB6); put(',', 0xB8); put('/', 0xBA); put('é', 0x1B)
    }

    /** Whether every character of [s] has a byte in the games' charset. */
    fun encodable(s: String): Boolean = s.all { it in chars }

    /** [s] in [size] bytes: the characters, an 0xFF end if there's room, 0xFF padding (the naming screen's). */
    fun encode(s: String, size: Int): ByteArray {
        require(s.length <= size && encodable(s)) { "can't write \"$s\"" }
        val out = ByteArray(size) { 0xFF.toByte() }
        s.forEachIndexed { i, c -> out[i] = chars.getValue(c).toByte() }
        return out
    }
}
