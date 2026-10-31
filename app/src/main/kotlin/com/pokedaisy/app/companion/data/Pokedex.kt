package com.pokedaisy.app.companion.data

import android.graphics.Bitmap
import java.util.concurrent.ConcurrentHashMap

/** Which memory a game's seen / caught arrays are offsets into. */
enum class DexFlagBlock { SAVE_BLOCK_1, SAVE_BLOCK_2, FIXED }

/**
 * Where the save keeps its seen / caught bits: two [bytes]-long bit arrays at
 * [seen] / [caught] in [block] ([DexFlagBlock.FIXED]: absolute addresses).
 * No. n is bit n - [firstBit] (bit n-1, like the games' own
 * GetSetPokedexFlag; Celia's hack keeps No. n at bit n).
 */
data class DexFlags(
    val block: DexFlagBlock,
    val seen: Long,
    val caught: Long,
    val bytes: Int,
    /**
     * SaveBlock1 offsets of FireRed / Emerald's two anti-cheat copies of
     * `seen`. When set, a bit only counts if every copy agrees, and caught
     * needs seen too - exactly what the game's own check does.
     */
    val seenCopies: List<Long> = emptyList(),
    val firstBit: Int = 1,
    /** Quetzal: `seen` holds a 3-bit level per species (0 unseen, 1 seen, 2 caught), 8 to a u32
     * (bits 0-23), so [bytes] * 4 of them. */
    val seenLevels: Boolean = false,
    /** Quetzal: caught is one bit array per region ([bytes] each, at these offsets) and counts in
     * any of them - what its card and continue screen count; [caught] is then unused. */
    val caughtRegions: List<Long> = emptyList(),
    /** Quetzal Alpha 0.8.4: no caught arrays - a [seenLevels] level of 2 or more is caught (its POKéDEX's OWN). */
    val caughtFromLevel: Boolean = false,
    /** Emerald Rogue: the arrays are indexed by species id, not dex number (No. n reads its species' bit). */
    val bySpecies: Boolean = false,
    /** Emerald Rogue's 2-bit state per species: [seen] holds bit 0 and [caught] bit 1 (1 seen, 2 caught,
     * 3 caught shiny), so a caught species is seen even with its [seen] bit clear. */
    val seenOrCaught: Boolean = false,
)

/** struct Pokedex in SaveBlock2 (+0x18: owned[52] at +0x10, seen[52] at +0x44) + SaveBlock1's copies. */
private fun vanillaDexFlags(seen1: Long, seen2: Long) = DexFlags(DexFlagBlock.SAVE_BLOCK_2, 0x5C, 0x28, 52, listOf(seen1, seen2))

/**
 * pokeemerald-expansion keeps a species' whole dex page in its gSpeciesInfo
 * entry ([PokedexTables.speciesInfo] is entry 0, [stride] bytes each) rather
 * than in gPokedexEntries / gMonFrontPicTable. Base stats (0-5) and types
 * (6, 7) are where vanilla has them; everything else moves per release, so
 * each hack lists its own offsets - found by dumping a known species' entry
 * and checking it field by field (natDexNum 25 / height 4 / weight 60 for
 * PIKACHU, its dex text, LZ77 / smol pic headers, the vanilla footprint bytes).
 */
data class SpeciesInfoDex(
    val stride: Int,
    val catchRateOff: Int = 0x08,
    val genderOff: Int = 0x12,
    val eggGroupsOff: Int = 0x16,
    /** Three u16 abilities, the third the hidden one. */
    val abilitiesOff: Int = 0x18,
    /** categoryName, inline (13 bytes). */
    val categoryOff: Int = 0x1F,
    /** natDexNum, then height and weight (u16 each). */
    val natDexOff: Int = 0x3C,
    val descriptionOff: Int = 0x4C,
    /** frontPic (LZ77 or smol, may hold two frames). */
    val frontPicOff: Int = 0x58,
    /** palette: LZ77 if it starts with 0x10, else 16 raw colours. */
    val paletteOff: Int,
    /** footprint (32 bytes, 1bpp); -1 = the game has none. */
    val footprintOff: Int = -1,
    /** levelUpLearnset ({u16 move, u16 level} ending at move 0xFFFF), teachableLearnset (u16 moves
     * ending 0xFFFF: TMs, HMs and tutors) and evolutions ([EvoLayout]) pointers; -1 = none / unknown.
     * Found by dumping BULBASAUR's entry (TACKLE 1, GROWL ..., LEVEL 16 -> IVYSAUR). */
    val levelUpOff: Int = -1,
    val teachableOff: Int = -1,
    val evolutionsOff: Int = -1,
)

/**
 * Where the POKéDEX tab reads from: the ROM's own dex tables (entries,
 * front sprites, base stats, ...) and where the save keeps the seen / caught
 * flags. Only games with a config get the tab.
 *
 * The retail ROM addresses were found by matching the tables of a decomp
 * build (pokefirered_rev1.map / pokeemerald.map) against the retail ROMs
 * (FireRed rev 1 sha1 dd5945db…, Emerald sha1 f3ae0881…): the pointer-free
 * tables byte-for-byte, the rest by record shape, then decoded and checked
 * (entry 1 = SEED, 2'04", 15.2 lbs.). LeafGreen (pret/pokefirered @ c75f352
 * `leafgreen` / `leafgreen_rev1`) and Ruby / Sapphire (pret/pokeruby
 * `ruby_rev1/2`, `sapphire_rev1/2`) were each built unmodified to a ROM
 * byte-identical to the user's retail dump, so their addresses are straight
 * from those builds' .map files; the static tables a map doesn't list
 * (LeafGreen's species -> national, R/S's footprints) by content. The ROM
 * hacks' were found by content in their own ROMs (see each config).
 */
data class PokedexTables(
    /** gPokedexEntries: [entryStride]-byte records, indexed by national dex number. */
    val entries: Long,
    /** gMonFrontPicTable: {LZ77 tiles ptr, u16 size, u16 tag}, by species. */
    val frontPics: Long,
    /** gMonPaletteTable: {LZ77 palette ptr, u16 tag, u16 pad}, by species. */
    val palettes: Long,
    /** gSpeciesInfo (gBaseStats): 28-byte records, by species ([expansion]: its entry 0). */
    val speciesInfo: Long,
    /** sSpeciesToNationalPokedexNum: u16 per species, starting at species 1. */
    val speciesToNational: Long,
    /** gAbilityNames: [abilityNameLength]-byte strings, by ability id. */
    val abilityNames: Long,
    /** gMonFootprintTable: ptr to a 16x16 1bpp footprint, by species; 0 = none. */
    val footprints: Long,
    val abilityNameLength: Int = 13,
    /** Bytes from one ability name to the next; 0 = [abilityNameLength]
     * (expansion's gAbilitiesInfo has a description and AI rating after it). */
    val abilityNameStride: Int = 0,
    /** Each [abilityNameStride]-byte record starts with a POINTER to the name, not the name (Elite Redux). */
    val abilityNamePtr: Boolean = false,
    /** gSpeciesInfo offset of a third (hidden) ability byte; -1 = none (vanilla). */
    val hiddenAbilityOff: Int = -1,
    /** After the category on the dex page, in the game's own casing ("" = none: German, French). */
    val categorySuffix: String = "POKéMON",
    /** Before it instead (Spanish / Italian Emerald: "POKéMON POLLUELO"). */
    val categoryPrefix: String = "",
    /** HT / WT in metres and kilograms with a decimal comma, as the European releases print them. */
    val metric: Boolean = false,
    /** FireRed's entries carry an extra unused description pointer (0x24); Emerald's don't (0x20). */
    val entryStride: Int = 0x24,
    /** Ruby/Sapphire split the dex text over two pages: the second page's
     * pointer's offset in the entry (0x14); -1 = one page (FireRed's 0x14 is unused). */
    val descriptionPage2Off: Int = -1,
    val speciesCount: Int = 412,
    val nationalCount: Int = 386,
    /** The regional dex before the National Dex is unlocked (Kanto: 1-151). */
    val regionalCount: Int = 151,
    /** null = the game has one dex only (no regional / national switch). */
    val regionName: String? = "KANTO",
    /** National numbers in regional-dex order (Emerald's sHoennToNationalOrder);
     * 0 = the regional dex is national 1..[regionalCount] (Kanto). */
    val regionalOrder: Long = 0,
    /** Bytes per [regionalOrder] entry: u16, or u32 (Heart and Soul's Johto). */
    val regionalOrderStride: Int = 2,
    /** [regionalOrder] lists species ids, not national numbers (Unbound). */
    val regionalOrderIsSpecies: Boolean = false,
    /** u16 species per dex number, from No. 0 - read instead of inverting
     * [speciesToNational] (Celia's hack, whose table is only this way round). */
    val nationalToSpecies: Long = 0,
    /** [entries] is indexed by species, not by dex number (Celia's hack). */
    val entriesBySpecies: Boolean = false,
    /** The dex page lives in gSpeciesInfo (pokeemerald-expansion); null = vanilla tables. */
    val expansion: SpeciesInfoDex? = null,
    /** A Game Boy game's dex ([Gen1Dex]: read from the cart's bytes); null = a GBA one. */
    val gen1: Gen1DexTables? = null,
    /** A Game Boy Color game's dex ([Gen2Dex]: read from the cart's bytes); null = not one. */
    val gen2: Gen2DexTables? = null,
    /** A DS game's dex ([Gen4Dex]: the cart's own pages and pictures, taken out by NdsArt); null = not one. */
    val gen4: Gen4DexTables? = null,
    val flags: DexFlags = FIRERED_DEX_FLAGS,
    /** The flags' SaveBlock2 struct Pokedex, whose nationalMagic says the National Dex is unlocked. */
    val sb2DexOff: Long = 0x18,
    /** pokedex.nationalMagic's offset and the value meaning "unlocked";
     * -1 = the National Dex is always there (Unbound). */
    val nationalMagicOff: Int = 3,
    val nationalMagic: Int = 0xB9,
    /** gEvolutionTable: 5 {u16 method, u16 param, u16 target, pad} per species
     * (see [EvolutionSource]); 0 = not located for this game yet. Found by
     * searching the retail ROMs for BULBASAUR's row (LEVEL 16 -> IVYSAUR). */
    val evolutions: Long = 0,
    /** Entry 1's category, as [pokedexMatchesRom] expects to read it: the game's own language. */
    val probeCategory: String = "SEED",
    /** Entry 1's height / weight, and the species whose national number is 1 (BULBASAUR's 7 / 69 / 1;
     * Super Mariomon's No.1 is PIRANHA P., species 906). */
    val probeHeight: Int = 7,
    val probeWeight: Int = 69,
    val probeSpecies: Int = 1,
    /** Japanese Emerald's 0x1C-byte entries: categoryName[6], height at +6, weight +8, the text at +0x0C. */
    val entryCategoryLen: Int = 12,
    /** The entry starts with a pointer to its category's text, not the text (Quetzal's 12-byte entries). */
    val entryCategoryPtr: Boolean = false,
    /** gBaseStats' shape, where it isn't vanilla's 28-byte one. */
    val baseStats: BaseStatsLayout = VANILLA_BASE_STATS,
    val entryHeightOff: Int = 0x0C,
    val entryDescOff: Int = 0x10,
    /** Between the category and [categorySuffix]: none in Japanese ("ひよこポケモン"). */
    val categorySeparator: String = " ",
    /** [metric]'s decimal mark and unit spacing: "0,4 m" in Europe, "0.4m" in Japan. */
    val decimalPoint: Char = ',',
    val unitSpace: Boolean = true,
    /** Where the DEX page's EVOLVE reads from when it isn't [evolutions]' vanilla table ([DexDetails]). */
    val evoLayout: EvoLayout? = null,
    /** A per-species pointer table of {u16 move, u16 level} level-up lists ending at move 0xFFFF, for
     * games whose [GuideTables] has none (Quetzal, R.O.W.E.); 0 = none. */
    val levelUpLearnsets: Long = 0,
    /** sTMHMLearnsets (a u64 per species: TM01-TM50, then HM01-HM08) and sTMHMMoves (u16 x 58), for the
     * DEX's MOVES page; 0 = not located. Found by BULBASAUR's bits and FOCUS PUNCH, DRAGON CLAW, ...
     * (TM01-04); both are checked before use, so a port that copied English's addresses gets none. */
    val tmhmLearnsets: Long = 0,
    val tmhmMoves: Long = 0,
) {
    val hasRegional: Boolean get() = regionName != null
}

/** gBaseStats (vanilla) / gSpeciesInfo (vanilla-shaped hacks): its stride and the fields that move. */
data class BaseStatsLayout(
    val stride: Int = 28,
    val genderOff: Int = 0x10,
    val eggGroupsOff: Int = 0x14,
    val abilitiesOff: Int = 0x16,
    /** Ability ids are u16 (Quetzal), not bytes. */
    val abilityU16: Boolean = false,
    /** Base stats are u16s (R.O.W.E.), types and catch rate then sit at [typesOff] / [catchRateOff]. */
    val statsU16: Boolean = false,
    val typesOff: Int = 6,
    val catchRateOff: Int = 8,
)

val VANILLA_BASE_STATS = BaseStatsLayout()

/**
 * CFRU moves the flags out of struct Pokedex into SaveBlock1 (the old bag
 * area): seen at +0x310, owned at +0x38D, 1000 bits each, no anti-cheat copies.
 */
val CFRU_DEX_FLAGS = DexFlags(DexFlagBlock.SAVE_BLOCK_1, 0x310, 0x38D, 125)

