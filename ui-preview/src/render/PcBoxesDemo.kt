package render

import android.graphics.Bitmap
import com.pokedaisy.app.companion.data.save.Gen3SaveEditor
import com.pokedaisy.app.companion.data.save.SyntheticSave
import com.pokedaisy.app.companion.data.save.TradeSide
import com.pokedaisy.app.companion.ui.MonIconLoader
import com.pokedaisy.app.companion.ui.PcBoxesState
import com.pokedaisy.app.companion.ui.TradeState

/** PC BOXES' shots: the save tests' made-up FireRed save. */
fun pcBoxesDemo(): PcBoxesState = PcBoxesState(Gen3SaveEditor(SyntheticSave.demoFireRed()))

/** The DS editor's shots: a made-up HeartGold save (SyntheticGen4), names from the app's tables. */
fun pcBoxesDsDemo(): PcBoxesState = PcBoxesState(
    com.pokedaisy.app.companion.data.save.Gen4SaveEditor(
        (com.pokedaisy.app.companion.data.save.Gen4SaveFile.parse(com.pokedaisy.app.companion.data.save.SyntheticGen4.save())
            as com.pokedaisy.app.companion.data.save.Gen4SaveFile.Parsed.Ok).save,
        com.pokedaisy.app.companion.data.save.SyntheticGen4.demoGame(),
    ),
)

/** The Black editor's shots: the made-up Black save (SyntheticGen5), names from the app's tables. */
fun pcBoxesBlackDemo(): PcBoxesState = PcBoxesState(
    com.pokedaisy.app.companion.data.save.Gen5SaveEditor(
        (com.pokedaisy.app.companion.data.save.Gen5SaveFile.parse(com.pokedaisy.app.companion.data.save.SyntheticGen5.save())
            as com.pokedaisy.app.companion.data.save.Gen5SaveFile.Parsed.Ok).save,
        com.pokedaisy.app.companion.data.save.SyntheticGen5.demoGame(),
    ),
)

/** MISSING on the made-up Black save: the made-up HeartGold save's PC (six more in box 1) through Poké Transfer. */
fun missingBlackDemo(state: PcBoxesState): com.pokedaisy.app.companion.ui.MissingView {
    val t = transferDemo()
    val target = TradeSide("Pokémon Black", state.editor, "IRBO")
    val sides = listOf(TradeSide("Pokémon HeartGold", t.gen4, "IPKE"))
    return com.pokedaisy.app.companion.ui.MissingView(com.pokedaisy.app.companion.data.save.MissingDex.find(target, sides), sides)
}

/**
 * Stand-ins for the icons the app reads from the player's ROM (no game art here): a 32x64 sheet whose first frame is
 * a dot in a colour of its own per species, outlined, so the cards' layout shows as it will with real icons.
 */
