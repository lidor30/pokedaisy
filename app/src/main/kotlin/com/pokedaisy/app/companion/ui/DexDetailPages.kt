package com.pokedaisy.app.companion.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.background
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.foundation.lazy.rememberLazyListState
import com.pokedaisy.app.companion.data.typeInText
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.unit.sp
import com.pokedaisy.app.companion.data.AreaKind
import com.pokedaisy.app.companion.data.CatchSpot
import com.pokedaisy.app.companion.data.DexDetails
import com.pokedaisy.app.companion.data.EvoLink
import com.pokedaisy.app.companion.data.EvoReq
import com.pokedaisy.app.companion.data.EvoTime
import com.pokedaisy.app.companion.data.GuideTables
import com.pokedaisy.app.companion.data.LevelMove
import com.pokedaisy.app.companion.data.PokedexSource
import com.pokedaisy.app.companion.data.PokedexTables
import com.pokedaisy.app.companion.data.TeachMove
import com.pokedaisy.app.companion.data.WildMethod
import com.pokedaisy.app.companion.data.activeTypeNames
import com.pokedaisy.app.companion.data.allAreaThings
import com.pokedaisy.app.companion.data.hasCatchSpots
import com.pokedaisy.app.companion.data.hasEvolutions
import com.pokedaisy.app.companion.data.hasLearnsets
import com.pokedaisy.app.companion.data.itemName
import com.pokedaisy.app.companion.data.lookupLocation
import com.pokedaisy.app.companion.data.lookupMove
import com.pokedaisy.app.companion.data.speciesName
import com.pokedaisy.app.companion.data.spriteAsset
import com.pokedaisy.app.companion.data.typeName
import com.pokedaisy.app.companion.i18n.tk
import com.pokedaisy.app.companion.i18n.tr
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.pokedaisy.app.companion.ui.theme.gbaFocusRing
import androidx.compose.foundation.interaction.collectIsFocusedAsState

/*
 * The DEX page's sections past INFO (GitHub #28): EVOLVE (the whole family, both
 * ways, with each method in plain words), AREA (the maps whose wild tables hold
 * it, plus gifts / trades from the GUIDE's area data) and MOVES (level-up, then
 * TMs / HMs or the expansion games' teachable list). Data from [DexDetails].
 */

/** A section of the dex page; [label] is English (the chip translates it). */
enum class DexSection(val label: String) { INFO(tk("INFO|dex section")), EVOLVE(tk("EVOLVE")), AREA(tk("AREA")), MOVES(tk("MOVES|dex section")) }

/** The sections [t] / [g] have data for - INFO always. */
fun dexSections(t: PokedexTables, g: GuideTables?): List<DexSection> = buildList {
    add(DexSection.INFO)
    if (hasEvolutions(t)) add(DexSection.EVOLVE)
    if (hasCatchSpots(g)) add(DexSection.AREA)
    if (hasLearnsets(t, g)) add(DexSection.MOVES)
}

/** The section switcher: one chip per section in the tab bar's look, the open one white. */
@Composable
internal fun DexSectionChips(sections: List<DexSection>, selected: DexSection, small: GbaTextMetrics, onPick: (DexSection) -> Unit) {
    val u = small.u
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(u * 3)) {
        sections.forEach { s ->
            val on = s == selected
            val noRipple = remember { MutableInteractionSource() }
            val focused by noRipple.collectIsFocusedAsState()
            Box(
                Modifier
                    // Shared out by the words' lengths: EVOLVE got INFO's room and was cut off on a narrow screen (the RG DS).
                    .weight(tr(s.label).length + 2f)
                    // The D-pad's focus (the companion's window), flush inside the chip's 3u corner, like the tab bar's.
                    .gbaFocusRing(focused, PixelRoundedShape(u * 3))
                    .drawBehind {
                        val px = u.toPx()
                        drawLayeredBox(OptionColors.chipLayers.inPx(px), if (on) OptionColors.tabSelectedFill else OptionColors.tabIdleFill, radius = 3 * px, twoTone = true)
                    }
                    .soundClickable(interactionSource = noRipple, indication = null) { onPick(s) }
                    .padding(vertical = u * 4, horizontal = u * 2),
                contentAlignment = Alignment.Center,
            ) {
                GbaText(
                    tr(s.label),
                    if (on) OptionColors.value else OptionColors.label,
                    if (on) OptionColors.valueShadow else OptionColors.labelShadow,
                    small, bold = gameBoldLabels(),
                )
            }
        }
    }
}

