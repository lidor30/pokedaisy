package com.pokedaisy.app.companion.data

/**
 * Generation 2 (Game Boy Color) games' RAM. Addresses are pret/pokecrystal's symbols (its build is
 * byte-identical to every retail English Crystal: rev 0, rev 1 and Australia, which keep their RAM
 * at the same addresses), checked on headless captures of a new game played in each (the `crystal`
 * fixtures).
 *
 * Crystal keeps most of its state in WRAM bank 1 (D000-DFFF) but switches that window to other banks
 * mid-frame (palettes, the battle's scratch), so a read over the bus can land on the wrong bank: the
 * D000-DFFF addresses here are WRAM *bank 1's*, given as [wramx] addresses (0x1Dxxx), which the
 * native reader (pkReadBytes) and FixtureMemoryReader resolve to that bank whatever SVBK says.
 *
 * Gen 2's structs are plaintext and big-endian (HP, stats, experience); species are National Dex
 * numbers (1-251), there's a held item, DVs instead of IVs, and no ability. The bag is four pockets.
 */
data class Gen2Config(
    val partyCount: Long,       // wPartyCount, then wPartySpecies (an egg is EGG there, its species in the struct)
    val partyMons: Long,        // wPartyMons: 6 x party_struct (48 bytes)
    val numItems: Long,         // wNumItems: count, then {item, qty} pairs (20)
    val numKeyItems: Long,      // wNumKeyItems: count, then item ids (25)
    val numBalls: Long,         // wNumBalls: count, then {item, qty} pairs (12)
    val tmsHms: Long,           // wTMsHMs: a quantity per TM01-50, HM01-07
    val money: Long,            // wMoney: 3 bytes, big-endian binary
    val mapGroup: Long,         // wMapGroup, wMapNumber (then wYCoord, wXCoord unless [coords] says where)
    val coords: Long = -1,      // wYCoord, wXCoord when they aren't right after wMapNumber (Crystal Clear moved the map ids)
    val backupMapGroup: Long,   // wBackupMapGroup, wBackupMapNumber: where a SPECIAL landmark's map is (the town map's rule)
    val battleMode: Long,       // wBattleMode: 0 none, 1 wild, 2 trainer
    val battleMon: Long,        // wBattleMon: battle_struct (32 bytes)
    val enemyMon: Long,         // wEnemyMon
    val otPartyCount: Long,     // wOTPartyCount, then wOTPartySpecies
    val otPartyMons: Long,      // wOTPartyMons: a trainer's party_structs
    val curOTMon: Long,         // wCurOTMon: which of them is out
    val playerGender: Long,     // wPlayerGender: bit 0 = Kris; -1 = none (Gold / Silver: always the boy)
    val gameTime: Long,         // wGameTimeHours (big-endian word), Minutes, Seconds, Frames
    val dexCaught: Long,        // wPokedexCaught, wPokedexSeen: 32 bytes each, bit n-1 = Dex No. n
    val dexSeen: Long,
    // The 2D menu _2DMenu runs (w2DMenuCursorInitY .. w2DMenuCursorOffsets, then wMenuCursorY / X, 1-based),
    // wMoveSelectionMenuType (0 = the battle's FIGHT list) and wTilemap, the screen's 20x18 tiles: a menu waits
    // for input while its cursor cell holds the filled ▶ (an A turns it into ▷, then the box is redrawn).
    val menu2D: Long,
    val menuCursor: Long,
    val moveMenuType: Long,
    val tileMap: Long,
    /** The battle POKéMON switch ([Gen2PartySwitch]): wPartyMenuActionText (which party menu is up: 0 PKMN's, 2 the
     * pick after a faint, 1 an item's) and wCurBattleMon (the party slot in battle) - Crystal's, kept by its hacks. */
    val partyMenuAction: Long = wramx(1, 0xD141),
    val curBattleMon: Long = wramx(1, 0xD0D4),
    val landmarks: Map<Int, Int>,
    val genderRatios: IntArray,
    val tmItems: IntArray,
    val pokedex: PokedexTables? = null,
    /** The highest species id (Crystal's 251 Dex numbers; Prism's own 254 ids - 253 is EGG there too). */
    val speciesCount: Int = 251,
    /** The pockets' sizes: wNumItems' and wNumBalls' {item, quantity} pairs, wNumKeyItems' ids. */
    val itemsMax: Int = 20,
    val ballsMax: Int = 12,
    val keyItemsMax: Int = 25,
    /** wTMsHMs holds a bit per TM / HM (Prism: reusable TMs, no quantities) rather than a quantity each. */
    val tmBits: Boolean = false,
    /** The Mail items (the party menu marks a mon holding one with Mail's icon). */
    val mailItems: Set<Int> = GEN2_MAIL_ITEMS,
    /** Growth rates by species when they aren't Crystal's (Gen 3's, by Dex number): [expProgress]. */
    val growthRates: IntArray? = null,
    /** The filled ▶ tile a waiting menu's cursor is drawn with (Crystal's charmap: 0xED; Polished Crystal's: 0xF0). */
    val cursorTile: Int = 0xED,
    /** Polished Crystal ([GEN2_POLISHED_CRYSTAL]): its own party / battle structs (9-bit species with forms: [polishedSpeciesId],
     * gender a bit), a MEDICINE and a BERRIES pocket ([numMedicine] / [numBerries]) and key items as a 0-terminated list
     * of their own ids at [numKeyItems]. */
    val polished: Boolean = false,
    val numMedicine: Long = -1,
    val numBerries: Long = -1,
    val medicineMax: Int = 0,
    val berriesMax: Int = 0,
    /** A faithful build: its own species types, moves and item names over the normal ones (ActiveTables). */
    val polishedFaithful: Boolean = false,
)

