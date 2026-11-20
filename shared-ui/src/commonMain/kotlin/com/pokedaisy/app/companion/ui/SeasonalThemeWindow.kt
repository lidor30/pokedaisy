package com.pokedaisy.app.companion.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.unit.dp
import com.pokedaisy.app.companion.i18n.AppLanguage
import com.pokedaisy.app.companion.i18n.L10n
import com.pokedaisy.app.companion.i18n.tr
import com.pokedaisy.app.companion.ui.theme.ThemeSpec
import kotlinx.datetime.LocalDate
import kotlinx.datetime.number

/**
 * The popup for a limited-time theme whose dates have come (ThemeSpec.season; the Library shows it once per window):
 * the theme's picture on a swatch of its own page and colours, its name, the day it goes, NO THANKS and TRY IT (where
 * the D-pad starts), which puts it on at once. BACK or a tap outside is NO THANKS; the THEME row keeps it until then.
 */
@Composable
fun SeasonalThemeWindow(
    theme: ThemeSpec,
    until: LocalDate,
    m: GbaTextMetrics,
    small: GbaTextMetrics,
    onTry: () -> Unit,
    onNoThanks: () -> Unit,
) {
    val u = m.u
    OptionOverlay(onNoThanks, Modifier.widthIn(max = 640.dp)) {
        Column {
            OptionTitleWindow(tr("A LIMITED TIME THEME"), m)
            Spacer(Modifier.height(u * 4))
            OptionListWindow(m, Modifier.fillMaxWidth()) {
              BoxWithConstraints {
                // No scrolling: a short screen (the RG DS) does without the paragraph; the name and date say enough.
                val short = maxHeight < m.lineHeight * 12
                Column(Modifier.padding(u * 4)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        ThemeSwatch(theme, Modifier.size(u * 56))
                        Spacer(Modifier.width(u * 8))
                        Column(Modifier.weight(1f)) {
                            GbaText(tr(theme.label), OptionColors.value, OptionColors.valueShadow, m, maxLines = 2)
                            Spacer(Modifier.height(u * 3))
                            GbaText(tr("Here until {0}", shortDate(until)), OptionColors.label, OptionColors.labelShadow, small)
                        }
                    }
                    if (!short) {
                        Spacer(Modifier.height(u * 6))
                        GbaText(
                            tr("A theme for the holiday, for a few days only. It colors the library, Settings and the companion. Change it any time in Settings > THEME."),
                            OptionColors.label, OptionColors.labelShadow, small, maxLines = Int.MAX_VALUE,
                        )
                    }
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(u * 4),
                        modifier = Modifier.fillMaxWidth().padding(top = u * 8),
                    ) {
                        OptionButton(tr("NO THANKS"), m, onClick = onNoThanks, modifier = Modifier.weight(1f).height(m.rowHeight * 1.4f))
                        OptionButton(
                            tr("TRY IT"), m, onClick = onTry, emphasis = true, focusStart = true,
                            modifier = Modifier.weight(1f).height(m.rowHeight * 1.4f),
                        )
                    }
                }
              }
            }
        }
    }
}

/** [theme] in little: its page with two of its blobs, its picture in the middle, framed like the list window. */
@Composable
fun ThemeSwatch(theme: ThemeSpec, modifier: Modifier = Modifier) {
    Box(modifier, contentAlignment = Alignment.Center) {
        Canvas(Modifier.matchParentSize().clipToBounds()) {
            drawRect(theme.page)
            theme.blobs.take(2).forEachIndexed { i, c ->
                val center = if (i == 0) Offset(0f, 0f) else Offset(size.width, size.height)
                drawCircle(Brush.radialGradient(listOf(c, c.copy(alpha = 0f)), center, size.width * 0.8f), size.width * 0.8f, center)
            }
            drawLayeredFrame(OptionColors.listLayers.map { (c, w) -> c to w * size.width / 56f }, 2 * size.width / 56f)
        }
        val logo = theme.motif
        if (logo != null) PixelArt(logo.rows, logo.palette, Modifier.matchParentSize().padding(12.dp))
        else PixelArt(LOGO_ROWS, LOGO_PALETTE, Modifier.matchParentSize().padding(12.dp))
    }
}

/** [date] as a short day and month in the app's language ("NOV 3", "3 NOV.", "11月3日", "11월 3일"). */
fun shortDate(date: LocalDate): String {
    val month = date.month.number
    val day = date.day
    return when (L10n.language) {
        AppLanguage.EN -> "${SHORT_MONTHS_EN[month - 1]} $day"
        AppLanguage.JA -> "${month}月${day}日"
        AppLanguage.KO -> "${month}월 ${day}일"
        AppLanguage.FR -> "$day ${SHORT_MONTHS_FR[month - 1]}"
        AppLanguage.DE -> "$day ${SHORT_MONTHS_DE[month - 1]}"
        AppLanguage.IT -> "$day ${SHORT_MONTHS_IT[month - 1]}"
        AppLanguage.ES -> "$day ${SHORT_MONTHS_ES[month - 1]}"
        AppLanguage.BR, AppLanguage.PT -> "$day ${SHORT_MONTHS_PT[month - 1]}"
    }
}

// Each language's abbreviated months in capitals, as Java's CLDR data gives them (the format this used before).
private val SHORT_MONTHS_EN = listOf("JAN", "FEB", "MAR", "APR", "MAY", "JUN", "JUL", "AUG", "SEP", "OCT", "NOV", "DEC")
private val SHORT_MONTHS_FR = listOf("JANV.", "FÉVR.", "MARS", "AVR.", "MAI", "JUIN", "JUIL.", "AOÛT", "SEPT.", "OCT.", "NOV.", "DÉC.")
private val SHORT_MONTHS_DE = listOf("JAN.", "FEB.", "MÄRZ", "APR.", "MAI", "JUNI", "JULI", "AUG.", "SEPT.", "OKT.", "NOV.", "DEZ.")
private val SHORT_MONTHS_IT = listOf("GEN", "FEB", "MAR", "APR", "MAG", "GIU", "LUG", "AGO", "SET", "OTT", "NOV", "DIC")
private val SHORT_MONTHS_ES = listOf("ENE", "FEB", "MAR", "ABR", "MAY", "JUN", "JUL", "AGO", "SEPT", "OCT", "NOV", "DIC")
private val SHORT_MONTHS_PT = listOf("JAN.", "FEV.", "MAR.", "ABR.", "MAI.", "JUN.", "JUL.", "AGO.", "SET.", "OUT.", "NOV.", "DEZ.")
