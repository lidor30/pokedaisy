package com.pokedaisy.app.companion.data

// One sample of the running game, as every reader (NativeReader, Gen1Reader) builds it.
const val PARTY_SIZE = 6
const val NUM_MOVES = 4

const val BATTLE_POS_PLAYER_LEFT = 0
const val BATTLE_POS_OPPONENT_LEFT = 1
const val BATTLE_POS_PLAYER_RIGHT = 2
const val BATTLE_POS_OPPONENT_RIGHT = 3

const val TYPE_NONE = 255

// battleInputState values: which battle menu waits for input (readNativeBattleInputFast).
const val BATTLE_INPUT_NONE = 0
const val BATTLE_INPUT_BUSY = 1
const val BATTLE_INPUT_ACTION_SELECT = 2
const val BATTLE_INPUT_MOVE_SELECT = 3
const val BATTLE_INPUT_BAG_OPEN = 4
const val BATTLE_INPUT_PARTY_OPEN = 5
const val BATTLE_INPUT_TARGET_SELECT = 6

// Item.pocket values - stable, game-independent ids. NOT the same as either
// game's own engine POCKET_* constants, which number the same pockets
// differently between FireRed and Emerald.
const val POCKET_ITEMS = 0
const val POCKET_POKE_BALLS = 1
const val POCKET_TM_HM = 2
const val POCKET_BERRIES = 3
const val POCKET_KEY_ITEMS = 4
// Gen 4's own pockets (HeartGold / SoulSilver's bag shows them apart); everything that knows only the five above folds
// them into ITEMS ([basePocket]).
const val POCKET_MEDICINE = 5
const val POCKET_MAIL = 6
const val POCKET_BATTLE_ITEMS = 7

/** [pocket] as one of the five pockets every game has: Gen 4's MEDICINE / MAIL / BATTLE ITEMS ride with ITEMS. */
fun basePocket(pocket: Int): Int = if (pocket >= POCKET_MEDICINE) POCKET_ITEMS else pocket

data class Mon(
    val species: Int,
    val level: Int,
    val hp: Int,
    val maxHp: Int,
    val status: Long,
    val moves: IntArray,
    val pp: IntArray,
    // The party menu's gender symbol (GENDER_SYMBOL_*), when known.
    val genderSymbol: Int = GENDER_SYMBOL_UNKNOWN,
    // Total experience points (the Growth substruct), from the decrypted RAM struct.
    val exp: Long? = null,
    // The mon's personality value (its RAM struct's first word): which mon
    // the battle POKéMON pane switches to. 0 = unknown.
    val personality: Long = 0,
    // IVs / EVs / nature from the decrypted RAM struct; null = unknown.
    val stats: MonStats? = null,
    // An egg read from the RAM struct's flags where [species] stays the egg's own
    // species (the expansion hacks with no SPECIES_EGG 412; see withNativeGender).
    val isEgg: Boolean = false,
    // The held item's id from the RAM struct (the party menu's held-item marker); 0 = none or unknown.
    val heldItem: Int = 0,
    // The name the game stores for it (its nickname; unnicknamed mons store the species name); "" = not read.
    val nickname: String = "",
)

// What FireRed's party menu shows next to the level. UNKNOWN = a source that can't tell.
const val GENDER_SYMBOL_UNKNOWN = 0
const val GENDER_SYMBOL_MALE = 1
const val GENDER_SYMBOL_FEMALE = 2
const val GENDER_SYMBOL_NONE = 3

data class BattleMon(
    val species: Int,
    val level: Int,
    val type1: Int,
    val type2: Int,
    val hp: Int,
    val maxHp: Int,
    val status1: Long,
    val moves: IntArray,
    val pp: IntArray,
    /** gBattleMons' own fields (0 / null where the source has none). */
    val ability: Int = 0,
    val item: Int = 0,
    /** Attack, Defense, Speed, Sp. Atk, Sp. Def - in battle, before stages. */
    val stats: IntArray? = null,
    /** Attack, Defense, Speed, Sp. Atk, Sp. Def, accuracy, evasion; 6 = unchanged (0..12). */
    val statStages: IntArray? = null,
)

data class Item(val itemId: Int, val quantity: Int, val pocket: Int = POCKET_ITEMS)