/**
 * Pokémon Unbound v2.1.1.1 (sha1 b4776b82…), a closed-source CFRU/DPE hack:
 * no map file, so every table was found in the ROM by content (Bulbasaur's
 * base stats / dex entry, {ptr, size, tag = species} pic records, the
 * species -> national u16 run starting 1, 2, 3...) and checked by decoding
 * (sprites rendered and looked at; 905 entries through ENAMORUS). The flags
 * were found in a headless capture of the user's save - the only bits set
 * matched the party's GIBLE (owned) and the game's own "Seen: National 4" -
 * then confirmed by poking one more bit into each array and watching the
 * game's POKéDEX counts go up by one. The Borrius order was read off the
 * game's own "Numerical Mode: Borrius" list in RAM, then found in the ROM.
 */
val POKEDEX_UNBOUND = PokedexTables(
    entries = 0x09A357CCL,
    frontPics = 0x09A1D5B4L,
    palettes = 0x09A32BDCL,
    speciesInfo = 0x099E0C9CL,
    speciesToNational = 0x09A41FECL,
    abilityNames = 0x08A36398L,
    footprints = 0,
    abilityNameLength = 17,
    hiddenAbilityOff = 0x1A,
    categorySuffix = "Pokémon",
    speciesCount = 1294,
    nationalCount = 905,
    regionalCount = 498,
    regionName = "BORRIUS",
    regionalOrder = 0x09A3F398L,
    regionalOrderIsSpecies = true,
    nationalMagicOff = -1,
    flags = CFRU_DEX_FLAGS,
    // CFRU's gEvolutionTable: 16 rows per species (Mega Evolutions among them), repointed.
    evoLayout = EvoLayout(EvoScheme.CFRU, table = 0x099F3A7AL, perMon = 16),
)

/** Unbound v2.1.1.1 FR: English's tables, the dex text translated in place - its page reads
 * "Pokémon Graine", "Ta 0.7m", "Po 16.8 kg" (headless, the game's own POKéDEX). */
val POKEDEX_UNBOUND_FR = POKEDEX_UNBOUND.copy(
    categoryPrefix = "Pokémon",
    categorySuffix = "",
    metric = true,
    decimalPoint = '.',
    probeCategory = "Graine",
)

/** Ruby (rev 1 and rev 2 share every address): Emerald's flag rules,
 * seen copies at SaveBlock1+0x938/+0x3A8C, two-page dex text. */
val POKEDEX_RUBY = PokedexTables(
    entries = 0x083B1874L,
    frontPics = 0x081E836CL,
    palettes = 0x081EA5CCL,
    speciesInfo = 0x081FEC30L,
    speciesToNational = 0x081FC52EL,
    abilityNames = 0x081FA260L,
    footprints = 0x083B4F00L,
    evolutions = 0x08203B80L,
    tmhmLearnsets = 0x081FD108L,
    tmhmMoves = 0x0837651CL,
    descriptionPage2Off = 0x14,
    regionalCount = 202,
    regionName = "HOENN",
    regionalOrder = 0x081FC864L,
    flags = vanillaDexFlags(0x938, 0x3A8C),
    nationalMagicOff = 2,
    nationalMagic = 0xDA,
)

/** Sapphire (rev 1 and rev 2): Ruby's layout, its own ROM addresses. */
val POKEDEX_SAPPHIRE = POKEDEX_RUBY.copy(
    entries = 0x083B18D0L,
    frontPics = 0x081E82FCL,
    palettes = 0x081EA55CL,
    speciesInfo = 0x081FEBC0L,
    speciesToNational = 0x081FC4BEL,
    abilityNames = 0x081FA1F0L,
    footprints = 0x083B4F5CL,
    evolutions = 0x08203B10L,
    regionalOrder = 0x081FC7F4L,
    tmhmLearnsets = 0x081FD098L,
    tmhmMoves = 0x083764ACL,
)

/*
 * Pokémon Emerald Legacy v1.1.4: retail's dex, rebuilt - every table moved (its own ELF: the release is its source built
 * with agbcc, byte for byte). Three Deoxys forms sit at species 412-414 (SPECIES_EGG 415), its Hoenn dex is retail's 202
 * plus eight (PONYTA .. HOUNDOOM after DEOXYS), and SaveBlock1 grew before seen1 / seen2 (a 120-slot ITEMS pocket, more
 * trainer flags: offsetof with agbcc against its headers).
 */
val EMERALD_LEGACY_DEX_FLAGS = vanillaDexFlags(0xAF0, 0x3B34)
val POKEDEX_EMERALD_LEGACY = POKEDEX_EMERALD.copy(
    entries = 0x0859DD5CL, frontPics = 0x0831F560L, palettes = 0x08318958L, speciesInfo = 0x08348AF4L,
    speciesToNational = 0x08346290L, abilityNames = 0x08343CBDL, footprints = 0x085A0E40L,
    regionalOrder = 0x083465CCL, evolutions = 0x0834E1D0L, tmhmLearnsets = 0x08346FA4L, tmhmMoves = 0x0864A31CL,
    speciesCount = 415, regionalCount = 210, flags = EMERALD_LEGACY_DEX_FLAGS,
)

/*
 * The European Emerald releases: English's dex, its tables moved (localized
 * text has other lengths) - each address is the word English's code loads it
 * from, read at the same place in the language's code. The page is worded as
 * each game's own (headless screenshots of TORCHIC's): German / French print
 * the category alone, Spanish / Italian put POKéMON first; all four in metres
 * and kilograms.
 */
val POKEDEX_EMERALD_ES = POKEDEX_EMERALD.copy(
    entries = 0x0856F078L, frontPics = 0x083103F0L, palettes = 0x083098DCL, speciesInfo = 0x08326688L,
    speciesToNational = 0x08323F3EL, abilityNames = 0x08321999L, footprints = 0x0857215CL,
    regionalOrder = 0x08324274L, evolutions = 0x0832B5D8L, tmhmLearnsets = 0x08324B54L, tmhmMoves = 0x086189D4L,
    probeCategory = "SEMILLA", categoryPrefix = "POKéMON", categorySuffix = "", metric = true,
)
val POKEDEX_EMERALD_DE = POKEDEX_EMERALD.copy(
    entries = 0x0857D39CL, frontPics = 0x0831EAFCL, palettes = 0x08317FE8L, speciesInfo = 0x08334D8CL,
    speciesToNational = 0x08332642L, abilityNames = 0x0833009EL, footprints = 0x08580480L,
    regionalOrder = 0x08332978L, evolutions = 0x08339CDCL, tmhmLearnsets = 0x08333258L, tmhmMoves = 0x0862705CL,
    probeCategory = "SAMEN", categorySuffix = "", metric = true,
)
val POKEDEX_EMERALD_FR = POKEDEX_EMERALD.copy(
    entries = 0x08570914L, frontPics = 0x08311CBCL, palettes = 0x0830B1A8L, speciesInfo = 0x08327F3CL,
    speciesToNational = 0x083257F2L, abilityNames = 0x0832324EL, footprints = 0x085739F8L,
    regionalOrder = 0x08325B28L, evolutions = 0x0832CE8CL, tmhmLearnsets = 0x08326408L, tmhmMoves = 0x08619F1CL,
    probeCategory = "GRAINE", categorySuffix = "", metric = true,
)
val POKEDEX_EMERALD_IT = POKEDEX_EMERALD.copy(
    entries = 0x08568C80L, frontPics = 0x08309B50L, palettes = 0x0830303CL, speciesInfo = 0x0831FDCCL,
    speciesToNational = 0x0831D682L, abilityNames = 0x0831B0DBL, footprints = 0x0856BD64L,
    regionalOrder = 0x0831D9B8L, evolutions = 0x08324D1CL, tmhmLearnsets = 0x0831E298L, tmhmMoves = 0x08612730L,
    probeCategory = "SEME", categoryPrefix = "POKéMON", categorySuffix = "", metric = true,
)

/**
 * Japanese Emerald: its own build, the same tables at other addresses (English's
 * literal pools again), but 0x1C-byte entries - categoryName[6], height +6,
 * weight +8, the text at +0x0C - and 8-byte ability names. Its page reads
 * "ひよこポケモン", "0.4m", "2.5kg" (TORCHIC's, headless).
 */
val POKEDEX_EMERALD_JA = POKEDEX_EMERALD.copy(
    entries = 0x0854069CL, frontPics = 0x082DDA1CL, palettes = 0x082D6F08L, speciesInfo = 0x082F0D54L,
    speciesToNational = 0x082EE60AL, abilityNames = 0x082EBDC4L, footprints = 0x08543168L,
    regionalOrder = 0x082EE940L, evolutions = 0x082F5CA4L, tmhmLearnsets = 0x082EF220L, tmhmMoves = 0x085E144CL,
    entryStride = 0x1C, entryCategoryLen = 6, entryHeightOff = 6, entryDescOff = 0x0C, abilityNameLength = 8,
    probeCategory = "たね", categorySuffix = "ポケモン", categorySeparator = "",
    metric = true, decimalPoint = '.', unitSpace = false,
)

/*
 * The FireRed-engine hacks below keep vanilla's record layouts (28-byte
 * gBaseStats, 0x24-byte gPokedexEntries, {ptr, size, tag} pic tables), just
 * relocated and longer. Each table was found in the hack's own ROM by content
 * - BULBASAUR's base stats and SEED entry, species 1's pic records, a
 * 1, 2, 3, ... species -> dex run, STENCH then DRIZZLE - picking the copy the
 * code references when a stale one is left behind, then checked by decoding
 * (category, height / weight, dex text, abilities) and rendering sprites.
 * The flags were found in headless captures of the user's saves (the bits of
 * the party / rival Pokémon) and confirmed against each game's own POKéDEX
 * screen, poking a bit and watching its counts or list change.
 */

/**
 * Pokémon Odyssey v4.1.1: vanilla rev-0 tables, except the dex entries
 * (moved and grown to 409) and ability names. Its 23 added species reuse
 * the old unused slots 252-274 as dex numbers 387-409 (ROSERADE, FROSLASS,
 * GLACEON, ...); the TALREGA dex is national 1-151. The flags are vanilla
 * struct Pokedex without SaveBlock1's anti-cheat copies; the game's own
 * table of contents read Seen National 8 / Owned 2 for the user's save.
 */
val POKEDEX_ODYSSEY = PokedexTables(
    entries = 0x0920C980L,
    frontPics = 0x082350ACL,
    palettes = 0x0823730CL,
    speciesInfo = 0x08254784L,
    speciesToNational = 0x08251FEEL,
    abilityNames = 0x090B7E00L,
    footprints = 0, // every species points at one blank footprint
    categorySuffix = "Pokémon",
    nationalCount = 409,
    regionName = "TALREGA",
    flags = DexFlags(DexFlagBlock.SAVE_BLOCK_2, 0x5C, 0x28, 52),
    nationalMagicOff = -1, // its table of contents lists National mode from the start
    evolutions = 0x08259754L,
)

/**
 * Pokémon Radical Red v4.1 (CFRU, like Unbound): 1025 national entries,
 * hidden abilities, 17-byte ability names; the regional dex is Kanto 1-151.
 * Its owned array sits 164 bytes after `seen` (SaveBlock1+0x3B4), not at
 * Unbound's +0x38D. The National Dex unlocks the vanilla way: setting
 * nationalMagic made the game's table of contents add National mode.
 */
val POKEDEX_RADICAL_RED = PokedexTables(
    entries = 0x09814030L,
    frontPics = 0x097FA1C4L,
    palettes = 0x09811208L,
    speciesInfo = 0x097B98ECL,
    speciesToNational = 0x098218F0L,
    abilityNames = 0x090E32C0L,
    footprints = 0,
    abilityNameLength = 17,
    hiddenAbilityOff = 0x1A,
    categorySuffix = "Pokémon",
    speciesCount = 1376,
    nationalCount = 1025,
    flags = DexFlags(DexFlagBlock.SAVE_BLOCK_1, 0x310, 0x3B4, 164),
    evoLayout = EvoLayout(EvoScheme.CFRU_RADICAL_RED, table = 0x097CD9B0L, perMon = 16),
)

/**
 * Pokémon Amethyst v1.3.0 (CFRU): numbers its dex its own way - No. 1-390 is
 * its regional dex (the game calls it KANTO; TEPIG is No. 340) and the rest
 * runs to 690 - and both the flags and gPokedexEntries are indexed by that
 * number. Read off the game's own list in RAM, then found in the ROM: the
 * species -> number table and the entries (a stale entry table with no code
 * references sits before them).
 */
val POKEDEX_AMETHYST = PokedexTables(
    entries = 0x09C13D58L,
    frontPics = 0x09BFDD0CL,
    palettes = 0x09C113F8L,
    speciesInfo = 0x09BC257CL,
    speciesToNational = 0x09C1E4B8L,
    abilityNames = 0x089272F8L,
    footprints = 0,
    abilityNameLength = 17,
    hiddenAbilityOff = 0x1A,
    categorySuffix = "Pokémon",
    speciesCount = 1268,
    nationalCount = 690,
    regionalCount = 390,
    flags = CFRU_DEX_FLAGS,
    evoLayout = EvoLayout(EvoScheme.CFRU, table = 0x09BD4F3CL, perMon = 16),
)

/** Amethyst v1.4.1: the same dex (entries byte for byte), its tables moved; 26 more species. */
val POKEDEX_AMETHYST_V1_4_1 = POKEDEX_AMETHYST.copy(
    entries = 0x09AEB678L,
    frontPics = 0x09AD4E34L,
    palettes = 0x09AE8BD0L,
    speciesInfo = 0x09A98390L,
    speciesToNational = 0x09AF5F14L,
    abilityNames = 0x0894DD1CL,
    speciesCount = 1294,
    evoLayout = EvoLayout(EvoScheme.CFRU, table = 0x09AAB2FCL, perMon = 16),
)

/**
 * Pokémon Gaia v3.2: one dex, national 1-721. Its flags live outside the save
 * blocks, at fixed EWRAM addresses: poking bits there made BULBASAUR (seen)
 * and VENUSAUR (caught) appear in the game's own list.
 */
