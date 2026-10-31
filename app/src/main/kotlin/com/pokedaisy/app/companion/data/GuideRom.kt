package com.pokedaisy.app.companion.data

import java.util.concurrent.ConcurrentHashMap

/**
 * Where the GUIDE's live pages (HERE, NEXT BOSS) and the battle's foe team
 * read from: the ROM's wild-encounter, trainer and learnset tables, and the
 * save's event flags / vars (badges, key items, the starter choice).
 *
 * The retail FireRed rev 1 addresses were found by taking each table's
 * pointer-free bytes from the decomp build and searching the retail ROM
 * (sha1 dd5945db…) for them, then for the pointers to them - BROCK's party
 * then decoded to GEODUDE Lv12 (TACKLE, DEFENSE CURL) / ONIX Lv14.
 */
data class GuideTables(
    /** gTrainers: 0x28-byte records (party flags, class, name at +4, size at +0x20, party ptr at +0x24). */
    val trainers: Long,
    /** gLevelUpLearnsets: a pointer per species to u16 (level << 9 | move) runs ending 0xFFFF
     * (CFRU's: {u16 move, u8 level} ending at level 0xFF, see [learnsetCfru]); 0 = not located. */
    val learnsets: Long,
    /** gWildMonHeaders: {u8 group, u8 num, pad, land*, water*, rockSmash*, fishing*}, ending at group 0xFF. */
    val wildHeaders: Long,
    /** SaveBlock1.flags / .vars (include/global.h). */
    val sb1FlagsOff: Long = 0xEE0,
    val sb1VarsOff: Long = 0x1000,
    val flagBytes: Int = 0x120,
    val varCount: Int = 0x100,
    /** A trainer id and the name its record must hold, to check the tables are where we think ([guideTablesMatchRom]). */
    val probeTrainer: Int = 414, // TRAINER_LEADER_BROCK
    val probeName: String = "BROCK",
    /** The hand-written guide and area data this game gets ([gameGuide]). */
    val guide: GuideId = GuideId.FIRERED,
    /** struct Trainer's size and where its name / party size / party pointer sit. */
    val trainerStride: Int = 0x28,
    val trainerNameOff: Int = 4,
    val trainerNameLen: Int = 12,
    /** The record holds a POINTER to the name at [trainerNameOff], not the name (Elite Redux). */
    val trainerNamePtr: Boolean = false,
    val trainerSizeOff: Int = 0x20,
    val trainerPartyOff: Int = 0x24,
    /** pokeemerald-expansion's struct TrainerMon (always custom moves); null = vanilla's party structs. */
    val trainerMon: TrainerMonLayout? = null,
    /** WildPokemonHeader: expansion keeps one set of four tables per time of day. */
    val wildSets: Int = 1,
    /** Which set HERE shows (expansion's TIME_DAY); a missing table falls back to the others. */
    val wildSet: Int = 0,
    /** CFRU's 3-byte learnset entries (Radical Red, Unbound, Amethyst). */
    val learnsetCfru: Boolean = false,
    /** An old pokeemerald-expansion's {u16 move, u16 level} learnset entries, ending at move 0xFFFF
     * (Super Mariomon) - for [trainerMon]s without moves of their own, which get their level-up
     * moves (CreateNPCTrainerParty's GiveMonInitialMoveset). */
    val learnsetPairs: Boolean = false,
    /** Bytes from one species' learnset pointer to the next: 4 for a pointer table, gSpeciesInfo's stride where the
     * pointers live in it ([learnsets] = gSpeciesInfo + its levelUpLearnset offset: Wishes of Tomorrow). */
    val learnsetStride: Int = 4,
    /** More gTrainers tables a hack picks between (Amethyst's per difficulty): a
     * trainer id or'd with (k + 1) << [TRAINER_TABLE_SHIFT] reads table k, and
     * an empty record there falls back to [trainers], as the game does. */
    val altTrainers: List<Long> = emptyList(),
    /** A WildPokemonHeader of its own shape (Imperium's 24 bytes, Quetzal's 28); null = vanilla's / [wildSets]'. */
    val wildLayout: WildLayout? = null,
    /** Flags / vars past the main arrays that live elsewhere in SaveBlock1 (Quetzal's 0x1000+ flags, 0x5000+ vars). */
    val flagBanks: List<SaveBank> = emptyList(),
    val varBanks: List<SaveBank> = emptyList(),
    /** gMapGroups (pointers to each group's MapHeader pointers), for naming the wild maps' sections in
     * the DEX's AREA page ([MapSections]); 0 = found by shape. Each group pointer is XORed with
     * [mapGroupsXor] (Unbound's hooked lookup). Found by the scan scripts/gen_guide_areas_rom.py uses. */
    val mapGroups: Long = 0,
    val mapGroupsXor: Long = 0,
    /** MapHeader.regionMapSectionId is a u16 (SoulGold), and groups [altMapSecGroups] name theirs as
     * 0x100 + id (Quetzal's Johto) - as [NativeConfig.mapSecWide] / [NativeConfig.altMapSecGroups]. */
    val mapSecWide: Boolean = false,
    val altMapSecGroups: IntRange? = null,
    /** A second (HARD) party pointer in the same trainer record, its size in the size byte's high
     * nibble and the normal one's in the low (Recharged Yellow); -1 = none. A trainer id or'd with
     * 1 << [TRAINER_TABLE_SHIFT] reads it. */
    val trainerHardPartyOff: Int = -1,
    /** Maps (group << 8 | num) with more than one wild header back to back, and the var whose value picks one:
     * GetCurrentMapWildMonHeaderId's `i += VarGet(VAR_*_WILD_SET)` after the map's first header (Emerald Legacy). */
    val wildSetVars: Map<Int, Int> = emptyMap(),
    /** A map's second wild header, right after its first, is its night set, picked by the game's clock (Lazarus). */
    val nightWild: NightWild? = null,
    /** The save's progress also carries SaveBlock2's trainer ID ([GBA_VAR_TRAINER_ID]): Wishes of Tomorrow's randomizer
     * swaps the wild Pokémon by it ([WishesRandomizer]). */
    val trainerIdVar: Boolean = false,
)

/** GetCurrentMapWildMonHeaderId's `i++` at night: the next header when it's the same map, while gLocalTime's hour
 * ([hourAddr]) is outside [day]. Maps of [skipGroup] (caves) and any map numbered [skipNum] never switch - the game
 * tests the two apart, so a cave's second header (Lazarus's 24.112) is never read. */
data class NightWild(val hourAddr: Long, val day: IntRange = 7..19, val skipGroup: Int = -1, val skipNum: Int = -1) {
    fun applies(group: Int, num: Int) = group != skipGroup && num != skipNum
    /** Night by the game's clock; false while the hour isn't known (no save read yet). */
    fun isNight(p: SaveProgress?): Boolean = p?.variable(GBA_VAR_HOUR)?.takeIf { it in 0..23 }?.let { it !in day } ?: false
}

/** The game's clock hour (gLocalTime.hours, 0-23) as a var past the save's own, like the DS games' [GEN4_VAR_HOUR]. */
const val GBA_VAR_HOUR = 0x10000

/** SaveBlock2's trainer ID (+0xA) as two vars past the save's own, low half first ([GuideTables.trainerIdVar]). */
const val GBA_VAR_TRAINER_ID = 0x10010

/** Which of map [group].[num]'s back-to-back wild headers the game reads now ([GuideTables.wildSetVars], else
 * [GuideTables.nightWild]'s clock); 0 = the first. */
fun GuideTables.wildSetOf(group: Int, num: Int, p: SaveProgress?): Int {
    wildSetVars[group shl 8 or num]?.let { return p?.variable(it) ?: 0 }
    val night = nightWild?.takeIf { it.applies(group, num) } ?: return 0
    return if (night.isNight(p)) 1 else 0
}

/** The other time of day's set for map [group].[num] ([GuideTables.nightWild]), or null where the clock picks none. */
fun GuideTables.otherTimeSetOf(group: Int, num: Int, p: SaveProgress?): Int? =
    nightWild?.takeIf { (group shl 8 or num) !in wildSetVars && it.applies(group, num) }?.let { if (it.isNight(p)) 0 else 1 }

/** A WildPokemonHeader's size and the pointer slot (after the 4-byte map id) of each method. */
data class WildLayout(val stride: Int, val land: Int = 0, val water: Int = 1, val rockSmash: Int = 2, val fishing: Int = 3)

/** [count] flags (or vars) numbered from [firstId], kept at SaveBlock1 + [sb1Off]. */
data class SaveBank(val firstId: Int, val sb1Off: Long, val count: Int)

const val TRAINER_TABLE_SHIFT = 16

/** pokeemerald-expansion's struct TrainerMon: moves (u16 x4), species, held item, level. */
data class TrainerMonLayout(val stride: Int, val movesOff: Int, val speciesOff: Int, val itemOff: Int, val levelOff: Int)

// The European Emerald releases: English's tables, moved (found through the
// literal pools of English's code); ROXANNE is each game's own name for her.
val GUIDE_TABLES_EMERALD_ES = GUIDE_TABLES_EMERALD.copy(
    trainers = 0x08316294L, learnsets = 0x0832F638L, wildHeaders = 0x085563A4L, probeName = "PETRA", mapGroups = 0x08489BD4L,
)
val GUIDE_TABLES_EMERALD_DE = GUIDE_TABLES_EMERALD.copy(
    trainers = 0x083249A0L, learnsets = 0x0833DD3CL, wildHeaders = 0x08564A78L, probeName = "FELIZIA", mapGroups = 0x084982A8L,
)
val GUIDE_TABLES_EMERALD_FR = GUIDE_TABLES_EMERALD.copy(
    trainers = 0x08317B60L, learnsets = 0x08330EECL, wildHeaders = 0x08557C34L, probeName = "ROXANNE", mapGroups = 0x0848B464L,
)
val GUIDE_TABLES_EMERALD_IT = GUIDE_TABLES_EMERALD.copy(
    trainers = 0x0830F9F4L, learnsets = 0x08328D7CL, wildHeaders = 0x0854FA8CL, probeName = "PETRA", mapGroups = 0x084832BCL,
)

