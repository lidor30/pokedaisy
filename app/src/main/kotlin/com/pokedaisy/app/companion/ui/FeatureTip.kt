package com.pokedaisy.app.companion.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.pokedaisy.app.companion.FeatureTips
import com.pokedaisy.app.companion.TipId
import com.pokedaisy.app.companion.TipScheduler
import com.pokedaisy.app.companion.i18n.tk
import com.pokedaisy.app.companion.i18n.tr

/** A tip's words: the feature's name, a line or two about it, and its TRY IT button's label (English: OptionButton translates it). */
data class FeatureTip(val title: String, val body: String, val tryLabel: String)

/** What each [TipId]'s card says. */
fun featureTipText(id: TipId): FeatureTip = when (id) {
    TipId.ZOOM -> FeatureTip(
        tr("ZOOM OUT, LOOK AROUND"), tr("With EXPAND you can see further than the game ever showed you."), tk("SHOW ME"),
    )
    TipId.EXPAND -> FeatureTip(
        tr("SEE MORE OF THE WORLD"),
        tr("EXPAND draws the map past the game's edges, so the whole screen is the game. No black bars, nothing stretched."),
        tk("TURN ON"),
    )
    TipId.QUICK_MENU -> FeatureTip(
        tr("EVERYTHING IN ONE PRESS"),
        tr("The QUICK MENU opens the PC, flies, gets on your bike or saves a state without leaving the field."),
        tk("OPEN IT"),
    )
    TipId.HUD -> FeatureTip(
        tr("TAP THE HUD"), tr("Tap a party icon for its summary, the minimap for the map, BAG to use an item."), tk("SHOW ME"),
    )
    TipId.PC -> FeatureTip(
        tr("THE PC, FROM ANYWHERE"), tr("Open your boxes from the BAG tab or the QUICK MENU. No walk back to town."), tk("OPEN PC"),
    )
    TipId.AUTO_RUN -> FeatureTip(
        tr("NO NEED TO HOLD B"), tr("AUTO-RUN makes you run on the field by itself. Hold B when you want to walk."), tk("TURN ON"),
    )
    TipId.WILD -> FeatureTip(
        tr("TIRED OF WILD BATTLES?"), tr("Switch wild encounters off for a while. Fishing and story battles still happen."), tk("SWITCH OFF"),
    )
    TipId.REUSABLE_TMS -> FeatureTip(
        tr("KEEP YOUR TMS"), tr("With REUSABLE TMS a TM stays in your bag after it teaches a move."), tk("TURN ON"),
    )
    TipId.FLY -> FeatureTip(
        tr("FLY WITHOUT THE MENUS"), tr("Pick a town you've been to and fly there: FLY on the MAP, or in the QUICK MENU."), tk("FLY NOW"),
    )
    TipId.DS_TRIGGERS -> FeatureTip(
        tr("TRIGGERS IN DS GAMES"),
        tr("A DS game on one screen keeps its touch screen out of the way. Your triggers bring it in and change how the two screens sit."),
        tk("SHOW ME"),
    )
}

/**
 * The tip on screen ([FeatureTips]' scheduler), sliding in from the top: its bar runs out over [TipScheduler.SHOW_MS]
 * and the card goes by itself (that's the "later"). [active] false = another screen shows it. [keys]: the controls to
 * try it with (the player's own bindings), [onTry] runs it.
 */
