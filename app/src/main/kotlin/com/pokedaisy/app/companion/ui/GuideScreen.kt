package com.pokedaisy.app.companion.ui

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.pokedaisy.app.companion.data.EncounterSlot
import com.pokedaisy.app.companion.data.hasAreaGuide
import com.pokedaisy.app.companion.data.areaSection
import com.pokedaisy.app.companion.data.areaThings
import com.pokedaisy.app.companion.data.areaKey
import com.pokedaisy.app.companion.data.englishMapSecName
import com.pokedaisy.app.companion.data.Have
import com.pokedaisy.app.companion.data.AreaThing
import com.pokedaisy.app.companion.data.AreaKind
import com.pokedaisy.app.companion.data.Evolution
import com.pokedaisy.app.companion.data.EvolutionSource
import com.pokedaisy.app.companion.data.GameGuide
import com.pokedaisy.app.companion.data.GameKind
import com.pokedaisy.app.companion.data.GuideContext
import com.pokedaisy.app.companion.data.GuideEntry
import com.pokedaisy.app.companion.data.GuidePage
import com.pokedaisy.app.companion.data.GuideRomSource
import com.pokedaisy.app.companion.data.otherTimeSetOf
import com.pokedaisy.app.companion.data.wildSetOf
import com.pokedaisy.app.companion.data.GuideSection
import com.pokedaisy.app.companion.data.GuideTables
import com.pokedaisy.app.companion.data.MapEncounters
import com.pokedaisy.app.companion.data.MonView
import com.pokedaisy.app.companion.data.PokedexSource
import com.pokedaisy.app.companion.data.PokedexState
import com.pokedaisy.app.companion.data.PokedexTables
import com.pokedaisy.app.companion.data.SnapshotView
import com.pokedaisy.app.companion.data.TYPE_NONE
import com.pokedaisy.app.companion.data.TrainerMon
import com.pokedaisy.app.companion.data.activeSpeciesTypeData
import com.pokedaisy.app.companion.data.evolutionKind
import com.pokedaisy.app.companion.data.evolutionText
import com.pokedaisy.app.companion.data.GuideId
import com.pokedaisy.app.companion.data.activeMapSecData
import com.pokedaisy.app.companion.data.generatedWhereIs
import com.pokedaisy.app.companion.data.guideDexSpecies
import com.pokedaisy.app.companion.data.gameGuide
import com.pokedaisy.app.companion.data.guideId
import com.pokedaisy.app.companion.data.isPlainLevel
import com.pokedaisy.app.companion.data.itemName
import com.pokedaisy.app.companion.data.lookupMove
import com.pokedaisy.app.companion.data.monTypes
import com.pokedaisy.app.companion.data.speciesName
import com.pokedaisy.app.companion.data.spriteAsset
import com.pokedaisy.app.companion.i18n.tk
import com.pokedaisy.app.companion.i18n.tr
import com.pokedaisy.app.companion.i18n.trGuide
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll

/** What the GUIDE tab has for a game: its hand-written [text], the ROM's
 * evolution table ([evolutions]) and the live pages' tables ([live]). */
class GuideSource(val text: GameGuide?, val evolutions: PokedexTables?, val live: GuideTables?, val id: GuideId?)

/** The GUIDE tab's source for [game], or null when there's nothing to show (no tab). */
@Composable
fun rememberGuide(game: GameKind?, dex: PokedexState?, live: GuideTables?): GuideSource? {
    val tables = dex?.tables?.takeIf { it.evolutions != 0L }
    return remember(game, tables, live) {
        val id = guideId(game, live)
        val text = gameGuide(id)
        if (text == null && tables == null && live == null) null else GuideSource(text, tables, live, id)
    }
}

/**
 * The GUIDE tab's view state, held by CompanionScreen so the page, the open
 * entry and the scroll position survive switching tabs. Only one entry is
 * open at a time: opening another closes it.
 */
@Stable
class GuideUiState {
    var page by mutableStateOf<String?>(null)
    val list = LazyListState()
    /** The open entry's [key], and how far it's revealed (see [GuideEntry.steps]). */
    var open by mutableStateOf<String?>(null)
    var level by mutableStateOf(0)

    fun key(page: GuidePage, section: GuideSection, entry: GuideEntry) = "${page.title}/${section.heading}/${entry.title}/${entry.detail}"

