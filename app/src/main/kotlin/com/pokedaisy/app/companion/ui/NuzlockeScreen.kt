package com.pokedaisy.app.companion.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.pokedaisy.app.companion.Nuzlocke
import com.pokedaisy.app.companion.data.NuzlockeEncounter
import com.pokedaisy.app.companion.data.NuzlockeMon
import com.pokedaisy.app.companion.data.NuzlockeOutcome
import com.pokedaisy.app.companion.data.speciesName
import com.pokedaisy.app.companion.data.spriteAsset
import com.pokedaisy.app.companion.i18n.tk
import com.pokedaisy.app.companion.i18n.tr

/**
 * The NUZLOCKE tab ([Nuzlocke]): one list in SETTINGS' look. HERE (the area the player stands in: free, or what its
 * encounter was), TEAM (the run's living Pokémon), ENCOUNTERS (one row an area, newest first), GRAVEYARD and the
 * RULES with NEW RUN. Every row the tracker fills can be put right by hand: an encounter's outcome (or cleared), a
 * Pokémon marked dead or brought back.
 */
@Composable
internal fun NuzlockeScreen(area: Int, areaName: String, modifier: Modifier = Modifier) {
    if (!Nuzlocke.on) {
        NuzlockeOff(modifier)
        return
    }
    val m = rememberGbaTextMetrics()
    val run = Nuzlocke.run
    var cursor by rememberSaveable { mutableStateOf(1) }
    var pick by remember { mutableStateOf<NuzlockeEncounter?>(null) }
    var confirm by remember { mutableStateOf<Triple<String, String, () -> Unit>?>(null) }
    val icon: (Int) -> @Composable () -> Unit = { species ->
        { Box { SpeciesIcon(spriteAsset("pokemon", species), m.lineHeight * 1.6f, Modifier.requiredSize(m.lineHeight * 1.6f)) } }
    }
    val rows = buildList {
        add(groupTitle(tk("HERE")))
        val here = run.encounterAt(area)
        add(
            if (here == null) SettingRow(areaName.ifEmpty { "?" }, tk("FREE"), subtitle = tk("Your first wild POKéMON here is this area's catch")) {}
            else SettingRow(areaName, here.outcomeLabel(), labelIcon = icon(here.species), subtitle = speciesName(here.species)) { pick = here },
        )
        add(groupTitle(tk("TEAM")))
        if (run.alive.isEmpty()) add(SettingRow(tk("Nobody yet"), null, enabled = false) {})
        run.alive.forEach { mon ->
            add(SettingRow(mon.name, tr("Lv{0}", mon.level), labelIcon = icon(mon.species), subtitle = joinedLine(mon)) {
                confirm = Triple(
                    tr("{0} FAINTED?", mon.name),
                    tr("Marks {0} as out of the run, as if it had fainted.", mon.name),
                ) { Nuzlocke.setDead(mon.personality, true, areaName) }
            })
        }
        add(groupTitle(tk("ENCOUNTERS")))
        if (run.encounters.isEmpty()) add(SettingRow(tk("None yet"), null, enabled = false) {})
        run.encounters.asReversed().forEach { e ->
            add(SettingRow(e.areaName, e.outcomeLabel(), labelIcon = icon(e.species), subtitle = speciesName(e.species)) { pick = e })
        }
        if (run.dead.isNotEmpty()) {
            add(groupTitle(tk("GRAVEYARD")))
            run.dead.forEach { mon ->
                add(SettingRow(mon.name, tr("Lv{0}", mon.diedLevel), labelIcon = icon(mon.species),
                    subtitle = if (mon.diedIn.isEmpty()) null else tr("Fainted in {0}", mon.diedIn)) {
                    confirm = Triple(
                        tr("BRING BACK {0}?", mon.name),
                        tr("Puts {0} back in the run, for a faint that shouldn't count.", mon.name),
                    ) { Nuzlocke.setDead(mon.personality, false) }
                })
            }
        }
        add(groupTitle(tk("RULES")))
        val rules = run.rules
        add(SettingRow(tk("DUPES CLAUSE"), if (rules.dupes) tk("ON") else tk("OFF"), subtitle = tk("A POKéMON you've caught before doesn't use up an area")) {
            Nuzlocke.setRules(rules.copy(dupes = !rules.dupes))
        })
        add(SettingRow(tk("WARNINGS"), if (rules.warnings) tk("ON") else tk("OFF"), subtitle = tk("Notices on the game screen as the run goes")) {
            Nuzlocke.setRules(rules.copy(warnings = !rules.warnings))
        })
        add(SettingRow(tk("NEW RUN"), null) {
            confirm = Triple(
                tr("NEW RUN?"),
                tr("Forgets every encounter and fainted POKéMON of this run. Your party right now starts the new one."),
            ) { Nuzlocke.newRun() }
        })
    }
    Box(modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            OptionTitleWindow(tk("NUZLOCKE"), m, trailing = tr("{0} ALIVE · {1} FAINTED", run.alive.size, run.dead.size))
            Spacer(Modifier.height(m.u * 4))
            OptionListWindow(m, Modifier.fillMaxWidth().weight(1f)) {
                val at = cursor.coerceIn(0, rows.lastIndex)
                GroupedRows(rows, m, at, onClick = { i -> cursor = i; rows[i].onClick() }, labelWeight = 0.66f, onFocused = { cursor = it })
            }
        }
        pick?.let { e ->
            val options = listOf(NuzlockeOutcome.CAUGHT, NuzlockeOutcome.MISSED, null)
            OptionSelector(
                e.areaName, options, e.outcome.takeIf { it != NuzlockeOutcome.NOW },
                { o -> when (o) { NuzlockeOutcome.CAUGHT -> tr("CAUGHT|run"); NuzlockeOutcome.MISSED -> tr("MISSED"); else -> tr("NOT USED") } }, m,
                onPick = { Nuzlocke.setOutcome(e.area, it); pick = null },
                onDismiss = { pick = null },
            )
        }
        confirm?.let { (title, message, action) ->
            OptionConfirm(title, message, tk("YES"), m, onConfirm = { confirm = null; action() }, onDismiss = { confirm = null })
        }
    }
}