val POKEDEX_GAIA = PokedexTables(
    entries = 0x087319D0L,
    frontPics = 0x08720100L,
    palettes = 0x08724108L,
    speciesInfo = 0x08ACD000L,
    speciesToNational = 0x0993C1D0L,
    abilityNames = 0x08A66000L,
    footprints = 0x087382BCL,
    categorySuffix = "Pokémon",
    speciesCount = 935,
    nationalCount = 721,
    regionalCount = 721,
    regionName = null,
    flags = DexFlags(DexFlagBlock.FIXED, 0x0203C400L, 0x0203C45BL, 91),
    nationalMagicOff = -1,
    evoLayout = EvoLayout(EvoScheme.GAIA, table = 0x08A9556CL, perMon = 16),
)

/**
 * Celia's Stupid Romhack v1.1.4: one 150-entry dex in its own order (No. 1
 * BULBASAUR = species 2, No. 0 is a joke VICTINI), only stored as a
 * number -> species table. Entries are indexed by species, ability names are
 * 19 bytes. The flags are in SaveBlock2 but resized (owned +0x28, seen +0x59,
 * 49 bytes each) and No. n is bit n: poking bits 1-3 marked No. 1-3 in the
 * game's own list.
 */
val POKEDEX_CELIA = PokedexTables(
    entries = 0x08CA6B70L,
    frontPics = 0x0870D508L,
    palettes = 0x08713CDCL,
    speciesInfo = 0x0874B058L,
    speciesToNational = 0,
    nationalToSpecies = 0x08745138L,
    entriesBySpecies = true,
    abilityNames = 0x0873B9B5L,
    abilityNameLength = 19,
    footprints = 0x0874323CL,
    speciesCount = 411,
    nationalCount = 150,
    regionalCount = 150,
    regionName = null,
    flags = DexFlags(DexFlagBlock.SAVE_BLOCK_2, 0x59, 0x28, 49, firstBit = 0),
    nationalMagicOff = -1,
)

/*
 * The pokeemerald-expansion hacks keep the dex page in gSpeciesInfo
 * ([SpeciesInfoDex]) and the flags in SaveBlock1 (dexSeen / dexCaught, no
 * copies), indexed by each game's own natDexNum. Ability names are inline in
 * gAbilitiesInfo (17 bytes, 0x1C apart). The National Dex unlocks the Emerald
 * way (struct Pokedex.nationalMagic = 0xDA). The flags were found from the
 * saves' starter / intro Pokémon, then confirmed in each game's own POKéDEX
 * (opened by calling the game's FlagSet for FLAG_SYS_POKEDEX_GET headlessly)
 * by poking bits, or for Heart and Soul by a wild encounter that added its bit.
 * The regional orders are the ones the games list once every species is
 * marked seen.
 */

/** Pokémon Heart and Soul v2.0.6: national 1-1080, the JOHTO dex is a u32 list (282). */
val POKEDEX_HEART_AND_SOUL = PokedexTables(
    entries = 0,
    frontPics = 0,
    palettes = 0,
    speciesInfo = 0x087E6544L,
    speciesToNational = 0,
    abilityNames = 0x08D35734L,
    footprints = 0,
    abilityNameLength = 17,
    abilityNameStride = 0x1C,
    speciesCount = 1524,
    nationalCount = 1080,
    regionalCount = 282,
    regionName = "JOHTO",
    regionalOrder = 0x08D34C78L,
    regionalOrderStride = 4,
    expansion = SpeciesInfoDex(stride = 0x10C, paletteOff = 0x60, footprintOff = 0x84, levelUpOff = 0x98, teachableOff = 0x9C, evolutionsOff = 0xA4),
    evoLayout = EvoLayout(EvoScheme.EXPANSION_PARAMS, record = 12, conditionsEnd = 39),
    flags = DexFlags(DexFlagBlock.SAVE_BLOCK_1, 0x39C0, 0x3A7F, 191),
    nationalMagicOff = 2,
    nationalMagic = 0xDA,
)

/** Pokémon Lazarus v2.0: a sparse national dex up to 1028 (unused species are
 * zeroed), the ILIOS dex (430). No footprints. Its menu has no POKéDEX in the
 * user's save, so its flags are matched to the save (FENNEKIN seen and
 * caught), not to the game's screen. */
val POKEDEX_LAZARUS = PokedexTables(
    entries = 0,
    frontPics = 0,
    palettes = 0,
    speciesInfo = 0x08C7A338L,
    speciesToNational = 0,
    abilityNames = 0x088DD968L,
    footprints = 0,
    abilityNameLength = 17,
    abilityNameStride = 0x1C,
    categorySuffix = "Pokémon",
    speciesCount = 1561,
    nationalCount = 1028,
    regionalCount = 430,
    regionName = "ILIOS",
    regionalOrder = 0x088DFC5CL,
    expansion = SpeciesInfoDex(stride = 0xD4, paletteOff = 0x68, levelUpOff = 0x90, teachableOff = 0x94, evolutionsOff = 0x9C),
    evoLayout = EvoLayout(EvoScheme.EXPANSION_LAZARUS),
    flags = DexFlags(DexFlagBlock.SAVE_BLOCK_1, 0x32A8, 0x3329, 129),
    nationalMagicOff = 2,
    nationalMagic = 0xDA,
)

/** Emerald Seaglass v3.0: its own numbering puts Hoenn first (TREECKO = No. 1,
 * BULBASAUR = 141), 430 in all; the HOENN dex has 212. */
val POKEDEX_EMERALD_SEAGLASS = PokedexTables(
    entries = 0,
    frontPics = 0,
    palettes = 0,
    speciesInfo = 0x088F0780L,
    speciesToNational = 0,
    abilityNames = 0x086E15B0L,
    footprints = 0,
    abilityNameLength = 17,
    abilityNameStride = 0x1C,
    categorySuffix = "Pokémon",
    speciesCount = 1489,
    nationalCount = 430,
    regionalCount = 212,
    regionName = "HOENN",
    regionalOrder = 0x086E37E0L,
    expansion = SpeciesInfoDex(stride = 0xD0, paletteOff = 0x68, levelUpOff = 0x8C, teachableOff = 0x90, evolutionsOff = 0x98),
    evoLayout = EvoLayout(EvoScheme.EXPANSION),
    flags = DexFlags(DexFlagBlock.SAVE_BLOCK_1, 0x28FC, 0x2932, 54),
    nationalMagicOff = 2,
    nationalMagic = 0xDA,
)

/** Too Many Types 2 v1.5.2: a newer gSpeciesInfo (a third type byte shifts
 * everything after the types by one or two), its own numbering up to 1052
 * (CHIMCHAR = 397), the HOENN dex (398, SPRIGATITO first). */
val POKEDEX_TMT2 = PokedexTables(
    entries = 0,
    frontPics = 0,
    palettes = 0,
    speciesInfo = 0x08712A48L,
    speciesToNational = 0,
    abilityNames = 0x08DF4F9CL,
    footprints = 0,
    abilityNameLength = 17,
    abilityNameStride = 0x1C,
    categorySuffix = "Pokémon",
    speciesCount = 1651,
    nationalCount = 1052,
    regionalCount = 398,
    regionName = "HOENN",
    regionalOrder = 0x08DF4C80L,
    expansion = SpeciesInfoDex(
        stride = 0x104, catchRateOff = 0x09, genderOff = 0x14, eggGroupsOff = 0x18, abilitiesOff = 0x1A,
        categoryOff = 0x21, natDexOff = 0x3E, paletteOff = 0x60, footprintOff = 0x80,
        levelUpOff = 0x94, teachableOff = 0x98, evolutionsOff = 0xA0,
    ),
    evoLayout = EvoLayout(EvoScheme.EXPANSION_PARAMS, record = 12, conditionsEnd = 37),
    flags = DexFlags(DexFlagBlock.SAVE_BLOCK_1, 0x289C, 0x2920, 132),
    nationalMagicOff = 2,
    nationalMagic = 0xDA,
)

/**
 * R.O.W.E. v2.1.9.1 Experimental: vanilla-shaped tables, grown - 0x24-byte entries by dex number
 * (category[14], height +0xE, weight +0x10, text +0x14; valid through No. 1019), gBaseStats 0x40-byte
 * entries with u16 stats (types +0xC, catch rate +0xE, gender +0x18, egg groups +0x1C, u16 abilities
 * +0x1E / +0x20, hidden +0x24), 21-byte ability names; no footprints. Flags in SaveBlock1 (DURALUDON
 * in both arrays on the user's save; a wild WURMPLE marked seen only). Its START menu has no POKéDEX
 * yet on this save, so not compared with the game's own screen.
 */
val POKEDEX_ROWE = PokedexTables(
    entries = 0x08F592C8L,
    frontPics = 0x083E0AA4L,
    palettes = 0x083D35DCL,
    speciesInfo = 0x08502C54L,
    speciesToNational = 0x084F708EL,
    abilityNames = 0x08422C58L,
    footprints = 0,
    abilityNameLength = 21,
    hiddenAbilityOff = 0x24,
    entryStride = 0x24,
    entryCategoryLen = 14,
    entryHeightOff = 0x0E,
    entryDescOff = 0x14,
    baseStats = BaseStatsLayout(
        stride = 0x40, genderOff = 0x18, eggGroupsOff = 0x1C, abilitiesOff = 0x1E, abilityU16 = true,
        statsU16 = true, typesOff = 0x0C, catchRateOff = 0x0E,
    ),
    categorySuffix = "Pokémon",
    probeCategory = "Seed",
    evoLayout = EvoLayout(EvoScheme.ROWE, table = 0x085B6F60L, perMon = 10),
    levelUpLearnsets = 0x085E456CL,
    speciesCount = 1960,
    nationalCount = 1019,
    regionalCount = 1019,
    regionName = null,
    flags = DexFlags(DexFlagBlock.SAVE_BLOCK_1, seen = 0x3320, caught = 0x33DC, bytes = 0xBC),
    nationalMagicOff = -1,
)

/**
 * Emerald Rogue v2.2.1-EX: an expansion-shaped 0x98-byte gSpeciesInfo (natDexNum +0x3A, text +0x48,
 * front pic +0x54, palette +0x64, footprint +0x7C); species are national numbers up to 905, then
 * forms (906-1288), Gen 9 from 1289. SaveBlock1 keeps a 2-bit state per species id in
 * pokedexBitFlags1 (+0x30B4, bit 0) and pokedexBitFlags2 (+0x3179, bit 1) - 1 seen, 2 caught,
 * 3 caught shiny (src/pokedex.c GetSetPokedexSpeciesFlag; the offsets from the literal pools beside
 * gSaveBlock1Ptr). Its dex shows one "variant" at a time: the default MODERN list (400 species ids,
 * gPokedexVariants[0]) is the regional order. Matched to the game's own dex: Seen 2 / Caught 1 on
 * the user's save, Seen 4 / Caught 2 after poking MAREEP caught and PAWMI (1305) seen.
 */
val POKEDEX_EMERALD_ROGUE = PokedexTables(
    entries = 0,
    frontPics = 0,
    palettes = 0,
    speciesInfo = 0x0905BC40L,
    speciesToNational = 0,
    abilityNames = 0x08466A68L,
    footprints = 0,
    abilityNameLength = 17,
    categorySuffix = "Pokémon",
    speciesCount = 1574,
    nationalCount = 1025,
    regionalCount = 400,
    regionName = "MODERN",
    regionalOrder = 0x084A96D8L,
    regionalOrderIsSpecies = true,
    expansion = SpeciesInfoDex(
        stride = 0x98, natDexOff = 0x3A, descriptionOff = 0x48, frontPicOff = 0x54, paletteOff = 0x64, footprintOff = 0x7C,
        evolutionsOff = 0x8C,
    ),
    evoLayout = EvoLayout(EvoScheme.ROGUE),
    flags = DexFlags(
        DexFlagBlock.SAVE_BLOCK_1, seen = 0x30B4, caught = 0x3179, bytes = 197, firstBit = 0,
        bySpecies = true, seenOrCaught = true,
    ),
    nationalMagicOff = -1,
    probeCategory = "Seed",
)

/**
 * Emerald Imperium v1.3.1 (expansion 1.10): the page from gSpeciesInfo (0x104-byte entries, the
 * default field offsets; palette +0x60, footprint +0x80), 1025 national + its 214-entry Hoenn
 * order, flags in SaveBlock1. Checked field by field on CHARMANDER / PIKACHU and against its own
 * dex screen ("No0004 Charmander, Lizard Pokémon", NATIONAL 1 / 1).
 */
val POKEDEX_IMPERIUM = PokedexTables(
    entries = 0,
    frontPics = 0,
    palettes = 0,
    speciesInfo = 0x08D5D9D8L,
    speciesToNational = 0,
    abilityNames = 0x087134A0L,
    footprints = 0,
    abilityNameLength = 17,
    abilityNameStride = 0x1C,
    categorySuffix = "Pokémon",
    speciesCount = 1536,
    nationalCount = 1025,
    regionalCount = 214,
    regionName = "HOENN",
    regionalOrder = 0x08715890L,
    expansion = SpeciesInfoDex(stride = 0x104, paletteOff = 0x60, footprintOff = 0x80, levelUpOff = 0x94, teachableOff = 0x98, evolutionsOff = 0xA0),
    evoLayout = EvoLayout(EvoScheme.EXPANSION),
    flags = DexFlags(DexFlagBlock.SAVE_BLOCK_1, 0x2F58, 0x2FD9, 129),
    nationalMagicOff = 2,
    nationalMagic = 0xDA,
)

