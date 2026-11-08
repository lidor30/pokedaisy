package com.pokedaisy.app.companion.data

/**
 * Pokémon Modern Emerald (v3.6; resetes12's pokeemerald fork, public at github.com/resetes12/pokeemerald, tag Release3.6):
 * a gcc build of pokeemerald on Emerald's ids, 50 species past Emerald's 411, FAIRY as type 18. It runs as EMERALD with
 * its own RAM ([NATIVE_MODERN_EMERALD]) and its own tables laid over Emerald's ([emeraldHack], ModernEmeraldGen.kt).
 *
 * Its types follow the save: a new game picks MODERN TYPES, FAIRY TYPE and a TYPE CHART (permanent), and its randomizer can
 * shuffle species' types and the chart. The game reads them from SaveBlock1's tx_ bitfields (offsets by `offsetof` compiled
 * against the tag's headers); [read] reads the same bits each sample and [text] builds what the game would show:
 * pokemon.c's GetTypeBySpecies (MODERN TYPES off -> `types_old` for 21 species; FAIRY TYPE off -> `types_old` for the 20
 * that became Fairy; MODERN TYPES on -> `types_new` for two), then, with random types on, its RandomSeededModulo over the
 * trainer ID; the chart is gTypeEffectiveness_GenVI ("Gen VI+") or gTypeEffectiveness ("Modern"), its entries' types
 * passed through the live sTypeEffectivenessList with random type matchups on. Its CHAOS option draws a fresh random
 * number on every call, so then nothing can follow: [Settings.chaos] says so.
 */
object ModernEmerald {
    const val V3_6_SHA1 = TelemetrySampler.MODERN_EMERALD_V3_6_SHA1

    /** SaveBlock1 + this: tx_Random_Chaos bit 0, tx_Random_Type bit 5, tx_Random_TypeEffectiveness bit 6. */
    const val RANDOM_OFF = 0x3D9D
    /** SaveBlock1 + this: tx_Mode_Modern_Types bit 5, tx_Mode_Fairy_Types bit 6 (tx_Mode_New_Stats bit 7). */
    const val TYPES_OFF = 0x3DA5
    /** SaveBlock1 + this: tx_Mode_TypeEffectiveness bit 4 (1 = "Modern"). */
    const val CHART_OFF = 0x3DA7
    /** SaveBlock2 + this: playerTrainerId (GetTrainerId: little-endian u32). */
    const val TRAINER_ID_OFF = 0x0A
    /** sTypeEffectivenessList (EWRAM, the build's `nm`): each type's stand-in with random type matchups on. */
    const val TYPE_PERMUTATION = 0x0201E0F4L
    const val TYPE_COUNT = 19
    const val FAIRY = 18

    data class Settings(
        val modernTypes: Boolean = false,
        val fairyTypes: Boolean = false,
        val modernChart: Boolean = false,
        val randomTypes: Boolean = false,
        val randomChart: Boolean = false,
        /** The randomizer's CHAOS: its types change on every read, so the companion can't follow them. */
        val chaos: Boolean = false,
        val trainerId: Long = 0,
        /** sTypeEffectivenessList, read only with [randomChart] on. */
        val typePermutation: List<Int> = emptyList(),
    )

    /** The running save's settings, or the defaults (the Classic preset) when its save blocks can't be read. */
    fun read(c: MemoryReader, cfg: NativeConfig): Settings {
        val sb1 = saveBlock1(c, cfg)
        val sb2 = saveBlock2(c, cfg)
        if (sb1 !in 0x02000000L until 0x02040000L || sb2 !in 0x02000000L until 0x02040000L) return Settings()
        val random = c.readCoreMemory(sb1 + RANDOM_OFF, 1)[0].toInt()
        val types = c.readCoreMemory(sb1 + TYPES_OFF, 1)[0].toInt()
        val chart = c.readCoreMemory(sb1 + CHART_OFF, 1)[0].toInt()
        val randomChart = random and 0x40 != 0
        val id = c.readCoreMemory(sb2 + TRAINER_ID_OFF, 4)
        return Settings(
            modernTypes = types and 0x20 != 0,
            fairyTypes = types and 0x40 != 0,
            modernChart = chart and 0x10 != 0,
            randomTypes = random and 0x20 != 0,
            randomChart = randomChart,
            chaos = random and 0x01 != 0,
            trainerId = (0..3).fold(0L) { acc, i -> acc or ((id[i].toLong() and 0xFF) shl (8 * i)) },
            typePermutation = if (randomChart) c.readCoreMemory(TYPE_PERMUTATION, TYPE_COUNT).map { it.toInt() and 0xFF } else emptyList(),
        )
    }

