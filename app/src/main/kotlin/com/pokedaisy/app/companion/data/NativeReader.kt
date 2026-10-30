package com.pokedaisy.app.companion.data

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Rebuilds a Telemetry snapshot by polling a Gen 3 game's own RAM globals: how
 * every GBA game is read. Kotlin port of tools/telemetry-viewer/native.go - keep in sync.
 */

// pokeemerald-expansion's enum Pocket (include/constants/item.h, built with
// I_COMBINE_BAG_POCKETS=TRUE - confirmed from Heart and Soul v2.0.6's own
// source, and live: Potions in pocket 1, key items in 5): POCKET_ITEMS=0/POCKET_MEDICINE=1/POCKET_POKE_BALLS=2/POCKET_TM_HM=3/
// POCKET_BERRIES=4/POCKET_KEY_ITEMS=5 - SIX pockets, not five. There's no
// separate "medicine" bucket in our stable POCKET_* set, so it folds into
// POCKET_ITEMS same as everything else that isn't balls/TMs/berries/key items.
val HNS_BAG_POCKET_ORDER =
    listOf(POCKET_ITEMS, POCKET_ITEMS, POCKET_POKE_BALLS, POCKET_TM_HM, POCKET_BERRIES, POCKET_KEY_ITEMS)

data class NativeConfig(
    val playerParty: Long,
    val playerPartyCount: Long,
    val battleMons: Long,
    val battlerPositions: Long,
    val battlersCount: Long,
    val battleTypeFlags: Long,
    val gMain: Long,
    val saveBlock1Ptr: Long,
    val saveBlock2Ptr: Long,
    val objectEvents: Long,
    val mapHeader: Long,
    val bagPockets: Long,
    val bagPocketCount: Int,
    val encryptionKeyOff: Long,
    // bagPocketOrder[p] is the stable POCKET_* id (see Telemetry.kt) that
    // native gBagPockets[p] holds for THIS game's engine - the array's
    // index-to-pocket assignment is compiled-in per game and FireRed/Emerald
    // order it differently (confirmed by reading both decomps' item.c:
    // FireRed is Items/KeyItems/PokeBalls/TMCase/BerryPouch, Emerald is
    // Items/PokeBalls/TMHM/Berries/KeyItems - same 5 pockets, different array
    // positions). Defaults to FireRed's order since every FireRed-engine
    // config (rev0/rev1/Unbound/Gaia/Radical Red) shares it unmodified.
    val bagPocketOrder: List<Int> = FIRERED_BAG_POCKET_ORDER,
    // ROM addresses of the icon-graphics tables (0 = not known for this game →
    // the app falls back to bundled sprites, if any). Fixed retail addresses
    // read from a vanilla decomp build's ELF.
    val monIconTable: Long = 0,
    val monIconPaletteTable: Long = 0,
    val monIconPaletteIndices: Long = 0,
    val itemIconTable: Long = 0,
    // See IconTables' own comment - only expansion-based hacks change these.
    val monIconStride: Int = 4,
    val monPalIdxStride: Int = 1,
    val monPalIdxMask: Int = 0xFF,
    val itemIconStride: Int = 8,
    val itemPalCompressed: Boolean = true,
    // See IconTables.extraItemIconTable (Emerald Rogue's own items).
    val extraItemIconTable: Long = 0,
    // See IconTables.monIconPalettes (SoulGold's per-species icon palettes).
    val monIconPalettes: Long = 0,
    val extraItemFirst: Int = 0,
    val extraItemCount: Int = 0,
    val extraItemStride: Int = 8,
    // sizeof(struct Pokemon): gPlayerParty's stride. Emerald Rogue's is 104
    // (a RoguePartyMon tail after vanilla's 100 bytes, which are unchanged).
    val monStride: Int = MON_STRUCT_SIZE,
    // SaveBlock2 offset of a name the game prints for mapsec 0 instead of the
    // table's (Emerald Rogue's hub, ROGUE_HUB_NAME_OFF); -1 = none.
    val hubNameOff: Long = -1,
    // Newer pokeemerald-expansion packs extra bits next to these BoxPokemon
    // fields (species:11|teraType:5, move:11|evoTracker:5, pp:7|...) - the
    // checksum/decryption is unchanged, only the value needs masking.
    val speciesMask: Int = 0xFFFF,
    val moveMask: Int = 0xFFFF,
    val ppMask: Int = 0xFF,
    // Bottom-screen touch battle control (PLAN.md Phase 5): addresses for the
    // gBattlerControllerFuncs[] scan (readNativeBattleInputFast says why a scan,
    // not a single index read). 0 = not known for this game -> touch battle
    // control just stays disabled.
    val battlerControllerFuncs: Long = 0,
    val handleInputChooseAction: Long = 0,
    val handleInputChooseMove: Long = 0,
    // Bag/Party screen detection (for a BACK button while either is open): the
    // callbacks that wait while the bag / party is up, not
    // OpenBagAndChooseItem/OpenPartyMenuToChooseMon (which only run once).
    val completeWhenChoseItem: Long = 0,
    val waitForMonSelection: Long = 0,
    // "Which mon does this move hit" target-select screen (reachable even in
    // a single battle for MOVE_TARGET_USER_OR_SELECTED moves) - without this,
    // such a move's touch-selected confirm silently stalls one screen short.
    val handleInputChooseTarget: Long = 0,
    // gActionSelectionCursor / gMoveSelectionCursor (a cell per battler): touch picks go straight to
    // their cell from these (BattleInputController.cursorAddrs). 0 = unknown, the picks walk there blind.
    // Only on configs checked headless (and zeroed on the ones copying them whose RAM differs).
    val actionCursor: Long = 0,
    val moveCursor: Long = 0,
    // SaveBlock1 offset of `pos` (mapGroup/mapNum follow at +4/+5). Heart and
    // Soul puts 4 extra bytes at the start of SaveBlock1, so it's 4 there.
    val saveBlock1PosOff: Long = 0,
    // SaveBlock2 offset of playerGender (after the 8-byte name). Recharged Emerald Rebalanced puts 4 bytes in front
    // of SaveBlock2's fields (and SaveBlock1's: [saveBlock1PosOff]), so it's 0xC there.
    val playerGenderOff: Long = 8,
    // SaveBlock1 offset of `money` (XOR SaveBlock2.encryptionKey, like bag
    // quantities); -1 = not known for this game, so no money is shown.
    val moneyOff: Long = -1,
    // struct BattlePokemon's size and field offsets (see BattleMonLayout).
    val battleMonLayout: BattleMonLayout = VANILLA_BATTLE_MON,
    // struct Pokemon's party fields and flags byte (see PartyMonLayout).
    val partyMonLayout: PartyMonLayout = VANILLA_PARTY_MON,
    // gMapHeader.regionMapSectionId is a u16 (SoulGold: 314 sections), which
    // moves cave / weather / mapType a byte later (mapType at +0x18).
    val mapSecWide: Boolean = false,
    // The POKéDEX tab's ROM tables + flag offsets (Pokedex.kt); null = no tab.
    val pokedex: PokedexTables? = null,
    // The GUIDE's ROM tables + save flag/var offsets (GuideRom.kt); null = no HERE / NEXT BOSS.
    val guideTables: GuideTables? = null,
    // Where the ROM keeps the item descriptions the ITEMS tab shows (RomItemText.kt); null = none.
    val itemDescs: ItemDescTable? = null,
    // The species whose icon is the egg's, for games where an egg keeps its own
    // species in the struct (gSpeciesInfo's unnamed entry at the end: SoulGold
    // 1578, Lazarus 1561); 0 = none found (the egg's species icon then).
    val eggSpecies: Int = 0,
    // gPartyMenu, for the battle POKéMON pane's switch where the activity takes
    // it from the config (SoulGold's releases); 0 = not here.
    val partyMenu: Long = 0,
    // USE / GIVE / TAKE's functions (FieldItems.kt) for a build with no retail code to key them on (Recharged
    // Emerald's gcc rebuilds); null = by game kind and header code (fieldItemsForGame).
    val fieldItems: FieldItemFns? = null,
    // gEnemyParty (6 encrypted Pokémon structs, like gPlayerParty) for the
    // battle's FOE TEAM; 0 = not known for this game.
    val enemyParty: Long = 0,
    // Which of those is out, and which comes next: gBattlerPartyIndexes (u16
    // per battler) and gBattleStruct (a pointer) -> monToSwitchIntoId[battler]
    // at [monToSwitchIntoOff] - where Cmd_switchhandleorder puts the trainer's
    // pick before "... is about to use X". 0 = not known.
    val battlerPartyIndexes: Long = 0,
    val battleStructPtr: Long = 0,
    val monToSwitchIntoOff: Int = 0,
    // Ruby/Sapphire's BattleStruct isn't behind a pointer: it sits at a fixed
    // address (gSharedMem), and [battleStructPtr] is that address itself.
    val battleStructStatic: Boolean = false,
    // Ruby/Sapphire have no gSaveBlock1Ptr/2Ptr: the blocks sit at fixed
    // addresses, and [saveBlock1Ptr]/[saveBlock2Ptr] hold those addresses.
    val staticSaveBlocks: Boolean = false,
    // gMain.inBattle's byte (bit 1): FireRed/Emerald's struct Main has it at
    // +0x439, Ruby/Sapphire's (4 bytes more before it) at +0x43D.
    val inBattleOff: Long = 0x439,
    // Where the save keeps the TRAINER CARD's data (TrainerCard.kt); null = no
    // CARD tab. Retail FireRed / LeafGreen / Emerald only:
    // the hacks below copy these configs and their saves differ.
    val trainerCard: TrainerCardSave? = null,
    // The ROM's language letter (game code BPE<S|D|F|I>): a European Emerald's
    // names come from its own tables (EmeraldLanguages.kt). 'E' = English.
    val language: Char = 'E',
    // Its game code, when it has names of its own (GameText): "BPED", "AXVF".
    val gameCode: String = "",
    // A bag kept as a bit stream in SaveBlock1 instead of gBagPockets (Quetzal); null = gBagPockets.
    val bag3: Bag3Layout? = null,
    // Quetzal Alpha 9 v1's "BAG4" record, read before [bag3] (v1 rewrites a v0 save's BAG3 into it on load).
    val bag4: Bag4Layout? = null,
    // A bag kept as one "owned" bit per item id in SaveBlock1, no counts (Elite Redux); null = gBagPockets.
    val itemFlagsBag: ItemFlagsBag? = null,
    // Map groups whose sections are named from a second table (Quetzal's Johto): read as 0x100 + id.
    val altMapSecGroups: IntRange? = null,
    // The game's money cap: Gen 3's 999,999 (Glazed's AddMoney caps at 9,999,999); above it is a wrong read.
    val maxMoney: Long = 999_999L,
    // Quetzal's IDIOMA options pick each kind of name's language from the save ([readQuetzalNames]);
    // this is LUGARES' default when unset - its release's language (0 English, 1 Spanish). -1 = no such options.
    val quetzalPlacesDefault: Int = -1,
    // Quetzal Alpha 0.8.4 (ActiveTables' quetzalV084): Hoenn on Emerald's map, its map names in one language, its moves.
    val quetzalV084: Boolean = false,
    // Quetzal Alpha 9 v1 (ActiveTables' quetzalV1): v0's names but the Latin American ones of Johto's key items.
    val quetzalV1: Boolean = false,
    /** The game's shiny check, where it's known: shiny when the shiny value is below this (vanilla's 8); 0 = never shown.
     * The battle's SHINY marks and the shiny icons (party, summary, HUD, battle) go by it. Set by the Poller on retail
     * FireRed / Emerald only (by their hash), never on a config a hack copies. */
    val shinyBelow: Int = 0,
    /** gMonPaletteTable, for the shiny icons where [pokedex] (which has it) isn't mapped: English FireRed rev 0. */
    val monPalettes: Long = 0,
    /** gPokemonStoragePtr, for the BOXES tab ([readPcBoxes]); 0 = no tab. Set by the Poller on retail FireRed / LeafGreen /
     * Emerald (gSaveBlock2Ptr + 4), in the config where a hack's was checked. */
    val pokemonStoragePtr: Long = 0,
    /** How the boxes behind [pokemonStoragePtr] are laid out (SoulGold's plaintext ones, CFRU's compressed ones). */
    val pcStorage: PcStorageLayout = VANILLA_PC_STORAGE,
) {
    /** [partyMonLayout] with [shinyBelow] as its shiny check, where the layout has none of its own. */
    val monLayout: PartyMonLayout
        get() = if (shinyBelow > 0 && partyMonLayout.shinyBelow == 0) partyMonLayout.copy(shinyBelow = shinyBelow) else partyMonLayout
    val gMainInBattle get() = gMain + inBattleOff
    val gMainVblankCtr get() = gMain + 0x24
    val iconTables get() = IconTables(
        monIconTable, monIconPaletteTable, monIconPaletteIndices, itemIconTable,
        monIconStride, monPalIdxStride, monPalIdxMask, itemIconStride, itemPalCompressed,
        extraItemIconTable, extraItemFirst, extraItemCount, extraItemStride, monIconPalettes,
        // Shiny icons where the game's shiny check is known: recoloured through the battle palettes (decodeMonIcon).
        battlePalettes = if (shinyBelow > 0) pokedex?.palettes ?: monPalettes else 0L,
    )
    val hasBattleInputAddrs get() = battlerControllerFuncs != 0L && handleInputChooseAction != 0L && handleInputChooseMove != 0L
}

