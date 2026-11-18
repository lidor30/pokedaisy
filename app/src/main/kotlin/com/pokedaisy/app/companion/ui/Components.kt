package com.pokedaisy.app.companion.ui

import com.pokedaisy.app.companion.i18n.tr
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.foundation.layout.size
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pokedaisy.app.companion.data.TypeMatchup
import com.pokedaisy.app.companion.ui.theme.AppColors
import com.pokedaisy.app.companion.ui.theme.pixelFontFamily

/** Where each type's tag starts in the games' sheet, in 8x8 tiles from its top left, 16 to a row (sMenuInfoIcons). */
private val TYPE_TAG_TILES = mapOf(
    "Normal" to 0x20, "Fire" to 0x24, "Water" to 0x28, "Grass" to 0x2C, "Electric" to 0x40, "Rock" to 0x44, "Ground" to 0x48,
    "Ice" to 0x4C, "Flying" to 0x60, "Fighting" to 0x64, "Ghost" to 0x68, "Bug" to 0x6C, "Poison" to 0x80, "Psychic" to 0x84,
    "Steel" to 0x88, "Dark" to 0x8C, "Dragon" to 0xA0, "???" to 0xA4,
)
private const val TYPE_TAG_W = 32
private const val TYPE_TAG_H = 12
/** Each this much of a badge's font size is one more pixel of tag per GBA pixel: 12 sp 1x, a move card's 16 sp 2x. */
private const val TYPE_TAG_SP = 8f

/** The running game's sheet of type tags, read from its ROM: the original FireRed / Emerald engines in English (the
 * words are in the picture, so a hack's own types or another language keep the drawn badge). Null = draw it. */
@Composable
private fun rememberTypeTags(): androidx.compose.ui.graphics.ImageBitmap? {
    val game = com.pokedaisy.app.companion.data.activeGame
    val path = when {
        com.pokedaisy.app.companion.data.romLanguage != 'E' -> null
        com.pokedaisy.app.companion.data.runningFireRedHack != null || com.pokedaisy.app.companion.data.runningEmeraldHack != null -> null
        game == com.pokedaisy.app.companion.data.GameKind.FIRERED -> com.pokedaisy.app.companion.data.RomArt.TYPE_TAGS_FIRERED
        game == com.pokedaisy.app.companion.data.GameKind.EMERALD -> com.pokedaisy.app.companion.data.RomArt.TYPE_TAGS_EMERALD
        else -> null
    } ?: return null
    val generation = rememberArtGeneration()
    return androidx.compose.runtime.remember(path, generation) {
        GameArt.get(path)
    }
}

@Composable
fun TypeBadge(type: String, modifier: Modifier = Modifier, fontSize: TextUnit = 12.sp) {
    val tags = rememberTypeTags()
    val tile = TYPE_TAG_TILES[type]
    if (tags != null && tile != null) {
        // Whole pixels of the tag per GBA pixel, as large as the words the badge would have had.
        val u = rememberGbaTextMetrics().u * (fontSize.value / TYPE_TAG_SP).toInt().coerceAtLeast(1)
        androidx.compose.foundation.Canvas(modifier.size(u * TYPE_TAG_W, u * TYPE_TAG_H).semantics { contentDescription = com.pokedaisy.app.companion.data.typeLabel(type) }) {
            drawImage(
                tags, srcOffset = androidx.compose.ui.unit.IntOffset(tile % 16 * 8, tile / 16 * 8),
                srcSize = androidx.compose.ui.unit.IntSize(TYPE_TAG_W, TYPE_TAG_H),
                dstSize = androidx.compose.ui.unit.IntSize(size.width.toInt(), size.height.toInt()),
                filterQuality = androidx.compose.ui.graphics.FilterQuality.None,
            )
        }
        return
    }
    GameTag(com.pokedaisy.app.companion.data.typeLabel(type), AppColors.typeColors[type] ?: AppColors.muted, modifier, fontSize, minWidth = TYPE_TAG_W)
}

/**
 * Every drawn tag (verdicts, types where the ROM has no tag art, statuses, multipliers), built like the games' own type
 * tags (FireRed's sMenuInfoIcons, measured from the ROM): one flat colour, each corner notched by a single pixel, white
 * capitals with the font's #424242 shadow a pixel right, down and diagonally ([GbaText]'s), 12 pixels tall with the
 * lettering centred, four pixels either side. The game's capitals are 7 pixels tall; Pixel Operator's are 9 font pixels, so a font pixel is
 * 7/9 of a GBA pixel, rounded to whole screen pixels. [fontSize] picks the size the way the ROM tags do (8 sp a step:
 * 12 sp 1x, a move card's 16 sp 2x); [minWidth] in GBA pixels (a type tag: the game's 32).
 */
