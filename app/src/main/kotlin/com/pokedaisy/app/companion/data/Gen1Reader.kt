package com.pokedaisy.app.companion.data

/**
 * Generation 1 (Game Boy) games' RAM, read over the GB core's own 16-bit bus:
 * WRAM C000-DFFF. Addresses are pret/pokeyellow's symbols (its build is
 * byte-identical to retail Yellow), checked on headless captures of the
 * user's save (the `yellow` fixtures).
 *
 * Gen 1's structs are plaintext and big-endian (HP, stats, experience), the
 * species byte is the game's internal index ([internalToDex] maps it to the
 * National Dex number every table here is keyed by), and there's no gender,
 * ability or held item. The bag is one list.
 */
data class Gen1Config(
    val partyCount: Long,       // wPartyCount, then wPartySpecies
    val partyMons: Long,        // wPartyMons: 6 x party_struct (44 bytes)
    val bagCount: Long,         // wNumBagItems, then wBagItems: {item, qty} pairs
    val money: Long,            // wPlayerMoney: 3 bytes BCD
    val badges: Long,           // wObtainedBadges: a bit per badge
    val curMap: Long,           // wCurMap (wYCoord / wXCoord at +3 / +4)
    val yCoord: Long,
    val xCoord: Long,
    val isInBattle: Long,       // wIsInBattle: 0 none, 1 wild, 2 trainer, 0xFF lost
    val battleMon: Long,        // wBattleMon: battle_struct (29 bytes)
    val enemyMon: Long,         // wEnemyMon
    val enemyMonPartyPos: Long, // wEnemyMonPartyPos: which wEnemyMons slot is out
    val enemyPartyCount: Long,  // wEnemyPartyCount, then its species list
    val enemyMons: Long,        // wEnemyMons: a trainer's party_structs
    val internalToDex: IntArray,
    // The menu HandleMenuInput is running: wTopMenuItemY / X, wCurrentMenuItem, (+1 tile
    // behind the cursor), wMaxMenuItem; wMoveMenuType (0 = the battle's move list); and
    // wTileMap, the screen's 20x18 tiles, where the cursor is a filled ▶ only while the
    // menu waits for input (an A turns it into ▷).
    val topMenuItemY: Long = 0,
    val moveMenuType: Long = 0,
    val tileMap: Long = 0,
    val pokedex: PokedexTables? = null,
    // wPlayTimeHours, Maxed, Minutes, Seconds, Frames: the telemetry's frame counter (the
    // companion's per-second refreshes, STATES' list, key off it moving).
    val playTime: Long = 0,
    /** The cart's language letter (E, or the European ones' D / F / I / S) and, for those, its CGB
     * manufacturer code ("APSD"): the companion's names ([gameText]) and language follow it. */
    val language: Char = 'E',
    val gameCode: String = "",
    /** DisplayBattleMenu's cursor columns (wTopMenuItemX) for FIGHT / ITEM and PkMn / RUN: each language's
     * words moved them (English 9 / 15; read off the `yellow*_battle_menu` fixtures' tile maps). */
    val battleMenuLeftX: Int = 0x09,
    val battleMenuRightX: Int = 0x0F,
    /** The battle POKéMON switch ([Gen1PartySwitch]): wPartyMenuTypeOrMessageID (which party menu is up: 0 PkMn's,
     * 2 the pick after a faint, 1 an item's "use on which?") and wPlayerMonNumber (the party slot in battle). */
    val partyMenuType: Long = 0,
    val playerMonNumber: Long = 0,
)

/** Pokémon Yellow (USA, Europe). Red / Blue's own addresses are one byte later ([GEN1_RED]). */
val GEN1_YELLOW = Gen1Config(
    partyCount = 0xD162L,
    partyMons = 0xD16AL,
    bagCount = 0xD31CL,
    money = 0xD346L,
    badges = 0xD355L,
    curMap = 0xD35DL,
    yCoord = 0xD360L,
    xCoord = 0xD361L,
    isInBattle = 0xD056L,
    battleMon = 0xD013L,
    enemyMon = 0xCFE4L,
    enemyMonPartyPos = 0xCFE7L,
    enemyPartyCount = 0xD89BL,
    enemyMons = 0xD8A3L,
    internalToDex = gen1InternalToDexYellow,
    topMenuItemY = 0xCC24L,
    moveMenuType = 0xCCDBL,
    tileMap = 0xC3A0L,
    pokedex = POKEDEX_YELLOW,
    playTime = 0xDA40L,
    partyMenuType = 0xD07CL,
    playerMonNumber = 0xCC2FL,
)