/**
 * Pokémon Wishes of Tomorrow v1.2.6 (expansion 1.15.3): the page from gSpeciesInfo (0x104-byte entries with Imperium's
 * field offsets: natDexNum +0x3C, category +0x1F, text +0x4C, smol front pic +0x58, a plain palette +0x60, footprint
 * +0x80; level-up / teachable / evolutions +0x94 / +0x98 / +0xA0, 12-byte evolution records with a condition list),
 * gAbilitiesInfo 0x08CC1EFC (0x1C-byte records, from its RHHEXP block). Its dex is expansion's own Hoenn list (214, u32
 * national numbers at 0x08CC18B0, what HoennToNationalOrder reads), then National (1025) once unlocked; flags in
 * SaveBlock1 +0x359C / +0x361D (the GF header's seen offset). Checked against its own dex screen on the headless save:
 * SEEN 1 / OWN 1 in the regional list (PIKACHU, No. 164; the party's other four aren't in it).
 */
val POKEDEX_WISHES = PokedexTables(
    entries = 0,
    frontPics = 0,
    palettes = 0,
    speciesInfo = 0x087011C0L,
    speciesToNational = 0,
    abilityNames = 0x08CC1EFCL,
    footprints = 0,
    abilityNameLength = 17,
    abilityNameStride = 0x1C,
    categorySuffix = "Pokémon",
    speciesCount = 1581,
    nationalCount = 1025,
    regionalCount = 214,
    regionName = "HOENN",
    regionalOrder = 0x08CC18B0L,
    regionalOrderStride = 4,
    expansion = SpeciesInfoDex(stride = 0x104, paletteOff = 0x60, footprintOff = 0x80, levelUpOff = 0x94, teachableOff = 0x98, evolutionsOff = 0xA0),
    evoLayout = EvoLayout(EvoScheme.EXPANSION_PARAMS, record = 12, conditionsEnd = 39),
    flags = DexFlags(DexFlagBlock.SAVE_BLOCK_1, 0x359C, 0x361D, 129),
    nationalMagicOff = 2,
    nationalMagic = 0xDA,
)

/**
 * Pokémon Dreamstone Mysteries (expansion 1.11.2): Imperium's gSpeciesInfo page (the same 0x104-byte entries and
 * offsets; gSpeciesInfo 0x087B0160, gAbilitiesInfo 0x08E65E98), its own regional order - 315 national numbers from
 * 0x08E65C20 (GOTHITA first; CHIKORITA No. 054, OSHAWOTT No. 224) - and the flags in its SaveBlock1 (+0x3494 seen,
 * +0x3515 caught; the bits of the five a new game was given). Checked against its own dex screen headless: "No054
 * Chikorita, Leaf POKéMON", 2'11", 14.1 lbs., SEEN 2 / OWN 2 (of the five, only CHIKORITA and OSHAWOTT are in its
 * list). The game titles that list HOENN (expansion's leftover word); the app names the region the game is in.
 */
val POKEDEX_DREAMSTONE = PokedexTables(
    entries = 0,
    frontPics = 0,
    palettes = 0,
    speciesInfo = 0x087B0160L,
    speciesToNational = 0,
    abilityNames = 0x08E65E98L,
    footprints = 0,
    abilityNameLength = 17,
    abilityNameStride = 0x1C,
    categorySuffix = "Pokémon",
    speciesCount = 1524,
    nationalCount = 1025,
    regionalCount = 315,
    regionName = "CORMORIA",
    regionalOrder = 0x08E65C20L,
    expansion = SpeciesInfoDex(stride = 0x104, paletteOff = 0x60, footprintOff = 0x80, levelUpOff = 0x94, teachableOff = 0x98, evolutionsOff = 0xA0),
    evoLayout = EvoLayout(EvoScheme.EXPANSION),
    flags = DexFlags(DexFlagBlock.SAVE_BLOCK_1, 0x3494, 0x3515, 129),
    nationalMagicOff = 2,
    nationalMagic = 0xDA,
)

/**
 * Pokémon Quetzal English Alpha 9 v0: vanilla's table shapes, grown. Entries are 12 bytes
 * {category*, height, weight, text*} by species (Mega forms have their own); front pics are
 * smol; gSpeciesInfo is 0x24-byte entries with u16 abilities (hidden at +0x1C); species map to
 * dex numbers up to 1034 (Gen 9, then its own three starter lines). Its dex is one National
 * list (no SaveBlock2 nationalMagic). The save keeps a 3-bit seen level per species and a
 * caught bit array per region (Hoenn / Johto / Kanto) - counted in any, as its card and
 * continue screen do; its dex screen counts the current region's alone. Checked against the
 * game's own dex: SEEN 6 / OWN 1 on the user's save, CHARMANDER "Lizard", 0.6 m, 8.5 kg.
 */
val POKEDEX_QUETZAL = PokedexTables(
    entries = 0x091BC4F0L,
    frontPics = 0x084ED8A8L,
    palettes = 0x084D8EB4L,
    speciesInfo = 0x0853E560L,
    speciesToNational = 0x08536A52L,
    abilityNames = 0x0852766DL,
    footprints = 0x091C0D2CL,
    abilityNameLength = 17,
    hiddenAbilityOff = 0x1C,
    categorySuffix = "POKéMON",
    entryStride = 12,
    entriesBySpecies = true,
    entryCategoryPtr = true,
    entryCategoryLen = 20,
    entryHeightOff = 4,
    entryDescOff = 8,
    baseStats = BaseStatsLayout(stride = 0x24, genderOff = 0x12, eggGroupsOff = 0x16, abilitiesOff = 0x18, abilityU16 = true),
    evoLayout = EvoLayout(EvoScheme.QUETZAL, table = 0x0858911CL, perMon = 11),
    levelUpLearnsets = 0x085A9EB4L,
    speciesCount = 1529,
    nationalCount = 1034,
    regionalCount = 1034,
    regionName = null,
    flags = DexFlags(
        DexFlagBlock.SAVE_BLOCK_1, seen = 0x2560, caught = 0, bytes = 150,
        seenLevels = true, caughtRegions = listOf(0x3850L, 0x304CL, 0x3254L),
    ),
    nationalMagicOff = -1,
    probeCategory = "Seed",
)

/** Quetzal Spanish Alpha 9 v0: English's shapes at its own addresses (the literal pools of
 * English's code, read at the same place in its code); the dex text is Spanish - its page reads
 * "POKéMON Semilla", "ALT. 0,7 m", "PESO 6,9 kg" (headless, the game's own POKéDEX). */
val POKEDEX_QUETZAL_ES = POKEDEX_QUETZAL.copy(
    categoryPrefix = "POKéMON",
    categorySuffix = "",
    metric = true,
    entries = 0x091BFA70L,
    frontPics = 0x084F92B8L,
    palettes = 0x084E48C4L,
    speciesInfo = 0x08549E94L,
    speciesToNational = 0x08542386L,
    abilityNames = 0x08532F97L,
    footprints = 0x091C42ACL,
    // Its own copies (English's addresses read other bytes here: EVOLVE / MOVES were wrong before 2026-10-15).
    evoLayout = EvoLayout(EvoScheme.QUETZAL, table = 0x08594A50L, perMon = 11),
    levelUpLearnsets = 0x085B57E8L,
    probeCategory = "Semilla",
)

/** Quetzal Alpha 9 v1 (English / Spanish): v0 rebuilt - every table its own address, each v0's entry for entry (pointers
 * followed: the pages, pics, palettes, footprints, evolutions and learnsets they point at are v0's bytes). */
val POKEDEX_QUETZAL_V1 = POKEDEX_QUETZAL.copy(
    entries = 0x091C5BA8L,
    frontPics = 0x085013F4L,
    palettes = 0x084ECA00L,
    speciesInfo = 0x085520A8L,
    speciesToNational = 0x0854A59AL,
    abilityNames = 0x0853B1B6L,
    footprints = 0x091CA3E4L,
    evoLayout = EvoLayout(EvoScheme.QUETZAL, table = 0x0859CC90L, perMon = 11),
    levelUpLearnsets = 0x085BDA28L,
)
val POKEDEX_QUETZAL_ES_V1 = POKEDEX_QUETZAL_ES.copy(
    entries = 0x091C9160L,
    frontPics = 0x0850CE34L,
    palettes = 0x084F8440L,
    speciesInfo = 0x0855DA18L,
    speciesToNational = 0x08555F0AL,
    abilityNames = 0x08546B1CL,
    footprints = 0x091CD99CL,
    evoLayout = EvoLayout(EvoScheme.QUETZAL, table = 0x085A8600L, perMon = 11),
    levelUpLearnsets = 0x085C9398L,
)

/** Quetzal Alpha 0.8.4: Alpha 9's shapes over its first 1520 species (no Browt / Pombon / Gecqua lines: dex numbers to
 * 1025), at its own addresses - the GF header's pics / gSpeciesInfo / ability names, the rest through English Alpha 9's
 * literal pools, each table checked against Alpha 9's bytes (entries, footprints, evolutions, learnsets). Its own POKéDEX
 * reads SEEN 466 / OWN 347 on the Spanish Alpha 9 save. */
val POKEDEX_QUETZAL_V084 = POKEDEX_QUETZAL.copy(
    entries = 0x08F71698L,
    frontPics = 0x083F7624L,
    palettes = 0x083E34C0L,
    speciesInfo = 0x0843C2CCL,
    speciesToNational = 0x084347FCL,
    abilityNames = 0x08426FCFL,
    footprints = 0x08F75E68L,
    evoLayout = EvoLayout(EvoScheme.QUETZAL, table = 0x08486560L, perMon = 11),
    levelUpLearnsets = 0x084A6FE0L,
    speciesCount = 1520,
    nationalCount = 1025,
    regionalCount = 1025,
    // Alpha 9's seen levels; its per-region caught arrays aren't there (a level of 2 or more is caught).
    flags = POKEDEX_QUETZAL.flags.copy(caughtRegions = emptyList(), caughtFromLevel = true),
)

/** Quetzal Spanish Alpha 0.8.4: English 0.8.4's shapes at its own addresses, Alpha 9 Spanish's wording. */
val POKEDEX_QUETZAL_V084_ES = POKEDEX_QUETZAL_V084.copy(
    categoryPrefix = "POKéMON",
    categorySuffix = "",
    metric = true,
    entries = 0x08F71370L,
    frontPics = 0x083FF7F4L,
    palettes = 0x083EB690L,
    speciesInfo = 0x08444368L,
    speciesToNational = 0x0843C898L,
    abilityNames = 0x0842F05EL,
    footprints = 0x08F75B40L,
    evoLayout = EvoLayout(EvoScheme.QUETZAL, table = 0x0848E5FCL, perMon = 11),
    levelUpLearnsets = 0x084AF07CL,
    probeCategory = "Semilla",
)

/** Moemon Quetzal English / Spanish Alpha 0.8.4 Hotfix: their base builds' tables, moved (their own pictures). */
val POKEDEX_QUETZAL_MOEMON_V084 = POKEDEX_QUETZAL_V084.copy(
    entries = 0x08F7167CL,
    frontPics = 0x083F7724L,
    palettes = 0x083E35C0L,
    speciesInfo = 0x0843C3CCL,
    speciesToNational = 0x084348FCL,
    abilityNames = 0x084270CFL,
    footprints = 0x08F75E4CL,
    evoLayout = EvoLayout(EvoScheme.QUETZAL, table = 0x08486660L, perMon = 11),
    levelUpLearnsets = 0x084A70E0L,
)

val POKEDEX_QUETZAL_MOEMON_V084_ES = POKEDEX_QUETZAL_V084_ES.copy(
    entries = 0x08F71354L,
    frontPics = 0x083FF8F4L,
    palettes = 0x083EB790L,
    speciesInfo = 0x08444468L,
    speciesToNational = 0x0843C998L,
    abilityNames = 0x0842F15EL,
    footprints = 0x08F75B24L,
    evoLayout = EvoLayout(EvoScheme.QUETZAL, table = 0x0848E6FCL, perMon = 11),
    levelUpLearnsets = 0x084AF17CL,
)

/**
 * Pokémon Orange Islands: retail FireRed rev 0's tables (each 0x70 before rev 1's, the entries
 * 0x60), edited in place - its CRYSTAL ONIX (species 409) and rebalanced starters among them.
 */
val POKEDEX_ORANGE_ISLANDS = POKEDEX_FIRERED_REV1.copy(
    entries = 0x0844E850L,
    frontPics = 0x082350ACL,
    palettes = 0x0823730CL,
    speciesInfo = 0x08254784L,
    speciesToNational = 0x08251FEEL,
    abilityNames = 0x0824FC40L,
    footprints = 0x0843FAB0L,
    evolutions = 0x08259754L,
)

/**
 * Pokémon Ultra Violet: retail FireRed rev 0's tables at rev 0's addresses (its literal pools
 * still name them), the dex text edited in place; TM / HM learnsets and moves rev 0's too.
 */
val POKEDEX_ULTRA_VIOLET = POKEDEX_ORANGE_ISLANDS.copy(
    tmhmLearnsets = 0x08252BC8L,
    tmhmMoves = 0x0845A5A4L,
)

/** FireRed Rocket Edition: rev 0's tables but its ability names, repointed to 0x08950000; its mixed-case text. */
val POKEDEX_ROCKET_EDITION = POKEDEX_ULTRA_VIOLET.copy(abilityNames = 0x08950000L, categorySuffix = "Pokémon")

/** Pokémon Ghost Grey: Rocket Edition's layout - rev 0's tables rewritten in place (its ghosts' entries), its
 * ability names at 0x08950000, its mixed-case text. */
val POKEDEX_GHOST_GREY = POKEDEX_ROCKET_EDITION

