package com.pokedaisy.app.companion.data

import com.pokedaisy.app.companion.i18n.tr

data class MoveVsFoe(val vsName: String, val label: String, val pct: Int)
data class MoveView(val name: String, val type: String, val pp: Int, val power: Int, val vs: List<MoveVsFoe> = emptyList(), val id: Int = 0)

/**
 * Doubles as the party-mon detail view: Types/Moves/Weaknesses/Resistances/
 * Immunities are populated for party mons too, not just battlers, so the UI
 * can show a party member's moveset and matchups without a separate type.
 * Mirrors battleMonView in tools/telemetry-viewer/web.go.
 */
data class MonView(
    val species: Int,
    val name: String,
    val level: Int,
    val hp: Int,
    val maxHp: Int,
    val status: String,
    val types: List<String>,
    val iconAsset: String?,
    val genderSymbol: Int = GENDER_SYMBOL_UNKNOWN,
    // An egg: the party menu shows just "EGG" + the egg icon (no Lv/HP/gender).
    val isEgg: Boolean = false,
    // Player/party side: this mon's moveset (with per-foe multipliers when in battle).
    val moves: List<MoveView> = emptyList(),
    // Foe side only: type matchup summary.
    val weaknesses: List<TypeMatchup> = emptyList(),
    val resistances: List<TypeMatchup> = emptyList(),
    val immunities: List<TypeMatchup> = emptyList(),
    /** Party mons on FireRed/Emerald: EXP within the current level; null = unknown. */
    val exp: ExpProgress? = null,
    /** Party mons: the personality value (see [Mon.personality]); 0 = unknown. */
    val personality: Long = 0,
    /** IVs / EVs / nature (native reads only); null = unknown. */
    val stats: MonStats? = null,
    /** Battlers (native reads): the ability's id in the game's table; 0 = unknown. Its name is
     * read from the ROM where it's shown ([PokedexSource.abilityName]) unless [abilityName] has it. */
    val abilityId: Int = 0,
    val abilityName: String? = null,
    /** Battlers: the held item's name ("" = none); null = unknown. */
    val heldItem: String? = null,
    /** Party mons (native reads): the held item's id, for the party menu's marker; 0 = none or unknown. */
    val heldItemId: Int = 0,
    /** Battlers: Attack, Defense, Speed, Sp. Atk, Sp. Def as the battle has them; null = unknown. */
    val battleStats: List<Int>? = null,
    /** Battlers: the stages on [battleStats], then accuracy, evasion (-6..+6); null = unknown. */
    val statStages: List<Int>? = null,
    /** Party mons: the nickname as the game stores it ([Mon.nickname]); null = not read. */
    val nickname: String? = null,
    /** Shiny (the battle screen's and INFO's SHINY marks). */
    val shiny: Boolean = false,
) {
    /** What the game's party menu prints: the nickname where it was read, else the species ([name]). */
    val shownName: String get() = nickname ?: name

    /** The nickname is the player's own, not the species' name (the summary adds the species then). */
    val renamed: Boolean get() = nickname != null && !nickname.equals(name, ignoreCase = true)
}

/** [total] EXP; this level starts at [levelStart], the next at [nextLevel] (== levelStart at Lv100). */
data class ExpProgress(val total: Long, val levelStart: Long, val nextLevel: Long) {
    val toNext: Long get() = (nextLevel - total).coerceAtLeast(0)
    val fraction: Float get() =
        if (nextLevel <= levelStart) 1f else ((total - levelStart).toFloat() / (nextLevel - levelStart)).coerceIn(0f, 1f)
}