/** Crystal's Mail (FLOWER_MAIL 0x9E, SURF_MAIL 0xB5 .. MIRAGE_MAIL 0xBD): the party menu marks a mon holding one with Mail's icon. */
val GEN2_MAIL_ITEMS = setOf(0x9E) + (0xB5..0xBD)
// (Declared before the configs below: a file's top-level values are set up in order.)

/** WRAM bank [bank]'s byte at [addr] (D000-DFFF), as the companion's readers address it (see [Gen2Config]). */
fun wramx(bank: Int, addr: Int): Long = (bank.toLong() shl 16) or addr.toLong()

/** Retail English Crystal: rev 0, rev 1 (USA, Europe) and Australia share every address here. */
val GEN2_CRYSTAL = Gen2Config(
    partyCount = wramx(1, 0xDCD7),
    partyMons = wramx(1, 0xDCDF),
    numItems = wramx(1, 0xD892),
    numKeyItems = wramx(1, 0xD8BC),
    numBalls = wramx(1, 0xD8D7),
    tmsHms = wramx(1, 0xD859),
    money = wramx(1, 0xD84E),
    mapGroup = wramx(1, 0xDCB5),
    backupMapGroup = wramx(1, 0xDCAD),
    battleMode = wramx(1, 0xD22D),
    battleMon = 0xC62CL,
    enemyMon = wramx(1, 0xD206),
    otPartyCount = wramx(1, 0xD280),
    otPartyMons = wramx(1, 0xD288),
    curOTMon = 0xC663L,
    playerGender = wramx(1, 0xD472),
    gameTime = wramx(1, 0xD4C4),
    dexCaught = wramx(1, 0xDE99),
    dexSeen = wramx(1, 0xDEB9),
    menu2D = 0xCFA1L,
    menuCursor = 0xCFA9L,
    moveMenuType = wramx(1, 0xD235),
    tileMap = 0xC4A0L,
    landmarks = mapLandmarksCrystal,
    genderRatios = gen2GenderRatiosCrystal,
    tmItems = gen2TmItemsCrystal,
    pokedex = POKEDEX_CRYSTAL,
)

/**
 * Retail English Gold and Silver (USA, Europe): pret/pokegold's symbols (its gold and silver builds are byte-identical to
 * both carts), checked on headless captures of a new game played in each (the `gold*` / `silver*` fixtures). The two
 * keep every RAM address here at the same place; their WRAM is laid out apart from Crystal's (every address its own,
 * wBattleMon / wCurOTMon / wCurBattleMon in bank 0), the structs and menus are Crystal's. No gender choice: the player
 * is always the boy. Their items and landmarks are their own tables (Crystal's less its additions), species / moves /
 * types Crystal's; dex pages, front pics and party icons are read from each version's ROM ([POKEDEX_GOLD] / [POKEDEX_SILVER]).
 */
val GEN2_GOLD = Gen2Config(
    partyCount = wramx(1, 0xDA22),
    partyMons = wramx(1, 0xDA2A),
    numItems = wramx(1, 0xD5B7),
    numKeyItems = wramx(1, 0xD5E1),
    numBalls = wramx(1, 0xD5FC),
    tmsHms = wramx(1, 0xD57E),
    money = wramx(1, 0xD573),
    mapGroup = wramx(1, 0xDA00),
    backupMapGroup = wramx(1, 0xD9F6),
    battleMode = wramx(1, 0xD116),
    battleMon = 0xCB0CL,
    enemyMon = wramx(1, 0xD0EF),
    otPartyCount = wramx(1, 0xDD55),
    otPartyMons = wramx(1, 0xDD5D),
    curOTMon = 0xCB41L,
    playerGender = -1,
    gameTime = wramx(1, 0xD1EB),
    dexCaught = wramx(1, 0xDBE4),
    dexSeen = wramx(1, 0xDC04),
    menu2D = 0xCED8L,
    menuCursor = 0xCEE0L,
    moveMenuType = wramx(1, 0xD11F),
    tileMap = 0xC3A0L,
    partyMenuAction = wramx(1, 0xD03E),
    curBattleMon = 0xCFC6L,
    landmarks = mapLandmarksGoldSilver,
    genderRatios = gen2GenderRatiosCrystal,
    tmItems = gen2TmItemsCrystal,
    pokedex = POKEDEX_GOLD,
)
val GEN2_SILVER = GEN2_GOLD.copy(pokedex = POKEDEX_SILVER)

