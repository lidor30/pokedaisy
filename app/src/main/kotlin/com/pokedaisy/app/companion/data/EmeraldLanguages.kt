package com.pokedaisy.app.companion.data

/**
 * A game's names in its own language - the other-language Emeralds (read from
 * each ROM by scripts/gen_emerald_lang_tables.py, EmeraldText<Lang>Gen.kt) and
 * FireRed / LeafGreen / Ruby / Sapphire (scripts/port_retail.py,
 * GameText<Code>Gen.kt): the same ids as English, their own names. The companion
 * shows a game's names in the game's language; types, powers and map rectangles
 * stay English's (the same in every release) - [baseMapSecs] is the English table
 * the names go over. Item descriptions come from the ROM itself (RomItemText).
 */
class GameText(
    species: () -> Map<Int, String>,
    moves: () -> Map<Int, String>,
    items: () -> Map<Int, String>,
    val natures: List<String>,
    mapSecs: () -> Map<Int, String>,
    smallFontWidths: () -> IntArray,
    englishMapSecs: () -> Map<Int, MapSecInfo> = { mapSecDataEmerald },
    // The moves' types and powers the names go over: the shared table, or a hack's own
    // (Unbound / Quetzal in another language: the English release's, the same ids).
    englishMoves: () -> Map<Int, MoveInfo> = { englishMoveData },
) {
    /** English's map sections (what hand-written GUIDE entries are tagged with). */
    val baseMapSecs: Map<Int, MapSecInfo> by lazy(englishMapSecs)
    private val baseMoves: Map<Int, MoveInfo> by lazy(englishMoves)
    val species by lazy(species)
    val items by lazy(items)
    val moveData: Map<Int, MoveInfo> by lazy {
        val names = moves()
        baseMoves.mapValues { (id, m) -> names[id]?.let { m.copy(name = it) } ?: m }
    }
    val mapSecData: Map<Int, MapSecInfo> by lazy {
        val names = mapSecs()
        baseMapSecs.mapValues { (id, s) -> names[id]?.let { s.copy(name = it) } ?: s }
    }

    /** The party slot's FONT_SMALL advances: a few localized glyphs are wider or narrower (empty for Japanese). */
    val smallFontWidths by lazy(smallFontWidths)
}

// The shared FireRed / Emerald move table (the member above shadows its name).
private val englishMoveData get() = moveData

private val EMERALD_ES by lazy {
    GameText({ speciesNamesEmeraldEs }, { moveNamesEmeraldEs }, { itemNamesEmeraldEs },
        natureNamesEmeraldEs, { mapSecNamesEmeraldEs }, { smallFontWidthsEmeraldEs })
}
private val EMERALD_DE by lazy {
    GameText({ speciesNamesEmeraldDe }, { moveNamesEmeraldDe }, { itemNamesEmeraldDe },
        natureNamesEmeraldDe, { mapSecNamesEmeraldDe }, { smallFontWidthsEmeraldDe })
}
private val EMERALD_FR by lazy {
    GameText({ speciesNamesEmeraldFr }, { moveNamesEmeraldFr }, { itemNamesEmeraldFr },
        natureNamesEmeraldFr, { mapSecNamesEmeraldFr }, { smallFontWidthsEmeraldFr })
}
private val EMERALD_IT by lazy {
    GameText({ speciesNamesEmeraldIt }, { moveNamesEmeraldIt }, { itemNamesEmeraldIt },
        natureNamesEmeraldIt, { mapSecNamesEmeraldIt }, { smallFontWidthsEmeraldIt })
}

private val EMERALD_JA by lazy {
    GameText({ speciesNamesEmeraldJa }, { moveNamesEmeraldJa }, { itemNamesEmeraldJa },
        natureNamesEmeraldJa, { mapSecNamesEmeraldJa }, { smallFontWidthsEmeraldJa })
}