/**
 * The German, French, Italian and Spanish Yellows: English's code with their own text, which
 * moved everything from wBattleMon up 5 bytes (wPlayerName is 0xD15C) - the same in all four
 * (scripts/port_gen1.py, every field below from English's code in theirs; checked on headless
 * captures of the English save booted in each, the `yellow_de` .. `yellow_es` fixtures). The
 * menu, tile map and audio RAM didn't move.
 */
private val GEN1_YELLOW_EUROPE = GEN1_YELLOW.copy(
    partyCount = 0xD167L,
    partyMons = 0xD16FL,
    bagCount = 0xD321L,
    money = 0xD34BL,
    badges = 0xD35AL,
    curMap = 0xD362L,
    yCoord = 0xD365L,
    xCoord = 0xD366L,
    isInBattle = 0xD05BL,
    battleMon = 0xD018L,
    enemyMon = 0xCFE9L,
    enemyMonPartyPos = 0xCFECL,
    enemyPartyCount = 0xD8A0L,
    enemyMons = 0xD8A8L,
    playTime = 0xDA45L,
    partyMenuType = 0xD081L,
)
val GEN1_YELLOW_DE = GEN1_YELLOW_EUROPE.copy(language = 'D', gameCode = "APSD", pokedex = POKEDEX_YELLOW_DE,
    battleMenuLeftX = 7, battleMenuRightX = 12)
val GEN1_YELLOW_FR = GEN1_YELLOW_EUROPE.copy(language = 'F', gameCode = "APSF", pokedex = POKEDEX_YELLOW_FR,
    battleMenuLeftX = 7, battleMenuRightX = 13)
val GEN1_YELLOW_IT = GEN1_YELLOW_EUROPE.copy(language = 'I', gameCode = "APSI", pokedex = POKEDEX_YELLOW_IT,
    battleMenuLeftX = 7, battleMenuRightX = 14)
val GEN1_YELLOW_ES = GEN1_YELLOW_EUROPE.copy(language = 'S', gameCode = "APSS", pokedex = POKEDEX_YELLOW_ES)

/**
 * Pokémon Red and Blue (USA, Europe): pret/pokered's symbols (its red and blue builds are both carts byte for byte, and
 * pokered.sym / pokeblue.sym agree on every address here). Yellow's RAM plus one byte from wOnSGB (CF1B) up;
 * the menus, tile map and wPlayerMonNumber below it didn't move. Checked on headless captures of the user's Red save,
 * booted in both (the `red*` / `blue*` fixtures).
 */
val GEN1_RED = GEN1_YELLOW.copy(
    partyCount = 0xD163L,
    partyMons = 0xD16BL,
    bagCount = 0xD31DL,
    money = 0xD347L,
    badges = 0xD356L,
    curMap = 0xD35EL,
    yCoord = 0xD361L,
    xCoord = 0xD362L,
    isInBattle = 0xD057L,
    battleMon = 0xD014L,
    enemyMon = 0xCFE5L,
    enemyMonPartyPos = 0xCFE8L,
    enemyPartyCount = 0xD89CL,
    enemyMons = 0xD8A4L,
    pokedex = POKEDEX_RED,
    playTime = 0xDA41L,
    partyMenuType = 0xD07DL,
)
/** Blue: Red's addresses (its own dex tables object, so a cache never mixes the two carts' pages). */
val GEN1_BLUE = GEN1_RED.copy(pokedex = POKEDEX_BLUE)