/** Vanilla Gen 3 growth-rate tables (ExpTables.kt) - FireRed and Emerald share them. */
fun expProgress(species: Int, level: Int, exp: Long?): ExpProgress? {
    if (exp == null || level !in 1..100) return null
    // Gen 1's growth rates and curves are Gen 3's for the first 151 (Yellow's species are Dex numbers here).
    if (activeGame != GameKind.FIRERED && activeGame != GameKind.EMERALD && !activeGame.isGen1 &&
        activeGame != GameKind.ORANGE_ISLANDS && activeGame != GameKind.RECHARGED_YELLOW &&
        activeGame != GameKind.MARIOMON && activeGame != GameKind.TEAM_ROCKET && activeGame != GameKind.WISHES &&
        activeGame != GameKind.DREAMSTONE &&
        // Crystal's growth rates for its 251 are Gen 3's, byte for byte, and its curves the same formulas (CrystalDecodeTest);
        // Crystal Clear, Gold and Silver kept Crystal's base data (gen_gen2_tables.py checks it).
        !activeGame.isGen2) return null
    // Prism's species are its own ids, with its own growth rates (BaseData): the four Gen 3 shares by id (0 medium
    // fast, 3 medium slow, 4 fast, 5 slow); its others (1, 2, 6, 7) have no curve here.
    if (activeGame == GameKind.PRISM) {
        val rate = activeGen2.growthRates?.getOrNull(species)?.takeIf { it in setOf(0, 3, 4, 5) } ?: return null
        val table = gen3ExperienceTables.getOrNull(rate) ?: return null
        val start = table[level].toLong()
        return ExpProgress(exp, start, if (level < 100) table[level + 1].toLong() else start)
    }
    // Polished Crystal's ids (its 9-bit species, then the variant forms) with its own four rates, numbered 0 medium fast,
    // 1 medium slow, 2 fast, 3 slow (constants/pokemon_data_constants.asm) - the same curves as Gen 3's 0, 3, 4, 5.
    if (activeGame == GameKind.POLISHED_CRYSTAL) {
        val rate = activeGen2.growthRates?.getOrNull(species)?.let { intArrayOf(0, 3, 4, 5).getOrNull(it) } ?: return null
        val table = gen3ExperienceTables.getOrNull(rate) ?: return null
        val start = table[level].toLong()
        return ExpProgress(exp, start, if (level < 100) table[level + 1].toLong() else start)
    }
    // Recharged Yellow kept FireRed's growth rates and gExperienceTables (both checked byte for byte).
    // Orange Islands kept FireRed's growth rates but for its CRYSTAL ONIX slots.
    if (activeGame == GameKind.ORANGE_ISLANDS && species >= 409) return null
    // Super Mariomon keeps vanilla's curves; its species' rates come from its own gBaseStats.
    // So does Team Rocket Edition: Dragonsden (its species are National Dex ids, past Gen 3's).
    val rate = if (activeGame == GameKind.MARIOMON) (if (mariomonV13) growthRatesMariomonV13 else growthRatesMariomon).getOrNull(species)
        else if (activeGame == GameKind.TEAM_ROCKET) growthRatesTeamRocket.getOrNull(species)?.takeIf { it <= 5 }
        // Wishes of Tomorrow (expansion 1.15.3): vanilla's curves, its gSpeciesInfo's rates (gen_expansion_tables.py).
        else if (activeGame == GameKind.WISHES) growthRatesWishes.getOrNull(species)?.takeIf { it <= 5 }
        // PureRGB moved 25 species to other curves: its own BaseStats' (gen_gen1_tables.py purered).
        else if (activeGame.isPureRgb) gen1GrowthRatesPureRgb.getOrNull(species)
        // Dreamstone Mysteries too (expansion's curves are vanilla's; its rates from gSpeciesInfo, gen_expansion_tables.py).
        else if (activeGame == GameKind.DREAMSTONE) growthRatesDreamstone.getOrNull(species)?.takeIf { it <= 5 }
        else gen3GrowthRates.getOrNull(species)?.toInt()
    if (rate == null) return null
    val table = gen3ExperienceTables.getOrNull(rate) ?: return null
    val start = table[level].toLong()
    val next = if (level < 100) table[level + 1].toLong() else start
    return ExpProgress(exp, start, next)
}

data class ItemView(
    val itemId: Int,
    val name: String,
    val quantity: Int,
    val iconAsset: String?,
    val pocket: Int = POCKET_ITEMS,
    val description: String = "",
)

data class LocationView(
    val mapSecName: String,
    val regionMapAsset: String?, // e.g. "kanto" -> regionmap/kanto.png (RomArt)
    val highlightX: Int,
    val highlightY: Int,
    val highlightW: Int,
    val highlightH: Int,
)

/**
 * A BEST EFFORT match: read as [title] ([id]); [full] = every part held, else [off] are switched off;
 * [fresh] = matched just now, by best effort itself - worth a notice ([report]: what sharing it would
 * send, when the ROM's hash is known). [picked]: the player chose it. [guess]: no party to check it
 * by yet, only the ROM's names. [doubt]: once a party read, it didn't hold - the player picks again
 * or keeps it ([suggest]: the game it reads as instead, if one is sure).
 */