val demoIcons = MonIconLoader { species ->
    val bmp = Bitmap.createBitmap(32, 64, Bitmap.Config.ARGB_8888)
    val hue = (species * 47) % 360
    val fill = hsv(hue, 0.55f, 0.95f)
    val ink = hsv(hue, 0.7f, 0.45f)
    for (y in 0 until 32) for (x in 0 until 32) {
        val d = (x - 15.5f) * (x - 15.5f) + (y - 17.5f) * (y - 17.5f)
        when {
            d < 9.5f * 9.5f -> bmp.setPixel(x, y, fill)
            d < 11f * 11f -> bmp.setPixel(x, y, ink)
        }
    }
    bmp
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

/** PAL PARK's shots: the made-up FireRed save's PC beside the made-up HeartGold one (with its National Pokédex). */
fun palParkDemo(): com.pokedaisy.app.companion.ui.PalParkState {
    val hg = com.pokedaisy.app.companion.data.save.Gen4SaveEditor(
        (com.pokedaisy.app.companion.data.save.Gen4SaveFile.parse(com.pokedaisy.app.companion.data.save.SyntheticGen4.save(national = true))
            as com.pokedaisy.app.companion.data.save.Gen4SaveFile.Parsed.Ok).save,
        com.pokedaisy.app.companion.data.save.SyntheticGen4.demoGame(),
    )
    return com.pokedaisy.app.companion.ui.PalParkState(
        TradeSide("Pokémon FireRed", Gen3SaveEditor(SyntheticSave.demoFireRed()), "BPRE"), TradeSide("Pokémon HeartGold", hg, "IPKE"),
    )
}

/** POKé TRANSFER's shots: the made-up HeartGold save (six more in box 1) beside the made-up Black one. */
fun transferDemo(): com.pokedaisy.app.companion.ui.PokeTransferState {
    val sv = com.pokedaisy.app.companion.data.save.SyntheticGen4
    val hg = com.pokedaisy.app.companion.data.save.Gen4SaveEditor(
        (com.pokedaisy.app.companion.data.save.Gen4SaveFile.parse(sv.save(national = true)) as com.pokedaisy.app.companion.data.save.Gen4SaveFile.Parsed.Ok).save,
        sv.demoGame(),
    )
    listOf(152, 155, 158, 25, 133, 143).forEachIndexed { i, sp ->
        hg.putIncoming(com.pokedaisy.app.companion.data.save.MonRef.Pc(com.pokedaisy.app.companion.data.save.BoxSlot(0, 2 + i)), sv.mon(0x3000L + i * 131, sp, 2000))
    }
    // A fresh editor over that file, so UNDO ALL and "not saved yet" start from it.
    val hgFile = (com.pokedaisy.app.companion.data.save.Gen4SaveFile.parse(hg.build()) as com.pokedaisy.app.companion.data.save.Gen4SaveFile.Parsed.Ok).save
    val g5 = com.pokedaisy.app.companion.data.save.SyntheticGen5
    val black = com.pokedaisy.app.companion.data.save.Gen5SaveEditor(
        (com.pokedaisy.app.companion.data.save.Gen5SaveFile.parse(g5.save()) as com.pokedaisy.app.companion.data.save.Gen5SaveFile.Parsed.Ok).save, g5.demoGame(),
    )
    return com.pokedaisy.app.companion.ui.PokeTransferState(
        "Pokémon HeartGold", com.pokedaisy.app.companion.data.save.Gen4SaveEditor(hgFile, sv.demoGame()), "Pokémon Black", black,
    ).apply { today = { java.time.LocalDate.of(2026, 11, 16) } }
}

/** COLLECTION's shots: the made-up FireRed, Emerald and HeartGold saves read together. */
fun collectionDemo(): com.pokedaisy.app.companion.ui.CollectionState {
    val (fr, emerald) = SyntheticSave.demoTrade()
    val p = palParkDemo()
    return com.pokedaisy.app.companion.ui.CollectionState().apply {
        load(listOf(
            TradeSide("Pokémon FireRed", Gen3SaveEditor(fr), "BPRE"), TradeSide("Pokémon Emerald", Gen3SaveEditor(emerald), "BPEE"), p.ds,
        ))
    }
}

/** MISSING's shots: the made-up FireRed save's Pokédex (with its National one) against the made-up Emerald save. */
fun missingDemo(state: PcBoxesState): com.pokedaisy.app.companion.ui.MissingView {
    val (_, emerald) = SyntheticSave.demoTrade()
    val target = TradeSide("Pokémon FireRed", state.editor, "BPRE")
    val sides = listOf(TradeSide("Pokémon Emerald", Gen3SaveEditor(emerald), "BPEE"))
    return com.pokedaisy.app.companion.ui.MissingView(com.pokedaisy.app.companion.data.save.MissingDex.find(target, sides), sides)
}

/** The MISSING shots' FireRed: [SyntheticSave.demoTrade]'s, with its National Pokédex and Sevii Islands. */
fun missingTargetDemo(): PcBoxesState = PcBoxesState(Gen3SaveEditor(SyntheticSave.demoTrade().first))