/**
 * Pokémon Sword and Shield Ultimate Plus v1.2.1.2 (CFRU): every table found through the
 * literal-pool word FireRed 1.0 loads it from (entries 0x09A04D08, front pics 0x099EDB5C,
 * palettes 0x09A02128, base stats 0x099AFBEC, species -> national 0x09A11274, ability names
 * 0x094A9324 - 13 bytes, vanilla's length - and the evolutions at 0x099C2C06, CFRU's 16 rows a
 * species: BULBASAUR's LEVEL 16 -> IVYSAUR at +0x80). SCORBUNNY is species 1105, No. 813.
 * The flags are Unbound's CFRU ones: the save's seen bits are GROOKEY / SCORBUNNY / WOOLOO /
 * ZACIAN, caught SCORBUNNY. Its POKéDEX (opened headless with FLAG_SYS_POKEDEX_GET 0x829 set)
 * lists one dex in national order; three Gen 9 species carry No. 1018-1019, which have no entries.
 */
val POKEDEX_SWORD_SHIELD = PokedexTables(
    entries = 0x09A04D08L,
    frontPics = 0x099EDB5CL,
    palettes = 0x09A02128L,
    speciesInfo = 0x099AFBECL,
    speciesToNational = 0x09A11274L,
    abilityNames = 0x094A9324L,
    footprints = 0,
    hiddenAbilityOff = 0x1A,
    categorySuffix = "Pokémon",
    speciesCount = 1268,
    nationalCount = 898,
    // One dex, in national order from No001 (its table of contents has Numerical Mode only).
    regionalCount = 898,
    regionName = null,
    flags = CFRU_DEX_FLAGS,
    nationalMagicOff = -1,
    evoLayout = EvoLayout(EvoScheme.CFRU, table = 0x099C2C06L, perMon = 16),
)

/**
 * Pokémon GS Chronicles v2.7.6 (CFRU): every table through the literal-pool word FireRed 1.0 loads
 * it from (entries 0x097EE8CC, front pics 0x097D650C, palettes 0x097EBC24, base stats 0x09798E18,
 * species -> national 0x097FB248, the 17-byte ability names 0x09CEA7C8, the evolutions 0x097AC008 -
 * CFRU's 16 rows a species). Its own national numbers run to 910 (No. 906 is a Gen 9 species). The
 * game's POKéDEX (headless) lists one Johto order of 501 species ids, at 0x097F8556 (No. 1
 * CHIKORITA, 4 CYNDAQUIL, 7 TOTODILE; MARSHADOW last), read like Unbound's Borrius list, so the tab
 * opens on JOHTO like the game; NATIONAL only once FireRed's own unlock byte (0xB9) is set, which a
 * new game doesn't have. The flags are CFRU's: the new game's seen = caught = its three starters, as
 * the game counts them.
 */
val POKEDEX_GS_CHRONICLES = PokedexTables(
    entries = 0x097EE8CCL,
    frontPics = 0x097D650CL,
    palettes = 0x097EBC24L,
    speciesInfo = 0x09798E18L,
    speciesToNational = 0x097FB248L,
    abilityNames = 0x09CEA7C8L,
    footprints = 0,
    abilityNameLength = 17,
    hiddenAbilityOff = 0x1A,
    categorySuffix = "Pokémon",
    speciesCount = 1313,
    nationalCount = 910,
    regionalCount = 501,
    regionName = "JOHTO",
    regionalOrder = 0x097F8556L,
    regionalOrderIsSpecies = true,
    flags = CFRU_DEX_FLAGS,
    probeCategory = "Seed",
    evoLayout = EvoLayout(EvoScheme.CFRU, table = 0x097AC008L, perMon = 16),
)

/**
 * Pokémon Elite Redux 2.65.4.1b: one National dex of 1222 (its own fakemon past Gen 9: LUCINEON is No. 1072), its forms and
 * Redux forms sharing their base species' number (the page shows the first). Every table from its ROM: gPokedexEntries
 * 0x18 bytes by dex number (a POINTER to the category, height / weight +4 / +6, the text's pointer +8), vanilla's front
 * pictures and palettes (LZ77), gBaseStats 0x2C bytes (genderRatio +0x12, egg groups +0x16, three u16 abilities from +0x18 -
 * its three innates after them aren't shown), species -> dex number by species from 0, the ability names behind a pointer in
 * 196-byte records. The flags are SaveBlock1's, a bit per dex number (seen +0x2E54, caught +0x2F10, 188 bytes): the save's
 * 29 caught = its title screen's POKéDEX 29.
 */
val POKEDEX_ELITE_REDUX_V2_65_4_1B = PokedexTables(
    entries = 0x08B3EB60L,
    frontPics = 0x089625A8L,
    palettes = 0x08581F34L,
    speciesInfo = 0x08C033E4L,
    speciesToNational = 0x08C004AEL,
    abilityNames = 0x08FD95E8L,
    footprints = 0,
    abilityNameLength = 20,
    abilityNameStride = 196,
    abilityNamePtr = true,
    hiddenAbilityOff = 0x1C,
    categorySuffix = "Pokémon",
    entryStride = 0x18,
    entryCategoryPtr = true,
    entryCategoryLen = 20,
    entryHeightOff = 4,
    entryDescOff = 8,
    speciesCount = 2902,
    nationalCount = 1222,
    regionName = null,
    flags = DexFlags(DexFlagBlock.SAVE_BLOCK_1, 0x2E54, 0x2F10, 188),
    nationalMagicOff = -1,
    probeCategory = "Seed",
    baseStats = BaseStatsLayout(stride = 0x2C, genderOff = 0x12, eggGroupsOff = 0x16, abilitiesOff = 0x18, abilityU16 = true),
)

/** 2.65.3b: the same shapes at its own addresses. */
val POKEDEX_ELITE_REDUX_V2_65_3B = POKEDEX_ELITE_REDUX_V2_65_4_1B.copy(
    entries = 0x08B31300L,
    frontPics = 0x08955B38L,
    palettes = 0x0857D07CL,
    speciesInfo = 0x08BF5B84L,
    speciesToNational = 0x08BF2C4EL,
    abilityNames = 0x08FC6DE4L,
)

/**
 * Pokémon Recharged Yellow: FireRed's dex - Kanto (1-151) + National, FireRed's species ids -
 * with its own entry shape: categoryName[14] ("Seed"), height +0x0E, weight +0x10, the text
 * +0x14 (no second page), 0x24 bytes. Found through BULBASAUR's text; the front pics / palettes /
 * gBaseStats / ability names from the GF ROM header; sSpeciesToNationalPokedexNum, footprints,
 * sTMHMLearnsets and sTMHMMoves are FireRed's bytes, moved. Its evolutions are FireRed's plus a
 * LINKING CORD for every trade evolution ([EvoScheme.RECHARGED_YELLOW]). The flags are FireRed's
 * struct Pokedex in SaveBlock2 with no anti-cheat copies (its GF header points both copies at one
 * unused spot): owned 31 / seen 58 on the user's save, what its POKéDEX screen shows.
 */
private val POKEDEX_RECHARGED_YELLOW = PokedexTables(
    entries = 0, frontPics = 0, palettes = 0, speciesInfo = 0, speciesToNational = 0, abilityNames = 0, footprints = 0,
    entryCategoryLen = 14,
    entryHeightOff = 0x0E,
    entryDescOff = 0x14,
    categorySuffix = "Pokémon",
    probeCategory = "Seed",
    flags = DexFlags(DexFlagBlock.SAVE_BLOCK_2, 0x5C, 0x28, 52),
)

private fun rechargedYellowDex(
    entries: Long, frontPics: Long, palettes: Long, speciesInfo: Long, speciesToNational: Long, abilityNames: Long,
    footprints: Long, evolutions: Long, tmhmLearnsets: Long, tmhmMoves: Long,
) = POKEDEX_RECHARGED_YELLOW.copy(
    entries = entries, frontPics = frontPics, palettes = palettes, speciesInfo = speciesInfo,
    speciesToNational = speciesToNational, abilityNames = abilityNames, footprints = footprints,
    evoLayout = EvoLayout(EvoScheme.RECHARGED_YELLOW, table = evolutions), tmhmLearnsets = tmhmLearnsets, tmhmMoves = tmhmMoves,
)

val POKEDEX_RECHARGED_YELLOW_V1_8_0 = rechargedYellowDex(
    0x088540B0L, 0x086543F4L, 0x0864D848L, 0x08860750L, 0x0885CC40L, 0x085F7E9CL,
    0x08857820L, 0x088658C0L, 0x0885EC18L, 0x08847F84L,
)
val POKEDEX_RECHARGED_YELLOW_V1_9_0 = rechargedYellowDex(
    0x08853B6CL, 0x08651518L, 0x0864A95CL, 0x0886020CL, 0x0885C6FCL, 0x085F5060L,
    0x088572DCL, 0x0886537CL, 0x0885E6D4L, 0x08847A40L,
)
val POKEDEX_RECHARGED_YELLOW_V1_9_4 = rechargedYellowDex(
    0x08933B70L, 0x08731494L, 0x0872A8D8L, 0x08940210L, 0x0893C700L, 0x085F779CL,
    0x089372E0L, 0x08945380L, 0x0893E6D8L, 0x08927A44L,
)
val POKEDEX_RECHARGED_YELLOW_V1_9_7 = rechargedYellowDex(
    0x0895C980L, 0x08757534L, 0x08750978L, 0x08969020L, 0x08965510L, 0x0861D454L,
    0x089600F0L, 0x0896E190L, 0x089674E8L, 0x08950854L,
)

/**
 * Pokémon Recharged Emerald: Emerald's dex (Hoenn 202 + National, Emerald's species ids and entry shape) at its
 * own addresses - the front pics / palettes / gBaseStats / ability names from the GF ROM header, the entries by
 * BULBASAUR's ("Seed", 0.7 m / 6.9 kg: its text is mixed case), sSpeciesToNationalPokedexNum, sHoennToNationalOrder,
 * the footprints, sTMHMLearnsets and sTMHMMoves by retail Emerald's bytes (Rebalanced rewrote its TM learnsets:
 * v2.2.8's place, moved like the rest). Its evolutions are Recharged Yellow's scheme (a LINKING CORD for every
 * trade evolution, [EvoScheme.RECHARGED_YELLOW]). The flags are Emerald's struct Pokedex in SaveBlock2 with no
 * anti-cheat copies (its GF header points both at SaveBlock2's own seen array); Rebalanced's sit 4 bytes later.
 */
private fun rechargedEmeraldDex(
    entries: Long, frontPics: Long, palettes: Long, speciesInfo: Long, speciesToNational: Long, abilityNames: Long,
    footprints: Long, regionalOrder: Long, evolutions: Long, tmhmLearnsets: Long, tmhmMoves: Long, lead: Long = 0,
) = POKEDEX_EMERALD.copy(
    entries = entries, frontPics = frontPics, palettes = palettes, speciesInfo = speciesInfo,
    speciesToNational = speciesToNational, abilityNames = abilityNames, footprints = footprints, regionalOrder = regionalOrder,
    evolutions = 0, evoLayout = EvoLayout(EvoScheme.RECHARGED_YELLOW, table = evolutions),
    tmhmLearnsets = tmhmLearnsets, tmhmMoves = tmhmMoves,
    categorySuffix = "Pokémon", probeCategory = "Seed",
    flags = DexFlags(DexFlagBlock.SAVE_BLOCK_2, 0x5C + lead, 0x28 + lead, 52), sb2DexOff = 0x18 + lead,
)

val POKEDEX_RECHARGED_EMERALD_V2_2_5 = rechargedEmeraldDex(
    0x088C4CB8L, 0x086F9628L, 0x086F2A64L, 0x088CFC50L, 0x088CC140L, 0x086AD384L,
    0x088C7D98L, 0x088CC478L, 0x088D4D38L, 0x088CE118L, 0x088C28D4L,
)
val POKEDEX_RECHARGED_EMERALD_V2_2_8 = rechargedEmeraldDex(
    0x088C6B50L, 0x086FB4A8L, 0x086F48E4L, 0x088D1AE8L, 0x088CDFD8L, 0x086AF204L,
    0x088C9C30L, 0x088CE310L, 0x088D6BD0L, 0x088CFFB0L, 0x088C476CL,
)
val POKEDEX_RECHARGED_EMERALD_REBALANCED_V2_2_8 = rechargedEmeraldDex(
    0x088C72B4L, 0x086FBBB8L, 0x086F4FF4L, 0x088D224CL, 0x088CE73CL, 0x086AF914L,
    0x088CA394L, 0x088CEA74L, 0x088D7354L, 0x088D0714L, 0x088C4ED0L, lead = 4,
)

/**
 * Super Mariomon v1.5.1-Anniversary: vanilla-shaped tables (an old pokeemerald-expansion's), the
 * Mario species from 906 numbered 1..155 (sSpeciesToNationalPokedexNum: PIRANHA P. 906 = No.1,
 * BOB-OMB 912 = No.7). Its TATTLEDEX lists the WORLD dex (sHoennToNationalOrder, the national
 * numbers in order but No.153 BOWSER): 152 entries in v1.5 (No.152 TIPTRON), 151 in v1.3 - the
 * game's own list, all flags poked on headlessly. gPokedexEntries are 0x24 bytes by dex number
 * (category[13] "Explosive", height +0xE 6 = 2'00", weight +0x10 104 = 22.9 lbs., text +0x14 -
 * BOB-OMB's page as the game prints it, "CAPTURE" after the category); gBaseStats 0x24-byte
 * entries (gender +0x12, egg groups +0x16, three u16 abilities +0x18, the third hidden); front
 * pics two 64x64 frames. Flags: SaveBlock1 dexSeen / dexCaught, 20 bytes each (the header's
 * seen offsets point there): the save's SEEN 2 / OWN 1 (No.1 seen, No.7 caught). Evolutions
 * 10 {method, param, target, pad} rows per species, expansion's numbering (BOB-OMB: LEVEL 14 ->
 * CHUCKYA); level-up learnsets {u16 move, u16 level} (BOB-OMB: Tackle 1, Charge 3, Self-Destruct 5,
 * Ember 6 - its moves on the save).
 */