/**
 * The tab while NUZLOCKE is off for this game (it's always under MORE): what it does and a TURN ON button, the same switch
 * as SETTINGS > GAMEPLAY > NUZLOCKE. A run kept from before picks up where it was.
 */
@Composable
private fun NuzlockeOff(modifier: Modifier = Modifier) {
    val m = rememberGbaTextMetrics()
    val u = m.u
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(Modifier.widthIn(max = 560.dp)) {
            OptionTitleWindow(tk("NUZLOCKE"), m, trailing = tk("OFF"))
            Spacer(Modifier.height(u * 4))
            OptionListWindow(m, Modifier.fillMaxWidth().weight(1f, fill = false)) {
                Column(Modifier.padding(horizontal = u * 8, vertical = u * 6), verticalArrangement = Arrangement.spacedBy(u * 6)) {
                    Column(
                        Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(u * 6),
                    ) {
                        GbaText(
                            tr("NUZLOCKE tracking is off for this game. Turn it on and the companion keeps score of your run: only the first wild POKéMON in each area counts, and a POKéMON that faints is out for good."),
                            OptionColors.label, OptionColors.labelShadow, m, maxLines = Int.MAX_VALUE,
                        )
                        GbaText(
                            tr("You can turn it off again in SETTINGS, under GAMEPLAY."),
                            OptionColors.muted, OptionColors.mutedShadow, rememberGbaTextMetrics(1f), maxLines = Int.MAX_VALUE,
                        )
                    }
                    // Where the D-pad starts (the companion's window).
                    OptionButton(
                        tk("TURN ON"), m, onClick = { Nuzlocke.setOn(true) }, emphasis = true,
                        modifier = Modifier.fillMaxWidth().height(m.rowHeight * 1.4f).focusStart(true),
                    )
                }
            }
        }
    }
}

/** Where a living Pokémon joined the run. */
private fun joinedLine(mon: NuzlockeMon): String =
    if (mon.areaName.isEmpty()) tr("With you from the start") else tr("Joined in {0}", mon.areaName)

/** NUZLOCKE's mark on a party slot: a fainted-for-good Pokémon's gravestone, on a tag like the held item's. */
@Composable
internal fun NuzlockeDeadTag(modifier: Modifier = Modifier) {
    val m = rememberGbaTextMetrics(0.75f)
    val u = m.u
    Box(
        modifier
            .drawBehind { drawLayeredBox(OptionColors.titleLayers.inPx(u.toPx()), OptionColors.titleFill, radius = 2 * u.toPx()) }
            .semantics { contentDescription = tr("Out of the run") }
            .padding(horizontal = u * 3, vertical = u * 1),
    ) {
        // A little taller than the held item's text line, so the cross on it reads.
        PixelArt(TabIcons.grave.bitmap, TabIcons.grave.palette, Modifier.size(m.lineHeight * 1.5f))
    }
}

/** An area's encounter as the tab shows its outcome. */
private fun NuzlockeEncounter.outcomeLabel(): String = when (outcome) {
    NuzlockeOutcome.NOW -> tr("NOW")
    NuzlockeOutcome.CAUGHT -> tr("CAUGHT|run")
    NuzlockeOutcome.MISSED -> tr("MISSED")
}