// Pokémon Unbound v2.1.1.1's French translation (gen_unbound_fr_tables.py): its names over
// Unbound's own moves and map sections (the same ids as English). Not a game code: the
// translation keeps English's header (BPRE), so NATIVE_UNBOUND_FR names it.
const val UNBOUND_FR_TEXT = "UNBOUND_FR"
private val UNBOUND_FR by lazy {
    GameText({ speciesNamesUnboundFr }, { moveNamesUnboundFr }, { itemNamesUnboundFr },
        natureNamesUnboundFr, { mapSecNamesUnboundFr }, { IntArray(0) },
        englishMapSecs = { mapSecDataUnbound }, englishMoves = { moveDataUnbound })
}

// Pokémon Emerald Kaizo v2.0 (gen_emerald_lang_tables.py kaizo): English Emerald edited in place - its own moves (29
// replaced, 217 rebalanced: names, types and powers from its gBattleMoves) under English's names for the rest. Not a
// game code: the hack keeps retail's header (BPEE), so NATIVE_EMERALD_KAIZO names it.
const val KAIZO_TEXT = "EMERALD_KAIZO"
private val EMERALD_KAIZO by lazy {
    GameText({ speciesNamesEmeraldKaizo }, { moveNamesEmeraldKaizo }, { itemNamesEmeraldKaizo },
        natureNamesEmeraldKaizo, { mapSecNamesEmeraldKaizo }, { smallFontWidthsEmeraldKaizo },
        englishMoves = { moveDataEmeraldKaizo })
}

// Pokémon Emerald Legacy v1.1.4 (gen_emerald_lang_tables.py legacy): pokeemerald rebuilt from its own source - English's
// names but for the three Deoxys forms at species 412-414 and the BRICK PIECE, its rebalanced moves (types and powers
// from its gBattleMoves). Not a game code: the hack keeps retail's header (BPEE), so NATIVE_EMERALD_LEGACY names it.
const val LEGACY_TEXT = "EMERALD_LEGACY"
private val EMERALD_LEGACY by lazy {
    GameText({ speciesNamesEmeraldLegacy }, { moveNamesEmeraldLegacy }, { itemNamesEmeraldLegacy },
        natureNamesEmeraldLegacy, { mapSecNamesEmeraldLegacy }, { smallFontWidthsEmeraldLegacy },
        englishMoves = { moveDataEmeraldLegacy })
}

/** Emerald Legacy is running ([LEGACY_TEXT]): its own species past 411, egg, move stats and guide. */
val emeraldLegacy: Boolean get() = romGameCode == LEGACY_TEXT

private val portTexts = java.util.concurrent.ConcurrentHashMap<String, GameText>()

/** The names of the game with [code] ("BPED", "AXVF", [UNBOUND_FR_TEXT]); null for English and every game without its own. */
fun gameText(code: String): GameText? = when (code) {
    "BPES" -> EMERALD_ES
    "BPED" -> EMERALD_DE
    "BPEF" -> EMERALD_FR
    "BPEI" -> EMERALD_IT
    "BPEJ" -> EMERALD_JA
    UNBOUND_FR_TEXT -> UNBOUND_FR
    KAIZO_TEXT -> EMERALD_KAIZO
    "APSD" -> YELLOW_DE_TEXT
    "APSF" -> YELLOW_FR_TEXT
    "APSI" -> YELLOW_IT_TEXT
    "APSS" -> YELLOW_ES_TEXT
    "BYTD" -> CRYSTAL_DE_TEXT
    "AAUD", "AAXD" -> GOLD_SILVER_DE_TEXT
    LEGACY_TEXT -> EMERALD_LEGACY
    else -> RETAIL_PORT_TEXTS[code]?.let { make -> portTexts.getOrPut(code, make) }
}

/** The European releases' game codes, by language letter. */
val EMERALD_EUROPEAN_CODES = setOf("BPES", "BPED", "BPEF", "BPEI")

/** Every Emerald but English's: the European ones and Japanese (BPEJ). */
val EMERALD_LOCALIZED_CODES = EMERALD_EUROPEAN_CODES + "BPEJ"