// Japanese Emerald: 0x20-byte trainers - trainerName[6] at +4, partySize at +0x18, the party at +0x1C.
val GUIDE_TABLES_EMERALD_JA = GUIDE_TABLES_EMERALD.copy(
    trainers = 0x082E383CL, learnsets = 0x082F9D04L, wildHeaders = 0x0852D9F4L, probeName = "ツツジ", mapGroups = 0x0845E998L,
    trainerStride = 0x20, trainerNameLen = 6, trainerSizeOff = 0x18, trainerPartyOff = 0x1C,
)

/**
 * Ruby / Sapphire (rev 1 and rev 2 share every address), from pret/pokeruby's
 * ruby_rev1 / sapphire_rev1 maps (both builds byte-identical to the user's
 * ROMs). Same table layouts as Emerald; SaveBlock1 (static, see
 * [NativeConfig.staticSaveBlocks]) has flags at 0x1220 (288 bytes) and vars at
 * 0x1340 (include/global.h). Trainer 265 is ROXANNE here too.
 */
val GUIDE_TABLES_RUBY = GuideTables(
    trainers = 0x081F0514L,
    learnsets = 0x08207BE0L,
    wildHeaders = 0x0839D46CL,
    sb1FlagsOff = 0x1220,
    sb1VarsOff = 0x1340,
    flagBytes = 0x120,
    probeTrainer = 265,
    probeName = "ROXANNE",
    guide = GuideId.RUBY,
    mapGroups = 0x083085A0L,
)

val GUIDE_TABLES_SAPPHIRE = GUIDE_TABLES_RUBY.copy(
    trainers = 0x081F04A4L,
    learnsets = 0x08207B70L,
    wildHeaders = 0x0839D2B4L,
    guide = GuideId.SAPPHIRE,
    mapGroups = 0x08308530L,
)

private const val TRAINER_PARTY_CUSTOM_MOVESET = 1
private const val TRAINER_PARTY_HELD_ITEM = 2
private const val WILD_SET_SIZE = 16 // four WildPokemonInfo pointers (+ expansion's hidden one, see below)
private const val MAP_GROUP_UNDEFINED = 0xFF

/** Whether the running ROM has these tables where [t] says: its probe trainer (FireRed's BROCK) has the right name. */
fun guideTablesMatchRom(c: MemoryReader, t: GuideTables): Boolean = runCatching {
    val at = t.trainers + t.probeTrainer.toLong() * t.trainerStride + t.trainerNameOff
    val name = if (t.trainerNamePtr) c.readCoreMemory(u32le(c.readCoreMemory(at, 4), 0), t.trainerNameLen)
        else c.readCoreMemory(at, t.trainerNameLen)
    Gen3Text.decode(name) == t.probeName && c.readCoreMemory(t.wildHeaders, 1)[0].toInt() and 0xFF != MAP_GROUP_UNDEFINED
}.getOrDefault(false)

/** The save's event flags and vars, as the scripts see them. */
class SaveProgress(
    private val flags: ByteArray,
    private val vars: ByteArray,
    /** [GuideTables.flagBanks] / [GuideTables.varBanks], each with its bytes. */
    private val flagBanks: List<Pair<SaveBank, ByteArray>> = emptyList(),
    private val varBanks: List<Pair<SaveBank, ByteArray>> = emptyList(),
) {
    /** FlagGet: bit n of the flags array (or of the bank holding n). */
    fun flag(n: Int): Boolean {
        flagBanks.firstOrNull { (b, _) -> n - b.firstId in 0 until b.count }?.let { (b, bytes) ->
            val i = n - b.firstId
            return (bytes.getOrNull(i / 8)?.toInt() ?: 0) shr (i % 8) and 1 != 0
        }
        return (flags.getOrNull(n / 8)?.toInt() ?: 0) shr (n % 8) and 1 != 0
    }

    /** VarGet for a 0x4000+ var (or one in a bank). */
    fun variable(id: Int): Int {
        varBanks.firstOrNull { (b, _) -> id - b.firstId in 0 until b.count }?.let { (b, bytes) ->
            return u16le(bytes, (id - b.firstId) * 2)
        }
        val i = (id - 0x4000) * 2
        return if (i in 0 until vars.size - 1) u16le(vars, i) else 0
    }

    override fun equals(other: Any?) = other is SaveProgress && flags.contentEquals(other.flags) && vars.contentEquals(other.vars) &&
        flagBanks.map { it.second.toList() } == other.flagBanks.map { it.second.toList() } &&
        varBanks.map { it.second.toList() } == other.varBanks.map { it.second.toList() }
    override fun hashCode() = 31 * flags.contentHashCode() + vars.contentHashCode() +
        flagBanks.sumOf { it.second.contentHashCode() } + varBanks.sumOf { it.second.contentHashCode() }
}

fun readSaveProgress(c: MemoryReader, cfg: NativeConfig, t: GuideTables): SaveProgress? {
    val sb1 = saveBlock1(c, cfg)
    if (sb1 !in 0x02000000L until 0x04000000L) return null
    return SaveProgress(
        c.readCoreMemory(sb1 + t.sb1FlagsOff, t.flagBytes), c.readCoreMemory(sb1 + t.sb1VarsOff, t.varCount * 2),
        t.flagBanks.map { it to c.readCoreMemory(sb1 + it.sb1Off, (it.count + 7) / 8) },
        t.varBanks.map { it to c.readCoreMemory(sb1 + it.sb1Off, it.count * 2) } +
            listOfNotNull(t.nightWild?.let { SaveBank(GBA_VAR_HOUR, 0, 1) to byteArrayOf(c.readCoreMemory(it.hourAddr, 1).getOrElse(0) { -1 }, 0) }) +
            listOfNotNull(if (t.trainerIdVar) SaveBank(GBA_VAR_TRAINER_ID, 0, 2) to c.readCoreMemory(saveBlock2(c, cfg) + 0xA, 4) else null),
    )
}

/** One wild slot group: a species, its level range and its share of that method's encounters. */
data class EncounterSlot(val species: Int, val minLevel: Int, val maxLevel: Int, val percent: Int)

/** A map's wild Pokémon, by method; empty lists = none that way. */
data class MapEncounters(
    val grass: List<EncounterSlot>,
    val water: List<EncounterSlot>,
    val rockSmash: List<EncounterSlot>,
    val oldRod: List<EncounterSlot>,
    val goodRod: List<EncounterSlot>,
    val superRod: List<EncounterSlot>,
    /** HEADBUTT on trees (the Game Boy games): the common table and the one rarer trees use. */
    val headbutt: List<EncounterSlot> = emptyList(),
    val headbuttRare: List<EncounterSlot> = emptyList(),
) {
    val isEmpty get() = listOf(grass, water, rockSmash, oldRod, goodRod, superRod, headbutt, headbuttRare).all { it.isEmpty() }
}

/** A boss's Pokémon, as its trainer data defines it. [moves] are the custom set or the level-up default. */
data class TrainerMon(val species: Int, val level: Int, val heldItem: Int, val moves: List<Int>)

// src/wild_encounter.c's slot odds (ChooseWildMonIndex_*), per slot.
private val LAND_ODDS = listOf(20, 20, 10, 10, 10, 10, 5, 5, 4, 4, 1, 1)
private val WATER_ODDS = listOf(60, 30, 5, 4, 1)
// Fishing: slots 0-1 OLD ROD, 2-4 GOOD ROD, 5-9 SUPER ROD.
private val OLD_ROD_ODDS = listOf(70, 30)
private val GOOD_ROD_ODDS = listOf(60, 20, 20)
private val SUPER_ROD_ODDS = listOf(40, 40, 15, 4, 1)

/**
 * Reads the GUIDE's ROM tables on demand and caches them. ROM data never
 * changes, so (like [PokedexSource], whose reader it shares) this runs on
 * Compose's IO dispatcher rather than the emulator thread.
 */
object GuideRomSource {
    private val encounters = ConcurrentHashMap<Int, MapEncounters>()
    private val parties = ConcurrentHashMap<Int, List<TrainerMon>>()
    @Volatile private var headers: Map<Int, Long>? = null
    @Volatile private var cachedFor: GuideTables? = null

    /** A new ROM ([PokedexSource.romChanged]): two can share tables, so start over. */
    fun romChanged() {
        synchronized(this) { cachedFor = null }
        GuideRandomizer.romChanged()
    }

    private fun sync(t: GuideTables) {
        if (cachedFor == t) return
        synchronized(this) {
            if (cachedFor == t) return
            encounters.clear(); parties.clear(); headers = null
            cachedFor = t
        }
    }

    fun cachedEncounters(t: GuideTables, group: Int, num: Int, set: Int = 0): MapEncounters? =
        if (cachedFor == t) encounters[group shl 8 or num or (set shl 16)]?.let(GuideRandomizer::wild) else null

    fun cachedParty(t: GuideTables, trainer: Int): List<TrainerMon>? = if (cachedFor == t) parties[trainer] else null

    /** The wild Pokémon of map [group].[num] - empty when it has none. [set]: which of its back-to-back headers
     * ([GuideTables.wildSetOf]). */
    fun encounters(t: GuideTables, group: Int, num: Int, set: Int = 0): MapEncounters? =
        romEncounters(t, group, num, set)?.let(GuideRandomizer::wild)

