package com.pokedaisy.app.companion.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import com.pokedaisy.app.companion.data.GENDER_SYMBOL_FEMALE
import com.pokedaisy.app.companion.data.GENDER_SYMBOL_MALE
import com.pokedaisy.app.companion.data.MonView
import com.pokedaisy.app.companion.i18n.tr

/*
 * Pokemon X / Y's own look for the companion (the user's call: the 3DS games' smooth UI, not the app's pixel windows;
 * colours sampled from the game's party screen, headless, 2026-11-15): its party screen's striped green backdrop, its
 * pill-shaped slots in a green rim with the level on a tab, and the HUD's pieces built from them (XyFieldHud).
 */

internal object XyColors {
    val outline = Color(0xFF2B8E26)
    val rimTop = Color(0xFFB4ED88)
    val rimBottom = Color(0xFF6CAF4B)
    val faceTop = Color(0xFFEEF0E8)
    val faceBottom = Color(0xFFD6D8CF)
    val faceLow = Color(0xFFFFFFFF)
    val tabTop = Color(0xFFA9E585)
    val tabBottom = Color(0xFF4B9829)
    val hpFrame = Color(0xFF222222)
    val hpLabel = Color(0xFFBBBBBB)
    val hpHigh = Color(0xFF00F200)
    val hpHighDark = Color(0xFF00A300)
    val hpMid = Color(0xFFF8D800)
    val hpMidDark = Color(0xFFC09000)
    val hpLow = Color(0xFFF84040)
    val hpLowDark = Color(0xFFB01818)
    val name = Color(0xFF202020)
    val male = Color(0xFF2E8CF0)
    val female = Color(0xFFF04848)
    val backTop = Color(0xFFB2D6A4)
    val backBottom = Color(0xFF8DBC96)
    val stripe = Color(0xFF7FA77A)
}

/**
 * The colours that make a panel X's green or Y's red: its dark edge, the rim's two tones, a tab's, a panel's backdrop.
 * The HUD takes the game's ([forHud]: Y's red, the user's call 2026-11-16; Y's own menus are X's green, so the red is
 * ours, from Y's logo and Yveltal); the companion's party screen keeps the game's green ([GREEN]).
 */
internal class XyTint(
    val outline: Color,
    val rimTop: Color,
    val rimBottom: Color,
    val tabTop: Color,
    val tabBottom: Color,
    val backTop: Color,
    val backBottom: Color,
) {
    companion object {
        val GREEN = XyTint(
            XyColors.outline, XyColors.rimTop, XyColors.rimBottom, XyColors.tabTop, XyColors.tabBottom,
            XyColors.backTop, XyColors.backBottom,
        )
        /** Y's: the same lightness steps as X's greens, in Y's crimson. */
        val RED = XyTint(
            outline = Color(0xFFA0222C),
            rimTop = Color(0xFFF7A9AC),
            rimBottom = Color(0xFFD24F5A),
            tabTop = Color(0xFFF29A9F),
            tabBottom = Color(0xFFB02A36),
            backTop = Color(0xFFE6B8BB),
            backBottom = Color(0xFFC9939A),
        )

        fun forHud(game: com.pokedaisy.app.companion.data.GameKind?): XyTint =
            if (game == com.pokedaisy.app.companion.data.GameKind.POKEMON_Y) RED else GREEN
    }
}

/** The game's party backdrop: a soft green, lighter at the top, crossed by thin darker diagonal stripes. */
@Composable
fun XyBackdrop(modifier: Modifier = Modifier.fillMaxSize()) {
    Canvas(modifier) {
        drawRect(Brush.verticalGradient(listOf(XyColors.backTop, XyColors.backBottom)))
        // One stripe every five game pixels of a 320-wide touch screen, as the game's.
        val step = (size.width / 64f).coerceAtLeast(4f)
        val w = (step * 0.28f).coerceAtLeast(1f)
        var x = -size.height
        while (x < size.width) {
            drawLine(XyColors.stripe.copy(alpha = 0.35f), Offset(x, size.height), Offset(x + size.height, 0f), w)
            x += step
        }
    }
}

