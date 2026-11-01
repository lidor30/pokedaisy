package com.pokedaisy.app.companion.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ImageShader
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.TileMode
import com.pokedaisy.app.companion.data.GameKind

/**
 * The DS games' own screen backgrounds, sampled headless from each game's screens (nds_dump on the player's saves): a
 * small tile of the game's pattern, repeated over the tab in whole GBA pixels ([rememberGbaTextMetrics]' unit, one DS
 * pixel each). Black's party grid, bag dots and Pokédex grid; Platinum's party stripes and its bag screen's two yellows;
 * HeartGold / SoulSilver's Pokédex.
 */
enum class DsPattern(internal val tile: List<String>, internal val colors: Map<Char, Color>) {
    /** Black's party screen: navy with a grey line every 32 pixels, a darker line beside it. */
    BLACK_PARTY(
        List(32) { y ->
            when (y) {
                0 -> "L".repeat(32)
                1 -> "Lh" + ".".repeat(30)
                31 -> "Lh" + "h".repeat(30)
                else -> "Lh" + ".".repeat(29) + "h"
            }
        },
        mapOf('.' to Color(0xFF101820), 'L' to Color(0xFF414141), 'h' to Color(0xFF202020)),
    ),
    /** Black's bag: blue with a darker cross every 8 pixels. */
    BLACK_BAG(
        listOf("........", "........", "...x....", "..xxx...", "...x....", "........", "........", "........"),
        mapOf('.' to Color(0xFF2069D3), 'x' to Color(0xFF00519A)),
    ),
    /** Black's Pokédex: green squared every 8 pixels. */
    BLACK_DEX(
        List(8) { y -> if (y == 0) "LLLLLLLL" else "L......." },
        mapOf('.' to Color(0xFF08B279), 'L' to Color(0xFF008261)),
    ),
    /** Black 2's and White 2's Pokédex: Black's grid in red (headless, its list screen). */
    BLACK2_DEX(
        List(8) { y -> if (y == 0) "LLLLLLLL" else "L......." },
        mapOf('.' to Color(0xFFBA2010), 'L' to Color(0xFF821808)),
    ),
    /** HeartGold / SoulSilver's Pokédex: white, a grey line every 40 pixels. */
    HGSS_DEX(
        List(40) { y -> if (y == 0) "L".repeat(40) else "L" + ".".repeat(39) },
        mapOf('.' to Color(0xFFFBFBFB), 'L' to Color(0xFFAAAAAA)),
    ),
    /** Platinum's party screen: slate stripes a pixel apart. */
    PLATINUM_PARTY(listOf("a", "b"), mapOf('a' to Color(0xFF383849), 'b' to Color(0xFF798292))),
    /** Platinum's bag screen (its touch screen): two warm yellows. */
    PLATINUM_BAG(listOf("a", "a", "b", "b"), mapOf('a' to Color(0xFFEBCB79), 'b' to Color(0xFFEBC369))),
}

/** The party screen's pattern behind every tab of [game], or null for its art / stripes ([GameBackdrop]). */
fun dsPartyPattern(game: GameKind): DsPattern? = when (game) {
    GameKind.BLACK, GameKind.WHITE, GameKind.BLACK2, GameKind.WHITE2 -> DsPattern.BLACK_PARTY
    GameKind.PLATINUM -> DsPattern.PLATINUM_PARTY
    else -> null
}

/** The Pokédex's own pattern over the party one while DEX is open (Black's green grid, HG / SS's white one), or null. */
fun dsDexPattern(game: GameKind): DsPattern? = when (game) {
    GameKind.BLACK, GameKind.WHITE -> DsPattern.BLACK_DEX
    GameKind.BLACK2, GameKind.WHITE2 -> DsPattern.BLACK2_DEX
    GameKind.HEARTGOLD, GameKind.SOULSILVER -> DsPattern.HGSS_DEX
    else -> null
}

/** [pattern] over the whole of [modifier]'s box, its tile [px] screen pixels a game pixel; dark mode dims it towards
 * [NIGHT] like the other backdrops ([scrim]). */
@Composable
fun DsBackdrop(pattern: DsPattern, modifier: Modifier = Modifier.fillMaxSize(), scrim: Float = OptionColors.backdropScrim) {
    val px = rememberGbaTextMetrics().px
    val brush = remember(pattern, px) { tileBrush(pattern, px) }
    Canvas(if (scrim > 0f) modifier.drawWithContent { drawContent(); drawRect(NIGHT.copy(alpha = scrim)) } else modifier) {
        drawRect(brush)
    }
}

/** [pattern]'s tile drawn at [px] screen pixels a pixel, repeated both ways. */
private fun tileBrush(pattern: DsPattern, px: Int): ShaderBrush {
    val rows = pattern.tile
    val w = rows.maxOf { it.length }
    val image = ImageBitmap(w * px, rows.size * px)
    val canvas = androidx.compose.ui.graphics.Canvas(image)
    val paint = Paint()
    rows.forEachIndexed { y, row ->
        for (x in 0 until w) {
            paint.color = pattern.colors[row.getOrElse(x) { row.last() }] ?: continue
            canvas.drawRect(androidx.compose.ui.geometry.Rect(Offset(x * px.toFloat(), y * px.toFloat()), Size(px.toFloat(), px.toFloat())), paint)
        }
    }
    val shader = ImageShader(image, TileMode.Repeated, TileMode.Repeated)
    return object : ShaderBrush() {
        override fun createShader(size: Size) = shader
    }
}