    /** [encounters] as the ROM's tables have them, before a randomizer's swaps ([GuideRandomizer]). */
    private fun romEncounters(t: GuideTables, group: Int, num: Int, set: Int): MapEncounters? {
        sync(t)
        val key = group shl 8 or num
        encounters[key or (set shl 16)]?.let { return it }
        return runCatching {
            val hdr = headerIndex(t)[key]?.let { alternateHeader(t, it, set) }
                ?: return@runCatching MapEncounters(emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList())
            val h = rd(hdr, wildHeaderStride(t))
            // Each method from the chosen time of day, or the first set that has it.
            val sets = listOf(t.wildSet) + (0 until t.wildSets).filter { it != t.wildSet }
            fun ptr(method: Int) = t.wildLayout?.let { l -> u32le(h, 4 + 4 * listOf(l.land, l.water, l.rockSmash, l.fishing)[method]) }
                ?: sets.map { u32le(h, 4 + it * wildSetStride(t) + 4 * method) }.firstOrNull { it != 0L } ?: 0L
            val fishing = slots(ptr(3), 10)
            MapEncounters(
                grass = merge(slots(ptr(0), 12), LAND_ODDS),
                water = merge(slots(ptr(1), 5), WATER_ODDS),
                rockSmash = merge(slots(ptr(2), 5), WATER_ODDS),
                oldRod = merge(fishing.take(2), OLD_ROD_ODDS),
                goodRod = merge(fishing.drop(2).take(3), GOOD_ROD_ODDS),
                superRod = merge(fishing.drop(5), SUPER_ROD_ODDS),
            )
        }.onFailure { android.util.Log.w("pokedaisy", "encounters $group.$num failed", it) }
            .getOrNull()?.also { encounters[key or (set shl 16)] = it }
    }

    /** The header [set] places after the map's first ([first]), as the game counts them - only while it's still
     * this map's (a var out of range keeps the first rather than read another map's). */
    private fun alternateHeader(t: GuideTables, first: Long, set: Int): Long {
        if (set <= 0) return first
        val id = rd(first, 2)
        val alt = first + set.toLong() * wildHeaderStride(t)
        return if (rd(alt, 2).contentEquals(id)) alt else first
    }

    /** How many headers in a row map [group].[num] has (1 but for [GuideTables.wildSetVars]' and night maps). */
    private fun headerRun(t: GuideTables, group: Int, num: Int): Int {
        if ((group shl 8 or num) !in t.wildSetVars && t.nightWild?.applies(group, num) != true) return 1
        val first = headerIndex(t)[group shl 8 or num] ?: return 1
        var n = 1
        while (n < 8 && alternateHeader(t, first, n) != first) n++
        return n
    }

    /** Every map with a wild header, as group << 8 | num (the DEX's AREA index walks them). */
    fun wildMapKeys(t: GuideTables): Set<Int> {
        sync(t)
        return runCatching { headerIndex(t).keys }.getOrDefault(emptySet())
    }

    /** Map [group].[num]'s wild Pokémon in each of its time-of-day sets ([GuideTables.wildSets]),
     * each set alone (no fallback to the others, unlike [encounters]); not cached. A map with
     * back-to-back headers ([GuideTables.wildSetVars]) lists each header's. */
    fun encounterSets(t: GuideTables, group: Int, num: Int): List<MapEncounters> {
        sync(t)
        val run = headerRun(t, group, num)
        if (run > 1) return (0 until run).mapNotNull { encounters(t, group, num, it) }
        return romEncounterSets(t, group, num).map(GuideRandomizer::wild)
    }

    private fun romEncounterSets(t: GuideTables, group: Int, num: Int): List<MapEncounters> {
        val hdr = headerIndex(t)[group shl 8 or num] ?: return emptyList()
        val h = rd(hdr, wildHeaderStride(t))
        val sets = if (t.wildLayout != null) 1 else t.wildSets
        return (0 until sets).map { set ->
            fun ptr(method: Int) = t.wildLayout?.let { l -> u32le(h, 4 + 4 * listOf(l.land, l.water, l.rockSmash, l.fishing)[method]) }
                ?: u32le(h, 4 + set * wildSetStride(t) + 4 * method)
            val fishing = slots(ptr(3), 10)
            MapEncounters(
                grass = merge(slots(ptr(0), 12), LAND_ODDS),
                water = merge(slots(ptr(1), 5), WATER_ODDS),
                rockSmash = merge(slots(ptr(2), 5), WATER_ODDS),
                oldRod = merge(fishing.take(2), OLD_ROD_ODDS),
                goodRod = merge(fishing.drop(2).take(3), GOOD_ROD_ODDS),
                superRod = merge(fishing.drop(5), SUPER_ROD_ODDS),
            )
        }
    }

    /** Trainer [id]'s party as the game builds it (CreateNPCTrainerParty). */
    fun party(t: GuideTables, id: Int): List<TrainerMon>? {
        sync(t)
        parties[id]?.let { return it }
        return runCatching {
            val local = (id and 0xFFFF).toLong()
            val alt = t.altTrainers.getOrNull((id shr TRAINER_TABLE_SHIFT) - 1)
            val rec = alt?.let { rd(it + local * t.trainerStride, t.trainerStride) }
                ?.takeIf { it[0].toInt() != 0 || u32le(it, t.trainerPartyOff) != 0L }
                ?: rd(t.trainers + local * t.trainerStride, t.trainerStride)
            val flags = rec[0].toInt() and 0xFF
            val sizeByte = rec[t.trainerSizeOff].toInt() and 0xFF
            val hard = t.trainerHardPartyOff >= 0 && id shr TRAINER_TABLE_SHIFT == 1
            val size = when {
                t.trainerHardPartyOff < 0 -> sizeByte
                hard -> sizeByte shr 4
                else -> sizeByte and 0x0F
            }
            val ptr = u32le(rec, if (hard) t.trainerHardPartyOff else t.trainerPartyOff)
            if (size !in 1..6 || !Gfx.inRom(ptr)) return@runCatching emptyList()
            t.trainerMon?.let { m ->
                val raw = rd(ptr, size * m.stride)
                return@runCatching (0 until size).map { i ->
                    val o = i * m.stride
                    val species = u16le(raw, o + m.speciesOff)
                    val level = raw[o + m.levelOff].toInt() and 0xFF
                    TrainerMon(
                        species, level,
                        heldItem = u16le(raw, o + m.itemOff),
                        moves = (0 until 4).map { u16le(raw, o + m.movesOff + 2 * it) }.filter { it != 0 }
                            .ifEmpty { defaultMoves(t, species, level) },
                    )
                }
            }
            val custom = flags and TRAINER_PARTY_CUSTOM_MOVESET != 0
            val item = flags and TRAINER_PARTY_HELD_ITEM != 0
            // {u16 iv, u8 lvl, pad, u16 species, [u16 item], [u16 moves[4]]}, padded to 8 / 16 bytes.
            val stride = if (custom) 16 else 8
            val raw = rd(ptr, size * stride)
            (0 until size).map { i ->
                val o = i * stride
                val species = u16le(raw, o + 4)
                val level = raw[o + 2].toInt() and 0xFF
                val movesOff = if (item) o + 8 else o + 6
                TrainerMon(
                    species, level,
                    heldItem = if (item) u16le(raw, o + 6) else 0,
                    moves = if (custom) (0 until 4).map { u16le(raw, movesOff + 2 * it) }.filter { it != 0 }
                    else defaultMoves(t, species, level),
                )
            }
        }.onFailure { android.util.Log.w("pokedaisy", "trainer $id failed", it) }
            .getOrNull()?.also { parties[id] = it }
    }

    /** GiveMonInitialMoveset: the level-up moves up to [level], newest four, no repeats. */
    private fun defaultMoves(t: GuideTables, species: Int, level: Int): List<Int> {
        if (t.learnsets == 0L) return emptyList() // not located for this game: no moves shown
        val ptr = u32le(rd(t.learnsets + species.toLong() * t.learnsetStride, 4), 0)
        if (!Gfx.inRom(ptr)) return emptyList()
        val moves = ArrayDeque<Int>()
        fun learn(move: Int) {
            if (move in moves) return
            if (moves.size == 4) moves.removeFirst()
            moves.addLast(move)
        }
        if (t.learnsetPairs) { // {u16 move, u16 level}, ending at move 0xFFFF
            val raw = rd(ptr, 4 * 64)
            for (o in 0 until raw.size - 3 step 4) {
                val move = u16le(raw, o)
                if (move == 0xFFFF || u16le(raw, o + 2) > level) break
                learn(move)
            }
            return moves.toList()
        }
        if (t.learnsetCfru) { // {u16 move, u8 level}, ending at level 0xFF
            val raw = rd(ptr, 3 * 64)
            for (o in 0 until raw.size - 2 step 3) {
                val lv = raw[o + 2].toInt() and 0xFF
                if (lv == 0xFF || lv > level) break
                learn(u16le(raw, o))
            }
            return moves.toList()
        }
        val raw = rd(ptr, 2 * 64)
        var o = 0
        while (o + 1 < raw.size) {
            val v = u16le(raw, o); o += 2
            if (v == 0xFFFF) break
            if (v shr 9 > level) break
            learn(v and 0x1FF)
        }
        return moves.toList()
    }

    // Vanilla: {group, num, pad, 4 pointers}; expansion adds a hidden (DexNav) pointer per set, one set per time of day.
    private fun wildSetStride(t: GuideTables) = if (t.wildSets > 1) WILD_SET_SIZE + 4 else WILD_SET_SIZE
    private fun wildHeaderStride(t: GuideTables) = t.wildLayout?.stride ?: (4 + t.wildSets * wildSetStride(t))

