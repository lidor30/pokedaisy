package com.pokedaisy.app.companion.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.pokedaisy.app.companion.GamePickActions
import com.pokedaisy.app.companion.data.BestEffort
import com.pokedaisy.app.companion.data.BestEffortReport
import com.pokedaisy.app.companion.data.BestEffortSharing
import com.pokedaisy.app.companion.data.BestEffortView
import com.pokedaisy.app.companion.data.GamePickerView
import com.pokedaisy.app.companion.i18n.tk
import com.pokedaisy.app.companion.i18n.tr

/**
 * The picker itself: the games the ROM could be, best first (LIKELY where its names are that
 * game's), the one in use marked; NONE OF THESE and ASK FOR SUPPORT below, always in view. Fills the tab area
 * in place of NOT SUPPORTED while nothing reads the ROM, and opens over the companion from the
 * match notice's WRONG GAME? and SETTINGS > GAME ([onBack] then closes it).
 */
@Composable
fun GamePickerPage(
    picker: GamePickerView,
    actions: GamePickActions,
    askForSupport: (() -> Unit)?,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
) {
    val m = rememberGbaTextMetrics()
    val small = rememberGbaTextMetrics(1f)
    val current = picker.options.firstOrNull { it.id == picker.current }
    Column(modifier) {
        OptionTitleWindow(tk("WHICH GAME IS THIS?"), m, Modifier.fillMaxWidth(), onBack = onBack)
        Spacer(Modifier.height(m.u * 4))
        OptionListWindow(m, Modifier.fillMaxWidth().weight(1f)) {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                GbaText(
                    when {
                        current != null -> tr("Read as {0}. If that's not the game you're playing, pick it below.", current.title)
                        picker.miss == BestEffort.Miss.NO_MATCH ->
                            tr("Your party doesn't read clearly as any game PokéDaisy knows. Pick the one you're playing, or NONE OF THESE.")
                        else -> tr("PokéDaisy doesn't know this build. Pick the game it's a version of - or play on: once you have a POKéMON, it checks by itself.")
                    },
                    OptionColors.label, OptionColors.labelShadow, small, maxLines = Int.MAX_VALUE,
                    modifier = Modifier.padding(horizontal = m.u * 8, vertical = m.u * 4),
                )
                picker.options.forEach { opt ->
                    val on = opt.id == picker.current
                    OptionLine(
                        opt.title, if (on) "◀" else null, selected = on, m,
                        labelWeight = 0.85f, divider = true, labelBadge = if (opt.likely) tk("LIKELY") else null,
                    ) { if (!on) actions.pick(opt.id); onBack?.invoke() }
                }
            }
        }
        Spacer(Modifier.height(m.u * 4))
        Row(horizontalArrangement = Arrangement.spacedBy(m.u * 4), modifier = Modifier.fillMaxWidth()) {
            OptionButton(tk("NONE OF THESE"), m, onClick = { actions.none(); onBack?.invoke() }, modifier = Modifier.weight(1f))
            askForSupport?.let { OptionButton(tk("ASK FOR SUPPORT"), m, onClick = it, modifier = Modifier.weight(1f)) }
        }
    }
}

/** A game's title without the series name ("SoulGold v1.2"), where a value column is narrow. */
fun shortGameTitle(title: String) = title.removePrefix("Pokémon ").removePrefix("Pocket Monsters ")

/** [GamePickerPage] over the companion (WRONG GAME?, PICK AGAIN, SETTINGS > GAME). */
@Composable
fun GamePickerOverlay(picker: GamePickerView, actions: GamePickActions, askForSupport: (() -> Unit)?, onDismiss: () -> Unit) {
    OptionOverlay(onDismiss, Modifier.widthIn(max = 640.dp).fillMaxHeight()) {
        GamePickerPage(picker, actions, askForSupport, Modifier.fillMaxHeight(), onBack = onDismiss)
    }
}

/** What a part being off means, in the notice. */
@Composable
private fun partLabel(p: BestEffort.Part) = when (p) {
    BestEffort.Part.DEX -> tr("the POKéDEX")
    BestEffort.Part.GUIDE -> tr("the GUIDE's live pages")
    BestEffort.Part.ITEM_TEXT -> tr("item descriptions")
    BestEffort.Part.MON_ICONS -> tr("POKéMON sprites")
    BestEffort.Part.ITEM_ICONS -> tr("item sprites")
}

@Composable
private fun bestEffortSummary(be: BestEffortView): String = when {
    be.guess -> tr("Looks like {0}, from the names in the ROM. Read as that until you have a POKéMON to check it by.", be.title)
    be.full -> tr("Read as {0}: everything matched, so it works like a supported game.", be.title)
    else -> tr(
        "Read as {0}: its party and names checked out against this ROM. Off: {1}. The library tags it PARTIALLY SUPPORTED.",
        be.title, be.off.map { partLabel(it) }.joinToString(", "),
    )
}

/**
 * The notices of a BEST EFFORT match, over every tab: one it just made by itself (with SHARE when
 * it's sure and [share] is there) and one whose party doesn't hold with it (PICK AGAIN / KEEP).
 * Both offer the picker ([onPick]).
 */