@Composable
internal fun GameTag(
    text: String, color: Color, modifier: Modifier = Modifier, fontSize: TextUnit = 12.sp, textColor: Color = Color.White,
    minWidth: Int = 0,
) {
    val density = androidx.compose.ui.platform.LocalDensity.current
    val scale = (fontSize.value / TYPE_TAG_SP).toInt().coerceAtLeast(1)
    val k = with(density) { kotlin.math.round(20.dp.toPx() / 16f).toInt().coerceAtLeast(2) } * scale
    GameTagBox(text, color, k, modifier, textColor, minWidth = minWidth)
}

/**
 * [GameTag] at [k] screen pixels a tag pixel: the app's other tags (a settings row's NEW, the library's NEW / BLOCKED / NOT
 * SUPPORTED, BETA, WHAT'S NEW's version) at their row's GBA pixel ([OptionBadge]).
 */
@Composable
internal fun GameTagBox(
    text: String, color: Color, k: Int, modifier: Modifier = Modifier, textColor: Color = Color.White, minWidth: Int = 0,
    shadow: Color = TAG_SHADOW,
) {
    val density = androidx.compose.ui.platform.LocalDensity.current
    // The nearest whole font pixel to 7/9 of a GBA pixel that still fits the capitals and their shadow (10 font pixels) in the
    // box with a screen pixel to spare.
    // A short companion (the RG DS: 2 screen pixels a GBA pixel) rounds down: its narrow columns can't take 2-pixel letters,
    // which came out bigger than the game's own tags beside them and pushed the summary's matchups off the bottom.
    val short = LocalShortCompanion.current
    val f = (if (short) (7 * k) / 9 else kotlin.math.round(7f * k / 9f).toInt()).coerceAtMost((12 * k - 2) / 10).coerceAtLeast(1)
    val m = androidx.compose.runtime.remember(k, f, density) {
        with(density) { GbaTextMetrics(k, k.toDp(), (16f * f).toSp(), f.toFloat(), (16f * f).toDp(), (20f * f).toDp()) }
    }
    val u = with(density) { k.toDp() }
    // The game's tag height, 12 GBA pixels, the capitals and their shadow centred in it (as the ROM tags beside it are drawn).
    val height = with(density) { (if (short) 10 * f + 3 * k else 12 * k).toDp() }
    Box(
        modifier = modifier
            .height(height)
            .widthIn(min = u * minWidth)
            .background(color, NotchedShape(u))
            .padding(horizontal = u * 4),
        contentAlignment = Alignment.Center,
    ) {
        // The line is taller than a small tag (16 font pixels to the box's 12 GBA ones): laid out whole, centred over it, then
        // a font pixel up - the game's letters sit above the middle (two pixels over them, three under with the shadow).
        GbaText(text, textColor, shadow, m,
            Modifier.wrapContentHeight(unbounded = true).offset { androidx.compose.ui.unit.IntOffset(0, -f) }, maxLines = 1)
    }
}

/** The games' tag lettering's shadow (every type tag's, whatever its colour). */
internal val TAG_SHADOW = Color(0xFF424242)

/** A rectangle with a [notch]-square cut from each corner: the games' tags' one-pixel corners. */
class NotchedShape(private val notch: Dp) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val c = with(density) { notch.toPx() }.coerceAtMost(minOf(size.width, size.height) / 2f)
        val w = size.width
        val h = size.height
        return Outline.Generic(Path().apply {
            moveTo(c, 0f); lineTo(w - c, 0f); lineTo(w - c, c); lineTo(w, c)
            lineTo(w, h - c); lineTo(w - c, h - c); lineTo(w - c, h); lineTo(c, h)
            lineTo(c, h - c); lineTo(0f, h - c); lineTo(0f, c); lineTo(c, c); close()
        })
    }
}

@Composable
fun StatusBadge(status: String, modifier: Modifier = Modifier) {
    if (status.isEmpty()) return
    GameTag(status, AppColors.statusColor(status), modifier)
}

@Composable
fun MultiplierChip(label: String, pct: Int, modifier: Modifier = Modifier, fontSize: TextUnit = 10.sp) {
    val bg = AppColors.multiplierColor(pct)
    val fg = if (pct == 0) Color(0xFFF2B8BD) else Color.White
    GameTag(label, bg, modifier, textColor = fg)
}

/** "35" for a damaging move, "—" for a status move (power 0 in the ROM's own
 * move data, same convention every mainline Pokémon game uses on its own move
 * info screen). */
