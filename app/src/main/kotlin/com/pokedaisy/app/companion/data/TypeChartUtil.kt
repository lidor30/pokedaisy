package com.pokedaisy.app.companion.data

val typeNames = mapOf(
    0 to "Normal", 1 to "Fighting", 2 to "Flying", 3 to "Poison", 4 to "Ground",
    5 to "Rock", 6 to "Bug", 7 to "Ghost", 8 to "Steel", 9 to "???",
    10 to "Fire", 11 to "Water", 12 to "Grass", 13 to "Electric", 14 to "Psychic",
    15 to "Ice", 16 to "Dragon", 17 to "Dark",
)

fun typeName(t: Int): String {
    if (t == TYPE_NONE) return ""
    return activeTypeNames[t] ?: "Type#$t"
}

/** One attacking type vs one defending type, as a percentage (100 = 1x). */
fun singleTypeMultiplierPct(atkType: Int, defType: Int): Int {
    return activeTypeEffectiveness[atkType * 100 + defType] ?: 100
}

/** Combined effectiveness of an attacking type against a (possibly dual-typed) defender. */
fun typeMultiplierPct(atkType: Int, defType1: Int, defType2: Int): Int {
    var pct = singleTypeMultiplierPct(atkType, defType1)
    if (defType2 != TYPE_NONE && defType2 != defType1) {
        pct = pct * singleTypeMultiplierPct(atkType, defType2) / 100
    }
    return pct
}

/** A move's name with its case, accents and punctuation gone, for [moveMultiplierPct]'s lists. */
private fun moveKey(name: String): String =
    java.text.Normalizer.normalize(name, java.text.Normalizer.Form.NFD).uppercase().filter { it in 'A'..'Z' }

// As the games spell them: Unbound FR cuts its name short, Team Rocket Edition 2026's ROM has "Lioflización".
private val FREEZE_DRY = setOf("FREEZEDRY", "LYOPHILISAT", "LYOPHILISATION", "LIOFILIZACION", "LIOFLIZACION")
private val FLYING_PRESS = setOf("FLYINGPRESS", "PLANCHAVOLADORA", "PLVOLADORA")
private val THOUSAND_ARROWS = setOf("THOUSANDARROWS", "MILFLECHAS")

private fun typeIdIgnoringCase(name: String): Int =
    activeTypeNames.entries.firstOrNull { it.value.equals(name, ignoreCase = true) }?.key ?: TYPE_NONE

/**
 * [typeMultiplierPct] for a move, with the moves whose hit the type chart can't tell (a player's report: Freeze-Dry read RESISTED on
 * a Water foe in Seaglass): Freeze-Dry is 2x against Water whatever Ice does there, Flying Press counts as Fighting and
 * Flying at once, Thousand Arrows is 1x against anything Flying. They only exist in the hacks; by name, because every hack
 * numbers its moves its own way.
 */
fun moveMultiplierPct(moveName: String, atkType: Int, defType1: Int, defType2: Int): Int {
    val key = moveKey(moveName)
    return when (key) {
        in FREEZE_DRY -> {
            val water = typeIdIgnoringCase("Water")
            fun vs(def: Int) = if (water != TYPE_NONE && def == water) 200 else singleTypeMultiplierPct(atkType, def)
            if (defType2 != TYPE_NONE && defType2 != defType1) vs(defType1) * vs(defType2) / 100 else vs(defType1)
        }
        in FLYING_PRESS -> {
            val fighting = typeIdIgnoringCase("Fighting")
            val flying = typeIdIgnoringCase("Flying")
            if (fighting == TYPE_NONE || flying == TYPE_NONE) typeMultiplierPct(atkType, defType1, defType2)
            else typeMultiplierPct(fighting, defType1, defType2) * typeMultiplierPct(flying, defType1, defType2) / 100
        }
        in THOUSAND_ARROWS -> {
            val flying = typeIdIgnoringCase("Flying")
            if (flying != TYPE_NONE && (defType1 == flying || defType2 == flying)) 100
            else typeMultiplierPct(atkType, defType1, defType2)
        }
        else -> typeMultiplierPct(atkType, defType1, defType2)
    }
}

/** [typeName]'s inverse: the running game's type id for a displayed name, or TYPE_NONE. */
fun typeIdOf(name: String): Int = activeTypeNames.entries.firstOrNull { it.value == name }?.key ?: TYPE_NONE

