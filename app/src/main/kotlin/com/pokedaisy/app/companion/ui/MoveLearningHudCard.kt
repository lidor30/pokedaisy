package com.pokedaisy.app.companion.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.pokedaisy.app.companion.FieldHudStyle
import com.pokedaisy.app.companion.data.MoveLearning
import com.pokedaisy.app.companion.data.SnapshotView
import com.pokedaisy.app.companion.data.Verdict
import com.pokedaisy.app.companion.data.moveData
import com.pokedaisy.app.companion.data.lookupMove
import com.pokedaisy.app.companion.data.typeName
import com.pokedaisy.app.companion.i18n.tr
import com.pokedaisy.app.companion.ui.theme.AppColors
import kotlin.math.roundToInt

/**
 * The move-learning card in the game picture's top right, while the game's summary asks which move to forget: the
 * title, then "forget <old> learn <new>", each move name in its own type's colour. Shows over the game even though
 * the normal HUD is hidden in a battle or menu, and goes when the game's page does.
 */
@Composable
internal fun MoveLearningHudCard(snapshot: SnapshotView, style: FieldHudStyle, modifier: Modifier = Modifier) {
    val ml = snapshot.moveLearning ?: return
    val mon = snapshot.party.getOrNull(ml.partyIndex) ?: return
    val tables = snapshot.pokedex?.tables ?: return
    if (!MoveLearning.available(tables, snapshot.guideTables)) return
    val advice = remember(tables, mon, ml.move) { MoveLearning.advise(tables, mon, ml.move) }
    // The same whole-screen-pixel density FieldHud draws its own text with: at the plain device density the card's
    // glyphs land on fractional pixels and look soft next to the HUD.
    val base = LocalDensity.current
    val baseK = with(base) { (20.dp.toPx() / 16f).roundToInt().coerceAtLeast(2) }
    val k = (baseK + style.size.step).coerceAtLeast(2)
    val hudDensity = remember(k, base.fontScale) { Density(k * 16f / 20f, base.fontScale) }
    CompositionLocalProvider(LocalDensity provides hudDensity, LocalHudLook provides style.look) {
        BoxWithConstraints(modifier.fillMaxSize()) {
            val m = rememberGbaTextMetrics()
            val u = m.u
            val colors = ink
            fun typeColor(move: Int): Color =
                AppColors.typeColors[typeName(lookupMove(move).type)] ?: colors.text
            val old = advice.forget
            Column(
                Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 12.dp, end = 12.dp)
                    .widthIn(max = minOf(260.dp, maxWidth - 24.dp))
                    .wrapContentWidth(Alignment.End)
                    .hudPanel(u, radius = 4)
                    .padding(horizontal = u * 6, vertical = u * 4),
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(u * 2),
            ) {
                GbaText(tr("MOVE REPLACEMENT"), colors.muted, colors.shadow, m, maxLines = 1)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    when {
                        old > 0 -> {
                            GbaText(tr("FORGET"), colors.muted, colors.shadow, m)
                            Spacer(Modifier.width(u * 2))
                            GbaText((moveData[old]?.name ?: "#$old"), typeColor(old), colors.shadow, m)
                            Spacer(Modifier.width(u * 5))
                            GbaText(tr("LEARN"), colors.muted, colors.shadow, m)
                            Spacer(Modifier.width(u * 2))
                            GbaText((moveData[ml.move]?.name ?: "#${ml.move}"), typeColor(ml.move), colors.shadow, m)
                        }
                        advice.verdict == Verdict.SKIP -> {
                            GbaText(tr("SKIP"), colors.muted, colors.shadow, m)
                            Spacer(Modifier.width(u * 2))
                            GbaText((moveData[ml.move]?.name ?: "#${ml.move}"), typeColor(ml.move), colors.shadow, m)
                        }
                        advice.verdict == Verdict.KNOWN -> {
                            GbaText(tr("LEARNED"), colors.muted, colors.shadow, m)
                            Spacer(Modifier.width(u * 2))
                            GbaText((moveData[ml.move]?.name ?: "#${ml.move}"), typeColor(ml.move), colors.shadow, m)
                        }
                        else -> {
                            GbaText(tr("LEARN"), colors.muted, colors.shadow, m)
                            Spacer(Modifier.width(u * 2))
                            GbaText((moveData[ml.move]?.name ?: "#${ml.move}"), typeColor(ml.move), colors.shadow, m)
                        }
                    }
                }
            }
        }
    }
}