val POKEDEX_MARIOMON_V1_5_1 = PokedexTables(
    entries = 0x087E9C28L,
    frontPics = 0x08406E30L,
    palettes = 0x083F4078L,
    speciesInfo = 0x08439060L,
    speciesToNational = 0x0843668EL,
    abilityNames = 0x0842CB86L,
    footprints = 0,
    abilityNameLength = 17,
    hiddenAbilityOff = 0x1C,
    entryStride = 0x24,
    entryCategoryLen = 14,
    entryHeightOff = 0x0E,
    entryDescOff = 0x14,
    baseStats = BaseStatsLayout(stride = 0x24, genderOff = 0x12, eggGroupsOff = 0x16, abilitiesOff = 0x18, abilityU16 = true),
    categorySuffix = "CAPTURE",
    probeCategory = "Flytrap",
    probeHeight = 10,
    probeWeight = 70,
    probeSpecies = 906,
    evoLayout = EvoLayout(EvoScheme.EXPANSION, table = 0x0845877CL, perMon = 10),
    levelUpLearnsets = 0x08473D9CL,
    speciesCount = 1402,
    nationalCount = 155,
    regionalCount = 152,
    regionName = "WORLD",
    regionalOrder = 0x08437180L,
    flags = DexFlags(DexFlagBlock.SAVE_BLOCK_1, seen = 0x3790, caught = 0x37A4, bytes = 20),
    nationalMagicOff = 2,
    nationalMagic = 0xDA,
)

val POKEDEX_MARIOMON_V1_5_2 = POKEDEX_MARIOMON_V1_5_1.copy(
    entries = 0x087EA0DCL, frontPics = 0x084072C0L, palettes = 0x083F4508L, speciesInfo = 0x084394F0L,
    speciesToNational = 0x08436B1EL, abilityNames = 0x0842D016L, regionalOrder = 0x08437610L,
    evoLayout = EvoLayout(EvoScheme.EXPANSION, table = 0x08458C10L, perMon = 10), levelUpLearnsets = 0x08474230L,
)

/** v1.3 / v1.2.x: one species fewer (no TIPTRON: NUM_SPECIES 1401), 153 dex numbers, 151 in the WORLD list. */
val POKEDEX_MARIOMON_V1_3 = POKEDEX_MARIOMON_V1_5_1.copy(
    entries = 0x087CCE9CL, frontPics = 0x083F5B88L, palettes = 0x083E2E20L, speciesInfo = 0x08427A0CL,
    speciesToNational = 0x08425090L, abilityNames = 0x0841B649L, regionalOrder = 0x08425B80L,
    evoLayout = EvoLayout(EvoScheme.EXPANSION, table = 0x08446640L, perMon = 10), levelUpLearnsets = 0x08461C10L,
    speciesCount = 1401, nationalCount = 153, regionalCount = 151,
)

val POKEDEX_MARIOMON_V1_2_2 = POKEDEX_MARIOMON_V1_3.copy(
    entries = 0x087CCEB8L, frontPics = 0x083F5BC8L, palettes = 0x083E2E60L, speciesInfo = 0x08427A4CL,
    speciesToNational = 0x084250D0L, abilityNames = 0x0841B689L, regionalOrder = 0x08425BC0L,
    evoLayout = EvoLayout(EvoScheme.EXPANSION, table = 0x08446680L, perMon = 10), levelUpLearnsets = 0x08461C50L,
)

val POKEDEX_MARIOMON_V1_2_1 = POKEDEX_MARIOMON_V1_3.copy(
    entries = 0x087CCED4L, frontPics = 0x083F5BE8L, palettes = 0x083E2E80L, speciesInfo = 0x08427A6CL,
    speciesToNational = 0x084250F0L, abilityNames = 0x0841B6A9L, regionalOrder = 0x08425BE0L,
    evoLayout = EvoLayout(EvoScheme.EXPANSION, table = 0x084466A0L, perMon = 10), levelUpLearnsets = 0x08461C70L,
)

/** Pokémon SoulGold v1.1.4: national 1-1025, 323 of them disabled (no species),
 * so its JOHTO dex is the other 702 in national order - a u32 list the game's own
 * national -> Johto lookup (0x081F0D84) scans: PIKACHU is its 023, CHIKORITA 133,
 * CYNDAQUIL 136. The page is in its 0x118-byte gSpeciesInfo (smol front pics, raw
 * palettes, no footprints); ability names 0x20 apart. The flags (dexSeen /
 * dexCaught in SaveBlock1, 0x81 apart) were matched to the game's own POKéDEX
 * (opened via FLAG_SYS_POKEDEX_GET 0x98D headlessly): the save's Seen 6 / Own 1,
 * then Seen 8 / Own 2 after poking PIKACHU seen and CHIKORITA caught. The game
 * never sets an Emerald-style nationalMagic (its "National" label is an
 * all-caught check), so it stays at 0 and the tab opens on Johto, like the game. */
val POKEDEX_SOULGOLD = PokedexTables(
    entries = 0,
    frontPics = 0,
    palettes = 0,
    speciesInfo = 0x087D44A8L,
    speciesToNational = 0,
    abilityNames = 0x08F04C28L,
    footprints = 0,
    abilityNameLength = 17,
    abilityNameStride = 0x20,
    categorySuffix = "Pokémon",
    speciesCount = 1578,
    nationalCount = 1025,
    regionalCount = 702,
    regionName = "JOHTO",
    regionalOrder = 0x08F03DD8L,
    regionalOrderStride = 4,
    expansion = SpeciesInfoDex(
        stride = 0x118, abilitiesOff = 0x18, categoryOff = 0x26, natDexOff = 0x42,
        descriptionOff = 0x50, frontPicOff = 0x5C, paletteOff = 0x64,
        levelUpOff = 0xA8, teachableOff = 0xAC, evolutionsOff = 0xB4,
    ),
    evoLayout = EvoLayout(EvoScheme.EXPANSION_PARAMS, record = 12, conditionsEnd = 39),
    flags = DexFlags(DexFlagBlock.SAVE_BLOCK_1, 0x31F8, 0x3279, 129),
    nationalMagicOff = 2,
    nationalMagic = 0xDA,
)

/** SoulGold v1.2: v1.1.4's dex moved (gSpeciesInfo 0x120 bytes per entry, the
 * same offsets inside), the Johto list and ability names byte for byte the same. */
val POKEDEX_SOULGOLD_V1_2 = POKEDEX_SOULGOLD.copy(
    speciesInfo = 0x087D5CD0L,
    abilityNames = 0x08F0FCC8L,
    regionalOrder = 0x08F0EE78L,
    expansion = POKEDEX_SOULGOLD.expansion!!.copy(stride = 0x120),
)

/** The second SoulGold v1.2 build (sha1 5d6a0362...): the same tables 0x98 / 0x9C earlier. */
val POKEDEX_SOULGOLD_V1_2B = POKEDEX_SOULGOLD_V1_2.copy(
    speciesInfo = 0x087D5C38L,
    abilityNames = 0x08F0FC2CL,
    regionalOrder = 0x08F0EDDCL,
)

/** The third SoulGold v1.2 build (sha1 2be7c99a...): the same tables 0x18C / 0x190 later than the first. */
val POKEDEX_SOULGOLD_V1_2C = POKEDEX_SOULGOLD_V1_2.copy(
    speciesInfo = 0x087D5DC4L,
    abilityNames = 0x08F0FDBCL,
    regionalOrder = 0x08F0EF6CL,
)

/** SoulGold v1.2.1 (both builds): the same tables again, 0x7BA0 (gSpeciesInfo) / 0xB61C later than the third v1.2's. */
val POKEDEX_SOULGOLD_V1_2_1 = POKEDEX_SOULGOLD_V1_2.copy(
    speciesInfo = 0x087DD964L,
    abilityNames = 0x08F1B3D8L,
    regionalOrder = 0x08F1A588L,
)

/**
 * Whether the running ROM really has these tables where [t] says: entry 1 is
 * BULBASAUR's (SEED, 7 dm, 69 hg), species 1 is dex number 1 and its front
 * pic is tagged 1. Unrecognised hacks of the same size fall back to the
 * retail configs, and must not get a dex built from someone else's tables.
 */
fun pokedexMatchesRom(c: MemoryReader, t: PokedexTables): Boolean = runCatching {
    // Through the weight: 14-byte categories (R.O.W.E., Recharged Yellow) put it at +0x10.
    val e = c.readCoreMemory(t.entries + t.entryStride, maxOf(0x10, t.entryHeightOff + 4))
    val category = if (t.entryCategoryPtr) Gen3Text.decode(c.readCoreMemory(u32le(e, 0), t.entryCategoryLen + 1)) else Gen3Text.decode(e, 0, t.entryCategoryLen)
    category.equals(t.probeCategory, ignoreCase = true) &&
        u16le(e, t.entryHeightOff) == t.probeHeight && u16le(e, t.entryHeightOff + 2) == t.probeWeight &&
        u16le(c.readCoreMemory(t.speciesToNational + 2L * (t.probeSpecies - 1), 2), 0) == 1 &&
        u16le(c.readCoreMemory(t.frontPics + 8L * t.probeSpecies + 6, 2), 0) == t.probeSpecies
}.getOrDefault(false)

/** The save's dex progress, by national dex number (1-based). */
data class PokedexState(
    val tables: PokedexTables,
    val seen: Set<Int>,
    val caught: Set<Int>,
    /** SaveBlock2's nationalMagic: the National Dex has been unlocked. */
    val national: Boolean,
)

/**
 * The seen / caught flags ([PokedexTables.flags]), checked the way the game's
 * own GetSetPokedexFlag does: where there are anti-cheat copies of `seen`
 * (FireRed / Emerald), a bit only counts if they agree with it, and caught
 * needs seen too. Null if the save blocks aren't loaded yet (title screen).
 */
fun readPokedexState(c: MemoryReader, cfg: NativeConfig, t: PokedexTables): PokedexState? {
    val sb1 = saveBlock1(c, cfg)
    val sb2 = saveBlock2(c, cfg)
    if (sb1 !in 0x02000000L until 0x04000000L || sb2 !in 0x02000000L until 0x04000000L) return null
    val f = t.flags
    val base = when (f.block) {
        DexFlagBlock.SAVE_BLOCK_1 -> sb1
        DexFlagBlock.SAVE_BLOCK_2 -> sb2
        DexFlagBlock.FIXED -> 0L
    }
    val seenBits = c.readCoreMemory(base + f.seen, if (f.seenLevels) f.bytes * 4 else f.bytes)
    val caughtBits = if (f.caughtFromLevel) ByteArray(0)
        else if (f.caughtRegions.isEmpty()) c.readCoreMemory(base + f.caught, f.bytes)
        else f.caughtRegions.map { c.readCoreMemory(base + it, f.bytes) }.reduce { a, b -> ByteArray(a.size) { (a[it].toInt() or b[it].toInt()).toByte() } }
    val copies = f.seenCopies.map { c.readCoreMemory(sb1 + it, f.bytes) }
    fun bit(b: ByteArray, n: Int): Boolean {
        val i = n - f.firstBit
        if (f.seenLevels && (b === seenBits || (f.caughtFromLevel && b === caughtBits))) {
            // 8 three-bit levels per u32 word; any level but 0 is seen (2 and up caught, where that's how it's kept).
            val w = i / 8
            val least = if (b === caughtBits) 2L else 1L
            return i >= 0 && w * 4 + 3 < seenBits.size && ((u32le(seenBits, w * 4) ushr (3 * (i % 8))) and 7L) >= least
        }
        return i >= 0 && i / 8 < b.size && (b[i / 8].toInt() shr (i % 8)) and 1 != 0
    }
    val seen = mutableSetOf<Int>()
    val caught = mutableSetOf<Int>()
    for (n in 1..t.nationalCount) {
        val i = if (f.bySpecies) PokedexSource.speciesFor(t, n).takeIf { it > 0 } ?: continue else n
        val c = bit(caughtBits, i)
        val s = bit(seenBits, i) || (f.seenOrCaught && c)
        if (copies.isEmpty()) {
            if (s) seen.add(n)
            if (c) caught.add(n)
        } else if (s && copies.all { bit(it, i) }) {
            seen.add(n)
            if (c) caught.add(n)
        }
    }
    val national = t.nationalMagicOff < 0 ||
        (c.readCoreMemory(sb2 + t.sb2DexOff + t.nationalMagicOff, 1)[0].toInt() and 0xFF) == t.nationalMagic
    return PokedexState(t, seen, caught, national)
}

/** One dex page, straight from the ROM. */
data class DexEntry(
    val national: Int,
    val species: Int,
    val category: String,
    val heightDm: Int,
    val weightHg: Int,
    val description: String,
    val type1: Int,
    val type2: Int,
    /** HP, ATTACK, DEFENSE, SP. ATK, SP. DEF, SPEED. */
    val baseStats: List<Int>,
    val abilities: List<String>,
    val hiddenAbility: String? = null,
    val catchRate: Int,
    /** 0 = always male, 254 = always female, 255 = genderless. */
    val genderRatio: Int,
    val eggGroups: List<String>,
    /** Gen 1 keeps feet / inches and pounds x 10 exactly: shown as stored, not via [heightDm] / [weightHg]. */
    val heightIn: Int? = null,
    val weightLbs10: Int? = null,
) {
    val types: List<String> get() = monTypes(type1, type2)
}

/**
 * Reads [DexEntry]s and sprites out of the ROM on demand and caches them -
 * like [DecompIconSource], called from Compose's IO dispatcher rather than the
 * emulator thread (ROM data never changes, so a read racing a frame is harmless).
 * [reader] is swappable so tests and previews can read a ROM file instead.
 */
object PokedexSource {
    @Volatile
    var reader: MemoryReader = InProcessReader