data class Telemetry(
    val frameCounter: Long,
    val inBattle: Boolean,
    val isDoubleBattle: Boolean,
    val mapGroup: Int,
    val mapNum: Int,
    val regionMapSectionId: Int,
    val x: Int,
    val y: Int,
    val facingDirection: Int,
    val partyCount: Int,
    val party: List<Mon>,
    val battleMons: List<BattleMon>, // size 4, indexed by BATTLE_POS_*
    val itemCount: Int,
    val items: List<Item>,
    val icons: IconTables = IconTables(),
    // Which battler is choosing and what menu is up right now, for driving
    // synthetic button presses (bottom-screen touch battle control).
    val battleActiveBattler: Int = 0,
    val battleInputState: Int = BATTLE_INPUT_NONE,
    /** Native reads of games with a [NativeConfig.pokedex] only. */
    val pokedex: PokedexState? = null,
    /** Event flags / vars, for games with [NativeConfig.guideTables]. */
    val progress: SaveProgress? = null,
    val guideTables: GuideTables? = null,
    /** A trainer battle's whole opposing party ([NativeConfig.enemyParty]). */
    val enemyParty: List<Mon> = emptyList(),
    /** gEnemyParty in any battle, wild ones included: where the foe battlers' IVs come from. */
    val battleFoes: List<Mon> = emptyList(),
    /** [enemyParty] index of the foe that's out, and of monToSwitchIntoId (its trainer's last pick); -1 = unknown. */
    val enemyActive: Int = -1,
    val enemyNext: Int = -1,
    /** The player's money; null = not read for this game (see [readMoney]). */
    val money: Long? = null,
    /** The current map's size in metatiles and gMapHeader.mapType (see [readMapShape]); 0 / -1 = unknown. */
    val mapWidth: Int = 0,
    val mapHeight: Int = 0,
    val mapType: Int = -1,
    /** SaveBlock2.playerGender: 0 male, 1 female, -1 unknown. */
    val playerGender: Int = -1,
    /** The name the game itself prints here, over the mapsec table's (Emerald Rogue's own hub name). */
    val mapSecName: String? = null,
    /** What the TRAINER CARD shows (TrainerCard.kt); null where the game has no [NativeConfig.trainerCard]. */
    val trainerCard: TrainerCardInfo? = null,
    /** A Repel's steps left, where the reader has it itself (a DS game); null = from [progress]'s variable (GBA). */
    val repelSteps: Int? = null,
    /** The player's tile on the region map where the reader works it out itself (a DS game: their Town Map block); null =
     * from the map section and position ([PlayerMapTile]). */
    val regionMapTile: Pair<Int, Int>? = null,
    /** The towns the player has been to, as the game's first-arrival flags (bit n = first arrival n; a DS game). */
    val townsVisited: Long? = null,
    /** HeartGold / SoulSilver: the Pokégear's skin << 2 | its map's unlock level ([PokegearMap]); null elsewhere. */
    val pokegearState: Int? = null,
)

/** ROM addresses of the icon-graphics tables (0 = unknown). */
data class IconTables(
    val monIconTable: Long = 0,
    val monIconPaletteTable: Long = 0,
    val monIconPaletteIndices: Long = 0,
    val itemIconTable: Long = 0,
    // Per-entry strides, defaulting to the vanilla decomp arrays
    // (gMonIconTable: u32 ptr; gMonIconPaletteIndices: u8; gItemIconTable:
    // {tiles, pal} pair). pokeemerald-expansion hacks don't have those arrays -
    // the same fields live INSIDE each gSpeciesInfo/gItemsInfo entry - so they
    // point the three bases at the field within entry 0 and set the stride to
    // the whole struct's size instead (see NATIVE_EMERALD_SEAGLASS).
    val monIconStride: Int = 4,
    val monPalIdxStride: Int = 1,
    // Expansion packs iconPalIndex into a 3-bit bitfield sharing its byte
    // with iconPalIndexFemale.
    val monPalIdxMask: Int = 0xFF,
    val itemIconStride: Int = 8,
    // Newer pokeemerald-expansion stores item icon palettes as raw 32-byte
    // BGR555, not LZ77 like vanilla/older expansion (tiles stay LZ77).
    val itemPalCompressed: Boolean = true,
    // A second item table for ids [extraItemFirst, extraItemFirst + extraItemCount):
    // {tiles, pal} pointer pairs at extraItemIconTable + (id - first) * stride -
    // Emerald Rogue keeps its own items' icons in gRogueItems, not gItemIconTable.
    val extraItemIconTable: Long = 0,
    val extraItemFirst: Int = 0,
    val extraItemCount: Int = 0,
    val extraItemStride: Int = 8,
    // Each species' own icon palette (a pointer per gSpeciesInfo entry, [monIconStride]
    // apart) instead of an index into the 6 shared ones: SoulGold's. 0 = the shared ones.
    val monIconPalettes: Long = 0,
) {
    // Split per-kind: some hacks relocate one table but not the other (Gaia
    // keeps sItemIconTable at its vanilla address but moved gMonIconTable
    // elsewhere for its ~500 extra species - see NATIVE_GAIA_V3_2), so gating
    // both on a single combined flag would silently disable item icons too.
    val monPresent get() = monIconTable != 0L
    val itemPresent get() = itemIconTable != 0L
    val present get() = monPresent && itemPresent
}

class TelemetryDecodeException(message: String) : Exception(message)