// ---- EVOLVE ------------------------------------------------------------------

private class FamilyRow(val link: EvoLink, val fromNo: Int, val toNo: Int)

/**
 * The family, grouped by the Pokémon that evolves: its name, then a line per
 * evolution ("→ [icon] TO") over how (method + parameter). The open species
 * is in the value red; a tap on another opens its page ([onOpen], with its
 * dex number).
 */
@Composable
internal fun DexEvolveSection(t: PokedexTables, species: Int, m: GbaTextMetrics, small: GbaTextMetrics, onOpen: (Int) -> Unit) {
    val rows by produceKeyedState(DexDetails.cachedFamily(t, species)?.let { rowsOf(t, it) }, t, species) {
        if (value == null && species > 0) value = withContext(Dispatchers.IO) {
            DexDetails.family(t, species).orEmpty().let { rowsOf(t, it) }
        }
    }
    val list = rows
    when {
        list == null -> Note(tr("LOADING..."), small)
        list.isEmpty() -> Note(tr("Doesn't evolve."), small)
        else -> {
            val groups = remember(list) { list.groupBy { it.link.from }.toList() }
            LazyColumn(Modifier.fillMaxSize()) {
                items(groups.size) { i ->
                    val (from, links) = groups[i]
                    val u = small.u
                    Column(Modifier.fillMaxWidth().drawBehind { drawRowDivider(OptionColors.divider, u.toPx(), 0f) }.padding(vertical = u * 2)) {
                        FamilyMon(from, links.first().fromNo, species, m, onOpen, Modifier.fillMaxWidth())
                        links.forEach { r ->
                            Row(Modifier.padding(start = u * 6), verticalAlignment = Alignment.CenterVertically) {
                                GbaText("→", OptionColors.muted, OptionColors.mutedShadow, small)
                                Spacer(Modifier.width(u * 3))
                                FamilyMon(r.link.to, r.toNo, species, small, onOpen, Modifier.weight(1f))
                            }
                            GbaText(evolutionHow(r.link.reqs), OptionColors.value, OptionColors.valueShadow, small, Modifier.padding(start = u * 18), maxLines = 3)
                        }
                    }
                }
            }
        }
    }
}

private fun rowsOf(t: PokedexTables, links: List<EvoLink>) =
    links.map { FamilyRow(it, PokedexSource.nationalOf(t, it.from), PokedexSource.nationalOf(t, it.to)) }

@Composable
private fun FamilyMon(species: Int, national: Int, current: Int, m: GbaTextMetrics, onOpen: (Int) -> Unit, modifier: Modifier) {
    val here = species == current
    val noRipple = remember { MutableInteractionSource() }
    val focused by noRipple.collectIsFocusedAsState()
    Row(
        // The D-pad's cursor (the companion's window): the white row.
        modifier.background(if (focused) OptionColors.rowSelected else Color.Transparent)
            .soundClickable(interactionSource = noRipple, indication = null, enabled = !here && national > 0) { onOpen(national) },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SpeciesIcon(spriteAsset("pokemon", species), size = m.lineHeight * 1.5f)
        Spacer(Modifier.width(m.u * 2))
        GbaText(
            speciesName(species),
            if (here) OptionColors.value else OptionColors.label,
            if (here) OptionColors.valueShadow else OptionColors.labelShadow,
            m, Modifier.weight(1f),
        )
    }
}

/** An evolution's requirements in plain words: "LV 16", "Use FIRE STONE", "Level up, high friendship, by day". */
fun evolutionHow(reqs: List<EvoReq>): String {
    val trade = reqs.any { it is EvoReq.TradeFor }
    return reqs.filterNot { trade && it == EvoReq.Trade }.joinToString(", ") { evoReqText(it) }.replaceFirstChar { it.uppercase() }
}