/**
 * Pokémon Crystal Clear (ShockSlayer's open-world hack of Crystal): v2.6.0 and v2.5.11, each in a Standard Colors and a
 * Color Filter build (the same code, other palettes), share this RAM. It was mapped from pokecrystal11.sym by
 * scripts/port_gen1.py (the code that uses each symbol) and checked on headless captures (the `crystal_clear*`
 * fixtures): Crystal's RAM but for the map ids (wMapGroup / wMapNumber moved into bytes Crystal leaves unused at DA3A;
 * the coordinates stayed) and the caught flags (DA0E; the seen ones stayed). Its species, moves and types are
 * Crystal's; its items, landmarks and maps its own (gen_gen2_tables.py crystal_clear). The two versions differ only in
 * where some ROM tables sit ([GEN2_DEX_CRYSTAL_CLEAR_V2_5_11]).
 */
val GEN2_CRYSTAL_CLEAR_V2_6_0 = GEN2_CRYSTAL.copy(
    mapGroup = wramx(1, 0xDA3A),
    coords = wramx(1, 0xDCB7),
    dexCaught = wramx(1, 0xDA0E),
    landmarks = mapLandmarksCrystalClear,
    pokedex = POKEDEX_CRYSTAL_CLEAR_V2_6_0,
)
val GEN2_CRYSTAL_CLEAR_V2_5_11 = GEN2_CRYSTAL_CLEAR_V2_6_0.copy(pokedex = POKEDEX_CRYSTAL_CLEAR_V2_5_11)

/**
 * Pokémon Prism v0.95 build 254 (RainbowDevs' Crystal-engine hack: its own Naljo and Rijon). Its RAM kept Crystal's
 * addresses for everything here but the bag: wTMsHMs (D859) is a bit per TM01-96 / HM01-05, and the pockets moved
 * and grew - wNumItems D866 (40), wNumKeyItems D8B8 (50), wNumBalls D8EC (25; ReceiveItem's capacities, 12:437D).
 * Found by scripts/port_gen1.py from pokecrystal11.sym and the code that uses each address, then checked on headless
 * dumps (the `prism*` fixtures): the party, money, map ids, both battlers, a trainer's team and the battle menus sit
 * where Crystal keeps them. Its species ids are its own (254, not Dex numbers), and so are its party icons, town maps
 * and font (Gen2Art.PRISM). Its seen / caught flags are Crystal's, a bit per species id; its dex pages and item
 * descriptions are packed by its own text engine ([POKEDEX_PRISM], [Gen2PackedText]).
 */
val GEN2_PRISM = GEN2_CRYSTAL.copy(
    numItems = wramx(1, 0xD866),
    numKeyItems = wramx(1, 0xD8B8),
    numBalls = wramx(1, 0xD8EC),
    landmarks = mapLandmarksPrism,
    genderRatios = gen2GenderRatiosPrism,
    tmItems = gen2TmItemsPrism,
    pokedex = POKEDEX_PRISM,
    speciesCount = 254,
    itemsMax = 40,
    ballsMax = 25,
    keyItemsMax = 50,
    tmBits = true,
    mailItems = emptySet(),
    growthRates = gen2GrowthRatesPrism,
)

/**
 * Polished Crystal 3.2.0-3.2.3, the normal and the faithful builds (Rangi42's open-source Crystal hack): every RAM address
 * here is the same in all eight (their release .sym files agree), taken from them. Crystal's engine with its own data:
 * a 48-byte party_struct with three DV bytes, a personality and a form byte (gender bit 7, egg bit 6, the species'
 * 9th bit 5, form 0-4), a 35-byte battle_struct, and a bag of ITEMS / MEDICINE / BALLS / BERRIES ({id, quantity} lists),
 * key items (their own ids, a 0-terminated list), TMs / HMs (a bit each). Its tables: gen_polished_crystal_tables.py.
 */