    /** Map (group << 8 | num) -> its header's address, read once. */
    private fun headerIndex(t: GuideTables): Map<Int, Long> {
        headers?.let { return it }
        val out = HashMap<Int, Long>()
        var addr = t.wildHeaders
        while (out.size < 512) {
            val h = rd(addr, 2)
            val group = h[0].toInt() and 0xFF
            if (group == MAP_GROUP_UNDEFINED) break
            // The first header wins, as in GetCurrentMapWildMonHeaderId.
            out.putIfAbsent(group shl 8 or (h[1].toInt() and 0xFF), addr)
            addr += wildHeaderStride(t)
        }
        return out.also { headers = it }
    }

    /** A WildPokemonInfo's [count] slots: {u8 min, u8 max, u16 species} each; none for a null pointer. */
    private fun slots(infoPtr: Long, count: Int): List<Triple<Int, Int, Int>> {
        if (!Gfx.inRom(infoPtr)) return emptyList()
        val monsPtr = u32le(rd(infoPtr + 4, 4), 0)
        if (!Gfx.inRom(monsPtr)) return emptyList()
        val raw = rd(monsPtr, count * 4)
        return (0 until count).map { i ->
            Triple(u16le(raw, i * 4 + 2), raw[i * 4].toInt() and 0xFF, raw[i * 4 + 1].toInt() and 0xFF)
        }
    }

    /** Slots of the same species folded into one line, odds summed, most common first. A slot that
     * can't be one (levels out of order or past 100) is dropped: TMT2 lists 11 land slots, so the 12th
     * is the next table's pointer - the game reads it too on its 1% roll. */
    private fun merge(slots: List<Triple<Int, Int, Int>>, odds: List<Int>): List<EncounterSlot> =
        slots.withIndex().filter { it.value.first != 0 && it.value.second in 1..100 && it.value.third in it.value.second..100 }.groupBy { it.value.first }.map { (species, group) ->
            EncounterSlot(
                species,
                group.minOf { it.value.second },
                group.maxOf { it.value.third },
                group.sumOf { odds.getOrElse(it.index) { 0 } },
            )
        }.sortedByDescending { it.percent }

    private fun rd(addr: Long, n: Int): ByteArray {
        val out = ByteArray(n)
        var off = 0
        while (off < n) {
            val chunk = minOf(512, n - off)
            PokedexSource.reader.readCoreMemory(addr + off, chunk).copyInto(out, off, 0, chunk)
            off += chunk
        }
        return out
    }
}

/**
 * Heart and Soul v2.0.6 (pokeemerald-expansion), found in the release ROM:
 * gTrainers from FALKNER's record (0x34 bytes: party pointer at +8, name at
 * +0x1F, party size at +0x2B; trainer 402 = TRAINER_FALKNER_1_HNS in its
 * source), its TrainerMons from his party (PIDGEY Lv8 / NOCTOWL Lv11, 0x24
 * bytes each); gWildMonHeaders from the header pointing at ROUTE 29's day
 * table (PIDGEY, SENTRET, HOPPIP - as wild_encounters.json has it), four
 * time-of-day sets per map (HERE shows the day one). SaveBlock1's flags and
 * vars from the game's own GetFlagPointer / GetVarPointer literals.
 */
val GUIDE_TABLES_HEART_AND_SOUL = GuideTables(
    trainers = 0x084C9C8CL,
    learnsets = 0,
    wildHeaders = 0x091856C4L,
    sb1FlagsOff = 0x198C,
    sb1VarsOff = 0x1ABC,
    flagBytes = 0x130,
    probeTrainer = 402,
    probeName = "FALKNER",
    guide = GuideId.HEART_AND_SOUL,
    trainerStride = 0x34,
    trainerNameOff = 0x1F,
    trainerNameLen = 11,
    trainerSizeOff = 0x2B,
    trainerPartyOff = 8,
    trainerMon = TrainerMonLayout(stride = 0x24, movesOff = 0x0C, speciesOff = 0x14, itemOff = 0x16, levelOff = 0x1A),
    wildSets = 4,
    wildSet = 1,
    mapGroups = 0x092DF5A4L,
)

/**
 * Pokémon Unbound v2.1.1.1 (CFRU), found in its ROM: gTrainers is rewritten in
 * place at FireRed rev 0's address (same 0x28-byte records; trainer 6 is its
 * first gym leader, MIRSKLE), gWildMonHeaders moved to 0x08C230D8 (vanilla
 * layout - from the literal pools of FireRed's wild-encounter code), CFRU's
 * 3-byte learnsets at 0x09A2457C (the same literal-pool route).
 */
val GUIDE_TABLES_UNBOUND = GuideTables(
    trainers = 0x0823EAC8L,
    learnsets = 0x09A2457CL,
    learnsetCfru = true,
    wildHeaders = 0x08C230D8L,
    probeTrainer = 6,
    probeName = "Mirskle",
    guide = GuideId.UNBOUND,
    mapGroups = 0x08B70498L,
    mapGroupsXor = 0x00B749DEL,
)

// Unbound v2.1.1.1 FR: English's tables, its trainers renamed in place (MIRSKLE is Sylvain).
val GUIDE_TABLES_UNBOUND_FR = GUIDE_TABLES_UNBOUND.copy(probeName = "Sylvain")

/**
 * Radical Red v4.1 (CFRU): gTrainers rewritten in place at FireRed rev 0's
 * address (trainer 414 is BROCK, as in FireRed), gWildMonHeaders moved to
 * 0x0872C984 (vanilla layout, found by shape: 142 headers). Learnsets are
 * CFRU's 3-byte format at 0x0980175C.
 */
val GUIDE_TABLES_RADICAL_RED = GuideTables(
    trainers = 0x0823EAC8L,
    learnsets = 0x0980175CL,
    learnsetCfru = true,
    wildHeaders = 0x0872C984L,
    probeTrainer = 414,
    probeName = "Brock",
    guide = GuideId.RADICAL_RED,
    mapGroups = 0x083526A8L,
)

/** Odyssey v4.1.1: FireRed rev 0's gTrainers, learnsets (vanilla format) and save layout; its own gWildMonHeaders. */
val GUIDE_TABLES_ODYSSEY = GuideTables(
    trainers = 0x0823EAC8L,
    learnsets = 0x0825D7B4L,
    wildHeaders = 0x092C5540L,
    probeTrainer = 33,
    probeName = "Karin",
    guide = GuideId.ODYSSEY,
    mapGroups = 0x083526A8L,
)

/** Gaia v3.2: rev 0's gTrainers (its leaders at FireRed's 414..421), learnsets and wild headers moved. */
val GUIDE_TABLES_GAIA = GuideTables(
    trainers = 0x0823EAC8L,
    learnsets = 0x082BDD70L,
    wildHeaders = 0x08A2E1CCL,
    probeTrainer = 414,
    probeName = "Fernando",
    guide = GuideId.GAIA,
    mapGroups = 0x083528DCL,
)

/**
 * Amethyst v1.3.0: its trainer loader (0x088B3A98) picks one of four tables by
 * two save flags - 0x93C (HARD MODE) and 0x945 (the DIVERGENT Pokémon set) -
 * falling back to the standard one for an empty record. Parties are 18-byte
 * records; learnsets are CFRU's.
 */
val GUIDE_TABLES_AMETHYST = GuideTables(
    trainers = 0x089DC0D0L,
    learnsets = 0x09C04590L,
    wildHeaders = 0x083AFD00L,
    probeTrainer = 14,
    probeName = "Terrence",
    guide = GuideId.AMETHYST,
    trainerMon = TrainerMonLayout(stride = 18, movesOff = 8, speciesOff = 4, itemOff = 6, levelOff = 2),
    learnsetCfru = true,
    altTrainers = listOf(0x089CECCCL, 0x089B8A34L, 0x089C63B8L), // HARD, DIVERGENT, DIVERGENT + HARD
    mapGroups = 0x083526A8L,
)

/** Amethyst v1.4.1: the same loader and flags (0x93C / 0x945), the tables moved; the gym leaders'
 * records and parties are byte for byte v1.3.0's in all four. Its own area data (AMETHYST_V141). */
val GUIDE_TABLES_AMETHYST_V1_4_1 = GUIDE_TABLES_AMETHYST.copy(
    trainers = 0x08A25A14L,
    learnsets = 0x09ADB8D0L,
    guide = GuideId.AMETHYST_V141,
    mapGroups = 0x083526A8L,
    altTrainers = listOf(0x08A1861CL, 0x08A02384L, 0x08A0FD08L),
)

/**
 * Celia's Stupid Romhack v1.1.4 (a full pokefirered recompile): everything
 * moved, SaveBlock1's flags 16 bytes later (found by setting the POKéDEX flag
 * and diffing the save). Its learnsets aren't located; the leaders carry their
 * own moves anyway.
 */
// Pokémon Emerald Kaizo v2.0: retail's tables at retail's addresses, rewritten in place (its trainers' teams, base
// stats, TM compatibility) - only gWildMonHeaders moved, to 0x08E3D4A4 (all 13 literal-pool refs).
val GUIDE_TABLES_EMERALD_KAIZO = GUIDE_TABLES_EMERALD.copy(wildHeaders = 0x08E3D4A4L)