    /** Every entry's [key] in [section]; repeats (two STARDUSTs on one route, HIDDEN ITEMs) get #2, #3… as LazyColumn keys must be unique. */
    fun keys(page: GuidePage, section: GuideSection): List<String> {
        val seen = HashMap<String, Int>()
        return section.entries.map { e ->
            val k = key(page, section, e)
            val n = (seen[k] ?: 0) + 1
            seen[k] = n
            if (n == 1) k else "$k#$n"
        }
    }

    fun levelOf(key: String) = if (open == key) level else 0

    /** One tap: the next step of this entry (closing any other), or closed again after the answer. */
    fun tap(key: String, steps: Int) {
        if (steps == 0) return
        val now = levelOf(key)
        if (now >= steps) { open = null; level = 0 } else { open = key; level = now + 1 }
    }
}

// Page titles stay English (GuideUiState.page and the tests key on them);
// OptionRows translates them where they're drawn.
private val HERE = tk("HERE")
private val NEXT_BOSS = tk("NEXT BOSS")
private val EVOLUTIONS = tk("EVOLUTIONS")
private val WHERE_IS = tk("WHERE IS")
private val TIPS = tk("TIPS") // hand-written pages' titles (TIPS, STUCK?...) are translated where drawn too

/**
 * The GUIDE: HERE (this map's wild Pokémon) and NEXT BOSS from the ROM and
 * the save, the game's hand-written pages, and EVOLUTIONS. The left column
 * (OPTION look, like the DEX) picks the page; the right lists it. Nothing is
 * spoiled up front: an entry shows its title, a tap its hint, another tap
 * the answer, and one more closes it.
 */