val GEN2_POLISHED_CRYSTAL = GEN2_CRYSTAL.copy(
    partyCount = wramx(1, 0xDCCE),
    partyMons = wramx(1, 0xDCD6),
    numItems = wramx(1, 0xD827),
    numKeyItems = wramx(1, 0xD800),
    numBalls = wramx(1, 0xD90B),
    tmsHms = wramx(1, 0xD7F5),
    money = wramx(1, 0xD7E5),
    mapGroup = wramx(1, 0xDCAC),
    backupMapGroup = wramx(1, 0xDCA7),
    battleMode = wramx(1, 0xD233),
    battleMon = 0xC4A5L,
    enemyMon = wramx(1, 0xD209),
    otPartyCount = wramx(1, 0xD283),
    otPartyMons = wramx(1, 0xD28B),
    curOTMon = 0xC4DDL,
    playerGender = wramx(1, 0xD47A),
    gameTime = wramx(1, 0xD4B7),
    dexCaught = wramx(1, 0xDE7B),
    dexSeen = wramx(1, 0xDEAD),
    menu2D = 0xCE7CL,
    menuCursor = 0xCE84L,
    moveMenuType = wramx(1, 0xD239),
    tileMap = 0xC1A0L,
    partyMenuAction = wramx(1, 0xD113),
    curBattleMon = wramx(1, 0xD0DA),
    landmarks = mapLandmarksPolishedCrystal,
    genderRatios = gen2GenderRatiosPolishedCrystal,
    tmItems = gen2TmItemsPolishedCrystal,
    pokedex = null,
    speciesCount = POLISHED_NUM_SPECIES,
    itemsMax = 75,
    ballsMax = 25,
    keyItemsMax = 38,
    tmBits = true,
    mailItems = emptySet(),
    growthRates = gen2GrowthRatesPolishedCrystal,
    cursorTile = 0xF0,
    polished = true,
    numMedicine = wramx(1, 0xD8BF),
    numBerries = wramx(1, 0xD93F),
    medicineMax = 37,
    berriesMax = 31,
)

val GEN2_POLISHED_CRYSTAL_FAITHFUL = GEN2_POLISHED_CRYSTAL.copy(polishedFaithful = true)

/**
 * Polished Crystal's species id for the companion: a species' own 9-bit value (its byte, the form byte's bit 5 the 9th
 * bit), or POLISHED_NUM_SPECIES + 1 + k for a variant form, entry k of VariantSpeciesAndFormTable (GetSpeciesAndFormIndex:
 * ALOLAN RATTATA, GALARIAN MEOWTH, ...) - the index + 1 of every per-species table the game keeps.
 */
fun polishedSpeciesId(species: Int, form: Int): Int {
    val s9 = species or (if (form and 0x20 != 0) 0x100 else 0)
    val k = polishedVariantForms.indexOf((s9 shl 8) or (form and 0x1F))
    return if (k >= 0) POLISHED_NUM_SPECIES + 1 + k else s9
}

/** Polished Crystal's party_struct: species +0, item +1, moves +2, ID +6, exp +8, DVs +17, personality +20, form +21 (gender
 * bit 7: female, egg bit 6), PP +22, level +31, status +32, HP +34 / +36. Gender follows the bit unless the species is all
 * one gender (GetGender). */
fun decodePolishedPartyMon(raw: ByteArray, off: Int, cfg: Gen2Config): Mon? {
    val species = u8(raw, off)
    if (species == 0 || species == 0xFF) return null
    val form = u8(raw, off + 21)
    val id = polishedSpeciesId(species, form)
    if (id !in 1 until cfg.genderRatios.size) return null
    val level = u8(raw, off + 31)
    if (level !in 1..100) return null
    val egg = form and 0x40 != 0
    val gender = when (cfg.genderRatios[id]) {
        255 -> GENDER_SYMBOL_NONE
        0 -> GENDER_SYMBOL_MALE
        254 -> GENDER_SYMBOL_FEMALE
        else -> if (form and 0x80 != 0) GENDER_SYMBOL_FEMALE else GENDER_SYMBOL_MALE
    }
    return Mon(
        // An egg keeps its species in the struct: shown as the game's own egg (species byte 0xFF, the companion's 255).
        species = if (egg) 0xFF else id,
        level = level,
        hp = be16(raw, off + 34),
        maxHp = be16(raw, off + 36),
        status = u8(raw, off + 32).toLong(),
        moves = IntArray(4) { u8(raw, off + 2 + it) },
        pp = IntArray(4) { u8(raw, off + 22 + it) and 0x3F },
        genderSymbol = if (egg) GENDER_SYMBOL_NONE else gender,
        exp = (u8(raw, off + 8).toLong() shl 16) or (u8(raw, off + 9).toLong() shl 8) or u8(raw, off + 10).toLong(),
        isEgg = egg,
        heldItem = u8(raw, off + 1),
        personality = gbMonKey(id, be16(raw, off + 6), be16(raw, off + 17)),
    )
}

/** Polished Crystal's battle_struct (35 bytes): species +0, item +1, moves +2, DVs +6, form +10, PP +11, level +16, status
 * +17, HP +19 / +21, stats +23, types +33 / +34. */
fun decodePolishedBattleMon(raw: ByteArray): BattleMon? {
    val species = u8(raw, 0)
    if (species == 0 || species == 0xFF) return null
    val level = u8(raw, 16)
    if (level !in 1..100) return null
    return BattleMon(
        species = polishedSpeciesId(species, u8(raw, 10)),
        level = level,
        type1 = u8(raw, 33),
        type2 = u8(raw, 34),
        hp = be16(raw, 19),
        maxHp = be16(raw, 21),
        status1 = u8(raw, 17).toLong(),
        moves = IntArray(4) { u8(raw, 2 + it) },
        pp = IntArray(4) { u8(raw, 11 + it) and 0x3F },
        item = u8(raw, 1),
        stats = IntArray(5) { be16(raw, 23 + 2 * it) },
    )
}
const val POLISHED_BATTLE_MON_SIZE = 35