/** Pokémon Yellow, Red or Blue (every language): Gen 1's reader ([readGen1Telemetry]), tables and look. */
val GameKind?.isGen1: Boolean get() = this == GameKind.YELLOW || isRedBlue

/** Pokémon Red or Blue: Yellow's tables, their own art, dex pages and RAM ([GEN1_RED]). */
val GameKind?.isRedBlue: Boolean get() = this == GameKind.RED || this == GameKind.BLUE

const val GEN1_PARTY_MON_SIZE = 44
const val GEN1_BATTLE_MON_SIZE = 29
private const val GEN1_BAG_SLOTS = 20
/** wPartyMonNicks' 11-byte names, right after wPartyMonOT (6 x 11): see [readGen1Telemetry]. */
private const val GEN1_NAME_LEN = 11
private const val GEN1_NICKNAMES_AFTER_MONS = PARTY_SIZE * GEN1_PARTY_MON_SIZE + PARTY_SIZE * GEN1_NAME_LEN

private fun be16(b: ByteArray, o: Int) = ((b[o].toInt() and 0xFF) shl 8) or (b[o + 1].toInt() and 0xFF)
private fun u8(b: ByteArray, o: Int) = b[o].toInt() and 0xFF

private fun Gen1Config.dex(internal: Int): Int = internalToDex.getOrElse(internal) { 0 }

/** A party_struct at [off] in [raw], or null for an empty / garbage slot. */
fun decodeGen1PartyMon(raw: ByteArray, off: Int, cfg: Gen1Config): Mon? {
    val species = cfg.dex(u8(raw, off))
    if (species == 0) return null
    val level = u8(raw, off + 33)
    if (level !in 1..100) return null
    val exp = ((u8(raw, off + 14).toLong() shl 16) or (u8(raw, off + 15).toLong() shl 8) or u8(raw, off + 16).toLong())
    return Mon(
        species = species,
        level = level,
        hp = be16(raw, off + 1),
        maxHp = be16(raw, off + 34),
        status = u8(raw, off + 4).toLong(),   // same bits as Gen 3's: sleep 0-2, PSN, BRN, FRZ, PAR
        moves = IntArray(4) { u8(raw, off + 8 + it) },
        pp = IntArray(4) { u8(raw, off + 29 + it) and 0x3F },   // top 2 bits: PP Ups
        genderSymbol = GENDER_SYMBOL_NONE,
        exp = exp,
        personality = gen1MonKey(raw, off),   // no personality in Gen 1: the switch's key (GbPartySwitch)
    )
}

/** A battle_struct (wBattleMon / wEnemyMon); its types are the battler's current ones. */
fun decodeGen1BattleMon(raw: ByteArray, cfg: Gen1Config): BattleMon? {
    val species = cfg.dex(u8(raw, 0))
    if (species == 0) return null
    return BattleMon(
        species = species,
        level = u8(raw, 14),
        type1 = u8(raw, 5),
        type2 = u8(raw, 6),
        hp = be16(raw, 1),
        maxHp = be16(raw, 15),
        status1 = u8(raw, 4).toLong(),
        moves = IntArray(4) { u8(raw, 8 + it) },
        pp = IntArray(4) { u8(raw, 25 + it) and 0x3F },
    )
}

/** [raw]'s nickname at [off] (11 bytes, 0x50-terminated) in the cart's own charset. */
private fun gen1Nickname(raw: ByteArray, off: Int, charset: Gen1Charset): String {
    if (off + GEN1_NAME_LEN > raw.size) return ""
    val chars = charset.chars
    return buildString {
        for (i in 0 until GEN1_NAME_LEN) {
            val c = raw[off + i].toInt() and 0xFF
            if (c == 0x00 || c == 0x50) break
            chars[c]?.let { append(it) }
        }
    }.trim()
}

/** The cart's character set: German / French share one, Italian / Spanish another. */
private fun gen1Charset(language: Char): Gen1Charset = when (language) {
    'D', 'F' -> Gen1Charset.DF
    'I', 'S' -> Gen1Charset.IS
    else -> Gen1Charset.EN
}