    private val entries = ConcurrentHashMap<Int, DexEntry>()
    private val sprites = ConcurrentHashMap<Int, Bitmap>()
    private val footprints = ConcurrentHashMap<Int, Bitmap>()
    private val baseStats = ConcurrentHashMap<Int, IntArray>()
    private val growthRates = ConcurrentHashMap<Int, Int>()
    @Volatile private var nationalToSpecies: IntArray? = null
    @Volatile private var speciesToNational: IntArray? = null
    @Volatile private var regional: IntArray? = null
    @Volatile private var cachedFor: PokedexTables? = null

    private fun sync(t: PokedexTables) {
        if (cachedFor == t) return
        synchronized(this) {
            if (cachedFor == t) return
            entries.clear(); sprites.clear(); footprints.clear(); baseStats.clear(); growthRates.clear(); nationalToSpecies = null; speciesToNational = null; regional = null
            cachedFor = t
        }
    }

    /**
     * [species]' base stats in the struct's order (HP, ATTACK, DEFENSE, SPEED, SP. ATK, SP. DEF) from the ROM's
     * gBaseStats - the BOXES tab's stats, which a box record doesn't keep. Null = no table / unreadable.
     */
    fun baseStats(t: PokedexTables, species: Int): IntArray? {
        if (species <= 0 || t.speciesInfo == 0L || t.cartDex) return null
        sync(t)
        baseStats[species]?.let { return it }
        val bs = t.baseStats
        val stride = t.expansion?.stride ?: bs.stride
        return runCatching {
            val info = rd(t.speciesInfo + species.toLong() * stride, 12)
            IntArray(6) { if (t.expansion == null && bs.statsU16) u16le(info, it * 2) else info[it].toInt() and 0xFF }
        }.getOrNull()?.also { baseStats[species] = it }
    }

    /**
     * [species]' growth rate (GROWTH_*, gExperienceTables' row) from the same table: the byte before the egg groups in
     * vanilla's gBaseStats (+0x13) and in expansion's gSpeciesInfo (+0x15) alike. A box record's level comes from it.
     */
    fun growthRate(t: PokedexTables, species: Int): Int? {
        if (species <= 0 || t.speciesInfo == 0L || t.cartDex || (t.expansion == null && t.baseStats.statsU16)) return null
        sync(t)
        growthRates[species]?.let { return it }
        val stride = t.expansion?.stride ?: t.baseStats.stride
        val off = (t.expansion?.eggGroupsOff ?: t.baseStats.eggGroupsOff) - 1
        return runCatching { rd(t.speciesInfo + species.toLong() * stride + off, 1)[0].toInt() and 0xFF }
            .getOrNull()?.also { growthRates[species] = it }
    }

    /**
     * A new ROM is running: forget what was read from the last one. The caches here and in
     * [DexDetails] / [GuideRomSource] are keyed by their tables, and two ROMs can share them
     * (Glazed runs on Emerald's PokedexTables) - Emerald then Glazed in one session kept Emerald's.
     */
    fun romChanged() {
        synchronized(this) { cachedFor = null }
        DexDetails.romChanged()
        GuideRomSource.romChanged()
        EvolutionSource.romChanged()
    }

    /** Already-loaded data only (no ROM read) - composables start from these,
     * so a page seen before draws at once instead of after a load. */
    fun cachedEntry(t: PokedexTables, national: Int): DexEntry? = if (cachedFor == t) entries[national] else null
    fun cachedFrontSprite(t: PokedexTables, species: Int): Bitmap? = if (cachedFor == t) sprites[species] else null
    fun cachedFootprint(t: PokedexTables, species: Int): Bitmap? = if (cachedFor == t) footprints[species] else null
    fun cachedSpeciesMap(t: PokedexTables): IntArray? = when {
        // Gen 1's and 2's tables are by Dex number - but Prism's, by its own species ids in its own dex order.
        t.cartDex -> IntArray(t.nationalCount + 1) { n -> t.gen2?.speciesOf(n) ?: n }
        cachedFor == t -> nationalToSpecies
        else -> null
    }
    fun cachedRegionalOrder(t: PokedexTables): IntArray? = when {
        // A DS game's order comes with its pages, from the cart.
        t.gen4 != null -> Gen4Dex.cachedRegionalOrder(t.gen4)
        t.regionalOrder == 0L || t.cartDex -> IntArray(t.regionalCount) { it + 1 }
        cachedFor == t -> regional
        else -> null
    }

    /** National numbers in regional-dex order ([PokedexTables.regionalCount] of them). */
    fun regionalOrder(t: PokedexTables): IntArray? {
        cachedRegionalOrder(t)?.let { return it }
        if (t.gen4 != null) return Gen4Dex.regionalOrder(t.gen4)
        sync(t)
        return runCatching {
            val w = t.regionalOrderStride
            val raw = rd(t.regionalOrder, t.regionalCount * w)
            IntArray(t.regionalCount) { if (w == 4) u32le(raw, it * 4).toInt() else u16le(raw, it * 2) }.let { ids ->
                if (!t.regionalOrderIsSpecies) ids
                else IntArray(ids.size) { i -> ids[i].takeIf { it in 1 until t.speciesCount }?.let { nationalOf(t, it) } ?: 0 }
            }
        }.getOrNull()?.also { regional = it }
    }

    /** Species id for a national dex number (the first species that maps to it), or 0. */
    fun speciesFor(t: PokedexTables, national: Int): Int {
        // Its tables are by Dex number (Prism: its dex's order).
        if (t.cartDex) return if (national !in 1..t.nationalCount) 0 else t.gen2?.speciesOf(national) ?: national
        sync(t)
        val map = nationalToSpecies ?: runCatching {
            if (t.nationalToSpecies != 0L) {
                val raw = rd(t.nationalToSpecies, (t.nationalCount + 1) * 2)
                IntArray(t.nationalCount + 1) { n -> if (n == 0) 0 else u16le(raw, n * 2).takeIf { it < t.speciesCount } ?: 0 }
            } else {
                val nat = speciesNationals(t)
                IntArray(t.nationalCount + 1).also { out ->
                    for (s in 1 until t.speciesCount) {
                        val n = nat[s]
                        if (n in 1..t.nationalCount && out[n] == 0) out[n] = s
                    }
                }
            }
        }.getOrNull()?.also { nationalToSpecies = it } ?: return 0
        return map.getOrElse(national) { 0 }
    }

    /** National dex number of a species id (Hoenn species aren't in national order), or 0. */
    fun nationalOf(t: PokedexTables, species: Int): Int {
        if (t.cartDex) return t.gen2?.dexOf(species) ?: if (species in 1..t.nationalCount) species else 0
        sync(t)
        val map = speciesToNational ?: runCatching { speciesNationals(t) }.getOrNull()?.also { speciesToNational = it } ?: return 0
        return map.getOrElse(species) { 0 }
    }

    /** Every species' dex number, by species id (0 = none). */
    private fun speciesNationals(t: PokedexTables): IntArray {
        speciesToNational?.let { return it }
        val x = t.expansion
        return when {
            // Each species' natDexNum sits in its own gSpeciesInfo entry.
            x != null -> {
                val raw = rd(t.speciesInfo, t.speciesCount * x.stride)
                IntArray(t.speciesCount) { s -> if (s == 0) 0 else u16le(raw, s * x.stride + x.natDexOff) }
            }
            // Only the other way round in the ROM: invert it.
            t.nationalToSpecies != 0L -> IntArray(t.speciesCount).also { out ->
                val raw = rd(t.nationalToSpecies, (t.nationalCount + 1) * 2)
                for (n in t.nationalCount downTo 1) u16le(raw, n * 2).takeIf { it in 1 until t.speciesCount }?.let { out[it] = n }
            }
            else -> {
                val raw = rd(t.speciesToNational, (t.speciesCount - 1) * 2)
                IntArray(t.speciesCount) { s -> if (s == 0) 0 else u16le(raw, (s - 1) * 2) }
            }
        }.also { speciesToNational = it }
    }

    fun entry(t: PokedexTables, national: Int): DexEntry? {
        sync(t)
        entries[national]?.let { return it }
        t.gen1?.let { g -> return Gen1Dex.entry(g, national)?.also { entries[national] = it } }
        t.gen2?.let { g -> return Gen2Dex.entry(g, national)?.also { entries[national] = it } }
        // Not cached here: before NdsArt has the cart's pages in, there's nothing yet (Gen4Dex keeps them once read).
        t.gen4?.let { g -> return Gen4Dex.entry(g, national) }
        return runCatching { readEntry(t, national) }
            .onFailure { android.util.Log.w("pokedaisy", "dex entry $national failed", it) }
            .getOrNull()?.also { entries[national] = it }
    }

    /** The 64x64 front sprite, as the dex page shows it. */
    fun frontSprite(t: PokedexTables, species: Int): Bitmap? {
        sync(t)
        if (species <= 0) return null
        sprites[species]?.let { return it }
        t.gen2?.let { g ->
            return runCatching { Gen2Dex.frontPixels(g, species)?.let { Bitmap.createBitmap(it, 56, 56, Bitmap.Config.ARGB_8888) } }
                .getOrNull()?.also { sprites[species] = it }
        }
        t.gen1?.let { g ->
            return runCatching { Gen1Dex.frontPixels(g, species)?.let { Bitmap.createBitmap(it, 56, 56, Bitmap.Config.ARGB_8888) } }
                .getOrNull()?.also { sprites[species] = it }
        }
        t.gen4?.let { g -> return Gen4Dex.front(g, species)?.also { sprites[species] = it } }
        return runCatching {
            val x = t.expansion
            val tilesPtr: Long
            val palPtr: Long
            if (x != null) {
                val info = rd(t.speciesInfo + species.toLong() * x.stride, x.stride)
                tilesPtr = Gfx.u32(info, x.frontPicOff)
                palPtr = Gfx.u32(info, x.paletteOff)
            } else {
                tilesPtr = Gfx.u32(rd(t.frontPics + species * 8L, 8), 0)
                palPtr = Gfx.u32(rd(t.palettes + species * 8L, 8), 0)
            }
            if (!Gfx.inRom(tilesPtr) || !Gfx.inRom(palPtr)) return null
            // Emerald's front pics hold two animation frames (4 KiB); the first is the still.
            val packed = rd(tilesPtr, 0x1800)
            val tiles = if (Smol.isSmol(packed)) Smol.decompress(packed) else Gfx.lz77(packed)
            val pal = rd(palPtr, 64)
            // Expansion keeps some palettes uncompressed: 16 raw colours.
            val colors = if ((pal[0].toInt() and 0xFF) == 0x10) Gfx.lz77(pal) else pal.copyOf(32)
            Gfx.decode4bpp(tiles, 0, colors, 0, 8, 8)
        }.getOrNull()?.also { sprites[species] = it }
    }

    /** The 16x16 footprint (black on transparent). */
    fun footprint(t: PokedexTables, species: Int): Bitmap? {
        sync(t)
        if (species <= 0) return null
        footprints[species]?.let { return it }
        return runCatching {
            val x = t.expansion
            val ptr = when {
                x != null -> if (x.footprintOff < 0) return null else Gfx.u32(rd(t.speciesInfo + species.toLong() * x.stride + x.footprintOff, 4), 0)
                t.footprints == 0L -> return null
                else -> Gfx.u32(rd(t.footprints + species * 4L, 4), 0)
            }
            if (!Gfx.inRom(ptr)) return null
            val bits = rd(ptr, 32)
            // Four 8x8 1bpp tiles (TL, TR, BL, BR), least significant bit leftmost.
            val px = IntArray(16 * 16)
            for (tile in 0 until 4) for (row in 0 until 8) {
                val v = bits[tile * 8 + row].toInt()
                for (col in 0 until 8) if ((v shr col) and 1 != 0) {
                    px[((tile / 2) * 8 + row) * 16 + (tile % 2) * 8 + col] = 0xFF000000.toInt()
                }
            }
            Bitmap.createBitmap(px, 16, 16, Bitmap.Config.ARGB_8888)
        }.getOrNull()?.also { footprints[species] = it }
    }

    private val abilityNames = ConcurrentHashMap<Pair<PokedexTables, Int>, String>()

    /** Ability [id]'s name in the running game (battle INFO's FOE DETAILS); null = no table / unreadable. */
    fun cachedAbilityName(t: PokedexTables, id: Int): String? = abilityNames[t to id]
    fun abilityName(t: PokedexTables, id: Int): String? {
        if (id <= 0 || t.abilityNames == 0L) return null
        abilityNames[t to id]?.let { return it }
        // The ROM's own casing, as the DEX shows it.
        return runCatching { ability(t, id) }.getOrNull()?.trim()?.takeIf { it.isNotEmpty() }
            ?.also { abilityNames[t to id] = it }
    }

    private fun ability(t: PokedexTables, id: Int): String {
        val stride = if (t.abilityNameStride > 0) t.abilityNameStride else t.abilityNameLength
        val at = t.abilityNames + id.toLong() * stride
        if (t.abilityNamePtr) return Gfx.u32(rd(at, 4), 0).takeIf(Gfx::inRom)?.let { Gen3Text.decode(rd(it, t.abilityNameLength)) }.orEmpty()
        return Gen3Text.decode(rd(at, t.abilityNameLength))
    }

