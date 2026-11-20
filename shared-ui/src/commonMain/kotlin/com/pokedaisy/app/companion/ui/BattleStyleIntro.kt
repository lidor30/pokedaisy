package com.pokedaisy.app.companion.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import com.pokedaisy.app.companion.BattleInput
import com.pokedaisy.app.companion.BattleStyle
import com.pokedaisy.app.companion.data.BATTLE_INPUT_ACTION_SELECT
import com.pokedaisy.app.companion.data.MonView
import com.pokedaisy.app.companion.data.MoveView
import com.pokedaisy.app.companion.i18n.tr

/**
 * Shown once, on the Library, after the update that brought BATTLE STYLE (and on a new install once setup is done):
 * both battle screens side by side as live miniatures - the real [BattleControlsScreen] over a made-up turn - and a
 * button under each. A pick sets [com.pokedaisy.app.Prefs.battleStyle]; picking or closing marks it answered
 * ([com.pokedaisy.app.Prefs.battleStyleAsked]). The companion's SETTINGS > BATTLE STYLE changes it later.
 */
@Composable
fun BattleStyleIntro(
    m: GbaTextMetrics,
    small: GbaTextMetrics,
    current: BattleStyle,
    onPick: (BattleStyle) -> Unit,
    onDismiss: () -> Unit,
) {
    OptionOverlay(onDismiss, Modifier.widthIn(max = 680.dp)) {
        Column {
            OptionTitleWindow(tr("NEW: BATTLE STYLE"), m, onBack = onDismiss)
            Spacer(Modifier.height(m.u * 4))
            OptionListWindow(m, Modifier.fillMaxWidth()) {
                Column(Modifier.padding(m.u * 4)) {
                    GbaText(
                        tr("Pick the look of the companion's battle screen. Both work the same way.") + " " +
                            tr("Change it any time in the companion's SETTINGS > BATTLE STYLE."),
                        OptionColors.label, OptionColors.labelShadow, small, maxLines = Int.MAX_VALUE,
                        modifier = Modifier.padding(horizontal = m.u * 4),
                    )
                    Spacer(Modifier.height(m.u * 6))
                    Row(horizontalArrangement = Arrangement.spacedBy(m.u * 8), modifier = Modifier.fillMaxWidth()) {
                        for (style in BattleStyle.entries) {
                            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                                BattleStylePreview(
                                    style,
                                    Modifier.fillMaxWidth(0.8f).aspectRatio(PREVIEW_W / PREVIEW_H).clip(PixelRoundedShape(m.u * 3)),
                                    onClick = { onPick(style) },
                                )
                                Spacer(Modifier.height(m.u * 4))
                                OptionButton(
                                    style.label, m, onClick = { onPick(style) }, emphasis = style == current,
                                    modifier = Modifier.fillMaxWidth().height(m.rowHeight * 1.3f),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/** The companion's tab area in the Thor's bottom screen, in dp, drawn at whatever width it's given. */
private const val PREVIEW_W = 472f
private const val PREVIEW_H = 360f

/** [style]'s battle screen on a made-up turn, scaled down whole: the real composable, its taps swallowed. */
@Composable
fun BattleStylePreview(style: BattleStyle, modifier: Modifier = Modifier, onClick: (() -> Unit)? = null) {
    val input = remember { PreviewBattleInput() }
    BoxWithConstraints(modifier.background(PreviewBackdrop)) {
        val scale = maxWidth.value / PREVIEW_W
        Box(
            Modifier
                .wrapContentSize(Alignment.TopStart, unbounded = true)
                .requiredSize(PREVIEW_W.dp, PREVIEW_H.dp)
                .graphicsLayer { scaleX = scale; scaleY = scale; transformOrigin = TransformOrigin(0f, 0f) },
        ) {
            BattleControlsScreen(
                activeMon = PREVIEW_MON, battleInput = input, battleInputState = BATTLE_INPUT_ACTION_SELECT,
                party = PREVIEW_PARTY, showHints = true,
                // GEN 5 fills the tab edge to edge in game; GEN 4 keeps the tab's margin.
                modifier = Modifier.fillMaxSize().padding(if (style == BattleStyle.GEN5) 0.dp else 8.dp),
                foeParty = PREVIEW_FOES, style = style,
            )
        }
        // On top: a tap is the preview's own, never a battle button's (the top-most child takes it).
        Box(Modifier.fillMaxSize().then(if (onClick != null) Modifier.soundClickable(onClick = onClick) else Modifier))
    }
}

private class PreviewBattleInput : BattleInput {
    override val busy = false
    override fun selectAction(actionIndex: Int) {}
    override fun selectMove(moveIndex: Int) {}
    override fun back() {}
}

private val PreviewBackdrop = Color(0xFF2C7068)

// The website's invented creatures and moves (no real names in the app's own pictures).
private val PREVIEW_MON = MonView(
    species = 0, name = "EMBERPUP", level = 14, hp = 38, maxHp = 44, status = "", types = listOf("FIRE"), iconAsset = null,
    moves = listOf(
        MoveView("PAW SWIPE", "NORMAL", 30, 40),
        MoveView("CINDER SPIT", "FIRE", 20, 45),
        MoveView("WARY GLARE", "NORMAL", 25, 0),
        MoveView("IRON NIP", "STEEL", 15, 50),
    ),
)
private val PREVIEW_PARTY = listOf(PREVIEW_MON, PREVIEW_MON.copy(name = "SPROUTLING"), PREVIEW_MON.copy(name = "PEBBLON", hp = 0))
private val PREVIEW_FOES = listOf(PREVIEW_MON.copy(name = "CRAGMAW"), PREVIEW_MON.copy(name = "CRAGMAW"))