fun evoReqText(r: EvoReq): String = when (r) {
    is EvoReq.Level -> tr("LV {0}", r.level)
    EvoReq.LevelUp -> tr("Level up")
    EvoReq.Friendship -> tr("high friendship")
    is EvoReq.Time -> when (r.time) {
        EvoTime.MORNING -> tr("in the morning")
        EvoTime.DAY -> tr("by day")
        EvoTime.DUSK -> tr("at dusk")
        EvoTime.NIGHT -> tr("at night")
    }
    EvoReq.Trade -> tr("Trade")
    is EvoReq.TradeFor -> tr("Trade for {0}", speciesName(r.species))
    is EvoReq.UseItem -> tr("Use {0}", itemName(r.item))
    is EvoReq.Hold -> tr("holding {0}", itemName(r.item))
    is EvoReq.Gender -> if (r.female) "♀" else "♂"
    is EvoReq.Stats -> when {
        r.cmp > 0 -> tr("ATTACK > DEFENSE")
        r.cmp < 0 -> tr("ATTACK < DEFENSE")
        else -> tr("ATTACK = DEFENSE")
    }
    EvoReq.Random -> tr("by chance (personality)")
    is EvoReq.Beauty -> tr("BEAUTY {0}+", r.min)
    is EvoReq.KnowsMove -> tr("knows {0}", lookupMove(r.move).name)
    is EvoReq.KnowsMoveType -> tr("knows a {0} move", typeInText(typeName(r.type)))
    is EvoReq.SpeciesInParty -> tr("{0} in the party", speciesName(r.species))
    is EvoReq.TypeInParty -> tr("a {0} type in the party", typeInText(typeName(r.type)))
    EvoReq.DarkInParty -> tr("a {0} type in the party", activeTypeNames.values.firstOrNull { it.equals("DARK", ignoreCase = true) } ?: "DARK")
    is EvoReq.AtPlace -> tr("at {0}", placeName(r.mapsec))
    EvoReq.SomePlace -> tr("at a special place")
    is EvoReq.Weather -> when (r.kind) {
        EvoReq.WEATHER_RAIN -> tr("in the rain")
        EvoReq.WEATHER_FOG -> tr("in fog")
        EvoReq.WEATHER_RAIN_OR_FOG -> tr("in rain or fog")
        else -> tr("in a certain weather")
    }
    is EvoReq.Nature -> when {
        r.kind > 0 -> tr("an amped nature")
        r.kind < 0 -> tr("a low-key nature")
        else -> tr("a certain nature")
    }
    is EvoReq.Crits -> if (r.count > 0) tr("{0} critical hits in one battle", r.count) else tr("critical hits in one battle")
    is EvoReq.HpLost -> tr("{0}+ HP lost", r.hp)
    is EvoReq.Recoil -> tr("{0}+ recoil damage taken", r.hp)
    is EvoReq.Scroll -> if (r.dark) tr("Scroll of Darkness") else tr("Scroll of Waters")
    is EvoReq.Steps -> tr("walk {0} steps", r.steps)
    is EvoReq.BagCount -> tr("{0} {1} in the bag", r.count, itemName(r.item))
    is EvoReq.Defeat -> if (r.species > 0) tr("defeat {0} {1} holding {2}", r.count, speciesName(r.species), itemName(r.item))
        else tr("defeat {0} holding {1}", r.count, itemName(r.item))
    is EvoReq.UsedMove -> tr("use {0} {1} times", lookupMove(r.move).name, r.times)
    is EvoReq.Region -> (if (r.not) tr("outside {0}", regionName(r.region)) else tr("in {0}", regionName(r.region)))
    is EvoReq.Hours -> "%02d:00-%02d:00".format(r.from, r.to)
    EvoReq.Spin -> tr("Spin around")
    EvoReq.AfterBattle -> tr("After a battle")
    EvoReq.InBattle -> tr("in battle")
    EvoReq.OverworldTrigger -> tr("Special spot")
    EvoReq.SpareSlot -> tr("a free slot and a POKé BALL")
    is EvoReq.SplitFrom -> tr("When it becomes {0}", speciesName(r.species))
    EvoReq.Special -> tr("Special condition")
}