// The game's notes: [config.lazarus] in gamedata/gen3/hacks/lazarus.toml.
val LAZARUS_BAG_POCKET_ORDER = EMERALD_BAG_POCKET_ORDER

// The game's notes: [config.soulgold] in gamedata/gen3/hacks/soulgold.toml.
val SOULGOLD_BAG_POCKET_ORDER = listOf(
    POCKET_ITEMS, POCKET_ITEMS, POCKET_ITEMS, POCKET_POKE_BALLS,
    POCKET_TM_HM, POCKET_ITEMS, POCKET_BERRIES, POCKET_KEY_ITEMS,
)

// The game's notes: [config.rowe] in gamedata/gen3/hacks/rowe.toml.
val ROWE_BAG_POCKET_ORDER = listOf(
    POCKET_ITEMS, POCKET_ITEMS, POCKET_POKE_BALLS, POCKET_ITEMS, POCKET_ITEMS, POCKET_ITEMS,
    POCKET_BERRIES, POCKET_ITEMS, POCKET_TM_HM, POCKET_KEY_ITEMS,
)

// The game's notes: [config.emerald_rogue] in gamedata/gen3/hacks/emerald_rogue.toml.
val ROGUE_BAG_POCKET_ORDER = listOf(
    POCKET_ITEMS, POCKET_ITEMS, POCKET_ITEMS, POCKET_ITEMS, POCKET_POKE_BALLS,
    POCKET_TM_HM, POCKET_BERRIES, POCKET_ITEMS, POCKET_KEY_ITEMS,
)

/** SaveBlock2.pokemonHubName: Rogue prints it for its hub (mapsec 0), which has no name of its own. */
const val ROGUE_HUB_NAME_OFF = 0xEE4L

// The game's notes: [config.tmt2] in gamedata/gen3/hacks/tmt2.toml.
val TMT2_BAG_POCKET_ORDER = listOf(
    POCKET_ITEMS, POCKET_ITEMS, POCKET_ITEMS, POCKET_POKE_BALLS, POCKET_TM_HM, POCKET_BERRIES, POCKET_KEY_ITEMS,
)

// The game's notes: [config.imperium] in gamedata/gen3/hacks/imperium.toml.
val IMPERIUM_BAG_POCKET_ORDER = listOf(
    POCKET_ITEMS, POCKET_ITEMS, POCKET_POKE_BALLS, POCKET_TM_HM, POCKET_BERRIES, POCKET_KEY_ITEMS,
)

/** Quetzal 0.8.4's 11 bag pockets (its bag screen's tabs: ITEMS, MEDICINE, POKé BALLS, BERRIES, TRAINING, EVO / FORMS,
 * BATTLE ITEMS, MEGA STONES, Z CRYSTALS, TERA SHARDS, KEY ITEMS - gItems' +0x12 pocket, 1-based). TMs aren't in it. */
