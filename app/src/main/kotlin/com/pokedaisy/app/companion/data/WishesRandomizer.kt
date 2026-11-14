package com.pokedaisy.app.companion.data

import kotlin.concurrent.Volatile

/**
 * Pokémon Wishes of Tomorrow's randomizer (picked on a new game's RANDOMIZER screen, then fixed for that save). The ROM's
 * tables stay as they are: the game swaps each species as it makes the Pokémon - CreateWildMon (0x0823C050) for every wild
 * one while flag [FLAG_WILD] is set, CreateNPCTrainerParty for trainers while [FLAG_TRAINERS] is (shadow Pokémon and two
 * story trainers kept) - through one function, 0x0823ECF4, ported here as [species]:
 *
 *  - nothing happens unless [FLAG_RANDOMIZER] is set; species 0 and the egg (1581) stay;
 *  - x = trainerId xor (species * 0x9E3779B1); x ^= x >> 13; x *= 0x5BD1E995; x ^= x >> 15 (all u32), the trainer ID being
 *    SaveBlock2's u32 at +0xA, so a save keeps its swaps;
 *  - up to 64 tries, x += 0x1EEF each: national number x % 1025 + 1 -> its base species (the hack's
 *    NationalPokedexNumToSpecies: the first species with that natDexNum, then its formSpeciesIdTable's first entry), taken
 *    when its base HP isn't 0 (a species the build left out); none of 64 = the species itself.
 *
 * Checked headless on a randomizer-on new game: the game's own function, called for 15 species (0, 1, 4, 7, 25, 155, 261,
 * 263, 300, 448, 1000, 1025, 1300, 1580, 1581), returned what [species] does for that save's trainer ID.
 *
 * The GUIDE follows it for the wild Pokémon ([GuideRandomizer]): HERE, the HUD's WILD and the DEX's AREA show what the
 * save meets. Trainers aren't shown (the guide has no NEXT BOSS for this game).
 */
object WishesRandomizer {
    const val FLAG_RANDOMIZER = 0x4A0
    const val FLAG_STARTERS = 0x4A2
    const val FLAG_WILD = 0x4A3
    const val FLAG_TRAINERS = 0x4A4
    private const val EGG = 0x62D
    private const val NATIONAL_COUNT = 0x401

    /** The save's swap seed for wild Pokémon, null when it meets the ROM's own. */
    fun wildSeed(p: SaveProgress?): Long? {
        if (p == null || !p.flag(FLAG_RANDOMIZER) || !p.flag(FLAG_WILD)) return null
        return trainerId(p)
    }

    /** SaveBlock2's trainer ID as [GuideTables.trainerIdVar] put it in the save's progress. */
    fun trainerId(p: SaveProgress): Long =
        (p.variable(GBA_VAR_TRAINER_ID).toLong() and 0xFFFF) or ((p.variable(GBA_VAR_TRAINER_ID + 1).toLong() and 0xFFFF) shl 16)

    /** What the game makes of [species] for a save whose trainer ID is [seed], with gSpeciesInfo read through [info]. */
    fun species(species: Int, seed: Long, info: SpeciesInfoView): Int {
        if (species == 0 || species == EGG) return species
        val m = 0xFFFFFFFFL
        var x = (seed xor (species.toLong() * 0x9E3779B1L)) and m
        x = x xor (x ushr 13)
        x = (x * 0x5BD1E995L) and m
        x = x xor (x ushr 15)
        val end = (x + 0x7BBC0L) and m
        do {
            val s = info.baseSpeciesOf((x % NATIONAL_COUNT).toInt() + 1)
            if (s != 0 && info.baseHp(s) != 0) return s
            x = (x + 0x1EEFL) and m
        } while (x != end)
        return species
    }
}

/** The bits of a gSpeciesInfo (0x104-byte entries) [WishesRandomizer.species] needs, read once from [rd]. */
class SpeciesInfoView(private val baseHp: IntArray, private val natDex: IntArray, private val formTableFirst: IntArray) {
    fun baseHp(species: Int) = baseHp.getOrElse(species) { 0 }

    /** The game's NationalPokedexNumToSpecies: the first species with that number, then its form table's first. */
    fun baseSpeciesOf(national: Int): Int {
        for (s in 1 until natDex.size) if (natDex[s] == national) {
            if (baseHp[s] == 0) return s
            return formTableFirst[s].takeIf { it != 0 } ?: s
        }
        return 0
    }

    companion object {
        /** gSpeciesInfo at [base] ([count] entries of [stride] bytes): base HP +0, natDexNum +0x3C, formSpeciesIdTable +0xA4. */
        fun read(base: Long, count: Int, stride: Int, rd: (Long, Int) -> ByteArray): SpeciesInfoView {
            val raw = rd(base, count * stride)
            val hp = IntArray(count) { raw[it * stride].toInt() and 0xFF }
            val nat = IntArray(count) { u16le(raw, it * stride + 0x3C) }
            val forms = IntArray(count) { s ->
                val p = u32le(raw, s * stride + 0xA4)
                if (s == 0 || !Gfx.inRom(p)) 0 else u16le(rd(p, 2), 0)
            }
            return SpeciesInfoView(hp, nat, forms)
        }
    }
}

/**
 * Wild Pokémon a game swaps at run time, for the save being played: set by the sampler from the save's progress
 * ([update]), applied where the GUIDE's wild tables are read ([GuideRomSource.encounters], the DEX's AREA). Only Wishes of
 * Tomorrow has one so far ([WishesRandomizer]).
 */
object GuideRandomizer {
    /** The current save's wild swap seed; null = the ROM's own Pokémon. */
    @Volatile
    var wildSeed: Long? = null
        private set
    @Volatile private var view: Pair<Long, SpeciesInfoView>? = null

    fun update(t: GuideTables?, p: SaveProgress?) {
        wildSeed = if (t?.guide == GuideId.WISHES) WishesRandomizer.wildSeed(p) else null
    }

    /** A new ROM: no swaps until its save says so. */
    fun romChanged() {
        wildSeed = null
        view = null
    }

    /** [e] as the current save meets it. */
    fun wild(e: MapEncounters): MapEncounters {
        val seed = wildSeed ?: return e
        val info = speciesInfo() ?: return e
        fun swap(list: List<EncounterSlot>) = list.map { it.copy(species = WishesRandomizer.species(it.species, seed, info)) }
            .groupBy { it.species }.map { (s, g) -> EncounterSlot(s, g.minOf { it.minLevel }, g.maxOf { it.maxLevel }, g.sumOf { it.percent }) }
            .sortedByDescending { it.percent }
        return e.copy(grass = swap(e.grass), water = swap(e.water), rockSmash = swap(e.rockSmash), oldRod = swap(e.oldRod),
            goodRod = swap(e.goodRod), superRod = swap(e.superRod))
    }

    private fun speciesInfo(): SpeciesInfoView? {
        val t = POKEDEX_WISHES
        view?.takeIf { it.first == t.speciesInfo }?.let { return it.second }
        return runCatching {
            SpeciesInfoView.read(t.speciesInfo, t.speciesCount, 0x104) { addr, n ->
                val out = ByteArray(n)
                var off = 0
                while (off < n) {
                    val chunk = minOf(512, n - off)
                    PokedexSource.reader.readCoreMemory(addr + off, chunk).copyInto(out, off, 0, chunk)
                    off += chunk
                }
                out
            }
        }.getOrNull()?.also { view = t.speciesInfo to it }
    }
}