/**
 * The battle menu's four words as each Yellow prints them (FIGHT, PkMn, ITEM, RUN - the order [readGen1BattleInput]'s
 * cells go 0, 1, 2, 3), read off the `yellow*_battle_menu` fixtures' screens; PkMn is the same two ligature tiles in all.
 */
fun gen1BattleMenuWords(language: Char): List<String> = when (language) {
    'D' -> listOf("KMPF", "PkMn", "ITEM", "FLUCHT")
    'F' -> listOf("ATTAQ", "PkMn", "OBJET", "FUITE")
    'I' -> listOf("LOTTA", "PkMn", "STRUM.", "FUGA")
    'S' -> listOf("LUCHA", "PkMn", "OBJ.", "ESC")
    else -> listOf("FIGHT", "PkMn", "ITEM", "RUN")
}

private const val TILE_CURSOR = 0xED          // ▶, the waiting menu's cursor
private const val BATTLE_MENU_TOP = 0x0E         // DisplayBattleMenu: rows 14 / 16, X per Gen1Config (FIGHT / ITEM, PkMn / RUN)
private const val MOVE_MENU_TOP = 0x0C           // MoveSelectionMenu: moves on rows 13-16 (items 1-4), X 5
private const val MOVE_MENU_X = 0x05

/**
 * Which battle menu waits for the player, and its cursor: BATTLE_INPUT_ACTION_SELECT with
 * the cell (column + 2 x row: FIGHT 0, PkMn 1, ITEM 2, RUN 3 - Gen 1's own layout), or
 * BATTLE_INPUT_MOVE_SELECT with the move index; BATTLE_INPUT_BUSY in between; NONE outside
 * a battle. The menu variables stay set after a pick, so a menu only counts as waiting
 * while its cursor cell on screen is the filled arrow.
 */
fun readGen1BattleInput(r: MemoryReader, cfg: Gen1Config): Pair<Int, Int> {
    if (cfg.topMenuItemY == 0L) return BATTLE_INPUT_NONE to -1
    val battle = u8(r.readCoreMemory(cfg.isInBattle, 1), 0)
    if (battle != 1 && battle != 2) return BATTLE_INPUT_NONE to -1
    readGen1PartyMenu(r, cfg)?.let { return BATTLE_INPUT_PARTY_OPEN to it.pollCursor }
    val menu = r.readCoreMemory(cfg.topMenuItemY, 5)
    val top = u8(menu, 0)
    val x = u8(menu, 1)
    val item = u8(menu, 2)
    val max = u8(menu, 4)
    fun cursorShown(row: Int) = row in 0 until 18 && x < 20 &&
        u8(r.readCoreMemory(cfg.tileMap + row * 20 + x, 1), 0) == TILE_CURSOR
    if (top == BATTLE_MENU_TOP && max == 1 && item <= 1 && (x == cfg.battleMenuLeftX || x == cfg.battleMenuRightX) &&
        cursorShown(top + 2 * item)
    ) {
        return BATTLE_INPUT_ACTION_SELECT to ((if (x == cfg.battleMenuRightX) 1 else 0) + 2 * item)
    }
    if (top == MOVE_MENU_TOP && x == MOVE_MENU_X && item in 1..4 &&
        u8(r.readCoreMemory(cfg.moveMenuType, 1), 0) == 0 && cursorShown(top + item)
    ) {
        return BATTLE_INPUT_MOVE_SELECT to item - 1
    }
    return BATTLE_INPUT_BUSY to -1
}

/** Three BCD bytes (wPlayerMoney 99 99 99 = ¥999999). */
fun bcd(b: ByteArray): Long = b.fold(0L) { acc, x -> acc * 100 + ((x.toInt() shr 4) and 0xF) * 10 + (x.toInt() and 0xF) }

private val EMPTY_BATTLE_MON = BattleMon(0, 0, 0, 0, 0, 0, 0, IntArray(4), IntArray(4))