// Pokémon Emerald Legacy v1.1.4: its own build's tables (its ELF's symbols). SaveBlock1's flags / vars moved past the
// 120-slot ITEMS pocket and grew (960 trainer flags push the system flags to 0x8C0: offsetof / constants compiled with
// agbcc against its headers). Its trainers keep retail's ids (ROXANNE 265), the new ones from 855. Some maps carry a
// second set of wild Pokémon in the header after their first, picked by a var (wild_encounter.c's
// GetCurrentMapWildMonHeaderId), listed here by map.
val GUIDE_TABLES_EMERALD_LEGACY = GUIDE_TABLES_EMERALD.copy(
    trainers = 0x083380E8L,
    learnsets = 0x083522A8L,
    wildHeaders = 0x08585028L,
    sb1FlagsOff = 0x13D8,
    sb1VarsOff = 0x1510,
    flagBytes = 0x138,
    varCount = 0x101,
    // One struct TrainerMon for every party (its "trainer control"): nickname, IVs / EVs, lvl +0x19, species +0x1A,
    // heldItem +0x1C, moves +0x1E (none = the level-up moves, as CreateNPCTrainerParty does), 0x2C bytes.
    trainerMon = TrainerMonLayout(stride = 0x2C, movesOff = 0x1E, speciesOff = 0x1A, itemOff = 0x1C, levelOff = 0x19),
    guide = GuideId.EMERALD_LEGACY,
    mapGroups = 0x084AF90CL,
    wildSetVars = mapOf(
        (0 shl 8 or 16) to 0x40F7, (0 shl 8 or 17) to 0x40F8, (0 shl 8 or 18) to 0x40F9, (0 shl 8 or 19) to 0x40FA, // ROUTE 101-104
        (0 shl 8 or 25) to 0x40FB, (0 shl 8 or 26) to 0x40FC, (0 shl 8 or 27) to 0x40FD, (0 shl 8 or 29) to 0x40FE, // 110, 111, 112, 114
        (0 shl 8 or 31) to 0x40FF, (0 shl 8 or 32) to 0x40E5, (0 shl 8 or 22) to 0x409B, (0 shl 8 or 30) to 0x4091, // 116, 117, 107, 115
        (0 shl 8 or 34) to 0x4083, (0 shl 8 or 35) to 0x4081, (0 shl 8 or 36) to 0x4080, (0 shl 8 or 37) to 0x407F, // 119-122
        (0 shl 8 or 38) to 0x407E, (0 shl 8 or 46) to 0x4076, (0 shl 8 or 48) to 0x4075, // 123, 131, 133
        (0 shl 8 or 1) to 0x406D, (0 shl 8 or 6) to 0x406C, (0 shl 8 or 0) to 0x406B, (0 shl 8 or 7) to 0x4068, // SLATEPORT, MOSSDEEP, PETALBURG, SOOTOPOLIS
        (24 shl 8 or 11) to 0x40DB, (24 shl 8 or 4) to 0x40DC, (24 shl 8 or 7) to 0x40BB, (24 shl 8 or 8) to 0x40B8, // PETALBURG WOODS, RUSTURF, GRANITE 1F / B1F
        (24 shl 8 or 43) to 0x40A8, (24 shl 8 or 44) to 0x4073, (24 shl 8 or 45) to 0x4070, // VICTORY ROAD 1F / B1F / B2F
        (24 shl 8 or 14) to 0x40A1, (24 shl 8 or 13) to 0x409D, (24 shl 8 or 53) to 0x408B, // FIERY PATH, JAGGED PASS, NEW MAUVILLE
        (24 shl 8 or 16) to 0x407C, (24 shl 8 or 17) to 0x407A, (24 shl 8 or 18) to 0x4079, (24 shl 8 or 19) to 0x4078, // MT. PYRE 2F-5F
        (24 shl 8 or 21) to 0x4077, (24 shl 8 or 0) to 0x406E, (24 shl 8 or 83) to 0x406A, (24 shl 8 or 99) to 0x4067, // MT. PYRE outside, METEOR FALLS, SHOAL CAVE ice, ARTISAN CAVE
        (24 shl 8 or 47) to 0x4066, // SHOAL CAVE inner room
    ),
)

// Pokémon Glazed: retail Emerald's gTrainers / gLevelUpLearnsets at retail's addresses (its literal
// pools still name them), its gWildMonHeaders repointed to 0x094CA3F4 (all 13 refs); retail's
// SaveBlock1 flags / vars. Its first gym leader, SPARKY, holds ROXANNE's slot.
val GUIDE_TABLES_GLAZED = GUIDE_TABLES_EMERALD.copy(
    wildHeaders = 0x094CA3F4L,
    probeName = "SPARKY",
    guide = GuideId.GLAZED,
)

/**
 * Emerald Imperium v1.3.1 (pokeemerald-expansion 1.10.0), from the release ROM: gTrainers
 * 0x0854FA40 (36 refs; 0x78-byte records - party +4, class +0x10, name +0x13, size +0x20;
 * 265 = Roxanne: Rhyhorn 14, Wooper / Gligar / Nosepass 15, abilities at +0x18 Lightning Rod,
 * Water Absorb, Sand Veil, Sturdy), expansion's 0x24-byte TrainerMon, gWildMonHeaders
 * 0x08ED1CD0 (16 refs, 135 headers of 24 bytes: one set of land / water / rock smash / fishing /
 * hidden - Route 101's land table has the Shinx a walk there met), SaveBlock1 flags / vars at
 * 0x19C4 / 0x1AF0 (GetFlagPointer / GetVarPointer literals).
 */
val GUIDE_TABLES_IMPERIUM = GuideTables(
    trainers = 0x0854FA40L,
    learnsets = 0,
    wildHeaders = 0x08ED1CD0L,
    sb1FlagsOff = 0x19C4,
    sb1VarsOff = 0x1AF0,
    flagBytes = 0x12C,
    probeTrainer = 265,
    probeName = "Roxanne",
    guide = GuideId.IMPERIUM,
    trainerStride = 0x78,
    trainerNameOff = 0x13,
    trainerNameLen = 11,
    trainerSizeOff = 0x20,
    trainerPartyOff = 4,
    trainerMon = TrainerMonLayout(stride = 0x24, movesOff = 0x0C, speciesOff = 0x14, itemOff = 0x16, levelOff = 0x1A),
    wildLayout = WildLayout(stride = 24),
    mapGroups = 0x08FC2F74L,
)

/**
 * Pokémon Quetzal English Alpha 9 v0 (its own engine), from the ROM: GetTrainer (0x082942F0)
 * reads gTrainers 0x084FCCB0 (0x20-byte records: party +4, class +0x10, name +0x13, size +0x1F;
 * 265 = ROXANNE) for ids up to 0x3CD, Johto's table 0x09985F94 for 0x1000+, Kanto's 0x099914E8 for
 * 0x2000+ (altTrainers 0 / 1). Boss parties are 28-byte mons (level +8, species +0xA, item +0xE,
 * moves +0x12). gWildMonHeaders 0x0917A8C4: 353 headers of 28 bytes - land / water / a 3-mon
 * table / rock smash / fishing / unused (Kanto Route 1's land table holds all 8 species its
 * overworld showed). Flags 0..0xE97 at SaveBlock1+0, 0x1000..0x2FFF at +0x2F2C; vars 0x4000+
 * at +0x1D4, 0x5000+ at +0x332C (GetFlagPointer / GetVarPointer).
 */
val GUIDE_TABLES_QUETZAL = GuideTables(
    trainers = 0x084FCCB0L,
    learnsets = 0,
    wildHeaders = 0x0917A8C4L,
    sb1FlagsOff = 0,
    sb1VarsOff = 0x1D4,
    flagBytes = 0x1D3,
    probeTrainer = 265,
    probeName = "ROXANNE",
    guide = GuideId.QUETZAL,
    trainerStride = 0x20,
    trainerNameOff = 0x13,
    trainerNameLen = 11,
    trainerSizeOff = 0x1F,
    trainerPartyOff = 4,
    trainerMon = TrainerMonLayout(stride = 28, movesOff = 0x12, speciesOff = 0x0A, itemOff = 0x0E, levelOff = 8),
    altTrainers = listOf(0x09985F94L, 0x099914E8L),
    wildLayout = WildLayout(stride = 28, rockSmash = 3, fishing = 4),
    flagBanks = listOf(SaveBank(firstId = 0x1000, sb1Off = 0x2F2C, count = 0x2000)),
    varBanks = listOf(SaveBank(firstId = 0x5000, sb1Off = 0x332C, count = 0x100)),
    mapGroups = 0x089286BCL,
    altMapSecGroups = 34..35,
)

// Quetzal Spanish Alpha 9 v0: English's tables at its own addresses (English's literal pools,
// read at the same place in its code); its trainers carry Spanish names (265 is PETRA).
val GUIDE_TABLES_QUETZAL_ES = GUIDE_TABLES_QUETZAL.copy(
    trainers = 0x085086C0L,
    wildHeaders = 0x09186278L,
    probeName = "PETRA",
    altTrainers = listOf(0x0998AE7CL, 0x099963D0L),
    mapGroups = 0x08934070L,
)

// Quetzal Alpha 9 v1: v0's tables at v1's addresses (v0's literal pools read in v1's code; Kanto's trainers by their
// records), each v0's entry for entry, pointers followed.
val GUIDE_TABLES_QUETZAL_V1 = GUIDE_TABLES_QUETZAL.copy(
    trainers = 0x085107FCL,
    wildHeaders = 0x0918A638L,
    altTrainers = listOf(0x09991270L, 0x0999C7C4L),
    mapGroups = 0x0893C438L,
)
val GUIDE_TABLES_QUETZAL_ES_V1 = GUIDE_TABLES_QUETZAL_ES.copy(
    trainers = 0x0851C23CL,
    wildHeaders = 0x09196024L,
    altTrainers = listOf(0x09996218L, 0x099A176CL),
    mapGroups = 0x08947E24L,
)