    /** sOneTypeChallengeValidTypes: every type but "???", in the game's order. */
    private val RANDOM_TYPES = intArrayOf(0, 1, 2, 3, 4, 5, 6, 7, 8, 10, 11, 12, 13, 14, 15, 16, 17, 18)

    /** random.c's RandomSeededModulo without CHAOS: ISO_RANDOMIZE1 over the trainer ID, retried while past RAND_MAX. */
    internal fun randomSeededModulo(value: Long, modulo: Int, trainerId: Long): Int {
        val mask = 0xFFFFFFFFL
        val randMax = mask - (mask % modulo)
        var result = 0L
        var i = 0
        do {
            result = (1103515245L * ((trainerId + value + result) and mask) + 24691L) and mask
        } while (result >= randMax && ++i != 5)
        return (result % modulo).toInt()
    }

    /** GetTypeBySpecies for [species] under [s] (both types, as the game reads them). */
    fun speciesTypes(species: Int, s: Settings): SpeciesTypes? {
        val base = when {
            !s.modernTypes && species in classicTypesModernEmerald -> classicTypesModernEmerald[species]
            !s.fairyTypes && species in noFairyTypesModernEmerald -> noFairyTypesModernEmerald[species]
            s.modernTypes && species in modernNewTypesModernEmerald -> modernNewTypesModernEmerald[species]
            else -> speciesTypesModernEmerald[species]
        } ?: return null
        if (!s.randomTypes || s.chaos) return base
        fun random(type: Int, typeNum: Int) =
            RANDOM_TYPES[randomSeededModulo((type + typeNum + species).toLong(), RANDOM_TYPES.size, s.trainerId)]
        return SpeciesTypes(random(base.type1, 1), random(base.type2, 2))
    }

    /** The chart the game uses under [s]: atk * 100 + def -> percent. */
    fun typeChart(s: Settings): Map<Int, Int> {
        val chart = if (s.modernChart) typeChartModernModernEmerald else typeChartGen6ModernEmerald
        val p = s.typePermutation
        if (!s.randomChart || s.chaos || p.size < TYPE_COUNT) return chart
        return chart.entries.associate { (k, v) -> (p.getOrElse(k / 100) { k / 100 } * 100 + p.getOrElse(k % 100) { k % 100 }) to v }
    }

    private val cache = HashMap<Settings, HackText>()

    /** Its tables under [s], laid over Emerald's: one per settings the companion has met (a few at most). */
    @Synchronized
    fun text(s: Settings): HackText = cache.getOrPut(s) {
        HackText(
            species = speciesNamesModernEmerald,
            types = speciesNamesModernEmerald.keys.mapNotNull { id -> speciesTypes(id, s)?.let { id to it } }.toMap(),
            moves = movesModernEmerald,
            items = itemNamesModernEmerald,
            mapSecs = mapSecNamesModernEmerald,
            typeNames = mapOf(FAIRY to "Fairy"),
            typeChart = typeChart(s),
            genderRatios = genderRatiosModernEmerald,
            growthRates = growthRatesModernEmerald,
            ownCase = true,
        )
    }

    /** Whether [h] is one of its tables (the Recharged Emerald look must not reach it). */
    fun isIt(h: HackText?): Boolean = h != null && synchronized(this) { cache.values.any { it === h } }
}