    private fun readEntry(t: PokedexTables, national: Int): DexEntry? {
        val species = speciesFor(t, national)
        if (species == 0) return null
        t.expansion?.let { return readExpansionEntry(t, it, national, species) }
        val e = rd(t.entries + (if (t.entriesBySpecies) species else national).toLong() * t.entryStride, t.entryStride)
        val descPtr = Gfx.u32(e, t.entryDescOff)
        val bs = t.baseStats
        val info = rd(t.speciesInfo + species.toLong() * bs.stride, bs.stride)
        fun b(i: Int) = info[i].toInt() and 0xFF
        fun abilityAt(off: Int) = if (bs.abilityU16) u16le(info, off) else b(off)
        val abilities = listOf(abilityAt(bs.abilitiesOff), abilityAt(bs.abilitiesOff + if (bs.abilityU16) 2 else 1))
            .filter { it != 0 }.distinct().map { ability(t, it) }
        val hidden = t.hiddenAbilityOff.takeIf { it >= 0 }?.let { abilityAt(it) }?.takeIf { it != 0 }?.let { ability(t, it) }
        val category = if (t.entryCategoryPtr) Gfx.u32(e, 0).takeIf(Gfx::inRom)?.let { Gen3Text.decode(rd(it, t.entryCategoryLen + 1)) }.orEmpty()
            else Gen3Text.decode(e, 0, t.entryCategoryLen)
        return DexEntry(
            national = national,
            species = species,
            category = category,
            heightDm = u16le(e, t.entryHeightOff),
            weightHg = u16le(e, t.entryHeightOff + 2),
            description = listOfNotNull(
                descPtr.takeIf(Gfx::inRom),
                t.descriptionPage2Off.takeIf { it >= 0 }?.let { Gfx.u32(e, it) }?.takeIf(Gfx::inRom),
            ).joinToString(" ") { Gen3Text.decode(rd(it, 200)) },
            type1 = b(bs.typesOff),
            type2 = b(bs.typesOff + 1),
            baseStats = listOf(0, 1, 2, 4, 5, 3).map { if (bs.statsU16) u16le(info, it * 2) else b(it) },
            abilities = abilities,
            hiddenAbility = hidden,
            catchRate = b(bs.catchRateOff),
            genderRatio = b(bs.genderOff),
            eggGroups = listOf(b(bs.eggGroupsOff), b(bs.eggGroupsOff + 1)).distinct().mapNotNull { EGG_GROUP_NAMES.getOrNull(it) },
        )
    }

    private fun readExpansionEntry(t: PokedexTables, x: SpeciesInfoDex, national: Int, species: Int): DexEntry {
        val info = rd(t.speciesInfo + species.toLong() * x.stride, x.stride)
        fun b(i: Int) = info[i].toInt() and 0xFF
        val ids = (0 until 3).map { u16le(info, x.abilitiesOff + it * 2) }
        val descPtr = Gfx.u32(info, x.descriptionOff)
        return DexEntry(
            national = national,
            species = species,
            category = Gen3Text.decode(info, x.categoryOff, 13),
            heightDm = u16le(info, x.natDexOff + 2),
            weightHg = u16le(info, x.natDexOff + 4),
            description = if (Gfx.inRom(descPtr)) Gen3Text.decode(rd(descPtr, 200)) else "",
            type1 = b(6),
            type2 = b(7),
            baseStats = listOf(b(0), b(1), b(2), b(4), b(5), b(3)),
            abilities = ids.take(2).filter { it != 0 }.distinct().map { ability(t, it) },
            hiddenAbility = ids[2].takeIf { it != 0 }?.let { ability(t, it) },
            catchRate = b(x.catchRateOff),
            genderRatio = b(x.genderOff),
            eggGroups = listOf(b(x.eggGroupsOff), b(x.eggGroupsOff + 1)).distinct().mapNotNull { EGG_GROUP_NAMES.getOrNull(it) },
        )
    }

    private fun rd(addr: Long, n: Int): ByteArray {
        val out = ByteArray(n)
        var off = 0
        while (off < n) {
            val chunk = minOf(4096, n - off)
            reader.readCoreMemory(addr + off, chunk).copyInto(out, off, 0, chunk)
            off += chunk
        }
        return out
    }
}

/** EGG_GROUP_[id]'s name ([EGG_GROUP_NAMES]): the same ids in Gen 3 and 4. */
internal fun eggGroupName(id: Int): String? = EGG_GROUP_NAMES.getOrNull(id)

// include/constants/pokemon.h EGG_GROUP_*, as the games' own summary screens name them.
private val EGG_GROUP_NAMES = listOf(
    null, "MONSTER", "WATER 1", "BUG", "FLYING", "FIELD", "FAIRY", "GRASS", "HUMAN-LIKE",
    "WATER 3", "MINERAL", "AMORPHOUS", "WATER 2", "DITTO", "DRAGON", "UNDISCOVERED",
)

/** The dex page's category line, worded as [t]'s game does: "SEED POKéMON", "KÜKEN", "POKéMON POLLUELO". */
fun dexCategoryLine(t: PokedexTables, category: String): String =
    listOf(t.categoryPrefix, category, t.categorySuffix).filter { it.isNotEmpty() }.joinToString(t.categorySeparator)

/** HT as [t]'s game prints it: "2'04"" or, in the European releases, "0,7 m". */
/** HT / WT for [e] - a Gen 1 page's own feet / inches and pounds when it has them. */
fun formatDexHeight(t: PokedexTables, e: DexEntry): String =
    e.heightIn?.let { "%d'%02d\"".format(it / 12, it % 12) } ?: formatDexHeight(t, e.heightDm)

fun formatDexWeight(t: PokedexTables, e: DexEntry): String =
    e.weightLbs10?.let { "%d.%d lbs.".format(it / 10, it % 10) } ?: formatDexWeight(t, e.weightHg)

fun formatDexHeight(t: PokedexTables, dm: Int): String =
    if (t.metric) metric(t, dm, "m") else formatDexHeight(dm)

private fun metric(t: PokedexTables, tenths: Int, unit: String) =
    "${tenths / 10}${t.decimalPoint}${tenths % 10}${if (t.unitSpace) " " else ""}$unit"

/** WT as [t]'s game prints it: "15.2 lbs." or "6,9 kg". */
fun formatDexWeight(t: PokedexTables, hg: Int): String =
    if (t.metric) metric(t, hg, "kg") else formatDexWeight(hg)

/** "HT 2'04"", the game's own rounding (DexScreen_PrintMonHeight). */
fun formatDexHeight(dm: Int): String {
    var inches = 10000L * dm / 254 // tenths of an inch
    if (inches % 10 >= 5) inches += 10
    val feet = inches / 120
    val rest = (inches - feet * 120) / 10
    return "%d'%02d\"".format(feet, rest)
}

/** "15.2 lbs.", the game's own rounding (DexScreen_PrintMonWeight). */
fun formatDexWeight(hg: Int): String {
    var lbs = 100000L * hg / 4536 // hundredths of a pound
    if (lbs % 10 >= 5) lbs += 10
    return "%d.%d lbs.".format(lbs / 100, (lbs % 100) / 10)
}

/** Gen 3 (Western) text: the characters dex entries and ability names use
 * (anything else decodes as [UNKNOWN]). */
object Gen3Text {
    const val UNKNOWN = '\uFFFD'
    private val table = CharArray(256) { UNKNOWN }.also { t ->
        t[0x00] = ' '
        t[0x1B] = 'é'
        for (i in 0..9) t[0xA1 + i] = '0' + i
        t[0xAB] = '!'; t[0xAC] = '?'; t[0xAD] = '.'; t[0xAE] = '-'; t[0xB0] = '…'
        t[0xB1] = '“'; t[0xB2] = '”'; t[0xB3] = '‘'; t[0xB4] = '’'
        t[0xB5] = '♂'; t[0xB6] = '♀'; t[0xB8] = ','; t[0xBA] = '/'
        for (i in 0 until 26) { t[0xBB + i] = 'A' + i; t[0xD5 + i] = 'a' + i }
        t[0xF0] = ':'
        t[0x2D] = '&'; t[0x2E] = '+'; t[0x5B] = '%'; t[0x5C] = '('; t[0x5D] = ')'
        t[0x2A] = '°' // expansion dex text ("3,600°F")
        t[0x35] = '='; t[0x36] = ';'
        // The rest of charmap.txt's Western letters (hack text: "capítulo", "organización").
        "ÀÁÂÇÈÉÊËÌ ÎÏÒÓÔŒÙÚÛÑßàá çèéêëì îïòóôœùúûñ".forEachIndexed { i, c -> if (c != ' ') t[0x01 + i] = c }
        t[0x2B] = 'ª'; t[0x51] = '¿'; t[0x52] = '¡'; t[0x5A] = 'Í'; t[0x68] = 'â'; t[0x6F] = 'í'
        t[0x85] = '<'; t[0x86] = '>'; t[0xAF] = '·'; t[0xB9] = '×'
        "ÄÖÜäöü".forEachIndexed { i, c -> t[0xF1 + i] = c } // German Emerald's dex text
    }

    /** Japanese Emerald's text ([romLanguage] 'J'): hiragana then katakana where the Western letters
     * are (charmap.txt's Japanese half), full-width punctuation, the rest as above. */
    private val japanese = table.copyOf().also { t ->
        ("　あいうえおかきくけこさしすせそたちつてとなにぬねのはひふへほまみむめもやゆよらりるれろわをん" +
            "ぁぃぅぇぉゃゅょがぎぐげござじずぜぞだぢづでどばびぶべぼぱぴぷぺぽっ" +
            "アイウエオカキクケコサシスセソタチツテトナニヌネノハヒフヘホマミムメモヤユヨラリルレロワヲン" +
            "ァィゥェォャュョガギグゲゴザジズゼゾダヂヅデドバビブベボパピプペポッ").forEachIndexed { i, c -> t[i] = c }
        t[0xAB] = '！'; t[0xAC] = '？'; t[0xAD] = '。'; t[0xAE] = 'ー'; t[0xB0] = '⋯'
    }

    // Argument bytes after an 0xFC control code (text colour, font, pause, ...),
    // by code - charmap.txt's EXT_CTRL_CODE_* list.
    private val FC_ARGS = intArrayOf(0, 1, 1, 1, 3, 1, 1, 0, 1, 0, 0, 2, 1, 1, 1, 0, 2, 1, 1, 1, 1, 0, 0, 0, 0)

    // Western glyphs that draw more than one letter (charmap.txt's PK, MN, POKéBLOCK's
    // tiles, LV, the small raised letters), arrows, a spacer, the yen sign.
    private val western = mapOf(
        0x53 to "PK", 0x54 to "MN", 0x55 to "PO", 0x56 to "Ké", 0x34 to "Lv", 0x2C to "er", 0x84 to "e", 0xA0 to "re",
        0x79 to "↑", 0x7A to "↓", 0x7B to "←", 0x7C to "→", 0x77 to "", 0xB7 to "¥",
    )

    // The POKéBLOCK word's own glyphs, which each language redrew for its word
    // (POKéBLOCK, POKéCUBO, POKéRIEGEL, POKéBLOC, POKéMELLE / POKéMELLA), and in the
    // European releases 0x2A is the ordinal º ("2.º turno"), not expansion's degree sign.
    private val pokeblock = mapOf(
        'E' to mapOf(0x57 to "BL", 0x58 to "OC", 0x59 to "K"),
        'S' to mapOf(0x57 to "CU", 0x58 to "BO", 0x59 to "", 0x2A to "º"),
        'D' to mapOf(0x57 to "RIE", 0x58 to "GE", 0x59 to "L", 0x2A to "º"),
        'F' to mapOf(0x57 to "BL", 0x58 to "O", 0x59 to "C", 0x2A to "º"),
        'I' to mapOf(0x5E to "PO", 0x5F to "Ké", 0x60 to "ME", 0x61 to "LL", 0x62 to "A", 0x63 to "E", 0x2A to "º"),
    )

    // char -> the first byte drawing it, per table; ASCII ' is the game's ’ ("FARFETCH’D").
    private fun reverse(t: CharArray): Map<Char, Int> = HashMap<Char, Int>().also { m ->
        for (b in t.indices.reversed()) if (t[b] != UNKNOWN) m[t[b]] = b
        m['\''] = 0xB4
    }
    private val westernRev by lazy { reverse(table) }
    private val japaneseRev by lazy { reverse(japanese) }

    /** [s] in the running game's bytes ([romLanguage]'s table), no terminator; null when a
     * character has no byte of its own (the multi-letter glyphs aren't used). */
    fun encode(s: String): ByteArray? {
        val rev = if (romLanguage == 'J') japaneseRev else westernRev
        return ByteArray(s.length) { i -> (rev[s[i]] ?: return null).toByte() }
    }

    /** Byte -> the byte of the same letter upper-cased (case-blind matching: FireRed's
     * "BULBASAUR" is a hack table's "Bulbasaur"); Japanese has no case. */
    fun foldTable(): IntArray {
        val jp = romLanguage == 'J'
        val chars = if (jp) japanese else table
        val rev = if (jp) japaneseRev else westernRev
        return IntArray(256) { b -> if (jp) b else rev[chars[b].uppercaseChar()] ?: b }
    }

    /** Up to [max] bytes from [off], stopping at 0xFF; line breaks become
     * spaces, control codes (0xFC + args, e.g. a font change) and placeholders
     * (0xFD + id, e.g. the player's name) are dropped. */
    fun decode(b: ByteArray, off: Int = 0, max: Int = b.size - off): String = buildString {
        val end = minOf(b.size, off + max)
        val jp = romLanguage == 'J'
        val chars = if (jp) japanese else table
        val block = pokeblock[romLanguage] ?: pokeblock.getValue('E')
        var i = off
        while (i < end) {
            val c = b[i].toInt() and 0xFF
            if (c == 0xFF) break
            if (c == 0xFC && i + 1 < end) {
                i += 2 + FC_ARGS.getOrElse(b[i + 1].toInt() and 0xFF) { 0 }
                continue
            }
            if (c == 0xFD) {
                i += 2
                continue
            }
            // A line break reads as a space; in Japanese, the full-width one its phrases are spaced with.
            when {
                c == 0xFE || c == 0xFA || c == 0xFB -> append(if (jp) '　' else ' ')
                !jp && c in block -> append(block.getValue(c))
                !jp && c in western -> append(western.getValue(c))
                else -> append(chars[c])
            }
            i++
        }
    }.trim()
}