data class BestEffortView(
    val title: String, val full: Boolean, val off: Set<BestEffort.Part>, val fresh: Boolean,
    val report: BestEffortReport? = null,
    val id: String = "",
    val picked: Boolean = false,
    val guess: Boolean = false,
    val doubt: Boolean = false,
    val suggest: String? = null,
)

/** One game in WHICH GAME IS THIS?: [likely] = the ROM's names are its own, [readsParty] = the party reads through it. */
data class GameOption(val id: String, val title: String, val likely: Boolean, val readsParty: Boolean)

/**
 * WHICH GAME IS THIS? for a ROM the app doesn't know (a Gen 3 base game): the games it could be,
 * best first ([GameOption]s of its base game), the one in use ([current]), and why none was
 * chosen by itself ([miss]: no party yet / none reads). [declined]: the player said NONE OF THESE -
 * NOT SUPPORTED stays, with a button back here.
 */
data class GamePickerView(
    val options: List<GameOption>,
    val current: String? = null,
    val miss: BestEffort.Miss? = null,
    val declined: Boolean = false,
)

data class SnapshotView(
    val connected: Boolean,
    val error: String? = null,
    /** The running ROM is one the companion can't read (see TelemetrySampler.detect). */
    val unsupported: Boolean = false,
    /** Read through BEST EFFORT ([BestEffort]): what as, and how well; null = a ROM the app knows. */
    val bestEffort: BestEffortView? = null,
    /** WHICH GAME IS THIS? - set for every ROM the app doesn't know that's a Gen 3 base game. */
    val gamePicker: GamePickerView? = null,
    val frameCounter: Long = 0,
    val inBattle: Boolean = false,
    val isDoubleBattle: Boolean = false,
    val facingDirection: String = "",
    val x: Int = 0,
    val y: Int = 0,
    val location: LocationView = LocationView("", null, 0, 0, 0, 0),
    /** Raw MAPSEC id [location] was derived from — kept separately since
     * [LocationView] only has the resolved display name/highlight, not the
     * id itself. Used to look up the FF-music table (see FfMusicPlayer). */
    val regionMapSectionId: Int = 0,
    val party: List<MonView> = emptyList(),
    /** The PC's boxes (the BOXES tab); null = not read for this game. */
    val pcBoxes: BoxesView? = null,
    val battlePlayer: List<MonView> = emptyList(),
    val battleOpponent: List<MonView> = emptyList(),
    val items: List<ItemView> = emptyList(),
    // v3 telemetry only (0/NONE on an older ROM) - see BATTLE_INPUT_* in
    // Telemetry.kt. This ~1Hz snapshot is enough to gate the touch battle
    // UI's enabled/disabled look; actual sequencing timing uses the separate
    // fast poll (EmulatorEngine.onBattleInputSample), not this field.
    val battleActiveBattler: Int = 0,
    val battleInputState: Int = BATTLE_INPUT_NONE,
    /** The game this snapshot was read from; null until it's detected. The UI
     * keys on it - [activeGame] is a plain global Compose can't observe, so a
     * screen reading it alone keeps the previous game's look after a launch. */
    val game: GameKind? = null,
    /** The [romGameCode] / [romLanguage] this snapshot's names were read with: a launch's saved copy
     * ([SnapshotCache]) puts them back with [game], so names looked up while drawing (a held item's tag)
     * are the game's language before detection (GitHub #227: English until the tab was reopened). */
    val textCode: String = "",
    val textLanguage: Char = 'E',
    /** Seen / caught flags for the POKéDEX tab; null = no tab for this game. */
    val pokedex: PokedexState? = null,
    /** The map the player is on (SaveBlock1.location) - the GUIDE's HERE page. */
    val mapGroup: Int = 0,
    val mapNum: Int = 0,
    /** Event flags / vars and the ROM tables behind HERE / NEXT BOSS; null = not for this game. */
    val progress: SaveProgress? = null,
    /** A Repel's steps left where the reader has them itself (a DS game, [Telemetry.repelSteps]); null = see [progress]. */
    val repelSteps: Int? = null,
    /** [Telemetry.regionMapTile]: the player's region-map tile where the reader has it (a DS game's Town Map block). */
    val regionMapTile: Pair<Int, Int>? = null,
    /** [Telemetry.townsVisited]: the first-arrival flags that colour a DS Town Map's towns; null = not read. */
    val townsVisited: Long? = null,
    val guideTables: GuideTables? = null,
    /** A trainer battle's whole opposing party, in party order (the FOE TEAM strip). */
    val enemyParty: List<MonView> = emptyList(),
    /** [enemyParty] index of the foe that's out; -1 = unknown. */
    val enemyActive: Int = -1,
    /** [Telemetry.isTrainerBattle]: in a battle, whether it's a trainer's (null = not read). */
    val isTrainerBattle: Boolean? = null,
    /** The foe the trainer is about to send in ("... is about to use X"):
     * set only while the one that's out has fainted; -1 = none. */
    val enemyNext: Int = -1,
    /** The player's money; null = unknown for this game. */
    val money: Long? = null,
    /** The current map's size and type, for the player's spot on the region map (see [PlayerMapTile]). */
    val mapWidth: Int = 0,
    val mapHeight: Int = 0,
    val mapType: Int = -1,
    /** 0 male, 1 female, -1 unknown: which head marks the player on the region map. */
    val playerGender: Int = -1,
    /** The CARD tab's data; null = the game gets no card. */
    val trainerCard: TrainerCardInfo? = null,
    /** Non-null = the copy saved beside the savestate this launch resumed ([SnapshotCache]), shown
     * until the live game's data is in: the data tabs the game had then (DEX / GUIDE / CARD, whose
     * ROM tables aren't cached - they wait on LOADING). */
    val cachedTabs: Set<String>? = null,
)