@Composable
fun FeatureTipHost(
    active: Boolean,
    roomy: Boolean,
    modifier: Modifier = Modifier,
    cardModifier: Modifier = Modifier,
    maxWidth: Dp = 760.dp,
    keys: (TipId) -> List<Pair<String, String>> = { emptyList() },
    onTry: (TipId) -> Unit,
) {
    val scheduler = FeatureTips.scheduler ?: return
    val tip = scheduler.current
    // The last tip shown, kept while the card slides out.
    var shown by remember { mutableStateOf<TipId?>(null) }
    if (tip != null) shown = tip
    val left = remember { Animatable(1f) }
    LaunchedEffect(tip, active) {
        if (tip == null || !active) return@LaunchedEffect
        left.snapTo(1f)
        left.animateTo(0f, tween(TipScheduler.SHOW_MS.toInt(), easing = LinearEasing))
        scheduler.timedOut(tip)
    }
    Box(modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
        AnimatedVisibility(
            visible = active && tip != null,
            enter = slideInVertically(tween(260)) { -it } + fadeIn(tween(260)),
            exit = slideOutVertically(tween(220)) { -it } + fadeOut(tween(220)),
        ) {
            val id = shown ?: return@AnimatedVisibility
            FeatureTipCard(
                featureTipText(id), cardModifier, keys = keys(id), left = left.value, roomy = roomy, maxWidth = maxWidth,
                onTry = { scheduler.answered(id); onTry(id) },
                onNoThanks = { scheduler.answered(id) },
            )
        }
    }
}

/**
 * After the first scenic FLY has landed (FLY STYLE's default, where the game has it): keep it, or fly the classic way from
 * now on - and FLY STYLE is where to change it later. Shown once ([com.pokedaisy.app.Prefs.flyStyleAsked]) where the tips are
 * (the companion's screen, else over the game); like a tip its bar runs out, and that keeps SCENIC.
 */
object FlyStyleAsk {
    var showing by mutableStateOf(false)
}

/** The card's words: the tip card's pieces, KEEP IT for its TRY IT. */
fun flyStyleAskText(): FeatureTip = FeatureTip(
    tr("ENJOYED THE VIEW?"),
    tr("FLY now takes you there over the map. Keep it, or use the classic FLY. Change it any time in SETTINGS > FLY STYLE."),
    tk("KEEP IT"),
)

/** [FlyStyleAsk] on screen, sliding in from the top like [FeatureTipHost]; [onAnswer] gets the style picked (null: the bar ran out). */
@Composable
fun FlyStyleAskHost(
    active: Boolean,
    roomy: Boolean,
    modifier: Modifier = Modifier,
    cardModifier: Modifier = Modifier,
    maxWidth: Dp = 760.dp,
    onAnswer: (com.pokedaisy.app.companion.FlyStyle?) -> Unit,
) {
    val asking = FlyStyleAsk.showing
    val left = remember { Animatable(1f) }
    LaunchedEffect(asking, active) {
        if (!asking || !active) return@LaunchedEffect
        left.snapTo(1f)
        left.animateTo(0f, tween(TipScheduler.SHOW_MS.toInt(), easing = LinearEasing))
        FlyStyleAsk.showing = false
        onAnswer(null)
    }
    Box(modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
        AnimatedVisibility(
            visible = active && asking,
            enter = slideInVertically(tween(260)) { -it } + fadeIn(tween(260)),
            exit = slideOutVertically(tween(220)) { -it } + fadeOut(tween(220)),
        ) {
            FeatureTipCard(
                flyStyleAskText(), cardModifier, left = left.value, roomy = roomy, maxWidth = maxWidth,
                badge = tk("NEW"), noLabel = tk("USE CLASSIC"),
                onTry = { FlyStyleAsk.showing = false; onAnswer(com.pokedaisy.app.companion.FlyStyle.SCENIC) },
                onNoThanks = { FlyStyleAsk.showing = false; onAnswer(com.pokedaisy.app.companion.FlyStyle.CLASSIC) },
            )
        }
    }
}

/**
 * The tip's card: the flower on the left, the feature's name and a line or two about it, its keys, and TRY IT /
 * NO THANKS. [left] is the share of its time still to go (it goes by itself, which is the "later"); null = no bar.
 * [roomy]: the companion's screen, where the card has room for its keys under the text.
 */