/** The PARTY tab in the game's look: two columns of its pill slots, the right one set half a slot lower. */
@Composable
fun XyPartyScreen(party: List<MonView>, onMonClick: (MonView) -> Unit) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val w = constraints.maxWidth.toFloat()
        val h = constraints.maxHeight.toFloat()
        val gap = w * 0.02f
        // Wide and short, as the game's (158 x 58 of its 320); smaller where the tab is short.
        var slotW = (w - gap * 3) / 2f
        var slotH = slotW * 0.37f
        val stagger = slotH * 0.28f
        val needH = slotH * 3 + gap * 2 + stagger
        if (needH > h - gap * 2) {
            val k = (h - gap * 2) / needH
            slotW *= k
            slotH *= k
        }
        val totalW = slotW * 2 + gap
        val totalH = slotH * 3 + gap * 2 + slotH * 0.28f
        val x0 = (w - totalW) / 2f
        val y0 = (h - totalH) / 2f
        val density = LocalDensity.current
        party.take(6).forEachIndexed { i, mon ->
            val col = i % 2
            val row = i / 2
            val x = x0 + col * (slotW + gap)
            val y = y0 + row * (slotH + gap) + if (col == 1) slotH * 0.28f else 0f
            XyPartySlot(
                mon, slotW, slotH,
                Modifier.offset { IntOffset(x.toInt(), y.toInt()) }
                    .size(with(density) { slotW.toDp() }, with(density) { slotH.toDp() })
                    .clickable(remember { MutableInteractionSource() }, null) { onMonClick(mon) },
            )
        }
    }
}

/** One party slot as the game draws it, [w] x [h] px: the icon, name and gender, the level on its tab, HP. */
@Composable
fun XyPartySlot(mon: MonView, w: Float, h: Float, modifier: Modifier = Modifier) {
    val density = LocalDensity.current
    fun dp(px: Float) = with(density) { px.toDp() }
    Box(modifier) {
        Canvas(Modifier.fillMaxSize()) { drawXyPill(Rect(0f, h * 0.12f, w, h), h) }
        // The level's tab on the top rim, right of the middle.
        Box(
            Modifier.offset { IntOffset((w * 0.56f).toInt(), 0) }.size(dp(w * 0.36f), dp(h * 0.28f)),
            contentAlignment = Alignment.Center,
        ) {
            Canvas(Modifier.fillMaxSize()) { drawXyTab() }
            if (!mon.isEgg) XyText(tr("LV {0}", mon.level), h * 0.2f, Color.White, Color.Black, h * 0.025f)
        }
        val faceTop = h * 0.12f
        val faceH = h - faceTop
        // (The icon stands at the bottom of its square frame, as a GBA icon does: lifted to the slot's middle.)
        SpeciesIcon(
            mon.iconAsset, size = dp(faceH * 0.92f),
            modifier = Modifier.offset { IntOffset((w * 0.035f).toInt(), (faceTop - faceH * 0.12f).toInt()) },
        )
        Box(Modifier.offset { IntOffset((w * 0.29f).toInt(), (faceTop + faceH * 0.12f).toInt()) }.width(dp(w * 0.52f))) {
            XyText(mon.shownName, faceH * 0.3f, XyColors.name, Color.Transparent, 0f, weight = FontWeight.Medium)
        }
        val gender = when (mon.genderSymbol) {
            GENDER_SYMBOL_MALE -> "♂" to XyColors.male
            GENDER_SYMBOL_FEMALE -> "♀" to XyColors.female
            else -> null
        }
        if (gender != null && !mon.isEgg) Box(Modifier.offset { IntOffset((w * 0.84f).toInt(), (faceTop + faceH * 0.1f).toInt()) }) {
            XyText(gender.first, faceH * 0.32f, gender.second, Color.White, faceH * 0.02f)
        }
        if (!mon.isEgg) {
            Canvas(Modifier.fillMaxSize()) {
                drawXyHpBar(Rect(w * 0.22f, faceTop + faceH * 0.56f, w * 0.72f, faceTop + faceH * 0.7f), mon.hp, mon.maxHp)
            }
            Box(Modifier.offset { IntOffset((w * 0.225f).toInt(), (faceTop + faceH * 0.535f).toInt()) }) {
                XyText(tr("HP"), faceH * 0.13f, Color.Black, Color.Transparent, 0f)
            }
            Box(Modifier.offset { IntOffset((w * 0.27f).toInt(), (faceTop + faceH * 0.71f).toInt()) }) {
                XyText("${mon.hp}/${mon.maxHp}", faceH * 0.25f, Color.White, Color.Black, faceH * 0.03f)
            }
            val status = if (mon.hp == 0) "FNT" else mon.status
            if (status.isNotEmpty()) Box(Modifier.offset { IntOffset((w * 0.76f).toInt(), (faceTop + faceH * 0.55f).toInt()) }) {
                XyStatusTag(status, faceH * 0.2f)
            }
        }
    }
}