/** The game's data is in (not just detected): the save's party or the map. */
val SnapshotView.hasData: Boolean
    get() = connected && game != null && (party.isNotEmpty() || location.mapSecName.isNotEmpty())

/** This battler's moves with their effectiveness against [foe] instead of the
 * foe that's out (BATTLE INFO showing another of the trainer's Pokémon). */
fun MonView.withMovesVs(foe: MonView): MonView = withMovesVs(listOf(foe))

/** [withMovesVs] against every one of [targets] (a double battle's two). */
fun MonView.withMovesVs(targets: List<MonView>): MonView = copy(moves = moves.map { mv ->
    val atk = typeIdOf(mv.type)
    mv.copy(vs = targets.map { foe ->
        val t = foe.types.map(::typeIdOf)
        val t1 = t.getOrElse(0) { TYPE_NONE }
        val t2 = t.getOrElse(1) { TYPE_NONE }
        val pct = if (atk != TYPE_NONE && t1 != TYPE_NONE) typeMultiplierPct(atk, t1, t2) else 100
        MoveVsFoe(foe.name, formatMultiplier(pct), pct)
    })
})

/** A moveset's views: [ids] with their [pp], empty slots (0) dropped. */
private fun moveViews(ids: IntArray, pp: IntArray): List<MoveView> = ids.toList().mapIndexedNotNull { idx, moveId ->
    if (moveId == 0) null else lookupMove(moveId).let { MoveView(it.name, typeName(it.type), pp.getOrElse(idx) { 0 }, it.power, id = moveId) }
}

private const val REGION_MAP_TILE_SIZE = 8

/** Games with their own region but no map-section table yet: vanilla Kanto's
 * names and map would be wrong there, so the Map tab says so instead (until
 * [RomRegionMap] has read the ROM's own). */
private val gamesWithoutRegionData = setOf(GameKind.ODYSSEY)

fun lookupLocation(regionMapSectionId: Int): LocationView {
    // A FireRed-engine hack's own map, read from its ROM, over the bundled
    // tables; FireRed itself keeps those (they carry its title-cased names).
    val rom = if (readsRomRegionMap) RomRegionMap.current else null
    if (rom == null && activeGame in gamesWithoutRegionData) return LocationView("", null, 0, 0, 0, 0)
    val images = rom?.images ?: activeRegionMapImages
    val info = (rom?.sections ?: activeMapSecData)[regionMapSectionId]
        ?: return LocationView(
            // Buildings, caves and other interiors report a map section the
            // region map doesn't cover (often 0 or MAPSEC_NONE).
            if (regionMapSectionId <= 0 || regionMapSectionId >= 0xC0) tr("Indoors") else tr("Unknown area ({0})", regionMapSectionId),
            null, 0, 0, 0, 0,
        )
    if (info.region !in images.indices) {
        return LocationView(info.name, null, 0, 0, 0, 0)
    }
    return LocationView(
        mapSecName = info.name,
        regionMapAsset = images[info.region],
        highlightX = info.x * REGION_MAP_TILE_SIZE,
        highlightY = info.y * REGION_MAP_TILE_SIZE,
        highlightW = info.w * REGION_MAP_TILE_SIZE,
        highlightH = info.h * REGION_MAP_TILE_SIZE,
    )
}