// Quetzal Alpha 0.8.4 (English / Spanish, and their Moemon Hotfix builds): Alpha 9's record shapes for Hoenn alone - no
// Johto / Kanto trainer tables or map groups (34 groups, 124 wild headers). gTrainers, gWildMonHeaders and gMapGroups
// through Alpha 9's literal pools; 265 is ROXANNE (PETRA), 961 her HARD twin, as in Alpha 9.
val GUIDE_TABLES_QUETZAL_V084 = GUIDE_TABLES_QUETZAL.copy(
    trainers = 0x08406948L,
    wildHeaders = 0x08F34160L,
    guide = GuideId.QUETZAL_V084,
    altTrainers = emptyList(),
    mapGroups = 0x0861310CL,
    altMapSecGroups = null,
)
val GUIDE_TABLES_QUETZAL_V084_ES = GUIDE_TABLES_QUETZAL_V084.copy(
    trainers = 0x0840EB18L, wildHeaders = 0x08F3C278L, probeName = "PETRA", mapGroups = 0x0861B224L,
)
val GUIDE_TABLES_QUETZAL_MOEMON_V084 = GUIDE_TABLES_QUETZAL_V084.copy(
    trainers = 0x08406A48L, wildHeaders = 0x08F34144L, mapGroups = 0x086130F0L,
)
val GUIDE_TABLES_QUETZAL_MOEMON_V084_ES = GUIDE_TABLES_QUETZAL_V084_ES.copy(
    trainers = 0x0840EC18L, wildHeaders = 0x08F3C25CL, mapGroups = 0x0861B208L,
)

// The four below from their release ROMs (expansion 1.9.4 / 1.9.2 / 1.12.3 / 1.15.2 per their RHHEXP
// headers): SaveBlock1 flags / vars from the GF ROM header (+0x50 / +0x54); gTrainers found by a gym
// leader's record and its literal-pool refs; HERE checked by warping onto grass headless and walking
// until a wild battle - every foe the game sent (species and level) is in the table read here.

/** Pokémon Lazarus v2.0: gTrainers 0x085DA84C (0x24-byte records; 8 = Polymnia), 0x24-byte TrainerMons,
 * gWildMonHeaders 0x08E67010 (21 refs, 210 headers of 24 bytes: land, water, rock smash, unused, fishing).
 * Its GetCurrentMapWildMonHeaderId (0x0824A8CC) calls RtcCalcLocalTime and takes the map's next header at night
 * (gLocalTime 0x030039A8's hour outside 7..19; 37 outdoor maps have one), except in map group 24 or a map
 * numbered 106; then ALTERING CAVE (24.106) adds VAR_ALTERING_CAVE_WILD_SET (0x403E), as Emerald does. */
val GUIDE_TABLES_LAZARUS = GuideTables(
    trainers = 0x085DA84CL, learnsets = 0, wildHeaders = 0x08E67010L,
    sb1FlagsOff = 0x12E8, sb1VarsOff = 0x1414, flagBytes = 0x12C,
    probeTrainer = 8, probeName = "Polymnia", guide = GuideId.LAZARUS,
    trainerStride = 0x24, trainerNameOff = 0x13, trainerNameLen = 11, trainerSizeOff = 0x20, trainerPartyOff = 4,
    trainerMon = TrainerMonLayout(stride = 0x24, movesOff = 0x0C, speciesOff = 0x14, itemOff = 0x16, levelOff = 0x1A),
    wildLayout = WildLayout(stride = 24, fishing = 4),
    mapGroups = 0x08FAF098L,
    wildSetVars = mapOf((24 shl 8 or 106) to 0x403E),
    nightWild = NightWild(hourAddr = 0x030039AAL, skipGroup = 24, skipNum = 106),
)

/** Emerald Seaglass v3.0: gTrainers 0x0851C8CC (34 refs, Emerald's ids: 265 = ROXANNE), 0x20-byte
 * TrainerMons, gWildMonHeaders 0x08A4E8B8 (17 refs, 137 headers of 24 bytes: land, water, rock smash,
 * hidden, fishing). */
val GUIDE_TABLES_SEAGLASS = GuideTables(
    trainers = 0x0851C8CCL, learnsets = 0, wildHeaders = 0x08A4E8B8L,
    sb1FlagsOff = 0x13C0, sb1VarsOff = 0x14EC, flagBytes = 0x12C,
    probeTrainer = 265, probeName = "ROXANNE", guide = GuideId.SEAGLASS,
    trainerStride = 0x24, trainerNameOff = 0x13, trainerNameLen = 11, trainerSizeOff = 0x20, trainerPartyOff = 4,
    trainerMon = TrainerMonLayout(stride = 0x20, movesOff = 0x0C, speciesOff = 0x14, itemOff = 0x16, levelOff = 0x1A),
    wildLayout = WildLayout(stride = 24, fishing = 4),
    mapGroups = 0x08B45148L,
)

/** Too Many Types 2 v1.5.2: gTrainers[EASY][NORMAL][HARD][943] at 0x0853C758 (EASY / HARD empty), so its
 * NORMAL array 0x0854696C (0x2C-byte records); 0x24-byte TrainerMons; gWildMonHeaders 0x08F3C590 (14 refs,
 * 135 headers of 84 bytes: four time-of-day sets, only the first filled). */
val GUIDE_TABLES_TMT2 = GuideTables(
    trainers = 0x0854696CL, learnsets = 0, wildHeaders = 0x08F3C590L,
    sb1FlagsOff = 0x14A8, sb1VarsOff = 0x15D4, flagBytes = 0x12C,
    probeTrainer = 265, probeName = "ROXANNE", guide = GuideId.TMT2,
    trainerStride = 0x2C, trainerNameOff = 0x17, trainerNameLen = 11, trainerSizeOff = 0x24, trainerPartyOff = 8,
    trainerMon = TrainerMonLayout(stride = 0x24, movesOff = 0x0C, speciesOff = 0x14, itemOff = 0x16, levelOff = 0x1A),
    wildSets = 4, wildSet = 0,
    mapGroups = 0x09047730L,
)

/** SoulGold v1.1.4: gTrainers[EASY][NORMAL][HARD][1164] at 0x0849606C - NORMAL 0x084A4CDC (0x34-byte records
 * like Heart and Soul's; 19 = Falkner), HARD 0x084B394C (used when SaveBlock2+0x16 bit 3 is set and the
 * record has a party). 0x28-byte TrainerMons (level +0x1C). gWildMonHeaders 0x094158D8 (15 refs, 139
 * headers of 84 bytes; set 0 the default). Flags 0x1898 (0x2AA bytes), vars 0x1C10 (not right after the flags: the
 * fixtures' VAR_STARTER_SPECIES 0x4051 = 155 sits at +0x1CB2). */
val GUIDE_TABLES_SOULGOLD = GuideTables(
    trainers = 0x084A4CDCL, learnsets = 0, wildHeaders = 0x094158D8L,
    sb1FlagsOff = 0x1898, sb1VarsOff = 0x1C10, flagBytes = 0x2AA,
    probeTrainer = 19, probeName = "Falkner", guide = GuideId.SOULGOLD,
    trainerStride = 0x34, trainerNameOff = 0x1F, trainerNameLen = 11, trainerSizeOff = 0x2B, trainerPartyOff = 8,
    trainerMon = TrainerMonLayout(stride = 0x28, movesOff = 0x0C, speciesOff = 0x14, itemOff = 0x16, levelOff = 0x1C),
    wildSets = 4, wildSet = 0,
    altTrainers = listOf(0x084B394CL), // HARD
    mapGroups = 0x09587364L, mapSecWide = true,
)

// v1.2 / v1.2b / v1.2c / v1.2.1: the same layouts, ids, flags and scripts; only the tables moved.
val GUIDE_TABLES_SOULGOLD_V1_2 = GUIDE_TABLES_SOULGOLD.copy(trainers = 0x084A5800L, wildHeaders = 0x09420BA8L, altTrainers = listOf(0x084B4470L), mapGroups = 0x09592634L)
val GUIDE_TABLES_SOULGOLD_V1_2B = GUIDE_TABLES_SOULGOLD.copy(trainers = 0x084A5768L, wildHeaders = 0x09420B04L, altTrainers = listOf(0x084B43D8L), mapGroups = 0x09592590L)
val GUIDE_TABLES_SOULGOLD_V1_2C = GUIDE_TABLES_SOULGOLD.copy(trainers = 0x084A58F4L, wildHeaders = 0x0944EA34L, altTrainers = listOf(0x084B4564L), mapGroups = 0x095C04C0L)
// v1.2.1 (both builds): the same again, moved once more.
val GUIDE_TABLES_SOULGOLD_V1_2_1 = GUIDE_TABLES_SOULGOLD.copy(trainers = 0x084AD384L, wildHeaders = 0x0945A8E0L, altTrainers = listOf(0x084BBFF4L), mapGroups = 0x095CC3A8L)

val GUIDE_TABLES_CELIA = GuideTables(
    trainers = 0x0871FCA0L,
    learnsets = 0,
    wildHeaders = 0x08C01578L,
    sb1FlagsOff = 0xEF0,
    sb1VarsOff = 0x1010,
    guide = GuideId.CELIA,
    mapGroups = 0x089DCD7CL,
)

/**
 * Pokémon Ultra Violet: retail FireRed rev 0's gTrainers (FireRed's gyms and League, one
 * trainer added at 530) and gLevelUpLearnsets, its own gWildMonHeaders (0x087339C0, all 14
 * literal-pool words) and gMapGroups (0x0872AF04); FireRed's SaveBlock1 flags / vars.
 */
val GUIDE_TABLES_ULTRA_VIOLET = GuideTables(
    trainers = 0x0823EAC8L,
    learnsets = 0x0825D7B4L,
    wildHeaders = 0x087339C0L,
    guide = GuideId.ULTRA_VIOLET,
    mapGroups = 0x0872AF04L,
)

/** FireRed Rocket Edition: rev 0's tables and maps, its own story's guide (GuideRocketEdition.kt). */
val GUIDE_TABLES_ROCKET_EDITION = GUIDE_TABLES_ULTRA_VIOLET.copy(
    wildHeaders = 0x083C9CB8L,
    guide = GuideId.ROCKET_EDITION,
    mapGroups = 0x083526A8L,
    probeName = "Brock",   // its text is mixed case
)