fun monTypes(type1: Int, type2: Int): List<String> {
    val names = mutableListOf(typeName(type1))
    if (type2 != TYPE_NONE && type2 != type1) names.add(typeName(type2))
    return names
}

data class TypeMatchup(val type: String, val label: String, val pct: Int)

data class TypeMatchups(
    val weaknesses: List<TypeMatchup>,
    val resistances: List<TypeMatchup>,
    val immunities: List<TypeMatchup>,
)

/** Buckets every real attacking type into weaknesses (>1x), resistances (<1x but >0), immunities (0x). */
fun typeMatchups(defType1: Int, defType2: Int): TypeMatchups {
    val weaknesses = mutableListOf<TypeMatchup>()
    val resistances = mutableListOf<TypeMatchup>()
    val immunities = mutableListOf<TypeMatchup>()
    val named = HashSet<String>()
    for (atk in activeTypeNames.keys.sorted()) {
        // TYPE_MYSTERY - no real move uses it. Matched by name, not id 9:
        // expansion-numbered hacks put Steel at 9 (Too Many Types 2).
        if (activeTypeNames[atk] == "???") continue
        // A second type under a name already listed (PureRGB's BONEMERANG, which it calls GROUND): its one move is
        // shown as it is, the list keeps the real type.
        if (!named.add(activeTypeNames[atk].orEmpty())) continue
        val pct = typeMultiplierPct(atk, defType1, defType2)
        val m = TypeMatchup(typeName(atk), formatMultiplier(pct), pct)
        when {
            pct == 100 -> {} // normal effectiveness, not worth listing
            pct == 0 -> immunities.add(m)
            pct > 100 -> weaknesses.add(m)
            else -> resistances.add(m)
        }
    }
    return TypeMatchups(weaknesses, resistances, immunities)
}

private var typeIdByNameCache: Pair<Map<Int, String>, Map<String, Int>>? = null

/** Name -> id for the active game's type table (cached per table instance). */
fun activeTypeIdByName(): Map<String, Int> {
    val names = activeTypeNames
    typeIdByNameCache?.let { (src, m) -> if (src === names) return m }
    val m = names.entries.associate { (id, name) -> name to id }
    typeIdByNameCache = names to m
    return m
}

fun formatMultiplier(pct: Int): String = when (pct) {
    0 -> "0x"
    25 -> "¼x" // ¼x
    50 -> "½x" // ½x
    100 -> "1x"
    200 -> "2x"
    400 -> "4x"
    else -> "%.2gx".format(pct / 100.0)
}

private const val STATUS1_SLEEP_MASK = 0x7L
private const val STATUS1_POISON = 1L shl 3
private const val STATUS1_BURN = 1L shl 4
private const val STATUS1_FREEZE = 1L shl 5
private const val STATUS1_PARALYSIS = 1L shl 6
private const val STATUS1_TOXIC_POISON = 1L shl 7

/** Mirrors STATUS1_* bit layout in build/pokefirered/include/constants/battle.h. */
fun statusLabel(status: Long): String = when {
    status and STATUS1_SLEEP_MASK != 0L -> "SLP"
    status and STATUS1_FREEZE != 0L -> "FRZ"
    status and STATUS1_BURN != 0L -> "BRN"
    status and STATUS1_PARALYSIS != 0L -> "PAR"
    status and STATUS1_TOXIC_POISON != 0L -> "TOX"
    status and STATUS1_POISON != 0L -> "PSN"
    else -> ""
}

fun speciesName(id: Int): String {
    if (id == 0) return "-"
    return activeSpeciesNames[id]?.let(::gameCase) ?: "Species#$id"
}

fun itemName(id: Int): String = activeItemNames[id]?.let(::gameCase) ?: "Item#$id"

/**
 * [name] as the running game prints it. The shared vanilla tables are
 * title-cased ("Charmander", "Poké Ball"), but FireRed and Emerald print
 * species, move and item names in capitals ("CHARMANDER", "POKé BALL" - the
 * é stays small). Tables extracted from a hack's own ROM already carry that
 * game's casing, so everything else is left alone.
 */