fun spriteAsset(kind: String, id: Int): String? {
    if (id == 0) return null
    val dir = if (kind == "items") activeItemSpriteDir else activeMonSpriteDir
    return if (dir == null) null else "$dir/$id.png"
}

/** A shiny Pokémon's party icon: `<dir>/shiny/<id>.png`, drawn in its species' shiny palette (DecompIconSource). */
fun shinyIconAsset(species: Int): String? = spriteAsset("pokemon", species)?.let { it.substringBeforeLast('/') + "/shiny/" + it.substringAfterLast('/') }

/** A party (or PC box) Pokémon as the PARTY tab and the summary show it. */
fun partyMonView(m: Mon): MonView {
    val st = activeSpeciesTypeData[m.species]
    val type1 = st?.type1 ?: TYPE_NONE
    val type2 = st?.type2 ?: TYPE_NONE
    val matchups = typeMatchups(type1, type2)
    val moves = m.moves.toList().mapIndexedNotNull { idx, moveId ->
        if (moveId == 0) null
        else {
            val info = lookupMove(moveId)
            MoveView(info.name, typeName(info.type), m.pp[idx], info.power, id = moveId)
        }
    }
    val isEgg = m.isEgg || isVanillaEgg(m.species)
    return MonView(
        species = m.species,
        name = if (isEgg) gameCase("Egg") else speciesName(m.species),
        isEgg = isEgg,
        level = m.level,
        hp = m.hp,
        maxHp = m.maxHp,
        status = statusLabel(m.status),
        types = monTypes(type1, type2),
        // A DS game's eggs keep their species: the egg's own icon is NdsArt's 494.
        iconAsset = if (m.shiny && !isEgg) shinyIconAsset(m.species) else spriteAsset("pokemon", if (isEgg && activeGame.isGen5) NdsArt.BLACK_EGG else if (isEgg && activeGame.isDsGame) 494 else m.species),
        genderSymbol = m.genderSymbol,
        moves = moves,
        exp = if (isEgg) null else expProgress(m.species, m.level, m.exp),
        personality = m.personality,
        stats = if (isEgg) null else m.stats,
        heldItemId = m.heldItem,
        nickname = if (isEgg) null else m.nickname,
        shiny = m.shiny && !isEgg,
        weaknesses = matchups.weaknesses,
        resistances = matchups.resistances,
        immunities = matchups.immunities,
    )
}

/** The PC's boxes as the BOXES tab shows them. */
data class BoxesView(val currentBox: Int, val boxes: List<BoxView>)

/** One box: its name (and the game's bytes for it), wallpaper and 30 slots (null = empty). */
data class BoxView(val name: String, val wallpaper: Int, val mons: List<MonView?>) {
    val count: Int get() = mons.count { it != null }
}

// The last boxes turned into views: the reader hands back the same PcBoxes while the storage is unchanged.
private var lastBoxes: Pair<PcBoxes, BoxesView>? = null

private fun boxesView(b: PcBoxes): BoxesView = synchronized(PcBoxes::class) {
    lastBoxes?.takeIf { it.first === b }?.let { return it.second }
    BoxesView(b.currentBox, b.boxes.map { box -> BoxView(box.name, box.wallpaper, box.mons.map { it?.let(::partyMonView) }) })
        .also { lastBoxes = b to it }
}