val QUETZAL_V084_BAG_POCKET_ORDER = listOf(
    POCKET_ITEMS, POCKET_ITEMS, POCKET_POKE_BALLS, POCKET_BERRIES, POCKET_ITEMS, POCKET_ITEMS,
    POCKET_ITEMS, POCKET_ITEMS, POCKET_ITEMS, POCKET_ITEMS, POCKET_KEY_ITEMS,
)

/** Quetzal's IDIOMA options: the language each kind of name is shown in ([quetzalNames]):
 * 0 English, 1 Spanish, 2 Latin American Spanish (3, Portuguese, reads as English). */
data class QuetzalNames(val species: Int = 0, val moves: Int = 0, val items: Int = 0, val places: Int = 0)

/**
 * Quetzal's IDIOMA options, from SaveBlock2 - each read the way the game's own getter does
 * (disassembled in both releases): GetSpeciesName +0x2E0 bits 5-7 (1 or 2: its one Spanish
 * table), GetMoveName +0x2E1 bits 0-2, ItemId_GetName +0x2E2 bits 3-5 (1 Spanish, 2 Latin
 * American, 3 Portuguese), the map names +0x2F4 bits 1-3 (1-4 = English .. Portuguese, else
 * the release's default). Checked on the saves: Spanish everywhere in `quetzal_es`, English in
 * the English ones.
 */
fun readQuetzalNames(c: MemoryReader, cfg: NativeConfig): QuetzalNames {
    if (cfg.quetzalPlacesDefault < 0) return QuetzalNames()
    val sb2 = saveBlock2(c, cfg)
    if (sb2 !in 0x02000000L until 0x04000000L) return QuetzalNames(places = cfg.quetzalPlacesDefault)
    val b = c.readCoreMemory(sb2 + 0x2E0, 3)
    val p = (c.readCoreMemory(sb2 + 0x2F4, 1)[0].toInt() ushr 1) and 7
    fun lang(v: Int) = if (v in 1..2) v else 0
    return QuetzalNames(
        species = if (((b[0].toInt() ushr 5) and 7) in 1..2) 1 else 0,
        moves = lang(b[1].toInt() and 7),
        items = lang((b[2].toInt() ushr 3) and 7),
        // 0.8.4 has no LUGARES option: its map names are its build's language (gRegionMapEntries holds one).
        places = if (p in 1..4 && !cfg.quetzalV084) lang(p - 1) else cfg.quetzalPlacesDefault,
    )
}

// gBagPockets' eight pockets: Items, Medicine, Poké Balls, Battle Items, Berries, Treasures (evolution items), TMs, Key
// Items - its bag screen's order and each pocket's gItems.pocket (1-8).
val TEAM_ROCKET_BAG_POCKET_ORDER = listOf(
    POCKET_ITEMS, POCKET_ITEMS, POCKET_POKE_BALLS, POCKET_ITEMS, POCKET_BERRIES, POCKET_ITEMS, POCKET_TM_HM, POCKET_KEY_ITEMS,
)

/** Pokémon Dreamstone Mysteries' gBagPockets: seven, in its items' own pocket numbers (gItemsInfo +0x41: 1 Items,
 * 2 Medicine, 3 Battle Items, 4 Poké Balls, 5 Berries, 6 TMs, 7 Key Items), as its bag screen's tabs show them; Medicine
 * and Battle Items fold into the app's ITEMS, like the other hacks' extra pockets. */
val DREAMSTONE_BAG_POCKET_ORDER = listOf(
    POCKET_ITEMS, POCKET_ITEMS, POCKET_ITEMS, POCKET_POKE_BALLS, POCKET_BERRIES, POCKET_TM_HM, POCKET_KEY_ITEMS,
)

// The game's notes: [config.recharged_yellow_v1_8_0] in gamedata/gen3/hacks/recharged_yellow.toml.
val RECHARGED_YELLOW_BAG_POCKET_ORDER =
    listOf(POCKET_ITEMS, POCKET_ITEMS, POCKET_POKE_BALLS, POCKET_TM_HM, POCKET_BERRIES, POCKET_KEY_ITEMS)

/** Recharged Yellow's gItems slots 0..408 (409 on is other data). */
const val RECHARGED_YELLOW_ITEMS = 409

/** A Recharged Yellow ROM (any release), from its first bytes: its GF ROM header's SaveBlock1 size is
 * 0x3D94 (FireRed's 0x3D68) - for code that runs before detection on the ROM file alone (the music
 * renderer, [RegionMapWatch]). */
fun isRechargedYellowRom(head: ByteArray): Boolean = head.size >= GF_HEADER_SB1_SIZE + 4 &&
    String(head, 0xAC, 3, Charsets.US_ASCII) == "BPR" && u32le(head, GF_HEADER_SB1_SIZE) == RECHARGED_YELLOW_SB1_SIZE

private const val GF_HEADER_SB1_SIZE = 0x18C // struct GFRomHeader.saveBlock1Size
private const val RECHARGED_YELLOW_SB1_SIZE = 0x3D94L

// The game's notes: [config.mariomon_v1_5_1] in gamedata/gen3/hacks/mariomon.toml.
val MARIOMON_BAG_POCKET_ORDER = listOf(
    POCKET_ITEMS, POCKET_ITEMS, POCKET_POKE_BALLS, POCKET_TM_HM, POCKET_BERRIES, POCKET_KEY_ITEMS,
)

/** The egg's icon: the last gMonIconTable entry, 1402 in v1.5 (1401 before: no TIPTRON). */
const val MARIOMON_EGG = 1402

/** Every Super Mariomon release's config, newest first. Lazy: the configs live in NativeConfigsGen.kt,
 * whose vals read this file's (the bag pocket orders), so a plain val here could see them still null. */
val NATIVE_MARIOMON_ALL by lazy {
    listOf(NATIVE_MARIOMON_V1_5_2, NATIVE_MARIOMON_V1_5_1, NATIVE_MARIOMON_V1_3, NATIVE_MARIOMON_V1_2_2, NATIVE_MARIOMON_V1_2_1)
}

private const val MAP_HEADER_REGION_MAPSEC_OFF = 0x14L
private const val OBJECT_EVENT_FACING_OFF = 0x18L
private const val BATTLE_TYPE_DOUBLE = 0x0001
private const val BATTLE_TYPE_TRAINER = 0x0008
private const val BAG_POCKET_STRIDE = 8
private const val BAG_POCKET_SLOTS = 255 // a pocket's u8 capacity (SoulGold's Items pocket holds 150)
private const val BAG_MAX_ITEMS = 300

/** SaveBlock1's address: read through gSaveBlock1Ptr, or fixed (Ruby/Sapphire). */
internal fun saveBlock1(c: MemoryReader, cfg: NativeConfig): Long =
    if (cfg.staticSaveBlocks) cfg.saveBlock1Ptr else u32le(c.readCoreMemory(cfg.saveBlock1Ptr, 4), 0)

internal fun saveBlock2(c: MemoryReader, cfg: NativeConfig): Long =
    if (cfg.staticSaveBlocks) cfg.saveBlock2Ptr else u32le(c.readCoreMemory(cfg.saveBlock2Ptr, 4), 0)

internal fun u16le(b: ByteArray, off: Int) =
    ByteBuffer.wrap(b, off, 2).order(ByteOrder.LITTLE_ENDIAN).short.toInt() and 0xFFFF

internal fun u32le(b: ByteArray, off: Int) =
    (ByteBuffer.wrap(b, off, 4).order(ByteOrder.LITTLE_ENDIAN).int.toLong()) and 0xFFFFFFFFL

private const val BAG_REFRESH_EVERY = 6

/**
 * The player's money: SaveBlock1.money XOR SaveBlock2.encryptionKey (Ruby /
 * Sapphire store it plain). Null when [NativeConfig.moneyOff] isn't known or
 * the value can't be money.
 */
