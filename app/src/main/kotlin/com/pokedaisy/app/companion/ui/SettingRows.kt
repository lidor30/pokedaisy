package com.pokedaisy.app.companion.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.draw.drawBehind
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.pokedaisy.app.companion.i18n.tr

/**
 * One line of a settings list: [value] (red, in the value column) or null for a plain
 * command row; [header] = a group title. [badge] goes after the value ("ALPHA"),
 * [labelBadge] after the label ("BETA"); [enabled] false greys the row out.
 */
class SettingRow(
    val label: String, val value: String?, val badge: String? = null, val header: Boolean = false,
    val enabled: Boolean = true, val labelBadge: String? = null,
    val labelIcon: (@Composable () -> Unit)? = null,
    /** A line under the label saying what the row is for (English, translated where it's drawn). */
    val subtitle: String? = null,
    /** Hidden words Settings' search also matches (English, space-separated): the terms players use elsewhere. */
    val keywords: String = "",
    val onClick: () -> Unit,
)

/**
 * Settings' search: the rows whose label, value, subtitle or keywords hold every word of [query]
 * (in English or the language on screen, accents and case aside), each under its group's title.
 */
fun filterRows(rows: List<SettingRow>, query: String): List<SettingRow> {
    val words = fold(query).split(' ').filter { it.isNotBlank() }
    if (words.isEmpty()) return rows
    val out = mutableListOf<SettingRow>()
    var group: SettingRow? = null
    for (row in rows) {
        if (row.header) { group = row; continue }
        val text = fold(
            listOfNotNull(
                row.label, tr(row.label), row.value, row.value?.let { tr(it) }, row.subtitle, row.subtitle?.let { tr(it) },
                row.keywords, SETTING_KEYWORDS[row.label],
            ).joinToString(" "),
        )
        if (words.all { it in text }) {
            if (group != null && out.lastOrNull { it.header } !== group) out += group
            out += row
        }
    }
    return out
}

/**
 * Search words per row, by its English label (both screens' rows share them): what other emulators
 * and players call the same thing, and what's on the row's sub-page. English only - the label
 * itself is matched in the language on screen too.
 */
val SETTING_KEYWORDS: Map<String, String> = mapOf(
    "FF" to "fast forward turbo speed up",
    "FF SPEED" to "fast forward speed rate cap multiplier turbo",
    "FF MODE" to "smart fast forward menus battles normal",
    "FF MUSIC" to "fast forward audio sound music steady sped up mute",
    "REWIND" to "rewind undo go back time",
    "TOUCH PAD" to "on screen controls virtual gamepad overlay touch buttons",
    "GAME BUTTONS" to "key bindings keybinds keybinding controls remap remapping rebind mapping input controller gamepad buttons turbo",
    "HOTKEYS" to "shortcuts combos save state load state slot rewind fast forward key bindings",
    "STATUS BAR" to "clock time battery money location top bar",
    "ASPECT" to "stretch ratio fullscreen full screen size scale 3:2",
    "SHADERS" to "filter lcd crt scanlines grid gba colors color correction effects screen look",
    "OVERLAY" to "overlay overlays border borders bezel bezels frame skin retroarch background wallpaper decoration",
    "COMPANION" to "portrait phone bottom position layout",
    "SWAP SCREENS" to "dual screen display switch top bottom",
    "THEME" to "colors look background skin style",
    "RESUME GAMES" to "auto resume continue launch boot",
    "FOLDERS" to "roms folder saves directory path location storage retroarch syncthing save states",
    "COVER ART" to "box art boxart covers steamgriddb images thumbnails api key",
    "HIDDEN GAMES" to "hide unhide library show",
    "CHEATS" to "gameshark action replay codebreaker codes",
    "RetroAchievements" to "achievements cheevos ra login account trophies",
    "LANGUAGE" to "language translation locale english japanese french german italian spanish",
    "VERSION" to "update upgrade check release about",
    "LICENSES" to "license legal open source credits notices about",
    "RUN SETUP" to "setup onboarding wizard first time welcome",
    "TAB BAR" to "tabs layout bar order",
    "BATTLE HINTS" to "suggestions recommendations best move tips",
    "FOE IVS" to "enemy opponent ivs stats",
    "CLICK SOUND" to "sound effects button click audio",
    "TWEAKS" to "animations bounce blink cursor jump to battle effects motion",
)

private fun fold(s: String): String =
    java.text.Normalizer.normalize(s.lowercase(), java.text.Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "")

/** A group's title row in [GroupedRows]. */
fun groupTitle(label: String) = SettingRow(label, null, header = true) {}

/**
 * A settings list in groups, shared by both screens' SETTINGS: group titles (red, like
 * the bag's pocket names) over [OptionLine] rows, one column at the normal text size
 * (big touch targets), scrolling when it outgrows the window, then [footer]. [onClick]
 * gets the row's index in [rows]; [cursor] is the white row (-1: none). [scroll] is hoisted
 * by a list that sub-pages / pick-lists replace, so coming back keeps its place.
 */
@Composable
fun GroupedRows(
    rows: List<SettingRow>,
    m: GbaTextMetrics,
    cursor: Int,
    onClick: (Int) -> Unit,
    scroll: ScrollState = rememberScrollState(),
    /** The label column's share of a row (with its subtitle); TWEAKS' ON / OFF need little room. */
    labelWeight: Float = 0.58f,
    footer: @Composable () -> Unit = {},
) {
    Column(Modifier.fillMaxSize().verticalScroll(scroll)) {
        rows.forEachIndexed { i, row ->
            if (row.header) {
                val band = OptionColors.groupTitleFill
                if (band == null) {
                    GbaText(
                        tr(row.label), OptionColors.value, OptionColors.valueShadow, m,
                        modifier = Modifier.padding(start = m.u * 8, top = m.u * (if (i == 0) 1 else 6), bottom = m.u),
                    )
                } else {
                    // Black text on white rows (Gen 1): the title is a black band, so it can't pass for a row.
                    GbaText(
                        tr(row.label), OptionColors.groupTitleText, OptionColors.groupTitleShadow, m,
                        modifier = Modifier
                            .padding(start = m.u * 2, end = m.u * 2, top = m.u * (if (i == 0) 1 else 6), bottom = m.u * 2)
                            .fillMaxWidth()
                            .drawBehind { drawPixelRoundRect(band, radius = m.u.toPx() * 2, step = m.u.toPx()) }
                            .padding(horizontal = m.u * 6, vertical = m.u * 2),
                    )
                }
            } else {
                OptionLine(
                    row.label, row.value, selected = i == cursor, m,
                    height = if (row.subtitle != null) m.rowHeight * 1.2f + m.lineHeight else m.rowHeight * 1.2f,
                    divider = rows.getOrNull(i + 1)?.header == false, labelBadge = row.labelBadge, labelIcon = row.labelIcon,
                    valueBadge = row.badge, enabled = row.enabled, subtitle = row.subtitle, labelWeight = labelWeight,
                ) { onClick(i) }
            }
        }
        footer()
    }
}