fun buildSnapshotView(t: Telemetry): SnapshotView {
    // v2 ROMs export the icon-graphics table addresses; hand them to the live
    // sprite reader so nothing has to be bundled.
    DecompIconSource.tables = t.icons
    // HeartGold / SoulSilver's Pokégear map follows the save's state (PokegearMap).
    t.pokegearState?.let { PokegearMap.state = it to (t.townsVisited ?: 0L) }

    val party = (0 until t.partyCount.coerceAtMost(PARTY_SIZE)).map { i -> partyMonView(t.party[i]) }
    val pcBoxes = t.pcBoxes?.let(::boxesView)

    var battlePlayer = emptyList<MonView>()
    var battleOpponent = emptyList<MonView>()
    if (t.inBattle) {
        val opponents = listOf(BATTLE_POS_OPPONENT_LEFT, BATTLE_POS_OPPONENT_RIGHT)
            .map { t.battleMons[it] }
            .filter { it.species != 0 }

        // gBattleMons carries no EVs: each battler takes its party struct's
        // stats, matched by species / level / HP (each party slot once).
        val usedParty = mutableSetOf<Int>()
        val usedFoes = mutableSetOf<Int>()
        fun statsFor(b: BattleMon, pool: List<Mon>, used: MutableSet<Int>, prefer: Int = -1): MonStats? {
            fun fits(i: Int, hp: Boolean) = i !in used && pool[i].species == b.species && pool[i].level == b.level &&
                (!hp || pool[i].hp == b.hp)
            val i = prefer.takeIf { it in pool.indices && fits(it, false) }
                ?: pool.indices.firstOrNull { fits(it, true) } ?: pool.indices.firstOrNull { fits(it, false) }
                ?: return null
            used += i
            return pool[i].stats
        }

        fun buildPlayerMon(b: BattleMon): MonView? {
            if (b.species == 0) return null
            val moves = b.moves.toList().mapIndexedNotNull { idx, moveId ->
                if (moveId == 0) null
                else {
                    val info = lookupMove(moveId)
                    val vs = opponents.map { foe ->
                        val pct = if (info.type != TYPE_NONE) typeMultiplierPct(info.type, foe.type1, foe.type2) else 100
                        MoveVsFoe(speciesName(foe.species), formatMultiplier(pct), pct)
                    }
                    MoveView(info.name, typeName(info.type), b.pp[idx], info.power, vs, id = moveId)
                }
            }
            return MonView(
                species = b.species, name = speciesName(b.species), level = b.level,
                hp = b.hp, maxHp = b.maxHp, status = statusLabel(b.status1),
                types = monTypes(b.type1, b.type2),
                iconAsset = if (b.shiny) shinyIconAsset(b.species) else spriteAsset("pokemon", b.species),
                moves = moves,
                stats = statsFor(b, t.party, usedParty),
            )
        }

        fun buildOpponentMon(b: BattleMon, prefer: Int = -1): MonView? {
            if (b.species == 0) return null
            val matchups = typeMatchups(b.type1, b.type2)
            return MonView(
                species = b.species, name = speciesName(b.species), level = b.level,
                hp = b.hp, maxHp = b.maxHp, status = statusLabel(b.status1),
                types = monTypes(b.type1, b.type2),
                iconAsset = if (b.shiny) shinyIconAsset(b.species) else spriteAsset("pokemon", b.species),
                // The foe's moveset (battle INFO's FOE MOVES); verdicts against
                // the player's battlers are worked out where they're shown.
                moves = moveViews(b.moves, b.pp),
                abilityId = b.ability,
                heldItem = if (b.stats == null) null else if (b.item == 0) "" else itemName(b.item),
                battleStats = b.stats?.toList(),
                statStages = b.statStages?.map { it - 6 },
                weaknesses = matchups.weaknesses,
                resistances = matchups.resistances,
                immunities = matchups.immunities,
                stats = statsFor(b, t.battleFoes, usedFoes, prefer),
                shiny = b.shiny,
            )
        }

        battlePlayer = listOfNotNull(
            buildPlayerMon(t.battleMons[BATTLE_POS_PLAYER_LEFT]),
            buildPlayerMon(t.battleMons[BATTLE_POS_PLAYER_RIGHT]),
        )
        battleOpponent = listOfNotNull(
            // The left foe is gEnemyParty[enemyActive] in a trainer battle.
            buildOpponentMon(t.battleMons[BATTLE_POS_OPPONENT_LEFT], prefer = t.enemyActive),
            buildOpponentMon(t.battleMons[BATTLE_POS_OPPONENT_RIGHT]),
        )
    }

    val items = (0 until t.itemCount.coerceAtMost(t.items.size)).map { i ->
        val it = t.items[i]
        ItemView(
            it.itemId,
            itemName(it.itemId),
            it.quantity,
            spriteAsset("items", it.itemId),
            it.pocket,
            itemDescription(it.itemId),
        )
    }

    return SnapshotView(
        connected = true,
        frameCounter = t.frameCounter,
        inBattle = t.inBattle,
        isDoubleBattle = t.isDoubleBattle,
        facingDirection = directionNames[t.facingDirection] ?: "",
        x = t.x,
        y = t.y,
        location = lookupLocation(t.regionMapSectionId).let { l -> t.mapSecName?.let { l.copy(mapSecName = it) } ?: l },
        regionMapSectionId = t.regionMapSectionId,
        party = party,
        pcBoxes = pcBoxes,
        battlePlayer = battlePlayer,
        battleOpponent = battleOpponent,
        items = items,
        battleActiveBattler = t.battleActiveBattler,
        battleInputState = t.battleInputState,
        pokedex = t.pokedex,
        mapGroup = t.mapGroup,
        mapNum = t.mapNum,
        progress = t.progress,
        repelSteps = t.repelSteps,
        regionMapTile = t.regionMapTile,
        townsVisited = t.townsVisited,
        guideTables = t.guideTables,
        enemyParty = t.enemyParty.map { m ->
            val st = activeSpeciesTypeData[m.species]
            val type1 = st?.type1 ?: TYPE_NONE
            val type2 = st?.type2 ?: TYPE_NONE
            val matchups = typeMatchups(type1, type2)
            MonView(
                species = m.species, name = speciesName(m.species), level = m.level,
                hp = m.hp, maxHp = m.maxHp, status = statusLabel(m.status),
                types = monTypes(type1, type2),
                iconAsset = spriteAsset("pokemon", m.species),
                moves = moveViews(m.moves, m.pp),
                weaknesses = matchups.weaknesses,
                resistances = matchups.resistances,
                immunities = matchups.immunities,
                stats = m.stats,
            )
        },
        enemyActive = t.enemyActive,
        isTrainerBattle = t.isTrainerBattle,
        // Only meaningful once the foe that's out is down and a live one is picked.
        enemyNext = t.enemyNext.takeIf { next ->
            next != t.enemyActive && t.enemyParty.getOrNull(next)?.hp?.let { it > 0 } == true &&
                t.enemyParty.getOrNull(t.enemyActive)?.hp == 0
        } ?: -1,
        money = t.money,
        mapWidth = t.mapWidth,
        mapHeight = t.mapHeight,
        mapType = t.mapType,
        playerGender = t.playerGender,
        trainerCard = t.trainerCard,
    )
}