internal fun readMoney(c: MemoryReader, cfg: NativeConfig): Long? = runCatching {
    if (cfg.moneyOff < 0) return null
    val sb1 = saveBlock1(c, cfg)
    if (sb1 !in 0x02000000L until 0x04000000L) return null
    var key = 0L
    if (cfg.encryptionKeyOff >= 0) {
        val sb2 = saveBlock2(c, cfg)
        if (sb2 !in 0x02000000L until 0x04000000L) return null
        key = u32le(c.readCoreMemory(sb2 + cfg.encryptionKeyOff, 4), 0)
    }
    (u32le(c.readCoreMemory(sb1 + cfg.moneyOff, 4), 0) xor key).takeIf { it <= cfg.maxMoney }
}.getOrNull()
/** gMapHeader's map size (its layout's width / height, in metatiles) and mapType; null if unreadable. */
internal data class MapShape(val width: Int, val height: Int, val type: Int)

internal fun readMapShape(c: MemoryReader, mapHeader: Long, typeOff: Int = MAP_HEADER_MAP_TYPE_OFF): MapShape? = runCatching {
    val h = c.readCoreMemory(mapHeader, typeOff + 1)
    val layout = u32le(h, 0)
    if (layout !in 0x02000000L until 0x04000000L && layout !in 0x08000000L until 0x0A000000L) return null
    val l = c.readCoreMemory(layout, 8)
    val w = u32le(l, 0).toInt()
    val hgt = u32le(l, 4).toInt()
    if (w !in 1..MAX_MAP_SIDE || hgt !in 1..MAX_MAP_SIDE) return null
    MapShape(w, hgt, h[typeOff].toInt() and 0xFF)
}.getOrNull()

internal fun Telemetry.withMapShape(s: MapShape?): Telemetry =
    if (s == null) this else copy(mapWidth = s.width, mapHeight = s.height, mapType = s.type)

/** SaveBlock2.playerGender (right after the 8-byte name in every Gen 3 game: [NativeConfig.playerGenderOff]); -1 if unreadable. */
internal fun readPlayerGender(c: MemoryReader, cfg: NativeConfig): Int = runCatching {
    val sb2 = saveBlock2(c, cfg)
    if (sb2 !in 0x02000000L until 0x04000000L) return -1
    (c.readCoreMemory(sb2 + cfg.playerGenderOff, 1)[0].toInt() and 0xFF).takeIf { it <= 1 } ?: -1
}.getOrDefault(-1)

/** [NativeConfig.hubNameOff]'s name, or null. */
private fun readHubName(c: MemoryReader, cfg: NativeConfig): String? = runCatching {
    if (cfg.hubNameOff < 0) return null
    val sb2 = saveBlock2(c, cfg)
    if (sb2 !in 0x02000000L until 0x04000000L) return null
    Gen3Text.decode(c.readCoreMemory(sb2 + cfg.hubNameOff, 16)).trim().takeIf { it.isNotEmpty() && Gen3Text.UNKNOWN !in it }
}.getOrNull()

private const val MAP_HEADER_MAP_TYPE_OFF = 0x17
private const val MAX_MAP_SIDE = 1024

private var bagTick = 0
private var lastBag: List<Item> = emptyList()
private var bagValid = false

/** Forget the cached bag: it's process-wide, so a newly launched game (or the
 * next game's test) would otherwise show the previous game's bag until the
 * next throttled refresh. */
internal fun resetNativeBagCache() {
    bagTick = 0; lastBag = emptyList(); bagValid = false
    synchronized(PcBoxes::class) { lastStorageRaw = null; lastStorage = null }
}

fun readNativeTelemetry(client: MemoryReader, cfg: NativeConfig): Telemetry {
    val frameCounter = runCatching { u32le(client.readCoreMemory(cfg.gMainVblankCtr, 4), 0) }.getOrDefault(0L)

    // --- party ---
    val count = (client.readCoreMemory(cfg.playerPartyCount, 1)[0].toInt() and 0xFF).coerceAtMost(PARTY_SIZE)
    val party = mutableListOf<Mon>()
    if (count > 0) {
        val raw = client.readCoreMemory(cfg.playerParty, count * cfg.monStride)
        for (i in 0 until count) decodePartyMon(raw, i * cfg.monStride, cfg.monLayout)?.let {
            party.add(it.masked(cfg).withNativeGender(raw, i * cfg.monStride, cfg.partyMonLayout, cfg.eggSpecies))
        }
    }

    // --- battle --- (.inBattle is bit 1 of the byte at gMain+0x439)
    val inBattle = runCatching { (client.readCoreMemory(cfg.gMainInBattle, 1)[0].toInt() and 0x02) != 0 }
        .getOrDefault(false)
    var isDouble = false
    var isTrainer = false
    var trainerRead = false
    var foeBattler = -1
    val battleMons = MutableList(4) { emptyBattleMon() }
    // battleMons == 0: not found for this game - show the battle as on with no
    // mons rather than decoding whatever sits at another build's address.
    if (inBattle && cfg.battleMons != 0L) {
        runCatching { client.readCoreMemory(cfg.battleTypeFlags, 4) }
            .onSuccess {
                isDouble = (u32le(it, 0).toInt() and BATTLE_TYPE_DOUBLE) != 0
                isTrainer = (u32le(it, 0).toInt() and BATTLE_TYPE_TRAINER) != 0
                trainerRead = cfg.battleTypeFlags != 0L
            }
        val nRaw = runCatching { client.readCoreMemory(cfg.battlersCount, 1) }.getOrNull()
        val posRaw = runCatching { client.readCoreMemory(cfg.battlerPositions, 4) }.getOrNull()
        val layout = cfg.battleMonLayout
        val monsRaw = client.readCoreMemory(cfg.battleMons, 4 * layout.size)
        // A game that keeps shininess as bits of its own: never from the battlers' personality (matched below).
        val pidShiny = if (cfg.partyMonLayout.shinyBits < 0) cfg.shinyBelow else 0
        val n = (nRaw?.get(0)?.toInt()?.and(0xFF)?.takeIf { it in 1..4 }) ?: 4
        for (battler in 0 until n) {
            val pos = (posRaw?.get(battler)?.toInt()?.and(0xFF)?.takeIf { it < 4 }) ?: battler
            decodeBattleMon(monsRaw, battler * layout.size, layout, pidShiny)?.let { battleMons[pos] = it }
            if (pos == BATTLE_POS_OPPONENT_LEFT) foeBattler = battler
        }
    }

    // --- location ---
    var x = 0; var y = 0; var mapGroup = 0; var mapNum = 0
    runCatching {
        val sb1 = saveBlock1(client, cfg)
        if (sb1 in 0x02000000L until 0x04000000L) {
            val b = client.readCoreMemory(sb1 + cfg.saveBlock1PosOff, 6)
            x = u16le(b, 0); y = u16le(b, 2); mapGroup = b[4].toInt() and 0xFF; mapNum = b[5].toInt() and 0xFF
        }
    }
    val regionMapSectionId = runCatching {
        val b = client.readCoreMemory(cfg.mapHeader + MAP_HEADER_REGION_MAPSEC_OFF, 2)
        val id = if (cfg.mapSecWide) u16le(b, 0) else b[0].toInt() and 0xFF
        if (cfg.altMapSecGroups?.contains(mapGroup) == true) 0x100 + id else id
    }.getOrDefault(0)
    val facing = runCatching {
        client.readCoreMemory(cfg.objectEvents + OBJECT_EVENT_FACING_OFF, 1)[0].toInt() and 0x0F
    }.getOrDefault(0)

    // --- bag (throttled) ---
    bagTick++
    if (!bagValid || bagTick % BAG_REFRESH_EVERY == 0) {
        runCatching { readNativeBag(client, cfg) }.onSuccess { lastBag = it; bagValid = true }
    }
    val items = lastBag

    val battleInput = if (inBattle) readNativeBattleInputFast(client, cfg) else null

    val pokedex = cfg.pokedex?.let { runCatching { readPokedexState(client, cfg, it) }.getOrNull() }
    val progress = cfg.guideTables?.let { runCatching { readSaveProgress(client, cfg, it) }.getOrNull() }
    // A save that plays randomized wild Pokémon (Wishes of Tomorrow's randomizer): the GUIDE swaps them the same way.
    GuideRandomizer.update(cfg.guideTables, progress)
    // gEnemyParty: a trainer's whole party (the FOE TEAM strip), or the wild
    // one(s) - read in every battle for the foes' IVs.
    val battleFoes = if (inBattle && cfg.enemyParty != 0L) runCatching {
        val raw = client.readCoreMemory(cfg.enemyParty, PARTY_SIZE * cfg.monStride)
        (0 until PARTY_SIZE).mapNotNull { i -> decodePartyMon(raw, i * cfg.monStride, cfg.monLayout)?.masked(cfg) }
            .filter { it.species != 0 }
    }.getOrDefault(emptyList()) else emptyList()
    // A game whose battlers don't tell (Elite Redux and R.O.W.E. keep shininess as bits of the party record alone): the
    // foe's SHINY marks from its gEnemyParty record, matched by species, level and HP.
    if (cfg.shinyBelow > 0 && (cfg.battleMonLayout.personality < 0 || cfg.partyMonLayout.shinyBits >= 0)) {
        for (pos in listOf(BATTLE_POS_OPPONENT_LEFT, BATTLE_POS_OPPONENT_RIGHT)) {
            val b = battleMons[pos].takeIf { it.species != 0 } ?: continue
            val mon = battleFoes.firstOrNull { it.species == b.species && it.level == b.level && it.hp == b.hp } ?: continue
            if (mon.shiny) battleMons[pos] = b.copy(shiny = true)
        }
    }
    val enemyParty = if (isTrainer) battleFoes else emptyList()
    // The foe (left) that's out, and the one it's about to send in.
    var enemyActive = -1
    var enemyNext = -1
    if (enemyParty.isNotEmpty() && foeBattler >= 0 && cfg.battlerPartyIndexes != 0L) runCatching {
        enemyActive = u16le(client.readCoreMemory(cfg.battlerPartyIndexes + foeBattler * 2L, 2), 0)
            .takeIf { it in enemyParty.indices } ?: -1
        val bs = if (cfg.battleStructStatic) cfg.battleStructPtr else u32le(client.readCoreMemory(cfg.battleStructPtr, 4), 0)
        if (bs in 0x02000000L until 0x04000000L) {
            enemyNext = (client.readCoreMemory(bs + cfg.monToSwitchIntoOff + foeBattler, 1)[0].toInt() and 0xFF)
                .takeIf { it in enemyParty.indices } ?: -1
        }
    }

    return Telemetry(
        frameCounter = frameCounter,
        inBattle = inBattle,
        isDoubleBattle = isDouble,
        mapGroup = mapGroup,
        mapNum = mapNum,
        regionMapSectionId = regionMapSectionId,
        x = x,
        y = y,
        facingDirection = facing,
        partyCount = party.size,
        party = party,
        battleMons = battleMons,
        itemCount = items.size,
        items = items,
        icons = cfg.iconTables,   // 0s for games we don't have addresses for
        battleActiveBattler = battleInput?.first ?: 0,
        battleInputState = battleInput?.second ?: BATTLE_INPUT_NONE,
        pokedex = pokedex,
        progress = progress,
        guideTables = cfg.guideTables,
        enemyParty = enemyParty,
        battleFoes = battleFoes,
        isTrainerBattle = if (inBattle && trainerRead) isTrainer else null,
        enemyActive = enemyActive,
        enemyNext = enemyNext,
        money = readMoney(client, cfg),
    ).withMapShape(readMapShape(client, cfg.mapHeader, if (cfg.mapSecWide) MAP_HEADER_MAP_TYPE_OFF + 1 else MAP_HEADER_MAP_TYPE_OFF)).copy(
        playerGender = readPlayerGender(client, cfg),
        mapSecName = if (regionMapSectionId == 0) readHubName(client, cfg) else null,
        trainerCard = readTrainerCard(client, cfg),
        pcBoxes = if (cfg.pokemonStoragePtr != 0L) readPcBoxes(client, cfg) else null,
    )
}

