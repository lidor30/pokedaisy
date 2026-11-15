package render

import com.pokedaisy.app.companion.ui.imageBitmapOf
import com.pokedaisy.app.companion.data.save.Gen3SaveEditor
import com.pokedaisy.app.companion.data.save.SyntheticSave
import com.pokedaisy.app.companion.data.save.TradeSide
import com.pokedaisy.app.companion.ui.MonIconLoader
import com.pokedaisy.app.companion.ui.PcBoxesState
import com.pokedaisy.app.companion.ui.TradeState

/** PC BOXES' shots: the save tests' made-up FireRed save. */
fun pcBoxesDemo(): PcBoxesState = PcBoxesState(Gen3SaveEditor(SyntheticSave.demoFireRed()))

/**
 * Stand-ins for the icons the app reads from the player's ROM (no game art here): a 32x64 sheet whose first frame is
 * a dot in a colour of its own per species, outlined, so the cards' layout shows as it will with real icons.
 */
val demoIcons = MonIconLoader { species ->
    val px = IntArray(32 * 64)
    val hue = (species * 47) % 360
    val fill = hsv(hue, 0.55f, 0.95f)
    val ink = hsv(hue, 0.7f, 0.45f)
    for (y in 0 until 32) for (x in 0 until 32) {
        val d = (x - 15.5f) * (x - 15.5f) + (y - 17.5f) * (y - 17.5f)
        when {
            d < 9.5f * 9.5f -> px[y * 32 + x] = fill
            d < 11f * 11f -> px[y * 32 + x] = ink
        }
    }
    imageBitmapOf(px, 32, 64)
}

private fun hsv(h: Int, s: Float, v: Float): Int {
    val c = v * s
    val x = c * (1 - kotlin.math.abs((h / 60f) % 2 - 1))
    val (r, g, b) = when (h / 60) {
        0 -> Triple(c, x, 0f); 1 -> Triple(x, c, 0f); 2 -> Triple(0f, c, x)
        3 -> Triple(0f, x, c); 4 -> Triple(x, 0f, c); else -> Triple(c, 0f, x)
    }
    val m = v - c
    fun ch(f: Float) = ((f + m) * 255).toInt().coerceIn(0, 255)
    return (0xFF shl 24) or (ch(r) shl 16) or (ch(g) shl 8) or ch(b)
}

/** TRADE's shots: the save tests' made-up FireRed and Emerald saves. */
fun tradeDemo(): TradeState {
    val (fr, emerald) = SyntheticSave.demoTrade()
    return TradeState(TradeSide("Pokémon FireRed", Gen3SaveEditor(fr), "BPRE"), TradeSide("Pokémon Emerald", Gen3SaveEditor(emerald), "BPEE"))
}