/** Pokémon Ash Gray: rev 0's gTrainers / learnsets, its own gWildMonHeaders and gMapGroups (all their literal-pool words). */
val GUIDE_TABLES_ASH_GRAY = GUIDE_TABLES_ULTRA_VIOLET.copy(
    wildHeaders = 0x088CD1F8L,
    guide = GuideId.ASH_GRAY,
    mapGroups = 0x0873E2F4L,
)

/**
 * Pokémon Elite Redux 2.65.4.1b: HERE only (its wild Pokémon by map). Its gWildMonHeaders are 28 bytes - land, water, rock
 * smash, a hidden set, fishing, honey (HERE reads the four it knows); gTrainers 0x38 bytes with a name POINTER (265 is a
 * ROXANNE), its TrainerMons 28 bytes (level +0: 0 = the player's level cap). NEXT BOSS isn't given: its leaders have several
 * teams each, levelled to the cap and picked at run time. SaveBlock1's flags / vars at +0x1978 / +0x1C78 (767 flag bytes).
 */
val GUIDE_TABLES_ELITE_REDUX_V2_65_4_1B = GuideTables(
    trainers = 0x089B723CL,
    learnsets = 0,
    wildHeaders = 0x08FD3A50L,
    sb1FlagsOff = 0x1978,
    sb1VarsOff = 0x1C78,
    flagBytes = 767,
    probeTrainer = 265,
    probeName = "Roxanne",
    guide = GuideId.ELITE_REDUX,
    trainerStride = 0x38,
    trainerNameLen = 20,
    trainerNamePtr = true,
    trainerSizeOff = 0x18,
    trainerPartyOff = 0x1C,
    trainerMon = TrainerMonLayout(stride = 28, movesOff = 8, speciesOff = 2, itemOff = 4, levelOff = 0),
    wildLayout = WildLayout(stride = 28, land = 0, water = 1, rockSmash = 2, fishing = 4),
    mapGroups = 0x091F54A0L,
)

/**
 * Pokémon Team Rocket Edition: Dragonsden Version: HERE only. Vanilla-shaped tables: gWildMonHeaders 0x08C2A974 (20 bytes,
 * 443 headers, 14 literal-pool words; Route 101's land set decodes to its Lv70-85 Hoenn Pokémon), gTrainers 0x0857F588 (40
 * bytes; 415 is KANTO LEADER BROCK, whose four the headless trainer battle met, from 24-byte TrainerMons). SaveBlock1's flags / vars at +0x1CA8 /
 * +0x1F6C, as its GF ROM header (0x08000150) says.
 */
val GUIDE_TABLES_TEAM_ROCKET = GuideTables(
    trainers = 0x0857F588L,
    learnsets = 0,
    wildHeaders = 0x08C2A974L,
    sb1FlagsOff = 0x1CA8,
    sb1VarsOff = 0x1F6C,
    flagBytes = 0x2C4,
    probeTrainer = 415,
    probeName = "BROCK",
    guide = GuideId.TEAM_ROCKET,
    // 24-byte TrainerMons: iv, level (u16), species, held item, six EVs, a nature, moves +0x10.
    trainerMon = TrainerMonLayout(stride = 24, movesOff = 16, speciesOff = 4, itemOff = 6, levelOff = 2),
)

/**
 * Pokémon Wishes of Tomorrow v1.2.6 (expansion 1.15.3): HERE only. gTrainers[EASY][NORMAL][HARD][896] at 0x08428F54 (EASY
 * empty, so its NORMAL array 0x08435354; 0x38-byte records - its own, four bytes longer than the reference build's: party
 * +8, class +0x1C, a 13-byte name +0x1F, party size +0x2D; 885 is GENERAL EDWARDS, whose six the headless trainer battle
 * met), expansion's 0x24-byte TrainerMons (moves 0 = the level-up ones: gSpeciesInfo's levelUpLearnset, {move, level}
 * pairs). gWildMonHeaders 0x08E88AA4 (13 literal-pool words like the reference's; 142 headers of 84 bytes, four
 * time-of-day sets with only the first filled). SaveBlock1's flags / vars at +0x1270 / +0x13A0 (its GF ROM header).
 */
val GUIDE_TABLES_WISHES = GuideTables(
    trainers = 0x08435354L,
    learnsets = 0x08701254L, // gSpeciesInfo[0].levelUpLearnset
    learnsetStride = 0x104,
    learnsetPairs = true,
    wildHeaders = 0x08E88AA4L,
    sb1FlagsOff = 0x1270,
    sb1VarsOff = 0x13A0,
    flagBytes = 0x130,
    probeTrainer = 885,
    probeName = "EDWARDS",
    guide = GuideId.WISHES,
    trainerStride = 0x38,
    trainerNameOff = 0x1F,
    trainerNameLen = 13,
    trainerSizeOff = 0x2D,
    trainerPartyOff = 8,
    trainerMon = TrainerMonLayout(stride = 0x24, movesOff = 0x0C, speciesOff = 0x14, itemOff = 0x16, levelOff = 0x1A),
    wildSets = 4,
    wildSet = 0,
    mapGroups = 0x08F90738L,
    trainerIdVar = true,
)

/**
 * Pokémon Dreamstone Mysteries (pokeemerald-expansion 1.11.2), from the ROM: gTrainers is [3][855] 0x28-byte records
 * from 0x085837E4 (GetTrainerDifficultyLevel: VarGet(0x40A8) picks the table, a NULL party there falls back to the second);
 * [trainers] is the second, the first is [altTrainers] (see GuideDreamstone.kt), the third is empty. 12 = LEADER INGER
 * (Rockruff 12, Geodude 12, Minior 13), 1.11.2's 0x24-byte TrainerMon. gWildMonHeaders 0x0900247C (165 of 24 bytes:
 * land / water / rock smash / fishing / hidden; ROUTE 1's land set is its RATTATA / PIDOVE / CUBONE / ROCKRUFF), gMapGroups
 * 0x09186E6C (40 groups; CARABRUE TOWN is 0.0, ROUTE 1 0.4). SaveBlock1's flags / vars at +0x148C / +0x15B8 (FlagSet's and
 * GetVarPointer's literals, 0x0810E820 / 0x0810E672).
 */
val GUIDE_TABLES_DREAMSTONE = GuideTables(
    trainers = 0x085837E4L + 0x8598,
    learnsets = 0,
    wildHeaders = 0x0900247CL,
    sb1FlagsOff = 0x148C,
    sb1VarsOff = 0x15B8,
    flagBytes = 0x12C,
    probeTrainer = 12,
    probeName = "Inger",
    guide = GuideId.DREAMSTONE,
    trainerStride = 0x28,
    trainerNameOff = 0x13,
    trainerNameLen = 11,
    trainerSizeOff = 0x20,
    trainerPartyOff = 4,
    trainerMon = TrainerMonLayout(stride = 0x24, movesOff = 0x0C, speciesOff = 0x14, itemOff = 0x16, levelOff = 0x1A),
    altTrainers = listOf(0x085837E4L),
    wildLayout = WildLayout(stride = 24),
    mapGroups = 0x09186E6CL,
)

/** 2.65.3b's: the same shapes at its own addresses. */
val GUIDE_TABLES_ELITE_REDUX_V2_65_3B = GUIDE_TABLES_ELITE_REDUX_V2_65_4_1B.copy(
    trainers = 0x089A9DC0L,
    wildHeaders = 0x08FC13D4L,
    mapGroups = 0x0917995CL,
)

/** Pokémon Ghost Grey: rev 0's gTrainers (414 is its first leader, ASTON), learnsets and gMapGroups, its own
 * gWildMonHeaders (0x09300100, all 14 literal-pool words); mixed-case text. */
val GUIDE_TABLES_GHOST_GREY = GUIDE_TABLES_ULTRA_VIOLET.copy(
    wildHeaders = 0x09300100L,
    guide = GuideId.GHOST_GREY,
    mapGroups = 0x083526A8L,
    probeName = "Aston",
)

/**
 * Pokémon Sword and Shield Ultimate Plus (CFRU): gTrainers rewritten in place at FireRed rev
 * 0's address (trainer 414 is MILO), CFRU's 3-byte learnsets at 0x099F4D98, gWildMonHeaders
 * 0x0892E408 (all 14 literal-pool words), gMapGroups 0x087B1010.
 */
val GUIDE_TABLES_SWORD_SHIELD = GuideTables(
    trainers = 0x0823EAC8L,
    learnsets = 0x099F4D98L,
    learnsetCfru = true,
    wildHeaders = 0x0892E408L,
    probeTrainer = 414,
    probeName = "Milo",
    guide = GuideId.SWORD_SHIELD,
    mapGroups = 0x087B1010L,
)

/**
 * Pokémon GS Chronicles v2.7.6 (CFRU): gTrainers rewritten in place at FireRed rev 0's address
 * (trainer 321 is FALKNER), CFRU's 3-byte learnsets at 0x097DD63C, gWildMonHeaders 0x08A31664
 * (all 14 literal-pool words; vanilla's shape, 151 headers), gMapGroups at FireRed's 0x083526A8
 * (rewritten in place: 43 groups).
 */
val GUIDE_TABLES_GS_CHRONICLES = GuideTables(
    trainers = 0x0823EAC8L,
    learnsets = 0x097DD63CL,
    learnsetCfru = true,
    wildHeaders = 0x08A31664L,
    probeTrainer = 321,
    probeName = "Falkner",
    guide = GuideId.GS_CHRONICLES,
    mapGroups = 0x083526A8L,
)