/** The running Game Boy Color game's addresses (set by the Poller with [activeGame]): its dex tables, item text. */
@Volatile
var activeGen2: Gen2Config = GEN2_CRYSTAL

const val GEN2_PARTY_MON_SIZE = 48
const val GEN2_BATTLE_MON_SIZE = 32
private const val GEN2_EGG = 0xFD
private const val GEN2_TMS_HMS = 57
/** wPartyMonNicknames' 11-byte names, right after wPartyMonOT (6 x 11): see [readGen2Telemetry]. */
private const val GEN2_NAME_LEN = 11
private const val GEN2_NICKNAMES_AFTER_MONS = PARTY_SIZE * GEN2_PARTY_MON_SIZE + PARTY_SIZE * GEN2_NAME_LEN

private fun be16(b: ByteArray, o: Int) = ((b[o].toInt() and 0xFF) shl 8) or (b[o + 1].toInt() and 0xFF)
private fun u8(b: ByteArray, o: Int) = b[o].toInt() and 0xFF

/**
 * The party menu's gender (GetGender): genderless at ratio 255, all male at 0, all female at 254,
 * else female while (Attack DV << 4 | Speed DV) is at most the ratio.
 */
fun gen2Gender(species: Int, dvs0: Int, dvs1: Int, ratios: IntArray): Int {
    val r = ratios.getOrNull(species) ?: return GENDER_SYMBOL_UNKNOWN
    return when (r) {
        255 -> GENDER_SYMBOL_NONE
        0 -> GENDER_SYMBOL_MALE
        254 -> GENDER_SYMBOL_FEMALE
        else -> if (r < ((dvs0 and 0xF0) or (dvs1 shr 4))) GENDER_SYMBOL_MALE else GENDER_SYMBOL_FEMALE
    }
}

/** A party_struct at [off] in [raw], or null for an empty / garbage slot. [egg]: wPartySpecies says EGG. */
fun decodeGen2PartyMon(raw: ByteArray, off: Int, cfg: Gen2Config, egg: Boolean = false): Mon? {
    val species = u8(raw, off)
    if (species !in 1..cfg.speciesCount) return null
    val level = u8(raw, off + 31)
    if (level !in 1..100) return null
    val exp = ((u8(raw, off + 8).toLong() shl 16) or (u8(raw, off + 9).toLong() shl 8) or u8(raw, off + 10).toLong())
    return Mon(
        // An egg: Gen 2 keeps its species in the struct (EGG is only in wPartySpecies) - shown as the egg (Gen2Art's 253).
        species = if (egg) GEN2_EGG else species,
        level = level,
        hp = be16(raw, off + 34),
        maxHp = be16(raw, off + 36),
        status = u8(raw, off + 32).toLong(),   // Gen 3's bits: sleep 0-2, PSN, BRN, FRZ, PAR
        moves = IntArray(4) { u8(raw, off + 2 + it) },
        pp = IntArray(4) { u8(raw, off + 23 + it) and 0x3F },   // top 2 bits: PP Ups
        genderSymbol = if (egg) GENDER_SYMBOL_NONE else gen2Gender(species, u8(raw, off + 21), u8(raw, off + 22), cfg.genderRatios),
        exp = exp,
        isEgg = egg,
        heldItem = u8(raw, off + 1),
        personality = gen2MonKey(raw, off),   // no personality in Gen 2: the switch's key (GbPartySwitch)
    )
}

/**
 * [raw]'s nickname at [off] (11 bytes) in the game's charmap. Crystal ends names with 0x50; Polished Crystal's
 * own charmap ends them with 0x53 (or <DONE> 0x52), as its player / box names do.
 */
private fun gen2Nickname(raw: ByteArray, off: Int, polished: Boolean): String {
    if (off + GEN2_NAME_LEN > raw.size) return ""
    val chars = Gen2Art.chars(polished)
    return buildString {
        for (i in 0 until GEN2_NAME_LEN) {
            val c = raw[off + i].toInt() and 0xFF
            if (c == 0x00 || c == 0x50 || (polished && (c == 0x52 || c == 0x53))) break
            chars[c]?.let { append(it) }
        }
    }.trim()
}