// struct PokemonStorage (pokefirered's include/pokemon_storage_system.h): u8 currentBox (+ 3 bytes of padding), then
// boxes[14][30] of BoxPokemon, boxNames[14][9], boxWallpapers[14].
const val PC_BOXES = 14
const val PC_BOX_SLOTS = 30
private const val PC_BOX_NAME_LEN = 9
private const val PC_STORAGE_MONS_OFF = 4

/**
 * How a game keeps its PC boxes ([readPcBoxes]): vanilla's struct PokemonStorage behind [NativeConfig.pokemonStoragePtr]
 * with [boxes] boxes of [recordSize]-byte records in [format], or CFRU's 25 boxes in its own places ([cfru]).
 */
data class PcStorageLayout(
    val boxes: Int = PC_BOXES,
    val recordSize: Int = BOX_MON_SIZE,
    val format: BoxRecordFormat = BoxRecordFormat.ENCRYPTED,
    val cfru: CfruStorage? = null,
) {
    /** The struct's bytes: current box, the records, the names, the wallpapers (CFRU's stays vanilla's size). */
    val size: Int get() = if (cfru != null) CFRU_STORAGE_SIZE else PC_STORAGE_MONS_OFF + boxes * PC_BOX_SLOTS * recordSize + boxes * (PC_BOX_NAME_LEN + 1)
}

/**
 * CFRU's PC (Complete-Fire-Red-Upgrade's src/pokemon_storage_system.c): 25 boxes of 58-byte CompressedPokemon. Boxes
 * 1-19 fill vanilla's struct PokemonStorage at [storage] (its 14 x 30 x 80 bytes hold 19 x 30 x 58), boxes 20-25 sit at
 * [extraBoxes] (one address per box); boxes 1-14's names and wallpapers are vanilla's, boxes 15-25's names count back
 * from box 1's (9 bytes each) and their wallpapers follow box 19's last record. The game's code hardcodes it all, so
 * the storage pointer must lead to [storage]. A record keeps no PP: they're worked out from the moves' base PP in the
 * ROM's gBattleMoves ([movesTable], 12-byte records, PP at +4), as the game does (CalculatePPWithBonus).
 */
data class CfruStorage(val storage: Long, val extraBoxes: List<Long>, val movesTable: Long)

const val CFRU_PC_BOXES = 25
private const val CFRU_BOXES_IN_PLACE = 19
private const val CFRU_RECORD = 58
private const val CFRU_NAMES_OFF = 0x8344
private const val CFRU_WALLPAPERS_OFF = 0x83C2
private const val CFRU_STORAGE_SIZE = CFRU_WALLPAPERS_OFF + PC_BOXES
private const val CFRU_EXTRA_WALLPAPERS_OFF = PC_STORAGE_MONS_OFF + CFRU_BOXES_IN_PLACE * PC_BOX_SLOTS * CFRU_RECORD

val VANILLA_PC_STORAGE = PcStorageLayout()

/** SoulGold: 15 boxes of its 76-byte plaintext BoxPokemon (its 96-byte struct Pokemon up to the status; headless on the
 * user's save, `soulgold_party`: 31 boxed Pokémon, each one's stats worked out the game's way equal to a party copy's). */
val SOULGOLD_PC_STORAGE = PcStorageLayout(boxes = 15, recordSize = 0x4C, format = BoxRecordFormat.PLAIN)

/** Unbound (CFRU's own addresses): checked headless - the game's SendMonToPC put a party Pokémon at box 1's, 20's, 23's
 * and 25's place with the current box set before each, record for record equal to it. */
val UNBOUND_PC_STORAGE = PcStorageLayout(
    boxes = CFRU_PC_BOXES, recordSize = CFRU_RECORD, format = BoxRecordFormat.CFRU,
    cfru = CfruStorage(
        storage = 0x02029314L,
        extraBoxes = listOf(0x0203CB44L, 0x0203CB44L + 30 * 58, 0x0203CB44L + 60 * 58, 0x02027434L, 0x02027434L + 30 * 58, 0x02024638L),
        movesTable = 0x08A769AFL,
    ),
)

// The last storage read and what it decoded to: the boxes only change while the player is at a PC, so a sample whose
// bytes are the same hands back the same object (the BOXES tab then has nothing to redraw).
private var lastStorageRaw: ByteArray? = null
private var lastStorage: PcBoxes? = null

private fun MemoryReader.readInto(at: Long, raw: ByteArray, off: Int, len: Int) {
    var o = 0
    while (o < len) {
        val n = minOf(4096, len - o)
        readCoreMemory(at + o, n).copyInto(raw, off + o)
        o += n
    }
}