@Composable
fun FeatureTipCard(
    tip: FeatureTip,
    modifier: Modifier = Modifier,
    keys: List<Pair<String, String>> = emptyList(),
    left: Float? = 0.65f,
    roomy: Boolean = false,
    maxWidth: Dp = 760.dp,
    onTry: () -> Unit = {},
    onNoThanks: () -> Unit = {},
    badge: String = tk("TIP"),
    noLabel: String = tk("NO THANKS"),
) {
    val m = rememberGbaTextMetrics()
    val small = rememberGbaTextMetrics(1f)
    val u = m.u
    Column(
        modifier
            .widthIn(max = maxWidth)
            .drawBehind { drawLayeredBox(OptionColors.listLayers.inPx(u.toPx()), OptionColors.titleFill, radius = 3 * u.toPx()) }
            .padding(start = u * 10, end = u * 10, top = u * 8, bottom = u * 6),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            // The helper: the PokeDaisy flower, two lines and a bit tall.
            PixelArt(LOGO_ROWS, LOGO_PALETTE, Modifier.size(m.lineHeight * 2.4f))
            Spacer(Modifier.width(u * 8))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OptionBadge(badge, m)
                    Spacer(Modifier.width(u * 4))
                    GbaText(tip.title, OptionColors.label, OptionColors.labelShadow, m, maxLines = 1)
                }
                Spacer(Modifier.height(u * 2))
                GbaText(tip.body, OptionColors.muted, OptionColors.mutedShadow, small, maxLines = 3)
                if (!roomy && keys.isNotEmpty()) {
                    Spacer(Modifier.height(u * 4))
                    Keys(keys, small)
                }
            }
        }
        if (roomy) {
            // The companion's screen: the keys on a line of their own, then two wide buttons.
            if (keys.isNotEmpty()) {
                Spacer(Modifier.height(u * 6))
                Keys(keys, small)
            }
            Spacer(Modifier.height(u * 6))
            Row(horizontalArrangement = Arrangement.spacedBy(u * 6)) {
                OptionButton(noLabel, m, onClick = onNoThanks, modifier = Modifier.weight(1f))
                OptionButton(tip.tryLabel, m, onClick = onTry, emphasis = true, modifier = Modifier.weight(1f))
            }
        } else {
            // Over the game: the keys under the text, the buttons at the bottom line's right end.
            Spacer(Modifier.height(u * 4))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Spacer(Modifier.weight(1f))
                OptionButton(noLabel, small, onClick = onNoThanks)
                Spacer(Modifier.width(u * 4))
                OptionButton(tip.tryLabel, small, onClick = onTry, emphasis = true)
            }
        }
        if (left != null) {
            Spacer(Modifier.height(u * 5))
            // How long it stays: it goes by itself (= later), so ignoring it is an answer too.
            Box(Modifier.fillMaxWidth().height(u * 2).background(OptionColors.listFill, PixelRoundedShape(u))) {
                Box(Modifier.fillMaxWidth(left).height(u * 2).background(OptionColors.muted, PixelRoundedShape(u)))
            }
        }
    }
}

@Composable
private fun Keys(keys: List<Pair<String, String>>, m: GbaTextMetrics) {
    Row(horizontalArrangement = Arrangement.spacedBy(m.u * 10), verticalAlignment = Alignment.CenterVertically) {
        keys.forEach { (key, what) -> KeyHint(key, what, m) }
    }
}

/** A key cap (the title window's frame) and what it does. */
@Composable
private fun KeyHint(key: String, what: String, m: GbaTextMetrics) {
    val u = m.u
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .drawBehind { drawLayeredBox(OptionColors.titleLayers.inPx(u.toPx()), OptionColors.titleFill, radius = 2 * u.toPx()) }
                .padding(horizontal = u * 5, vertical = u * 2),
        ) { GbaText(key, OptionColors.value, OptionColors.valueShadow, m) }
        Spacer(Modifier.width(u * 4))
        GbaText(what, OptionColors.label, OptionColors.labelShadow, m)
    }
}