fun readGen1Telemetry(r: MemoryReader, cfg: Gen1Config): Telemetry {
    val count = u8(r.readCoreMemory(cfg.partyCount, 1), 0).coerceAtMost(PARTY_SIZE)
    val partyRaw = r.readCoreMemory(cfg.partyMons, PARTY_SIZE * GEN1_PARTY_MON_SIZE)
    // wPartyMonNicks: 6 x 11 bytes, after wPartyMons and wPartyMonOT.
    val nicknames = r.readCoreMemory(cfg.partyMons + GEN1_NICKNAMES_AFTER_MONS, PARTY_SIZE * GEN1_NAME_LEN)
    val charset = gen1Charset(cfg.language)
    val party = (0 until count).mapIndexedNotNull { i, _ ->
        decodeGen1PartyMon(partyRaw, i * GEN1_PARTY_MON_SIZE, cfg)?.copy(
            nickname = gen1Nickname(nicknames, i * GEN1_NAME_LEN, charset),
        )
    }

    val bagN = u8(r.readCoreMemory(cfg.bagCount, 1), 0).coerceAtMost(GEN1_BAG_SLOTS)
    val bagRaw = r.readCoreMemory(cfg.bagCount + 1, GEN1_BAG_SLOTS * 2)
    val items = (0 until bagN).map { bagRaw[2 * it].toInt() and 0xFF to (bagRaw[2 * it + 1].toInt() and 0xFF) }
        .takeWhile { it.first != 0xFF }
        .map { (id, qty) -> Item(id, qty) }   // one list, in the player's own order, like the game's bag

    val battle = u8(r.readCoreMemory(cfg.isInBattle, 1), 0)
    val inBattle = battle == 1 || battle == 2
    val battleMons = MutableList(4) { EMPTY_BATTLE_MON }
    var enemyParty = emptyList<Mon>()
    var enemyActive = -1
    if (inBattle) {
        decodeGen1BattleMon(r.readCoreMemory(cfg.battleMon, GEN1_BATTLE_MON_SIZE), cfg)?.let { battleMons[BATTLE_POS_PLAYER_LEFT] = it }
        decodeGen1BattleMon(r.readCoreMemory(cfg.enemyMon, GEN1_BATTLE_MON_SIZE), cfg)?.let { battleMons[BATTLE_POS_OPPONENT_LEFT] = it }
        if (battle == 2) {
            val n = u8(r.readCoreMemory(cfg.enemyPartyCount, 1), 0).coerceAtMost(PARTY_SIZE)
            val raw = r.readCoreMemory(cfg.enemyMons, PARTY_SIZE * GEN1_PARTY_MON_SIZE)
            enemyParty = (0 until n).mapNotNull { decodeGen1PartyMon(raw, it * GEN1_PARTY_MON_SIZE, cfg) }
            enemyActive = u8(r.readCoreMemory(cfg.enemyMonPartyPos, 1), 0).takeIf { it < enemyParty.size } ?: -1
        }
    }

    val map = u8(r.readCoreMemory(cfg.curMap, 1), 0)
    val frames = if (cfg.playTime == 0L) 0L else r.readCoreMemory(cfg.playTime, 5).let { t ->
        (((u8(t, 0) * 60L + u8(t, 2)) * 60L + u8(t, 3)) * 60L + u8(t, 4))
    }
    return Telemetry(
        frameCounter = frames,
        inBattle = inBattle,
        isDoubleBattle = false,
        mapGroup = 0,
        mapNum = map,
        regionMapSectionId = map,
        x = u8(r.readCoreMemory(cfg.xCoord, 1), 0),
        y = u8(r.readCoreMemory(cfg.yCoord, 1), 0),
        facingDirection = 0,
        partyCount = party.size,
        party = party,
        battleMons = battleMons,
        battleInputState = if (inBattle) readGen1BattleInput(r, cfg).first else BATTLE_INPUT_NONE,
        itemCount = items.size,
        items = items,
        enemyParty = enemyParty,
        enemyActive = enemyActive,
        money = bcd(r.readCoreMemory(cfg.money, 3)),
        pokedex = cfg.pokedex?.let { t -> t.gen1?.let { runCatching { readGen1PokedexState(r, it, t) }.getOrNull() } },
    )
}