/**
 * The PC's boxes through [NativeConfig.pokemonStoragePtr] laid out as [NativeConfig.pcStorage] says, each slot decoded
 * like a party member's (gender, egg, shiny) with its level and stats worked out ([decodeBoxMon]: base stats and growth
 * rates from the ROM's DEX tables). Null when the pointer doesn't lead into EWRAM (or, CFRU, to its storage).
 */
internal fun readPcBoxes(c: MemoryReader, cfg: NativeConfig): PcBoxes? = runCatching {
    val layout = cfg.pcStorage
    val cfru = layout.cfru
    val rec = layout.recordSize
    val at = u32le(c.readCoreMemory(cfg.pokemonStoragePtr, 4), 0)
    if (at !in 0x02000000L until 0x04000000L - layout.size) return null
    if (cfru != null && at != cfru.storage) return null
    // The struct, then (CFRU) its boxes kept elsewhere, one after another.
    val boxBytes = PC_BOX_SLOTS * rec
    val raw = ByteArray(layout.size + (cfru?.extraBoxes?.size ?: 0) * boxBytes)
    c.readInto(at, raw, 0, layout.size)
    cfru?.extraBoxes?.forEachIndexed { i, a -> c.readInto(a, raw, layout.size + i * boxBytes, boxBytes) }
    synchronized(PcBoxes::class) {
        if (raw.contentEquals(lastStorageRaw)) lastStorage?.let { return it }
    }
    val dex = cfg.pokedex
    val base = { species: Int -> dex?.let { PokedexSource.baseStats(it, species) } }
    val growth = { species: Int -> dex?.let { PokedexSource.growthRate(it, species) } }
    val pp = HashMap<Int, Int?>()
    val movePp = { move: Int ->
        pp.getOrPut(move) {
            cfru?.let { runCatching { c.readCoreMemory(it.movesTable + 12L * move + 4, 1)[0].toInt() and 0xFF }.getOrNull() }
        }
    }
    fun slot(b: Int, s: Int): Int = when {
        cfru == null || b < CFRU_BOXES_IN_PLACE -> PC_STORAGE_MONS_OFF + (b * PC_BOX_SLOTS + s) * rec
        else -> layout.size + ((b - CFRU_BOXES_IN_PLACE) * PC_BOX_SLOTS + s) * rec
    }
    val namesOff = PC_STORAGE_MONS_OFF + layout.boxes * PC_BOX_SLOTS * rec
    fun name(b: Int): Int = when {
        cfru == null -> namesOff + b * PC_BOX_NAME_LEN
        b < PC_BOXES -> CFRU_NAMES_OFF + b * PC_BOX_NAME_LEN
        else -> CFRU_NAMES_OFF - (b - PC_BOXES + 1) * PC_BOX_NAME_LEN
    }
    fun wallpaper(b: Int): Int = when {
        cfru == null -> namesOff + layout.boxes * PC_BOX_NAME_LEN + b
        b < PC_BOXES -> CFRU_WALLPAPERS_OFF + b
        else -> CFRU_EXTRA_WALLPAPERS_OFF + (b - PC_BOXES)
    }
    val boxes = List(layout.boxes) { b ->
        PcBox(
            name = Gen3Text.decode(raw, name(b), PC_BOX_NAME_LEN),
            wallpaper = raw[wallpaper(b)].toInt() and 0xFF,
            mons = List(PC_BOX_SLOTS) { s ->
                val o = slot(b, s)
                decodeBoxMon(raw, o, cfg.monLayout, base, layout.format, growth, movePp)
                    ?.masked(cfg)?.withNativeGender(raw, o, cfg.partyMonLayout, cfg.eggSpecies)
            },
        )
    }
    PcBoxes(currentBox = (raw[0].toInt() and 0xFF).coerceIn(0, layout.boxes - 1), boxes = boxes).also {
        synchronized(PcBoxes::class) { lastStorageRaw = raw; lastStorage = it }
    }
}.getOrNull()

/**
 * Pokémon Quetzal's bag: no gBagPockets, but a "BAG3" bit stream in SaveBlock1
 * (read off the game's own code, 0x0813C5B4-0x0813CDC0). Stream byte o sits at
 * SB1 + [off] + o up to [splitAt], at SB1 + [tailOff] + o after it; bits run
 * little-endian. A 16-byte header ("BAG3", u16 version 2, u16, then u16 counts:
 * item slots in all, key items in all, item slots in the bag, key items in the
 * bag - the slots past the bag's are the PC's), then 25-bit item slots from bit
 * 0x80 (id: 11 bits, quantity: 14 bits XOR the encryption key) and 11-bit key
 * item ids from bit [keyItemsBit]. Which pocket an item goes in is its gItems
 * entry's ([pockets], generated).
 */
data class Bag3Layout(
    val off: Long, val splitAt: Int, val tailOff: Long, val keyItemsBit: Int, val pockets: Map<Int, Int>,
)

/**
 * Quetzal Alpha 9 v1's bag, a "BAG4" record in three pieces of SaveBlock1 (its offsets 0..0xC07 at [off], 0xC08..0x10DF at
 * [midOff], the rest at [tailOff], as offsets from 0xC08 - the game's own accessor, 0x08142D40 in English v1). It keeps a
 * bag per campaign: byte 5 is the current one (0..2). Header: "BAG4", version 4 at +4; key items per bag at +8 / +9 / +0xA;
 * from +0x10, 30 u16 running totals of item entries, 10 pockets per bag. Item entry n is 22 bits at bit 0x300 * 8 + 22 n:
 * the low 8 an index into its pocket's ROM list of item ids ([lists] / [counts]: 11 lists, a pointer / u16 length each),
 * the high 14 the quantity XOR the encryption key. Key item j of bag g is a byte at 0x100 + 128 g + j: its index (low 7
 * bits) into the 11th list. Read off the game's code (the BAG4 check 0x08144F88, the entry and key readers) and checked
 * against v0's BAG3 of the same saves, item for item.
 */
data class Bag4Layout(
    val off: Long, val midOff: Long, val tailOff: Long, val lists: Long, val counts: Long, val pockets: Map<Int, Int>,
)

private fun readBag4(c: MemoryReader, cfg: NativeConfig, b: Bag4Layout): List<Item>? {
    val sb1 = saveBlock1(c, cfg)
    val sb2 = saveBlock2(c, cfg)
    if (sb1 !in 0x02000000L until 0x04000000L || sb2 !in 0x02000000L until 0x04000000L) return null
    val key = u32le(c.readCoreMemory(sb2 + cfg.encryptionKeyOff, 4), 0).toInt() and 0x3FFF
    val head = c.readCoreMemory(sb1 + b.off, 0xC08)
    val mid = c.readCoreMemory(sb1 + b.midOff, 0x4D8)
    val tail = c.readCoreMemory(sb1 + b.tailOff + 0x4D8, 0x400)
    fun byte(o: Int): Int = when {
        o < 0xC08 -> head.getOrElse(o) { 0 }
        o - 0xC08 < 0x4D8 -> mid.getOrElse(o - 0xC08) { 0 }
        else -> tail.getOrElse(o - 0xC08 - 0x4D8) { 0 }
    }.toInt() and 0xFF
    fun u16(o: Int) = byte(o) or (byte(o + 1) shl 8)
    if (byte(0) != 'B'.code || byte(1) != 'A'.code || byte(2) != 'G'.code || byte(3) != '4'.code || byte(4) != 4) return null
    val bag = byte(5).takeIf { it <= 2 } ?: return null
    val lists = c.readCoreMemory(b.lists, 11 * 4)
    val counts = c.readCoreMemory(b.counts, 11 * 2)
    fun itemAt(list: Int, index: Int): Int {
        if (index >= u16le(counts, 2 * list)) return 0
        return u16le(c.readCoreMemory(u32le(lists, 4 * list) + 2 * index, 2), 0)
    }
    val out = mutableListOf<Item>()
    var n = if (bag == 0) 0 else u16(0x10 + 2 * (10 * bag - 1))
    for (p in 0 until 10) {
        val end = u16(0x10 + 2 * (10 * bag + p))
        while (n < end && n < 0x5BC && out.size < BAG_MAX_ITEMS) {
            val bit = 0x300 * 8 + 22 * n
            var v = 0L
            for (k in 0 until 4) v = v or (byte((bit ushr 3) + k).toLong() shl (8 * k))
            val e = ((v ushr (bit and 7)) and 0x3FFFFF).toInt()
            val id = itemAt(p, e and 0xFF)
            if (id != 0) out.add(Item(id, ((e ushr 8) xor key) and 0x3FFF, b.pockets[id] ?: POCKET_ITEMS))
            n++
        }
    }
    for (j in 0 until minOf(byte(8 + bag), 128)) {
        val id = itemAt(10, byte(0x100 + 128 * bag + j) and 0x7F)
        if (id != 0) out.add(Item(id, 1, POCKET_KEY_ITEMS))
    }
    return out
}