fun gameCase(name: String): String = when {
    // A localized game's names come from its ROM, already in its casing.
    localText != null -> name
    // A mixed-case hack (Rocket Edition, Recharged Emerald): its own tables, already in the game's casing.
    runningFireRedHack?.ownCase == true || runningEmeraldHack?.ownCase == true -> name
    activeGame == GameKind.FIRERED || activeGame == GameKind.EMERALD -> buildString(name.length) {
        for (c in name) append(if (c == 'é') c else c.uppercaseChar())
    }
    else -> name
}

/**
 * Item [id]'s description as the running game's bag shows it - read from the player's own
 * ROM (RomItemText; the games' text isn't bundled), "" without one.
 */
fun itemDescription(id: Int): String = when (activeGame) {
    // Gen 1 items have no descriptions of their own: ours, and what each TM / HM teaches.
    // ... in English: the German / French / Italian / Spanish carts get none rather than English under their names.
    // Red and Blue have Yellow's items (gen_gen1_tables.py checks it): its text fits them.
    GameKind.YELLOW, GameKind.RED, GameKind.BLUE -> if (romLanguage != 'E') "" else itemDescriptionsYellow[id] ?: tmDescriptionsYellow[id] ?: ""
    // PureRGB renamed, replaced and added items: Yellow's text only where the item is still Yellow's (same id, same name);
    // its own TMs teach other moves (tmDescriptionsPureRgb).
    GameKind.PURE_RED, GameKind.PURE_BLUE -> tmDescriptionsPureRgb[id]
        ?: itemDescriptionsYellow[id]?.takeIf { itemNamesPureRgb[id] == itemNamesYellow[id] } ?: ""
    // Yellow Legacy kept Yellow's items; its TMs teach its own moves.
    GameKind.YELLOW_LEGACY -> tmDescriptionsYellowLegacy[id] ?: itemDescriptionsYellow[id] ?: ""
    // Crystal's own text, from the cart (a TM's: its move's), like the GBA games'.
    // (Prism's are compressed: none.)
    GameKind.CRYSTAL, GameKind.CRYSTAL_CLEAR, GameKind.PRISM, GameKind.GOLD, GameKind.SILVER, GameKind.POLISHED_CRYSTAL,
    GameKind.CRYSTAL_LEGACY -> activeGen2.pokedex?.gen2?.let { Gen2Dex.itemDescription(it, id) }.orEmpty()
    else -> RomItemText.description(id)
}

// Display order/labels for Item.pocket (POCKET_* in Telemetry.kt), matching
// the in-game bag's own tab order.
val pocketOrder: List<Int> = listOf(POCKET_ITEMS, POCKET_POKE_BALLS, POCKET_TM_HM, POCKET_BERRIES, POCKET_KEY_ITEMS)

/** The running game's bag tabs: [pocketOrder], or a game's own pockets where its bag shows more of them apart (Team Rocket
 * Edition 2026's eight, in its gBagPockets' order: [TEAM_ROCKET_V3_BAG_POCKET_ORDER]). */
val activePocketOrder: List<Int> get() =
    if (activeGame == GameKind.TEAM_ROCKET && teamRocketV3) TEAM_ROCKET_V3_BAG_POCKET_ORDER else pocketOrder

fun pocketLabel(pocket: Int): String = when (pocket) {
    POCKET_ITEMS -> "Items"
    POCKET_POKE_BALLS -> "Poke Balls"
    POCKET_TM_HM -> "TMs & HMs"
    POCKET_BERRIES -> "Berries"
    POCKET_KEY_ITEMS -> "Key Items"
    POCKET_MEDICINE -> "Medicine"
    POCKET_MAIL -> "Mail"
    POCKET_BATTLE_ITEMS -> "Battle Items"
    POCKET_MEGA_STONES -> "Mega Stones"
    else -> "Items"
}

data class MoveInfoLookup(val name: String, val type: Int, val power: Int = 0)

fun lookupMove(id: Int): MoveInfoLookup {
    if (id == 0) return MoveInfoLookup("-", TYPE_NONE)
    val info = activeMoveData[id] ?: return MoveInfoLookup("Move#$id", TYPE_NONE)
    return MoveInfoLookup(gameCase(info.name), info.type, info.power)
}

val directionNames = mapOf(
    1 to "South", 2 to "North", 3 to "West", 4 to "East",
    5 to "Southwest", 6 to "Southeast", 7 to "Northwest", 8 to "Northeast",
)