fun powerLabel(power: Int): String = if (power > 0) power.toString() else "—"

@Composable
fun HpBar(hp: Int, maxHp: Int, modifier: Modifier = Modifier) {
    val pct = if (maxHp == 0) 0f else (hp.toFloat() / maxHp).coerceIn(0f, 1f)
    Row(verticalAlignment = Alignment.CenterVertically, modifier = modifier) {
        Text(tr("HP"), color = AppColors.hpHigh, fontSize = 10.sp, fontFamily = pixelFontFamily())
        Box(
            modifier = Modifier
                .padding(horizontal = 4.dp)
                .weight(1f)
                .height(8.dp)
                .clip(PixelRoundedShape(2.dp))
                .background(Color(0xFF2C2418))
                .border(1.dp, AppColors.borderOuter, PixelRoundedShape(2.dp)),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(pct)
                    .background(AppColors.hpColor(hp, maxHp)),
            )
        }
        Text("$hp/$maxHp", color = AppColors.muted, fontSize = 12.sp, fontFamily = pixelFontFamily())
    }
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun MatchupChipRow(heading: String, list: List<TypeMatchup>) {
    if (list.isEmpty()) return
    Text(heading, color = AppColors.muted, fontSize = 10.sp, fontFamily = pixelFontFamily(), modifier = Modifier.padding(top = 6.dp, bottom = 2.dp))
    androidx.compose.foundation.layout.FlowRow {
        list.forEach { m ->
            Box(modifier = Modifier.padding(end = 4.dp, bottom = 4.dp)) {
                TypeBadgeWithLabel(m)
            }
        }
    }
}

/** A type badge with its multiplier ("FIRE 2x"). */
/** The move's category beside its [TypeBadge] ([moveKind]); nothing where the game's rule isn't known. */
@Composable
fun MoveKindIcon(mv: com.pokedaisy.app.companion.data.MoveView, m: GbaTextMetrics) {
    val kind = com.pokedaisy.app.companion.data.moveKind(mv) ?: return
    val art = when (kind) {
        com.pokedaisy.app.companion.data.MoveKind.PHYSICAL -> PixelIcons.movePhysical
        com.pokedaisy.app.companion.data.MoveKind.SPECIAL -> PixelIcons.moveSpecial
        com.pokedaisy.app.companion.data.MoveKind.STATUS -> PixelIcons.moveStatus
    }
    val label = com.pokedaisy.app.companion.i18n.tr(kind.label)
    Spacer(Modifier.width(m.u * 3))
    PixelArt(art, MOVE_KIND_COLORS, Modifier.size(m.u * 11).semantics { contentDescription = label })
}

private val MOVE_KIND_COLORS = mapOf(
    'R' to Color(0xFFC03018), 'O' to Color(0xFFF08030), 'Y' to Color(0xFFF8D030),
    'B' to Color(0xFF2848A8), 'C' to Color(0xFF58A0F0), 'W' to Color(0xFFF0F8FF),
    'G' to Color(0xFF686878), 'L' to Color(0xFFB8B8C8),
)

@Composable
fun TypeBadgeWithLabel(m: TypeMatchup, modifier: Modifier = Modifier) {
    // The multiplier stays inside the tag (the user's call): a drawn tag, since the game's tag picture has no room for it.
    GameTag("${com.pokedaisy.app.companion.data.typeLabel(m.type)} ${m.label}", AppColors.typeColors[m.type] ?: AppColors.muted, modifier)
}

@Composable
fun PanelBox(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    com.pokedaisy.app.companion.ui.theme.GbaWindow(
        modifier = modifier,
        padding = androidx.compose.foundation.layout.PaddingValues(12.dp),
    ) { content() }
}

/** A rounded rect with its corners cut to a single diagonal step instead of a
 * smooth arc - reads as a chunky pixel-art corner at typical button/badge
 * sizes. Shared by [TypeBadge] and [PlatinumButton] (in BattleControlsScreen)
 * so every pixel-corner element in the app uses the exact same shape. */
class PixelCornerShape(private val step: Dp) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val s = with(density) { step.toPx() }.coerceAtMost(minOf(size.width, size.height) / 2f)
        val w = size.width
        val h = size.height
        val path = Path().apply {
            moveTo(s, 0f)
            lineTo(w - s, 0f)
            lineTo(w - s, s)
            lineTo(w, s)
            lineTo(w, h - s)
            lineTo(w - s, h - s)
            lineTo(w - s, h)
            lineTo(s, h)
            lineTo(s, h - s)
            lineTo(0f, h - s)
            lineTo(0f, s)
            lineTo(s, s)
            close()
        }
        return Outline.Generic(path)
    }
}