@Composable
fun BestEffortNotices(be: BestEffortView, actions: GamePickActions, share: BestEffortSharing?, onPick: () -> Unit) {
    val m = rememberGbaTextMetrics()
    val report = be.report
    when {
        be.doubt -> NoticeWindow(
            tk("WRONG GAME?"),
            be.suggest?.let { tr("Your party doesn't read as {0} - it does as {1}. Pick the game you're playing.", be.title, it) }
                ?: tr("Your party doesn't read as {0}: names and sprites may be wrong. Pick the game you're playing, or keep it.", be.title),
            m, onDismiss = actions.keep,
            secondary = tk("KEEP") to actions.keep, primary = tk("PICK AGAIN") to onPick,
        )
        !be.fresh -> {}
        report != null && share != null -> BestEffortShareNotice(
            be, report, appVersion = share.appVersion, onShare = { share.send(report); actions.seen() }, onDismiss = actions.seen,
            onWrongGame = { actions.seen(); onPick() },
        )
        else -> NoticeWindow(
            tk("BEST EFFORT"), bestEffortSummary(be), m, onDismiss = actions.seen,
            secondary = tk("WRONG GAME?") to { actions.seen(); onPick() }, primary = tk("OK") to actions.seen,
        )
    }
}

/** Title, message and two buttons ([secondary] left, [primary] red, right). */
@Composable
private fun NoticeWindow(
    title: String, message: String, m: GbaTextMetrics, onDismiss: () -> Unit,
    secondary: Pair<String, () -> Unit>, primary: Pair<String, () -> Unit>,
) {
    OptionOverlay(onDismiss, Modifier.widthIn(max = 520.dp)) {
        Column {
            OptionTitleWindow(title, m)
            Spacer(Modifier.height(m.u * 4))
            OptionListWindow(m, Modifier.fillMaxWidth()) {
                Column {
                    GbaText(
                        message, OptionColors.label, OptionColors.labelShadow, m, maxLines = Int.MAX_VALUE,
                        modifier = Modifier.padding(horizontal = m.u * 8, vertical = m.u * 4),
                    )
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(m.u * 4),
                        modifier = Modifier.fillMaxWidth().padding(horizontal = m.u * 4, vertical = m.u * 4),
                    ) {
                        OptionButton(secondary.first, m, onClick = secondary.second, modifier = Modifier.weight(1f).height(m.rowHeight * 1.4f))
                        OptionButton(primary.first, m, onClick = primary.second, emphasis = true, modifier = Modifier.weight(1f).height(m.rowHeight * 1.4f))
                    }
                }
            }
        }
    }
}

/**
 * The match notice with the ask to share it: what the match is, then every field sharing
 * would send ([BestEffortReport.shareFields]), value by value - nothing goes out unless SHARE is tapped.
 */
@Composable
private fun BestEffortShareNotice(
    be: BestEffortView,
    report: BestEffortReport,
    appVersion: String,
    onShare: () -> Unit,
    onDismiss: () -> Unit,
    onWrongGame: () -> Unit,
) {
    val m = rememberGbaTextMetrics()
    val small = rememberGbaTextMetrics(1f)
    OptionOverlay(onDismiss, Modifier.widthIn(max = 640.dp).fillMaxHeight()) {
        Column(Modifier.fillMaxHeight()) {
            OptionTitleWindow(tr("BEST EFFORT"), m)
            Spacer(Modifier.height(m.u * 4))
            OptionListWindow(m, Modifier.fillMaxWidth().weight(1f)) {
                Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = m.u * 8, vertical = m.u * 4)) {
                    GbaText(bestEffortSummary(be), OptionColors.label, OptionColors.labelShadow, small, maxLines = Int.MAX_VALUE)
                    OptionButton(tk("WRONG GAME?"), small, onClick = onWrongGame, modifier = Modifier.padding(vertical = m.u * 4))
                    Spacer(Modifier.height(m.u * 2))
                    GbaText(tr("SHARE THIS MATCH?"), OptionColors.value, OptionColors.valueShadow, m)
                    GbaText(
                        tr("It helps other players and lets a future release support this ROM properly. Sent once to PokéDaisy's database (Google Firebase): exactly the lines below - no file name, account or device details."),
                        OptionColors.label, OptionColors.labelShadow, small, maxLines = Int.MAX_VALUE,
                        modifier = Modifier.padding(top = m.u * 2, bottom = m.u * 3),
                    )
                    report.shareFields(appVersion).forEach { (label, value) ->
                        Row(Modifier.fillMaxWidth().padding(vertical = m.u)) {
                            GbaText(tr(label), OptionColors.muted, OptionColors.mutedShadow, small, Modifier.weight(0.35f))
                            GbaText(value, OptionColors.label, OptionColors.labelShadow, small, Modifier.weight(0.65f), maxLines = 2)
                        }
                    }
                }
            }
            Spacer(Modifier.height(m.u * 4))
            Row(horizontalArrangement = Arrangement.spacedBy(m.u * 4), modifier = Modifier.fillMaxWidth()) {
                OptionButton(tk("NO THANKS"), m, onClick = onDismiss, modifier = Modifier.weight(1f).height(m.rowHeight * 1.4f))
                OptionButton(tk("SHARE"), m, onClick = onShare, emphasis = true, modifier = Modifier.weight(1f).height(m.rowHeight * 1.4f))
            }
        }
    }
}