private fun readBag3(c: MemoryReader, cfg: NativeConfig, b: Bag3Layout): List<Item> {
    val sb1 = saveBlock1(c, cfg)
    val sb2 = saveBlock2(c, cfg)
    if (sb1 !in 0x02000000L until 0x04000000L || sb2 !in 0x02000000L until 0x04000000L) return emptyList()
    val key = u32le(c.readCoreMemory(sb2 + cfg.encryptionKeyOff, 4), 0).toInt()
    val head = c.readCoreMemory(sb1 + b.off, b.splitAt)
    val tail = c.readCoreMemory(sb1 + b.tailOff + b.splitAt, (b.keyItemsBit + 11 * 256) / 8 + 8 - b.splitAt)
    fun byte(o: Int): Int = (if (o < b.splitAt) head.getOrElse(o) { 0 } else tail.getOrElse(o - b.splitAt) { 0 }).toInt() and 0xFF
    fun bits(pos: Int, n: Int): Int {
        var v = 0L
        for (k in 0 until 5) v = v or (byte((pos ushr 3) + k).toLong() shl (8 * k))
        return ((v ushr (pos and 7)) and ((1L shl n) - 1)).toInt()
    }
    if (byte(0) != 'B'.code || byte(1) != 'A'.code || byte(2) != 'G'.code || byte(3) != '3'.code) return emptyList()
    val bagSlots = byte(0xC) or (byte(0xD) shl 8)
    val bagKeys = byte(0xE) or (byte(0xF) shl 8)
    val out = mutableListOf<Item>()
    for (i in 0 until minOf(bagSlots, BAG_MAX_ITEMS)) {
        val v = bits(0x80 + 25 * i, 25)
        val id = v and 0x7FF
        if (id != 0) out.add(Item(id, ((v ushr 11) xor key) and 0x3FFF, b.pockets[id] ?: POCKET_ITEMS))
    }
    for (i in 0 until minOf(bagKeys, 256)) {
        val id = bits(b.keyItemsBit + 11 * i, 11)
        if (id != 0) out.add(Item(id, 1, POCKET_KEY_ITEMS))
    }
    return out
}

/**
 * Elite Redux's bag: no pockets of slots and no counts - every item is owned or not, a bit per item id in the u16 array
 * at SaveBlock1 + [off] (bit id % 16 of word id / 16), and the game lets an owned item be used without end. Which pocket
 * an item shows in is its gItems entry's ([pockets], generated). Only ids below [count] (ITEMS_COUNT) are items: the
 * save's union carries more past them. Each owned item reads as one.
 */
data class ItemFlagsBag(val off: Long, val count: Int, val pockets: Map<Int, Int>)

private fun readItemFlagsBag(c: MemoryReader, cfg: NativeConfig, b: ItemFlagsBag): List<Item> {
    val sb1 = saveBlock1(c, cfg)
    if (sb1 !in 0x02000000L until 0x04000000L) return emptyList()
    val raw = c.readCoreMemory(sb1 + b.off, (b.count + 7) / 8)
    val out = mutableListOf<Item>()
    for (id in 1 until b.count) {
        if ((raw[id / 8].toInt() shr (id % 8)) and 1 != 0) out.add(Item(id, 1, b.pockets[id] ?: POCKET_ITEMS))
        if (out.size >= BAG_MAX_ITEMS) break
    }
    return out
}

// gBagPockets: 5 pockets of { *itemSlots, u8 capacity }. Quantities XOR
// SaveBlock2.encryptionKey (retail FireRed obfuscates them; Unbound leaves it 0).
private fun readNativeBag(c: MemoryReader, cfg: NativeConfig): List<Item> {
    cfg.bag4?.let { b -> readBag4(c, cfg, b)?.let { return it } }
    cfg.bag3?.let { return readBag3(c, cfg, it) }
    cfg.itemFlagsBag?.let { return readItemFlagsBag(c, cfg, it) }
    var key = 0
    // encryptionKeyOff < 0: Ruby/Sapphire don't obfuscate bag quantities.
    if (cfg.encryptionKeyOff >= 0) runCatching {
        val sb2 = saveBlock2(c, cfg)
        if (sb2 in 0x02000000L until 0x04000000L) {
            key = u32le(c.readCoreMemory(sb2 + cfg.encryptionKeyOff, 4), 0).toInt() and 0xFFFF
        }
    }
    val pockets = c.readCoreMemory(cfg.bagPockets, cfg.bagPocketCount * BAG_POCKET_STRIDE)
    val out = mutableListOf<Item>()
    for (p in 0 until cfg.bagPocketCount) {
        val slotsPtr = u32le(pockets, p * BAG_POCKET_STRIDE)
        val capacity = pockets[p * BAG_POCKET_STRIDE + 4].toInt() and 0xFF
        if (slotsPtr < 0x02000000L || slotsPtr >= 0x04000000L || capacity == 0) continue
        val readSlots = minOf(capacity, BAG_POCKET_SLOTS)
        val raw = c.readCoreMemory(slotsPtr, readSlots * 4)
        val pocket = cfg.bagPocketOrder.getOrElse(p) { POCKET_ITEMS }
        var i = 0
        while (i < readSlots && out.size < BAG_MAX_ITEMS) {
            val id = u16le(raw, i * 4)
            if (id != 0) out.add(Item(id, u16le(raw, i * 4 + 2) xor key, pocket))
            i++
        }
    }
    return out
}

// Gen3 "NIDORAN♀" / "NIDORAN♂" (species 29 / 32) as stored in a nickname.
private val NIDORAN_F_NAME = byteArrayOf(0xC8.toByte(), 0xC3.toByte(), 0xBE.toByte(), 0xC9.toByte(), 0xCC.toByte(), 0xBB.toByte(), 0xC8.toByte(), 0xB6.toByte(), 0xFF.toByte())
private val NIDORAN_M_NAME = byteArrayOf(0xC8.toByte(), 0xC3.toByte(), 0xBE.toByte(), 0xC9.toByte(), 0xCC.toByte(), 0xBB.toByte(), 0xC8.toByte(), 0xB5.toByte(), 0xFF.toByte())

/**
 * The party-menu gender symbol for the native RAM path, computed the way the
 * game does (GetMonGender: personality & 0xFF vs the species' genderRatio;
 * DisplayPartyPokemonGender's Nidoran rule). Needs the game's own gBaseStats ratios, keyed by its own
 * species ids: retail FireRed/Emerald share [genderRatiosFireRed]; the CFRU
 * hacks have theirs in GenderRatiosCfru.kt.
 */