@Composable
fun GuideScreen(source: GuideSource, ui: GuideUiState, snapshot: SnapshotView, modifier: Modifier = Modifier) {
    val m = rememberGbaTextMetrics()
    val small = rememberGbaTextMetrics(1f)
    val u = m.u
    val dex = snapshot.pokedex
    val ctx = GuideContext(snapshot.progress, snapshot.items.map { it.itemId }.toSet(), dex?.caught)

    val table by produceKeyedState(source.evolutions?.let(EvolutionSource::cached), source.evolutions) {
        val t = source.evolutions
        if (value == null && t != null) value = withContext(Dispatchers.IO) { EvolutionSource.table(t) }
    }
    val live = source.live
    // The map's wild set the save picks (Emerald Legacy's per-map vars, Lazarus's clock); 0 everywhere else.
    val wildSet = live?.wildSetOf(snapshot.mapGroup, snapshot.mapNum, snapshot.progress) ?: 0
    val here by produceState(
        live?.let { GuideRomSource.cachedEncounters(it, snapshot.mapGroup, snapshot.mapNum, wildSet) },
        live, snapshot.mapGroup, snapshot.mapNum, wildSet,
    ) {
        if (live != null) value = withContext(Dispatchers.IO) {
            dex?.let { PokedexSource.nationalOf(it.tables, 1) } // warm the species -> dex number map
            GuideRomSource.encounters(live, snapshot.mapGroup, snapshot.mapNum, wildSet)
        }
    }
    // The other time of day's set, where the game's clock picks one (Lazarus): its extra Pokémon get a section of their own.
    val otherSet = live?.otherTimeSetOf(snapshot.mapGroup, snapshot.mapNum, snapshot.progress)
    val otherTime by produceState(
        if (live != null && otherSet != null) GuideRomSource.cachedEncounters(live, snapshot.mapGroup, snapshot.mapNum, otherSet) else null,
        live, snapshot.mapGroup, snapshot.mapNum, otherSet,
    ) {
        value = if (live != null && otherSet != null) withContext(Dispatchers.IO) {
            GuideRomSource.encounters(live, snapshot.mapGroup, snapshot.mapNum, otherSet)
        } else null
    }
    // A game with tables of its own (a Game Boy game): its wild Pokémon and boss teams for the save, at once.
    val world = source.text?.world
    val worldHere = remember(world, snapshot.mapGroup, snapshot.mapNum, snapshot.progress) {
        world?.encounters(snapshot.mapGroup, snapshot.mapNum, snapshot.progress)
    }
    val bosses = source.text?.let { it.bossesFor?.invoke(snapshot.mapGroup) ?: it.bosses }.orEmpty()
    val worldParties = remember(world, snapshot.progress) {
        val p = snapshot.progress
        if (world == null || p == null) null else bosses.flatMap { it.trainers(p) }.associateWith { world.party(it, p) }
    }
    // Starts from the cache when every team is already loaded, so the page draws at once.
    val cachedParties = remember(live, snapshot.progress) {
        val p = snapshot.progress
        if (live == null || p == null) emptyMap()
        else bosses.flatMap { it.trainers(p) }.associateWith { GuideRomSource.cachedParty(live, it) }
            .takeIf { m -> m.values.all { it != null } }?.mapValues { it.value.orEmpty() } ?: emptyMap()
    }
    val parties by produceState(cachedParties, live, snapshot.progress) {
        val p = snapshot.progress
        if (live != null && p != null && bosses.isNotEmpty()) value = withContext(Dispatchers.IO) {
            bosses.flatMap { it.trainers(p) }.associateWith { GuideRomSource.party(live, it).orEmpty() }
        }
    }

    val pages = buildList {
        if (live != null || world != null || hasAreaGuide(source.id)) {
            val night = otherSet?.let { wildSet == 1 }
            add(herePage(
                snapshot, worldHere ?: here, dex, live != null || world != null, source.text, source.id, ctx,
                world?.clockNote(snapshot.progress) ?: night?.let { if (it) "NIGHT" else "DAY" },
                otherTime = otherTime?.takeIf { night != null }, otherIsNight = night == false,
            ))
        }
        val teams = worldParties ?: parties
        snapshot.progress?.takeIf { bosses.isNotEmpty() && teams.isNotEmpty() }?.let { add(bossPage(bosses, it, teams, snapshot.party)) }
        addAll(source.text?.pages.orEmpty())
        // No hand-written WHERE IS (the ROM hacks): one built from the area data.
        if (source.text?.pages.orEmpty().none { it.title == WHERE_IS }) source.id?.let { id ->
            generatedWhereIs(id, { activeMapSecData[it]?.name?.uppercase() ?: tr("AREA {0}", it) }) { areaDone(it, snapshot, ctx, dex) }?.let(::add)
        }
        table?.let { add(evolutionsPage(it, snapshot.party)) }
    }
    val page = pages.firstOrNull { it.title == ui.page } ?: pages.firstOrNull()
    LaunchedEffect(page?.title) { ui.list.scrollToItem(0) }

    Row(modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Column(Modifier.fillMaxHeight().weight(0.34f), verticalArrangement = Arrangement.spacedBy(u * 4)) {
            OptionTitleWindow("GUIDE", m)
            OptionListWindow(m, Modifier.fillMaxWidth().weight(1f)) {
                OptionRows(
                    pages.map { p -> Triple(p.title, null, { ui.page = p.title }) },
                    m,
                    Modifier.fillMaxSize(),
                    selected = pages.indexOf(page),
                    minRow = m.rowHeight * 1.1f,
                    labelWeight = 1f,
                    // The D-pad (the companion's window): the cursor shows the page it's on, as a tap does.
                    onFocused = { i -> pages.getOrNull(i)?.let { ui.page = it.title } },
                )
            }
        }

        OptionListWindow(m, Modifier.fillMaxHeight().weight(0.66f)) {
            if (page == null) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    GbaText(tr("LOADING..."), OptionColors.muted, OptionColors.mutedShadow, m)
                }
            } else DenseText {
                // The pages a font pixel smaller where there's room (the Thor): more of them on screen.
                val m = rememberGbaTextMetrics()
                LazyColumn(Modifier.fillMaxSize(), state = ui.list) {
                    page.sections.forEach { section ->
                        item(key = "h/${section.heading}") { SectionHeading(section, small) }
                        val keys = ui.keys(page, section)
                        itemsIndexed(section.entries, key = { i, _ -> keys[i] }) { i, e ->
                            val key = keys[i]
                            EntryRow(e, ui.levelOf(key), e.owned || ctx.has(e.have), m, small) { ui.tap(key, e.steps) }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Shown the first time a game's GUIDE is opened: the hand-written pages are
 * AI-assisted, so they can be wrong. [onAccept] remembers it for this game;
 * [onBack] returns to the previous tab.
 */
@Composable
fun GuideNotice(verified: Boolean, onAccept: () -> Unit, onBack: () -> Unit, modifier: Modifier = Modifier) {
    CompanionBackHandler(onBack = onBack)
    val m = rememberGbaTextMetrics()
    val u = m.u
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(Modifier.widthIn(max = 560.dp)) {
            OptionTitleWindow(tk("BEFORE YOU READ"), m)
            Spacer(Modifier.height(u * 4))
            // Shorter than the text (the one-screen window, an unchecked guide's extra line): the text scrolls, the
            // buttons stay in view under it (they used to be pushed off the bottom).
            OptionListWindow(m, Modifier.fillMaxWidth().weight(1f, fill = false)) {
                Column(Modifier.padding(horizontal = u * 8, vertical = u * 6), verticalArrangement = Arrangement.spacedBy(u * 6)) {
                    Column(
                        Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(u * 6),
                    ) {
                    GbaText(
                        tr("This guide was written with the help of AI, from the game's own data. It can still get details wrong or leave things out - double-check anything that matters before relying on it.") +
                            if (verified) "" else " " + tr("This game's guide hasn't been checked in-game yet."),
                        OptionColors.label, OptionColors.labelShadow, m, maxLines = Int.MAX_VALUE,
                    )
                    GbaText(
                        tr("HERE, NEXT BOSS and EVOLUTIONS come straight from the game's own data."),
                        OptionColors.muted, OptionColors.mutedShadow, rememberGbaTextMetrics(1f), maxLines = Int.MAX_VALUE,
                    )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(u * 4)) {
                        OptionButton(tk("GO BACK"), m, onClick = onBack, modifier = Modifier.weight(1f).height(m.rowHeight * 1.4f))
                        // Where the D-pad starts (the companion's window).
                        OptionButton(tk("I UNDERSTAND"), m, onClick = onAccept, emphasis = true, modifier = Modifier.weight(1f).height(m.rowHeight * 1.4f).focusStart(true))
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionHeading(section: GuideSection, small: GbaTextMetrics) {
    val u = small.u
    Column(Modifier.padding(start = u * 4, end = u * 4, top = u * 6, bottom = u * 1)) {
        // Headings stay English in the page (keys, tests); the app's own ones are translated here.
        GbaText(tr(trGuide(section.heading)), OptionColors.value, OptionColors.valueShadow, small)
        section.note?.let { GbaText(trGuide(it), OptionColors.muted, OptionColors.mutedShadow, small, maxLines = 3) }
    }
}

/** Title (and [owned] ball, icon, detail), then as [level] rises the hint and the answer; a tag says what the next tap shows. */
@Composable
private fun EntryRow(e: GuideEntry, level: Int, owned: Boolean, m: GbaTextMetrics, small: GbaTextMetrics, onClick: () -> Unit) {
    val u = m.u
    val noRipple = remember { MutableInteractionSource() }
    val focused by noRipple.collectIsFocusedAsState()
    val showHint = e.hint != null && level >= 1
    val showAnswer = e.steps > 0 && level >= e.steps
    Column(
        modifier = Modifier
            .fillMaxWidth()
            // The D-pad's cursor (the companion's window): the white row. A fact with nothing to reveal is a stop too, so
            // the D-pad walks - and scrolls - the whole page.
            .background(if (focused) OptionColors.rowSelected else Color.Transparent)
            .drawBehind { drawRowDivider(OptionColors.divider, u.toPx(), 4 * u.toPx()) }
            .then(
                if (e.steps > 0) Modifier.soundClickable(interactionSource = noRipple, indication = null, onClick = onClick)
                else Modifier.focusable(interactionSource = noRipple),
            )
            .padding(horizontal = u * 6, vertical = u * 4),
        verticalArrangement = Arrangement.spacedBy(u * 2),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (owned) {
                PixelArt(PixelIcons.pokeBall, caughtBall, Modifier.size(u * 10).semantics { contentDescription = tr("You have it") })
                Spacer(Modifier.width(u * 4))
            }
            // An unseen Pokémon's icon stays hidden with its name.
            if (e.icon != 0 && (e.steps == 0 || showAnswer)) {
                SpeciesIcon(spriteAsset("pokemon", e.icon), size = u * 24)
                Spacer(Modifier.width(u * 4))
            }
            GbaText(trGuide(e.title), OptionColors.label, OptionColors.labelShadow, m, Modifier.weight(1f), maxLines = 2)
            e.detail?.let { GbaText(it, OptionColors.value, OptionColors.valueShadow, small, Modifier.padding(start = u * 6)) }
            val next = when {
                e.steps == 0 || showAnswer -> null
                e.hint != null && !showHint -> tr("HINT")
                else -> tr("ANSWER")
            }
            next?.let { GbaText(it, OptionColors.muted, OptionColors.mutedShadow, small, Modifier.padding(start = u * 6)) }
        }
        if (showHint) GbaText(tr("HINT: {0}", trGuide(e.hint!!)), OptionColors.muted, OptionColors.mutedShadow, small, maxLines = Int.MAX_VALUE)
        if (showAnswer) GbaText(trGuide(e.answer), OptionColors.value, OptionColors.valueShadow, small, maxLines = Int.MAX_VALUE)
    }
}

/**
 * HERE: what's around the area the player is in (a region map section - a
 * city with its houses, a route with its gates):
 *  - TO DO: the game's own guide entries about this area (areas = …);
 *  - the wild Pokémon of the map they're standing on, by method, straight
 *    from the ROM's encounter tables ([hasWild], native path only). Species
 *    the player has seen are named; unseen ones stay "???" until tapped;
 *  - PEOPLE: gifts and trades its maps' scripts hand out;
 *  - ITEMS: item balls and hidden items (the latter unnamed until tapped).
 * PEOPLE and ITEMS come from GuideAreas (generated from the decomp); what
 * the save's flags say is already done is marked and sorted last.
 */
private fun herePage(
    snapshot: SnapshotView,
    enc: MapEncounters?,
    dex: PokedexState?,
    hasWild: Boolean,
    guide: GameGuide?,
    guideId: GuideId?,
    ctx: GuideContext,
    /** The time of day the lists are for ([com.pokedaisy.app.companion.data.GuideWorld.clockNote]), an English word. */
    clock: String? = null,
    /** The map's other time-of-day set ([com.pokedaisy.app.companion.data.otherTimeSetOf]): what only it has is listed too. */
    otherTime: MapEncounters? = null,
    otherIsNight: Boolean = false,
): GuidePage {
    val place = snapshot.location.mapSecName.ifEmpty { tk("THIS MAP") }.uppercase()
    fun national(species: Int) = dex?.let { PokedexSource.nationalOf(it.tables, guideDexSpecies(species)) } ?: 0
    fun rows(slots: List<EncounterSlot>) = slots.map { s ->
        val n = national(s.species)
        val seen = dex == null || n in dex.seen
        val lv = tr("LV {0}", if (s.minLevel == s.maxLevel) "${s.minLevel}" else "${s.minLevel}-${s.maxLevel}")
        GuideEntry(
            title = if (seen) speciesName(s.species) else "???",
            answer = if (seen) "" else speciesName(s.species),
            detail = "$lv  ${s.percent}%",
            icon = s.species,
            owned = dex != null && n in dex.caught,
        )
    }
    val methods = if (!hasWild) emptyList() else enc?.let {
        listOf(
            tk("GRASS / CAVE") to it.grass, tk("SURFING") to it.water, tk("ROCK SMASH") to it.rockSmash,
            tk("OLD ROD") to it.oldRod, tk("GOOD ROD") to it.goodRod, tk("SUPER ROD") to it.superRod,
            tk("HEADBUTT") to it.headbutt, tk("HEADBUTT (RARE TREES)") to it.headbuttRare,
        ).filter { (_, l) -> l.isNotEmpty() }
    }.orEmpty()
    // The other time of day's Pokémon that the lists above don't have, each once (its first method's odds).
    val onlyThen = otherTime?.let { o ->
        val now = methods.flatMap { (_, l) -> l.map { it.species } }.toSet()
        listOf(o.grass, o.water, o.rockSmash, o.oldRod, o.goodRod, o.superRod, o.headbutt, o.headbuttRare)
            .flatten().filter { it.species !in now }.distinctBy { it.species }
    }.orEmpty()

    // The hand-written guide's entries about this area (not TIPS - they're about the whole game).
    // Hand-written entries tag areas by their English names, whatever the game's language.
    val key = areaKey(englishMapSecName(snapshot.regionMapSectionId, snapshot.location.mapSecName))
    val todo = if (key.isEmpty()) emptyList() else guide?.pages.orEmpty().filter { it.title != TIPS }
        .flatMap { p -> p.sections.flatMap { it.entries } }
        .filter { e -> e.areas.any { areaKey(it) == key } }
        .distinctBy { it.title }

    val things = areaThings(guideId, areaSection(snapshot.game, snapshot.mapNum, snapshot.regionMapSectionId))
    fun done(t: AreaThing) = areaDone(t, snapshot, ctx, dex)
    fun qty(t: AreaThing) = if (t.qty > 1) " x${t.qty}" else ""
    // Place names (and the hint's map names) are game data: only the sentences around them are translated.
    val placeShown = tr(place)
    fun at(t: AreaThing) = if (t.where.isEmpty()) tr("around {0}", placeShown) else tr("in the {0}", trGuide(t.where))
    val people = things.filter { it.kind in PEOPLE_KINDS }.map { t ->
        when (t.kind) {
            AreaKind.MON -> GuideEntry(speciesName(t.id), tr("Someone {0} gives you this POKéMON.", at(t)), icon = t.id, owned = done(t))
            AreaKind.EGG -> GuideEntry(tr("AN EGG"), tr("Someone {0} gives you an EGG. It hatches into {1}.", at(t), speciesName(t.id)), owned = done(t))
            AreaKind.TRADE -> GuideEntry(
                tr("TRADE FOR {0}", speciesName(t.id)), tr("Someone {0} trades it for your {1}.", at(t), speciesName(t.wants)),
                icon = t.id, owned = done(t),
            )
            else -> GuideEntry(itemName(t.id) + qty(t), tr("Someone {0} gives it to you.", at(t)), owned = done(t))
        }
    }.sortedBy { it.owned }
    val items = things.filter { it.kind == AreaKind.ITEM || it.kind == AreaKind.HIDDEN }.map { t ->
        val got = done(t)
        if (t.kind == AreaKind.HIDDEN && !got) {
            GuideEntry(tr("HIDDEN ITEM"), itemName(t.id) + qty(t), hint = tr("Buried {0}.", at(t)))
        } else {
            GuideEntry(itemName(t.id) + qty(t), if (t.kind == AreaKind.HIDDEN) tr("Hidden {0}.", at(t)) else tr("Lying {0}.", at(t)), owned = got)
        }
    }.sortedBy { it.owned }

    val intro = GuideSection(
        place, emptyList(),
        note = when {
            hasWild && enc == null -> tr("Reading the map...")
            else -> buildList {
                if (hasWild && methods.isEmpty()) add(tr("No wild POKéMON on this map."))
                // The area's DexNav (GitHub #85): how many of its wild POKéMON are caught, every method counted once.
                if (dex != null && methods.isNotEmpty()) {
                    val here = (methods.flatMap { (_, l) -> l } + onlyThen).map { national(it.species) }.filter { it > 0 }.toSet()
                    if (here.isNotEmpty()) add(tr("{0} of {1} caught here.", here.count { it in dex.caught }, here.size))
                }
                if (items.isNotEmpty() || people.isNotEmpty()) add(tr("Its buildings included."))
                if (snapshot.progress != null) add(tr("A ball = done."))
            }.joinToString(" ").ifEmpty { tr("Nothing listed for this area.") }
        },
    )
    return GuidePage(
        HERE,
        listOfNotNull(
            intro,
            todo.takeIf { it.isNotEmpty() }?.let { GuideSection(tk("TO DO"), it) },
        ) + methods.map { (name, l) ->
            // Grass follows the game's clock (Polished Crystal's morning / day / night): which list this is.
            GuideSection(name, rows(l), note = if (clock != null && l === enc?.grass) tr("For your game's clock: {0}.", tr(clock)) else null)
        } + listOfNotNull(
            onlyThen.takeIf { it.isNotEmpty() }?.let {
                if (otherIsNight) GuideSection(tk("AT NIGHT"), rows(it), note = tr("Only out at night."))
                else GuideSection(tk("BY DAY"), rows(it), note = tr("Only out by day."))
            },
            people.takeIf { it.isNotEmpty() }?.let { GuideSection(tk("PEOPLE"), it, note = tr("Some want something done first.")) },
            items.takeIf { it.isNotEmpty() }?.let { GuideSection(tk("ITEMS"), it) },
        ),
    )
}

/**
 * Whether the save already has [t]: its flag when known; else a gift key item /
 * TM / HM (ids 259+ in the vanilla games - nothing you'd buy more of) in the
 * bag, or a gift POKéMON already caught.
 */
private fun areaDone(t: AreaThing, snapshot: SnapshotView, ctx: GuideContext, dex: PokedexState?): Boolean = when {
    t.flag != 0 -> snapshot.progress?.flag(t.flag) == true
    t.kind == AreaKind.GIFT -> t.id >= 259 && ctx.has(Have.Item(t.id))
    t.kind == AreaKind.KEY -> ctx.has(Have.Item(t.id))
    t.kind == AreaKind.MON -> dex != null && PokedexSource.nationalOf(dex.tables, t.id) in dex.caught
    else -> false
}

private val PEOPLE_KINDS = setOf(AreaKind.GIFT, AreaKind.KEY, AreaKind.MON, AreaKind.EGG, AreaKind.TRADE)

/**
 * NEXT BOSS: the first boss (in the game's order) the save hasn't beaten,
 * then the rest still ahead. Each Pokémon is its own entry: its level shows,
 * the hint is its type, the answer its species, moves and held item.
 */
private fun bossPage(
    bosses: List<com.pokedaisy.app.companion.data.Boss>,
    progress: com.pokedaisy.app.companion.data.SaveProgress,
    parties: Map<Int, List<TrainerMon>>,
    party: List<MonView>,
): GuidePage {
    val ahead = bosses.filter { !it.isDone(progress) }
    val best = party.filter { !it.isEgg }.maxOfOrNull { it.level }
    // A boss with variants (a team per difficulty, or a run of battles) gets a section per team.
    val shown = ahead.withIndex().flatMap { (i, boss) ->
        boss.teams(progress).map { (label, id) -> Triple(i, if (label == null) trGuide(boss.title) else "${trGuide(boss.title)} · ${trGuide(label)}", id) }
    }
    val sections = shown.mapIndexed { k, (i, title, trainer) ->
        val boss = ahead[i]
        val team = parties[trainer].orEmpty()
        val ace = team.maxOfOrNull { it.level }
        val note = buildString {
            append(trGuide(boss.where))
            if (ace != null) append(" · " + tr("{0} POKéMON", team.size) + " · " + tr("UP TO LV {0}", ace))
            if (k == 0 && best != null) append(" · " + tr("YOUR BEST LV {0}", best))
        }
        GuideSection(
            if (i == 0) tr("NEXT: {0}", title) else title,
            team.mapIndexed { n, mon ->
                val st = activeSpeciesTypeData[mon.species]
                val types = monTypes(st?.type1 ?: TYPE_NONE, st?.type2 ?: TYPE_NONE).joinToString(" / ") { it.uppercase() }
                GuideEntry(
                    title = tr("POKéMON {0}", n + 1),
                    detail = tr("LV {0}", mon.level),
                    hint = tr("{0} TYPE", types),
                    answer = buildString {
                        append(speciesName(mon.species))
                        if (mon.moves.isNotEmpty()) append("\n" + mon.moves.joinToString(" · ") { lookupMove(it).name })
                        if (mon.heldItem != 0) append("\n" + tr("HOLDS {0}", itemName(mon.heldItem)))
                    },
                    icon = mon.species,
                )
            },
            note = note,
        )
    }
    return GuidePage(NEXT_BOSS, sections.ifEmpty { listOf(GuideSection(tk("ALL DONE"), emptyList(), note = tr("Every boss is beaten."))) })
}

/**
 * EVOLUTIONS, from the ROM's own table: the party's first (how each of them
 * evolves), then every species that evolves some way other than plain
 * levelling. The hint is only the kind (LEVEL UP, USE AN ITEM, TRADE…); the
 * answer names what it becomes.
 */
private fun evolutionsPage(table: List<List<Evolution>>, party: List<MonView>): GuidePage {
    fun entryFor(species: Int, evos: List<Evolution>) = GuideEntry(
        title = speciesName(species),
        answer = if (evos.isEmpty()) tr("Doesn't evolve.") else evos.joinToString("\n") { evolutionText(it) },
        hint = evos.takeIf { it.isNotEmpty() }?.map { evolutionKind(it) }?.distinct()?.joinToString(" / "),
    )
    val partySpecies = party.filter { !it.isEgg && it.species in table.indices }.map { it.species }.distinct()
    val special = table.indices.filter { s -> table[s].any { !it.isPlainLevel() } }
    return GuidePage(
        EVOLUTIONS,
        listOfNotNull(
            partySpecies.takeIf { it.isNotEmpty() }?.let { list -> GuideSection(tk("YOUR PARTY"), list.map { entryFor(it, table[it]) }) },
            GuideSection(tk("SPECIAL EVOLUTIONS"), special.map { entryFor(it, table[it]) }),
        ),
    )
}