/**
 * Pokémon Orange Islands: retail FireRed rev 0's gTrainers (0x0823EAC8, rewritten - its gym
 * leaders are trainers 1-5, CISSY first) and gLevelUpLearnsets (0x0825D7B4, 0x70 before rev
 * 1's), its own gWildMonHeaders (repointed to 0x08A1B1B0, FireRed's layout) and FireRed's
 * SaveBlock1 flags / vars. gMapGroups is rev 0's (0x083526A8).
 */
val GUIDE_TABLES_ORANGE_ISLANDS = GuideTables(
    trainers = 0x0823EAC8L,
    learnsets = 0x0825D7B4L,
    wildHeaders = 0x08A1B1B0L,
    probeTrainer = 1,
    probeName = "CISSY",
    guide = GuideId.ORANGE_ISLANDS,
    mapGroups = 0x083526A8L,
)

/**
 * Pokémon Recharged Yellow: FireRed's trainer ids (414 = Brock), map groups and wild-header shape, its own
 * tables. gTrainers is 0x24-byte records - aiFlags, party +4, items, class +0x10, name +0x13 (11 bytes,
 * title-cased), doubleBattle +0x1E, sizes +0x1F (normal in the low nibble, HARD in the high one) and a
 * HARD party +0x20, chosen when SaveBlock2+0x6E0's low bits read 1 (its difficulty option) - read off the
 * code that loads them. Its parties are 0x20-byte TrainerMons with their own moves. gLevelUpLearnsets by
 * BULBASAUR's list, gWildMonHeaders by its 132 FireRed-shaped headers (the first, MONEAN CHAMBER's
 * UNOWN), gMapGroups by shape (its Overworld_GetMapHeader loads it). SaveBlock1 flags / vars at
 * +0xEFB / +0x1028 (the GF ROM header's offsets; trainer flag 0x500 + 414 and the three badges are set
 * on the user's three-badge save). Every release moved the tables; the layouts are the same.
 */
private val GUIDE_TABLES_RECHARGED_YELLOW = GuideTables(
    trainers = 0, learnsets = 0, wildHeaders = 0,
    sb1FlagsOff = 0xEFB,
    sb1VarsOff = 0x1028,
    flagBytes = 0x12C,
    probeTrainer = 414,
    probeName = "Brock",
    guide = GuideId.RECHARGED_YELLOW,
    trainerStride = 0x24,
    trainerNameOff = 0x13,
    trainerNameLen = 11,
    trainerSizeOff = 0x1F,
    trainerPartyOff = 4,
    trainerMon = TrainerMonLayout(stride = 0x20, movesOff = 0x0C, speciesOff = 0x14, itemOff = 0x16, levelOff = 0x1A),
    trainerHardPartyOff = 0x20,
)

val GUIDE_TABLES_RECHARGED_YELLOW_V1_8_0 = GUIDE_TABLES_RECHARGED_YELLOW.copy(
    trainers = 0x08664374L, learnsets = 0x08869920L, wildHeaders = 0x08962648L, mapGroups = 0x08A356F0L,
    guide = GuideId.RECHARGED_YELLOW_V180,
)
val GUIDE_TABLES_RECHARGED_YELLOW_V1_9_0 = GUIDE_TABLES_RECHARGED_YELLOW.copy(
    trainers = 0x08661578L, learnsets = 0x088693DCL, wildHeaders = 0x08962810L, mapGroups = 0x08A35758L,
    guide = GuideId.RECHARGED_YELLOW_V190,
)
val GUIDE_TABLES_RECHARGED_YELLOW_V1_9_4 = GUIDE_TABLES_RECHARGED_YELLOW.copy(
    trainers = 0x087414F4L, learnsets = 0x089493E0L, wildHeaders = 0x08A42E88L, mapGroups = 0x08B16238L,
    guide = GuideId.RECHARGED_YELLOW_V194,
)
val GUIDE_TABLES_RECHARGED_YELLOW_V1_9_7 = GUIDE_TABLES_RECHARGED_YELLOW.copy(
    trainers = 0x08768748L, learnsets = 0x089721F0L, wildHeaders = 0x08A6BD84L, mapGroups = 0x08B3F134L,
)

/**
 * Pokémon Recharged Emerald: Emerald's trainer ids (265 = Roxanne, every one of the 855 names at retail's id), map
 * groups and SaveBlock1 flags / vars moved by its bigger bag (+0x1344 / +0x1470, its GF ROM header's; Rebalanced 4
 * later), Recharged Yellow's struct Trainer (0x24 bytes, name +0x13 in its mixed case, sizes +0x1F, a HARD party
 * +0x20) and TrainerMons. gLevelUpLearnsets by BULBASAUR's list, gWildMonHeaders by retail's first map ids (v2.2.x
 * keep retail's 20-byte header; Rebalanced's is 0x24 bytes - land, a second land table, water, rock smash, fishing
 * at slots 0, 1, 2, 4, 6 - followed from v2.2.8's through the code that loads it), gMapGroups by the current map's
 * header (gMapHeader's bytes in the ROM -> its group's table -> the table of groups).
 */
private val GUIDE_TABLES_RECHARGED_EMERALD = GUIDE_TABLES_EMERALD.copy(
    trainers = 0, learnsets = 0, wildHeaders = 0, mapGroups = 0,
    sb1FlagsOff = 0x1344,
    sb1VarsOff = 0x1470,
    probeName = "Roxanne",
    guide = GuideId.RECHARGED_EMERALD,
    trainerStride = 0x24,
    trainerNameOff = 0x13,
    trainerNameLen = 11,
    trainerSizeOff = 0x1F,
    trainerPartyOff = 4,
    trainerMon = TrainerMonLayout(stride = 0x20, movesOff = 0x0C, speciesOff = 0x14, itemOff = 0x16, levelOff = 0x1A),
    trainerHardPartyOff = 0x20,
)

val GUIDE_TABLES_RECHARGED_EMERALD_V2_2_5 = GUIDE_TABLES_RECHARGED_EMERALD.copy(
    trainers = 0x0870A924L, learnsets = 0x088D8D94L, wildHeaders = 0x08A3F59CL, mapGroups = 0x08B346B8L,
)
val GUIDE_TABLES_RECHARGED_EMERALD_V2_2_8 = GUIDE_TABLES_RECHARGED_EMERALD.copy(
    trainers = 0x0870C7A4L, learnsets = 0x088DAC2CL, wildHeaders = 0x08A41234L, mapGroups = 0x08B36350L,
)
val GUIDE_TABLES_RECHARGED_EMERALD_REBALANCED_V2_2_8 = GUIDE_TABLES_RECHARGED_EMERALD.copy(
    trainers = 0x0870CEB4L, learnsets = 0x088DB3B0L, wildHeaders = 0x08A42748L, mapGroups = 0x08B38104L,
    sb1FlagsOff = 0x1348, sb1VarsOff = 0x1474,
    wildLayout = WildLayout(stride = 0x24, land = 0, water = 2, rockSmash = 4, fishing = 6),
)

/**
 * Super Mariomon v1.5.1-Anniversary: its own struct Trainer (0x2C bytes: aiFlags, party* +4,
 * items, class +0x10, trainerName[12] +0x13, partySize +0x1F; two more parties the game picks by
 * flags 0x29 / 0x885 at +0x24 / +0x28, sizes +0x20 / +0x21 - CreateNPCTrainerParty, read from its
 * code) and 0x20-byte TrainerMons (moves +0xC, species +0x14, item +0x16, level +0x1A; no moves =
 * the level-up ones). Trainer 102 is PEACH, the first STAR's leader. gWildMonHeaders: vanilla's
 * 20-byte headers, Emerald's Hoenn entries left in front of its own (the table the code loads).
 * Flags / vars at SaveBlock1+0x1468 / +0x1594 (the GF header's offsets), 300 flag bytes.
 */
val GUIDE_TABLES_MARIOMON_V1_5_1 = GuideTables(
    trainers = 0x08417360L,
    learnsets = 0x08473D9CL,
    learnsetPairs = true,
    wildHeaders = 0x087CBA10L,
    sb1FlagsOff = 0x1468,
    sb1VarsOff = 0x1594,
    flagBytes = 0x12C,
    probeTrainer = 102,
    probeName = "PEACH",
    guide = GuideId.MARIOMON,
    trainerStride = 0x2C,
    trainerNameOff = 0x13,
    trainerNameLen = 12,
    trainerSizeOff = 0x1F,
    trainerPartyOff = 4,
    trainerMon = TrainerMonLayout(stride = 0x20, movesOff = 0x0C, speciesOff = 0x14, itemOff = 0x16, levelOff = 0x1A),
    mapGroups = 0x0869821CL,
)

val GUIDE_TABLES_MARIOMON_V1_5_2 = GUIDE_TABLES_MARIOMON_V1_5_1.copy(
    trainers = 0x084177F0L, learnsets = 0x08474230L, wildHeaders = 0x087CBEC4L, mapGroups = 0x086986B0L,
)

val GUIDE_TABLES_MARIOMON_V1_3 = GUIDE_TABLES_MARIOMON_V1_5_1.copy(
    trainers = 0x08405F2CL, learnsets = 0x08461C10L, wildHeaders = 0x087AF0C8L, mapGroups = 0x08680194L,
)

val GUIDE_TABLES_MARIOMON_V1_2_2 = GUIDE_TABLES_MARIOMON_V1_5_1.copy(
    trainers = 0x08405F6CL, learnsets = 0x08461C50L, wildHeaders = 0x087AF0E4L, mapGroups = 0x086801D4L,
)

val GUIDE_TABLES_MARIOMON_V1_2_1 = GUIDE_TABLES_MARIOMON_V1_5_1.copy(
    trainers = 0x08405F8CL, learnsets = 0x08461C70L, wildHeaders = 0x087AF104L, mapGroups = 0x086801F4L,
)