/** A battle_struct (wBattleMon / wEnemyMon); its types are the battler's current ones. */
fun decodeGen2BattleMon(raw: ByteArray, speciesCount: Int = 251): BattleMon? {
    val species = u8(raw, 0)
    if (species !in 1..speciesCount) return null
    return BattleMon(
        species = species,
        level = u8(raw, 13),
        type1 = u8(raw, 30),
        type2 = u8(raw, 31),
        hp = be16(raw, 16),
        maxHp = be16(raw, 18),
        status1 = u8(raw, 14).toLong(),
        moves = IntArray(4) { u8(raw, 2 + it) },
        pp = IntArray(4) { u8(raw, 8 + it) and 0x3F },
        item = u8(raw, 1),
        // Attack, Defense, Speed, Sp. Atk, Sp. Def - BattleMon's order is Gen 3's.
        stats = IntArray(5) { be16(raw, 20 + 2 * it) },
    )
}

private const val BATTLE_MENU_TOP = 0x0E      // BattleMenu's _2DMenu: 2 x 2 from (9, 14), 6 across / 2 down
private const val BATTLE_MENU_LEFT = 0x09
private const val MOVE_MENU_TOP = 0x0D        // MoveSelectionScreen: a column from (5, 13)
private const val MOVE_MENU_LEFT = 0x05

/**
 * Which battle menu waits for the player, and its cursor: BATTLE_INPUT_ACTION_SELECT with the cell
 * (column + 2 x row: FIGHT 0, PKMN 1, PACK 2, RUN 3 - Gen 1's layout), or BATTLE_INPUT_MOVE_SELECT with
 * the move index; BATTLE_INPUT_BUSY in between; NONE outside a battle. The menu variables stay set after
 * a pick, so a menu only counts as waiting while its cursor cell on screen is the filled arrow.
 */
fun readGen2BattleInput(r: MemoryReader, cfg: Gen2Config): Pair<Int, Int> {
    val battle = u8(r.readCoreMemory(cfg.battleMode, 1), 0)
    if (battle != 1 && battle != 2) return BATTLE_INPUT_NONE to -1
    readGen2PartyMenu(r, cfg)?.let { return BATTLE_INPUT_PARTY_OPEN to it.pollCursor }
    val m = r.readCoreMemory(cfg.menu2D, 7)
    val top = u8(m, 0)
    val left = u8(m, 1)
    val rows = u8(m, 2)
    val cols = u8(m, 3)
    val spacing = u8(m, 6)
    val cur = r.readCoreMemory(cfg.menuCursor, 2)
    val y = u8(cur, 0)
    val x = u8(cur, 1)
    if (y !in 1..rows || x !in 1..cols) return BATTLE_INPUT_BUSY to -1
    val tx = left + (x - 1) * (spacing and 0xF)
    val ty = top + (y - 1) * (spacing shr 4)
    val shown = tx < 20 && ty < 18 && u8(r.readCoreMemory(cfg.tileMap + ty * 20 + tx, 1), 0) == cfg.cursorTile
    if (!shown) return BATTLE_INPUT_BUSY to -1
    if (top == BATTLE_MENU_TOP && left == BATTLE_MENU_LEFT && rows == 2 && cols == 2) {
        return BATTLE_INPUT_ACTION_SELECT to (x - 1) + 2 * (y - 1)
    }
    if (top == MOVE_MENU_TOP && left == MOVE_MENU_LEFT && cols == 1 && rows in 1..4 &&
        u8(r.readCoreMemory(cfg.moveMenuType, 1), 0) == 0
    ) {
        return BATTLE_INPUT_MOVE_SELECT to y - 1
    }
    return BATTLE_INPUT_BUSY to -1
}

/** The landmark (map section) of map [group].[num]; a SPECIAL one is named by the backup map, as the town map does. */
fun gen2Landmark(cfg: Gen2Config, group: Int, num: Int, backupGroup: Int, backupNum: Int): Int {
    val lm = cfg.landmarks[group * 256 + num] ?: return 0
    return if (lm == 0) cfg.landmarks[backupGroup * 256 + backupNum] ?: 0 else lm
}

private val EMPTY_BATTLE_MON = BattleMon(0, 0, 0, 0, 0, 0, 0, IntArray(4), IntArray(4))