private fun Mon.withNativeGender(raw: ByteArray, off: Int, layout: PartyMonLayout, eggSpecies: Int = 0): Mon {
    val ratios = when (activeGame) {
        // Emerald shares FireRed's internal Gen3 species ids and base-stat gender ratios; so does
        // Recharged Yellow (gen_recharged_yellow_tables.py checks its gBaseStats against FireRed's).
        GameKind.FIRERED -> runningFireRedHack?.genderRatios ?: genderRatiosFireRed
        GameKind.EMERALD, GameKind.RECHARGED_YELLOW -> genderRatiosFireRed
        GameKind.UNBOUND -> genderRatiosUnbound
        GameKind.RADICAL_RED -> genderRatiosRadicalRed
        GameKind.ODYSSEY -> genderRatiosOdyssey
        GameKind.AMETHYST -> if (amethystV141) genderRatiosAmethystV141 else genderRatiosAmethyst
        GameKind.HEART_AND_SOUL -> genderRatiosHns
        GameKind.LAZARUS -> genderRatiosLazarus
        GameKind.SOULGOLD -> genderRatiosSoulGold
        GameKind.EMERALD_SEAGLASS -> genderRatiosSeaglass
        GameKind.GLAZED -> genderRatiosGlazed
        GameKind.IMPERIUM -> genderRatiosImperium
        GameKind.QUETZAL -> genderRatiosQuetzal
        GameKind.ROWE -> genderRatiosRowe
        GameKind.ORANGE_ISLANDS -> genderRatiosOrangeIslands
        GameKind.SWORD_SHIELD -> genderRatiosSwordShield
        GameKind.GS_CHRONICLES -> genderRatiosGsChronicles
        GameKind.MARIOMON -> if (mariomonV13) genderRatiosMariomonV13 else genderRatiosMariomon
        GameKind.ELITE_REDUX -> genderRatiosEliteRedux
        GameKind.TEAM_ROCKET -> genderRatiosTeamRocket
        GameKind.WISHES -> genderRatiosWishes
        GameKind.DREAMSTONE -> genderRatiosDreamstone
        else -> return this
    }
    // BoxPokemon +0x13 flags byte (isBadEgg:1, hasSpecies:1, isEgg:1): an egg
    // shows as SPECIES_EGG with no gender, like the party menu
    // (MON_DATA_SPECIES_OR_EGG). The CFRU hacks kept
    // vanilla's SPECIES_EGG (412 - checked against each ROM's icon table).
    // Quetzal keeps it in the IV word's bit 30 instead (QUETZAL_PARTY_MON).
    val egg = if (layout.eggInIvWord) (raw[off + layout.plainBox + 0x28 + 3].toInt() and 0x40) != 0
        else (raw[off + layout.flags].toInt() and layout.eggMask) != 0
    if (egg) {
        // Heart and Soul / Lazarus / SoulGold / Seaglass have no SPECIES_EGG 412, so an
        // egg is flagged instead: "Egg", no HP bar, the egg's icon where [eggSpecies] is known.
        // So does any config naming its egg (Emerald Legacy: 415, its 412-414 are Deoxys forms).
        if (eggSpecies != 0 || activeGame in setOf(GameKind.HEART_AND_SOUL, GameKind.LAZARUS, GameKind.SOULGOLD, GameKind.EMERALD_SEAGLASS, GameKind.IMPERIUM,
                GameKind.QUETZAL, GameKind.ROWE, GameKind.MARIOMON)) {
            return copy(species = if (eggSpecies != 0) eggSpecies else species, genderSymbol = GENDER_SYMBOL_NONE, isEgg = true)
        }
        return copy(species = SPECIES_EGG_VANILLA, genderSymbol = GENDER_SYMBOL_NONE)
    }
    if (species !in ratios.indices) return this
    if (species == 29 || species == 32) {
        val want = if (species == 29) NIDORAN_F_NAME else NIDORAN_M_NAME
        if ((want.indices).all { raw[off + 8 + it] == want[it] }) return copy(genderSymbol = GENDER_SYMBOL_NONE)
    }
    val ratio = ratios[species]
    val pidLow = raw[off].toInt() and 0xFF
    val g = when (ratio) {
        255 -> GENDER_SYMBOL_NONE
        0 -> GENDER_SYMBOL_MALE
        254 -> GENDER_SYMBOL_FEMALE
        else -> if (pidLow < ratio) GENDER_SYMBOL_FEMALE else GENDER_SYMBOL_MALE
    }
    return copy(genderSymbol = g)
}

private fun Mon.masked(cfg: NativeConfig): Mon =
    if (cfg.speciesMask == 0xFFFF && cfg.moveMask == 0xFFFF && cfg.ppMask == 0xFF) this
    else copy(
        species = species and cfg.speciesMask,
        moves = IntArray(moves.size) { moves[it] and cfg.moveMask },
        pp = IntArray(pp.size) { pp[it] and cfg.ppMask },
        // The same packing puts experience in 21 bits, the nickname's 11th character above
        // them (Imperium's Charmander: 0x1FE00087, 135 exp).
        exp = exp?.let { it and 0x1FFFFFL },
    )

private fun emptyBattleMon() = BattleMon(0, 0, 0, 0, 0, 0, 0L, IntArray(NUM_MOVES), IntArray(NUM_MOVES))

private const val MAX_BATTLERS_COUNT = 4
private var lastLoggedFn0 = -1L

/**
 * Fast, non-1Hz-throttled peek at (battleActiveBattler, battleInputState),
 * for TelemetrySampler.sampleBattleInputFast. See PLAN.md Phase 5. Returns null if [cfg] doesn't have the
 * addresses this needs (Unbound - Tier C, unimplemented).
 *
 * Reads the whole gBattlerControllerFuncs[MAX_BATTLERS_COUNT] array and scans
 * it host-side - NOT a single gActiveBattler-indexed read, which is a stale/out-of-range
 * loop variable by the time anything outside battle_main.c's own per-frame
 * dispatch loop gets to look at it (see gactivebattler-loop-var-gotcha memory).
 *
 * Matches with bit 0 masked off both sides: confirmed by disassembling the
 * literal pool at the exact site that does
 * `gBattlerControllerFuncs[i] = HandleInputChooseAction;` (in a genuinely
 * vanilla firered_rev1 build) that the ARM/Thumb-interworking bit is set in
 * the actual runtime pointer value (e.g. `.word 0x0802e44d`) even though
 * `arm-none-eabi-nm` reports the plain even code address (`0802e44c`) for
 * that same symbol - this toolchain's `nm` doesn't reflect the ELF
 * Thumb-symbol convention. The hardcoded addresses below are nm's (even)
 * values; masking makes the comparison correct regardless of which
 * convention either side happens to follow.
 */
fun readNativeBattleInputFast(client: MemoryReader, cfg: NativeConfig): Pair<Int, Int>? {
    if (!cfg.hasBattleInputAddrs) return null
    val inBattle = runCatching { (client.readCoreMemory(cfg.gMainInBattle, 1)[0].toInt() and 0x02) != 0 }
        .getOrDefault(false)
    if (!inBattle) return 0 to BATTLE_INPUT_NONE
    val thumbMask = 0xFFFFFFFEL
    val wantAction = cfg.handleInputChooseAction and thumbMask
    val wantMove = cfg.handleInputChooseMove and thumbMask
    val wantBag = cfg.completeWhenChoseItem and thumbMask
    val wantParty = cfg.waitForMonSelection and thumbMask
    val wantTarget = cfg.handleInputChooseTarget and thumbMask
    return runCatching {
        val raw = client.readCoreMemory(cfg.battlerControllerFuncs, MAX_BATTLERS_COUNT * 4)
        val fn0 = u32le(raw, 0) and thumbMask
        if (fn0 != lastLoggedFn0) {
            lastLoggedFn0 = fn0
            android.util.Log.i(
                "pokedaisy-battle",
                "raw battler0 fn=0x${fn0.toString(16)} (wantAction=0x${wantAction.toString(16)} " +
                    "wantMove=0x${wantMove.toString(16)} wantBag=0x${wantBag.toString(16)} " +
                    "wantParty=0x${wantParty.toString(16)} wantTarget=0x${wantTarget.toString(16)})",
            )
        }
        for (i in 0 until MAX_BATTLERS_COUNT) {
            val fn = u32le(raw, i * 4) and thumbMask
            if (fn == wantAction) return@runCatching i to BATTLE_INPUT_ACTION_SELECT
            if (fn == wantMove) return@runCatching i to BATTLE_INPUT_MOVE_SELECT
            if (cfg.completeWhenChoseItem != 0L && fn == wantBag) return@runCatching i to BATTLE_INPUT_BAG_OPEN
            if (cfg.waitForMonSelection != 0L && fn == wantParty) return@runCatching i to BATTLE_INPUT_PARTY_OPEN
            if (cfg.handleInputChooseTarget != 0L && fn == wantTarget) return@runCatching i to BATTLE_INPUT_TARGET_SELECT
        }
        0 to BATTLE_INPUT_BUSY
    }.getOrNull()
}