/** A status (or FNT) as the game's small coloured tag. */
@Composable
internal fun XyStatusTag(status: String, size: Float) {
    val color = when (status) {
        "PSN", "TOX" -> Color(0xFFA040C0)
        "PAR" -> Color(0xFFD8B020)
        "SLP" -> Color(0xFF8A8A8A)
        "BRN" -> Color(0xFFE06030)
        "FRZ" -> Color(0xFF50A8E0)
        else -> Color(0xFFC03030)
    }
    Box(
        Modifier.drawBehind { drawRoundRect(color, cornerRadius = CornerRadius(this.size.height / 2)) },
        contentAlignment = Alignment.Center,
    ) {
        XyText(" $status ", size, Color.White, Color.Black.copy(alpha = 0.5f), size * 0.08f)
    }
}

/** The game's pill: a dark green outline, a light-to-dark green rim, the face light above a white lower lobe. */
internal fun DrawScope.drawXyPill(r: Rect, h: Float) {
    val radius = r.height / 2f
    drawRoundRect(Color.Black.copy(alpha = 0.22f), Offset(r.left, r.top + h * 0.04f), r.size, CornerRadius(radius))
    drawRoundRect(XyColors.outline, r.topLeft, r.size, CornerRadius(radius))
    val rim = r.deflate(h * 0.025f)
    drawRoundRect(Brush.verticalGradient(listOf(XyColors.rimTop, XyColors.rimBottom), rim.top, rim.bottom), rim.topLeft, rim.size, CornerRadius(rim.height / 2))
    val face = rim.deflate(h * 0.07f)
    val fr = CornerRadius(face.height / 2)
    drawRoundRect(Brush.verticalGradient(listOf(XyColors.faceTop, XyColors.faceBottom), face.top, face.bottom), face.topLeft, face.size, fr)
    // The lower white lobe, its top edge a gentle wave (higher on the left), as the game's face.
    val lobe = Path().apply {
        moveTo(face.left, face.top + face.height * 0.62f)
        cubicTo(
            face.left + face.width * 0.35f, face.top + face.height * 0.45f,
            face.left + face.width * 0.65f, face.top + face.height * 0.78f,
            face.right, face.top + face.height * 0.55f,
        )
        lineTo(face.right, face.bottom)
        lineTo(face.left, face.bottom)
        close()
    }
    val clip = Path().apply { addRoundRect(androidx.compose.ui.geometry.RoundRect(face, fr)) }
    clipPath(clip) { drawPath(lobe, XyColors.faceLow) }
}

/** The level's tab: a small green pill with a dark edge. */
internal fun DrawScope.drawXyTab(tint: XyTint = XyTint.GREEN) {
    val r = CornerRadius(size.height / 2)
    drawRoundRect(tint.outline, cornerRadius = r)
    val i = Rect(size.height * 0.08f, size.height * 0.08f, size.width - size.height * 0.08f, size.height * 0.92f)
    drawRoundRect(Brush.verticalGradient(listOf(tint.tabTop, tint.tabBottom), i.top, i.bottom), i.topLeft, i.size, CornerRadius(i.height / 2))
}

/** HP as the game's bar: a grey "HP" end, the bar green / yellow / red by what's left, in a dark frame. */
internal fun DrawScope.drawXyHpBar(r: Rect, hp: Int, maxHp: Int) {
    val frame = CornerRadius(r.height / 2)
    drawRoundRect(XyColors.hpFrame, r.topLeft, r.size, frame)
    val label = Rect(r.left, r.top, r.left + r.height * 1.6f, r.bottom).deflate(r.height * 0.12f)
    drawRoundRect(XyColors.hpLabel, label.topLeft, label.size, CornerRadius(label.height / 2))
    val bar = Rect(label.right + r.height * 0.15f, r.top + r.height * 0.18f, r.right - r.height * 0.25f, r.bottom - r.height * 0.18f)
    val share = if (maxHp > 0) (hp.toFloat() / maxHp).coerceIn(0f, 1f) else 0f
    if (share > 0f) {
        val (light, dark) = when {
            share > 0.5f -> XyColors.hpHigh to XyColors.hpHighDark
            share > 0.2f -> XyColors.hpMid to XyColors.hpMidDark
            else -> XyColors.hpLow to XyColors.hpLowDark
        }
        val filled = Size(bar.width * share, bar.height)
        drawRoundRect(Brush.verticalGradient(listOf(light, dark), bar.top, bar.bottom), bar.topLeft, filled, CornerRadius(bar.height / 2))
    }
}