/** The four pockets in the game's own order: ITEMS, BALLS, KEY ITEMS, TM / HM. */
fun readGen2Bag(r: MemoryReader, cfg: Gen2Config): List<Item> {
    // Each list ends in 0xFF right after its count; before a save is loaded (the title screen) WRAM holds
    // leftovers, and the app once listed 31 TMs there: no bag at all until every list is well formed.
    fun pairs(addr: Long, max: Int, pocket: Int): List<Item>? {
        val raw = r.readCoreMemory(addr, 2 + 2 * max)
        val n = u8(raw, 0)
        if (n > max || u8(raw, 1 + 2 * n) != 0xFF) return null
        return (0 until n).map { Item(u8(raw, 1 + 2 * it), u8(raw, 2 + 2 * it), pocket) }
    }
    val items = pairs(cfg.numItems, cfg.itemsMax, POCKET_ITEMS) ?: return emptyList()
    val balls = pairs(cfg.numBalls, cfg.ballsMax, POCKET_POKE_BALLS) ?: return emptyList()
    if (cfg.polished) {
        // MEDICINE goes with the items (the companion's pockets); key items: a 0-terminated list of their own ids (0x200 + n).
        val medicine = pairs(cfg.numMedicine, cfg.medicineMax, POCKET_ITEMS) ?: return emptyList()
        val berries = pairs(cfg.numBerries, cfg.berriesMax, POCKET_BERRIES) ?: return emptyList()
        val keyRaw = r.readCoreMemory(cfg.numKeyItems, cfg.keyItemsMax + 1)
        val n = (0..cfg.keyItemsMax).firstOrNull { u8(keyRaw, it) == 0 } ?: return emptyList()
        val keys = (0 until n).map { Item(0x200 + u8(keyRaw, it), 1, POCKET_KEY_ITEMS) }
        val bits = r.readCoreMemory(cfg.tmsHms, (cfg.tmItems.size + 7) / 8)
        val tms = cfg.tmItems.indices.filter { (u8(bits, it / 8) shr (it % 8)) and 1 != 0 }
            .map { Item(cfg.tmItems[it], 1, POCKET_TM_HM) }
        return (items + medicine + balls + berries).filter { it.itemId in 1..0xFE } + keys + tms
    }
    val keyRaw = r.readCoreMemory(cfg.numKeyItems, 2 + cfg.keyItemsMax)
    val nKeys = u8(keyRaw, 0)
    if (nKeys > cfg.keyItemsMax || u8(keyRaw, 1 + nKeys) != 0xFF) return emptyList()
    val keys = (0 until nKeys).map { Item(u8(keyRaw, 1 + it), 1, POCKET_KEY_ITEMS) }
    if (cfg.tmBits) {
        // A bit per TM / HM, TM01 first (Prism's FlagAction on wTMsHMs): each one the player has, once.
        val bits = r.readCoreMemory(cfg.tmsHms, (cfg.tmItems.size + 7) / 8)
        val tms = cfg.tmItems.indices.filter { (u8(bits, it / 8) shr (it % 8)) and 1 != 0 }
            .map { Item(cfg.tmItems[it], 1, POCKET_TM_HM) }
        return (items + balls + keys).filter { it.itemId in 1..0xFE } + tms
    }
    val tmRaw = r.readCoreMemory(cfg.tmsHms, GEN2_TMS_HMS)
    if ((0 until GEN2_TMS_HMS).any { u8(tmRaw, it) > 99 }) return emptyList()
    val tms = (0 until GEN2_TMS_HMS).filter { u8(tmRaw, it) > 0 }
        .map { Item(cfg.tmItems[it], u8(tmRaw, it), POCKET_TM_HM) }
    return (items + balls + keys).filter { it.itemId in 1..0xFE } + tms
}

/** The seen / caught flags: a bit per species (bit n-1 = species n), as dex numbers (Prism's are its own list's). */
fun readGen2PokedexState(r: MemoryReader, cfg: Gen2Config, tables: PokedexTables): PokedexState {
    val g = tables.gen2
    fun bits(b: ByteArray) = (1..cfg.speciesCount).filter { n -> (b[(n - 1) / 8].toInt() shr ((n - 1) % 8)) and 1 != 0 }
        .map { g?.dexOf(it) ?: it }.filter { it in 1..tables.nationalCount }.toSet()
    val bytes = (cfg.speciesCount + 7) / 8   // 32; Polished Crystal's 291 (by the species' index: its 9-bit value - 1)
    val caught = bits(r.readCoreMemory(cfg.dexCaught, bytes))
    return PokedexState(tables, bits(r.readCoreMemory(cfg.dexSeen, bytes)) + caught, caught, national = true)
}