/**
 * SPECIES_EGG (412) in FireRed/Emerald's own species numbering - what the native
 * reads map an egg to (MON_DATA_SPECIES_OR_EGG, like the party menu's icon). Most hacks renumber species, so
 * this only means "egg" for the games checked to keep it: the retail pair and
 * the CFRU hacks (each ROM's icon table has the egg at 412).
 */
const val SPECIES_EGG_VANILLA = 412

fun isVanillaEgg(species: Int): Boolean =
    // Emerald Legacy runs as EMERALD, but its 412 is a Deoxys form (its eggs are flagged: NATIVE_EMERALD_LEGACY.eggSpecies).
    species == SPECIES_EGG_VANILLA && !emeraldLegacy && when (activeGame) {
        GameKind.FIRERED, GameKind.EMERALD,
        GameKind.UNBOUND, GameKind.RADICAL_RED, GameKind.ODYSSEY, GameKind.AMETHYST, GameKind.ORANGE_ISLANDS,
        GameKind.SWORD_SHIELD, GameKind.RECHARGED_YELLOW, GameKind.GS_CHRONICLES -> true
        else -> false
    }

/**
 * Whether the battle gave the BATTLE tab anything to show: a battler on either
 * side, the foe's party, or the game's battle input state (touch controls). A
 * game whose battle memory isn't mapped reads none of them - then the tab stays
 * away rather than open on an empty page.
 */
val SnapshotView.hasBattleData: Boolean
    get() = battlePlayer.isNotEmpty() || battleOpponent.isNotEmpty() || enemyParty.isNotEmpty() ||
        battleInputState != BATTLE_INPUT_NONE

/** In a battle with something to show ([hasBattleData]): when the BATTLE tab is there. */
val SnapshotView.showsBattle: Boolean get() = inBattle && hasBattleData