/** expansion's enum Region - the games' own words, so not translated. */
private fun regionName(id: Int): String =
    listOf("", "KANTO", "JOHTO", "HOENN", "SINNOH", "UNOVA", "KALOS", "ALOLA", "GALAR", "HISUI", "PALDEA").getOrNull(id)?.takeIf { it.isNotEmpty() } ?: "?"

/** A region map section's name, as the MAP tab has it ("MAP 3.43" for a map with no section table). */
private fun placeName(mapsec: Int): String =
    if (mapsec < 0) tr("MAP {0}", "${(-1 - mapsec) shr 8}.${(-1 - mapsec) and 0xFF}")
    else lookupLocation(mapsec).mapSecName.ifBlank { tr("AREA {0}", mapsec) }

// ---- AREA --------------------------------------------------------------------

private class AreaPlace(val name: String, val lines: List<Pair<String, String>>)

/**
 * Where it's met: one block per map section, a line per method ("GRASS / CAVE
 * LV 2-5 50%", with the times of day when it's only out at some), then gifts,
 * eggs and trades from the GUIDE's area data.
 */
@Composable
internal fun DexAreaSection(g: GuideTables, species: Int, m: GbaTextMetrics, small: GbaTextMetrics) {
    val spots by produceKeyedState(DexDetails.cachedCatchSpots(g, species), g, species) {
        if (value == null && species > 0) value = withContext(Dispatchers.IO) { DexDetails.catchSpots(g, species).orEmpty() }
    }
    val list = spots
    if (list == null) {
        Note(tr("Reading the map..."), small)
        return
    }
    val places = remember(list, species, g) { areaPlaces(list, g, species) }
    if (places.isEmpty()) {
        Note(tr("Not found in the wild."), small)
        return
    }
    // Nothing to tap: one D-pad stop that scrolls (the companion's window).
    val scroll = rememberLazyListState()
    LazyColumn(Modifier.fillMaxSize().dpadScroll(scroll, RectangleShape, small.lineHeight * 3), state = scroll, verticalArrangement = Arrangement.spacedBy(small.u * 2)) {
        items(places.size) { i ->
            val p = places[i]
            Column(Modifier.fillMaxWidth().drawBehind { drawRowDivider(OptionColors.divider, small.u.toPx(), 0f) }.padding(bottom = small.u * 3)) {
                GbaText(p.name, OptionColors.label, OptionColors.labelShadow, m)
                p.lines.forEach { (what, detail) ->
                    Row(Modifier.padding(start = small.u * 6), verticalAlignment = Alignment.CenterVertically) {
                        GbaText(what, OptionColors.label, OptionColors.labelShadow, small, Modifier.weight(1f))
                        GbaText(detail, OptionColors.value, OptionColors.valueShadow, small)
                    }
                }
            }
        }
    }
}

private fun areaPlaces(spots: List<CatchSpot>, g: GuideTables, species: Int): List<AreaPlace> {
    // Map section -> its lines, in the wild headers' order (roughly the story's), then the area data's.
    val lines = LinkedHashMap<Int, MutableList<Pair<String, String>>>()
    spots.forEach { lines.getOrPut(it.mapsec) { ArrayList() } }
    for (s in spots.sortedBy { it.method.ordinal }) {
        val lv = if (s.minLevel == s.maxLevel) tr("LV {0}", s.minLevel) else tr("LV {0}", "${s.minLevel}-${s.maxLevel}")
        val times = if (s.times == 0) "" else " " + listOf(tr("MORNING"), tr("DAY"), tr("EVENING"), tr("NIGHT"))
            .filterIndexed { i, _ -> s.times shr i and 1 != 0 }.joinToString("/")
        lines.getOrPut(s.mapsec) { ArrayList() }.add(methodLabel(s.method) + times to "$lv  ${s.percent}%")
    }
    // Gifts, eggs and in-game trades for this species, from the GUIDE's per-area data.
    for ((sec, things) in allAreaThings(g.guide)) {
        for (th in things) {
            if (th.id != species || th.kind !in setOf(AreaKind.MON, AreaKind.EGG, AreaKind.TRADE)) continue
            val what = when (th.kind) {
                AreaKind.EGG -> tr("AN EGG")
                AreaKind.TRADE -> tr("TRADE FOR {0}", speciesName(th.wants))
                else -> tr("GIFT")
            }
            lines.getOrPut(sec) { ArrayList() }.add(what to th.where)
        }
    }
    return lines.map { (sec, l) -> AreaPlace(placeName(sec).uppercase(), l) }
}