fun readGen2Telemetry(r: MemoryReader, cfg: Gen2Config): Telemetry {
    val countRaw = r.readCoreMemory(cfg.partyCount, 1 + PARTY_SIZE)
    // wPartySpecies ends in 0xFF after the count: anything else is WRAM before a save is loaded.
    // (Polished Crystal keeps no species list after it: 7 unused bytes.)
    val count = u8(countRaw, 0).takeIf { it <= PARTY_SIZE && (cfg.polished || it == PARTY_SIZE || u8(countRaw, 1 + it) == 0xFF) } ?: 0
    val partyRaw = r.readCoreMemory(cfg.partyMons, PARTY_SIZE * GEN2_PARTY_MON_SIZE)
    // wPartyMonNicknames: 6 x 11 bytes, after wPartyMons and wPartyMonOT.
    val nicknames = r.readCoreMemory(cfg.partyMons + GEN2_NICKNAMES_AFTER_MONS, PARTY_SIZE * GEN2_NAME_LEN)
    val party = (0 until count).mapIndexedNotNull { i, _ ->
        val mon = if (cfg.polished) decodePolishedPartyMon(partyRaw, i * GEN2_PARTY_MON_SIZE, cfg)
        else decodeGen2PartyMon(partyRaw, i * GEN2_PARTY_MON_SIZE, cfg, egg = u8(countRaw, 1 + i) == GEN2_EGG)
        mon?.copy(nickname = gen2Nickname(nicknames, i * GEN2_NAME_LEN, cfg.polished))
    }

    val items = readGen2Bag(r, cfg)

    val battle = u8(r.readCoreMemory(cfg.battleMode, 1), 0)
    val inBattle = battle == 1 || battle == 2
    val battleMons = MutableList(4) { EMPTY_BATTLE_MON }
    var enemyParty = emptyList<Mon>()
    var enemyActive = -1
    if (inBattle) {
        fun battler(addr: Long) = if (cfg.polished) decodePolishedBattleMon(r.readCoreMemory(addr, POLISHED_BATTLE_MON_SIZE))
            else decodeGen2BattleMon(r.readCoreMemory(addr, GEN2_BATTLE_MON_SIZE), cfg.speciesCount)
        battler(cfg.battleMon)?.let { battleMons[BATTLE_POS_PLAYER_LEFT] = it }
        battler(cfg.enemyMon)?.let { battleMons[BATTLE_POS_OPPONENT_LEFT] = it }
        if (battle == 2) {
            val ot = r.readCoreMemory(cfg.otPartyCount, 1 + PARTY_SIZE)
            val n = u8(ot, 0).coerceAtMost(PARTY_SIZE)
            val raw = r.readCoreMemory(cfg.otPartyMons, PARTY_SIZE * GEN2_PARTY_MON_SIZE)
            enemyParty = (0 until n).mapNotNull {
                if (cfg.polished) decodePolishedPartyMon(raw, it * GEN2_PARTY_MON_SIZE, cfg) else decodeGen2PartyMon(raw, it * GEN2_PARTY_MON_SIZE, cfg)
            }
            enemyActive = u8(r.readCoreMemory(cfg.curOTMon, 1), 0).takeIf { it < enemyParty.size } ?: -1
        }
    }

    val map = if (cfg.coords < 0) r.readCoreMemory(cfg.mapGroup, 4)
        else r.readCoreMemory(cfg.mapGroup, 2) + r.readCoreMemory(cfg.coords, 2)
    val backup = r.readCoreMemory(cfg.backupMapGroup, 2)
    val group = u8(map, 0)
    val num = u8(map, 1)
    val t = r.readCoreMemory(cfg.gameTime, 5)
    val frames = (((be16(t, 0) * 60L + u8(t, 2)) * 60L + u8(t, 3)) * 60L + u8(t, 4))
    val m = r.readCoreMemory(cfg.money, 3)
    return Telemetry(
        frameCounter = frames,
        inBattle = inBattle,
        isDoubleBattle = false,
        mapGroup = group,
        mapNum = num,
        regionMapSectionId = gen2Landmark(cfg, group, num, u8(backup, 0), u8(backup, 1)),
        x = u8(map, 3),
        y = u8(map, 2),
        facingDirection = 0,
        partyCount = party.size,
        party = party,
        battleMons = battleMons,
        battleInputState = if (inBattle) readGen2BattleInput(r, cfg).first else BATTLE_INPUT_NONE,
        itemCount = items.size,
        items = items,
        enemyParty = enemyParty,
        enemyActive = enemyActive,
        money = ((u8(m, 0).toLong() shl 16) or (u8(m, 1).toLong() shl 8) or u8(m, 2).toLong()),
        playerGender = if (cfg.playerGender < 0) 0 else u8(r.readCoreMemory(cfg.playerGender, 1), 0) and 1,
        pokedex = cfg.pokedex?.let { runCatching { readGen2PokedexState(r, cfg, it) }.getOrNull() },
        // The GUIDE's flags, badges and clock (Polished Crystal's: GuidePolishedCrystal.kt).
        progress = if (cfg.polished) runCatching { readPolishedProgress(r) }.getOrNull() else null,
    )
}

/** A Game Boy game (the Gen 1 and Gen 2 games): the black-on-white text-box look, plain white screens, the 2x2 battle menu. */
val GameKind?.isGameBoyGame: Boolean get() = isGen1 || isGen2

/** Crystal or a hack of it (Crystal Clear, Prism, Polished Crystal). */
val GameKind?.isCrystalFamily: Boolean get() = this == GameKind.CRYSTAL || this == GameKind.CRYSTAL_CLEAR || this == GameKind.PRISM ||
    this == GameKind.POLISHED_CRYSTAL

/** Gold, Silver: retail Gen 2 before Crystal, one RAM layout and one look ([GEN2_GOLD]). */
val GameKind?.isGoldSilver: Boolean get() = this == GameKind.GOLD || this == GameKind.SILVER

/** A Gen 2 game (Gold, Silver, the Crystal family): Gen 2's reader ([readGen2Telemetry]), PACK, POKéGEAR maps and battle menus. */
val GameKind?.isGen2: Boolean get() = isCrystalFamily || isGoldSilver