private fun methodLabel(m: WildMethod): String = when (m) {
    WildMethod.GRASS -> tr("GRASS / CAVE")
    WildMethod.SURF -> tr("SURFING")
    WildMethod.ROCK_SMASH -> tr("ROCK SMASH")
    WildMethod.OLD_ROD -> tr("OLD ROD")
    WildMethod.GOOD_ROD -> tr("GOOD ROD")
    WildMethod.SUPER_ROD -> tr("SUPER ROD")
}

// ---- MOVES -------------------------------------------------------------------

private class DexMoves(val levelUp: List<LevelMove>, val teach: List<TeachMove>?)

/** LEVEL UP (level, move, type), then TM / HM - or the expansion games' TM / TUTOR list, by name. */
@Composable
internal fun DexMovesSection(t: PokedexTables, g: GuideTables?, species: Int, m: GbaTextMetrics, small: GbaTextMetrics) {
    val moves by produceKeyedState(
        DexDetails.cachedLevelUp(t, g, species)?.let { DexMoves(it, DexDetails.cachedTeachable(t, g, species)) }
            ?.takeIf { it.teach != null || !DexDetails.hasTeachable(t) },
        t, g, species,
    ) {
        if (value == null && species > 0) value = withContext(Dispatchers.IO) {
            DexMoves(DexDetails.levelUp(t, g, species).orEmpty(), DexDetails.teachable(t, g, species))
        }
    }
    val mv = moves
    if (mv == null) {
        Note(tr("LOADING..."), small)
        return
    }
    val expansionList = mv.teach?.all { it.label.isEmpty() } == true
    val teach = mv.teach?.let { list -> if (expansionList) list.sortedBy { lookupMove(it.move).name } else list }
    // Nothing to tap: one D-pad stop that scrolls (the companion's window).
    val scroll = rememberLazyListState()
    LazyColumn(Modifier.fillMaxSize().dpadScroll(scroll, RectangleShape, small.lineHeight * 3), state = scroll) {
        header(tr("LEVEL UP"), small)
        if (mv.levelUp.isEmpty()) item { Note(tr("NONE"), small) }
        items(mv.levelUp) { lm -> MoveLine(if (lm.level == 0) tr("EVO.") else tr("LV {0}", lm.level), lm.move, small) }
        when {
            teach == null -> item { Note(tr("This game's TMs aren't read yet: level-up moves only."), small, Modifier.padding(top = small.u * 6)) }
            else -> {
                header(if (expansionList) tr("TM / TUTOR") else tr("TM / HM"), small)
                if (teach.isEmpty()) item { Note(tr("NONE"), small) }
                items(teach) { tm -> MoveLine(tm.label, tm.move, small) }
            }
        }
    }
}

private fun LazyListScope.header(text: String, small: GbaTextMetrics) = item {
    GbaText(text, OptionColors.muted, OptionColors.mutedShadow, small, Modifier.padding(top = small.u * 4, bottom = small.u * 2))
}

@Composable
private fun MoveLine(label: String, move: Int, small: GbaTextMetrics) {
    val u = small.u
    val info = lookupMove(move)
    Row(
        Modifier.fillMaxWidth().drawBehind { drawRowDivider(OptionColors.divider, u.toPx(), 0f) }.padding(vertical = u * 2),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (label.isNotEmpty()) GbaText(label, OptionColors.value, OptionColors.valueShadow, small, Modifier.width(u * 40))
        GbaText(info.name, OptionColors.label, OptionColors.labelShadow, small, Modifier.weight(1f))
        typeName(info.type).takeIf { it.isNotEmpty() }?.let { TypeBadge(it, fontSize = 11.sp) }
    }
}

@Composable
private fun Note(text: String, small: GbaTextMetrics, modifier: Modifier = Modifier) {
    GbaText(text, OptionColors.muted, OptionColors.mutedShadow, small, modifier.padding(top = small.u * 2), maxLines = 3)
}
