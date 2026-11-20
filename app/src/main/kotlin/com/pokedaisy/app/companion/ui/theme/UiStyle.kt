package com.pokedaisy.app.companion.ui.theme

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color

/**
 * The menus' build: how the shared OPTION pieces (title window, list window, rows, group titles, separators, keys,
 * cells) are drawn. One look for every game (never per game, Unbound's colours aside), on both screens; THEME and DARK
 * MODE still colour it. The Game Boy games keep their own black-on-white windows whatever this says.
 *
 * - [SUMMARY_MINT] (the look, the user's pick of eight tried, 2026-11-12): Emerald's summary pages, sampled headless -
 *   a teal header joined to a mint-framed list, green section bands, banded rows, mint keys for tabs and buttons, data in
 *   HG / Black's connected cells. In Unbound it wears Unbound's purple and gold ([unboundStylePalette]).
 * - [CLASSIC]: FireRed's OPTION screen, as before (ui-preview's `-Pstyle=CLASSIC`, the screenshot tests' override).
 */
enum class UiStyle(val label: String) {
    CLASSIC("CLASSIC"), SUMMARY_MINT("SUMMARY MINT"),
}

object UiStyles {
    var setting by mutableStateOf(UiStyle.SUMMARY_MINT)

    /** ui-preview / screenshot tests: this look whatever the setting. */
    var override: UiStyle? = null

    val current: UiStyle get() = override ?: setting
}

/**
 * The colours a [UiStyle] adds over OptionColors' (light, dark). Every fill goes through the THEME's tint where it's a
 * grey; the accents keep their colour, like HP bars and types do.
 */
class UiStylePalette(
    /** The title window: fill, text, its shadow, its frame (outermost first). */
    val titleFill: Color,
    val titleText: Color,
    val titleShadow: Color,
    val titleLayers: List<Pair<Color, Int>>,
    /** The list window. */
    val listFill: Color,
    val listLayers: List<Pair<Color, Int>>,
    /** A second band under every other row (null: none). */
    val stripe: Color?,
    /** A solid rule under each row (null: none). */
    val rule: Color?,
    /** The cursor row. */
    val selected: Color,
    val label: Color,
    val labelShadow: Color,
    /** A section's band: fill, text, its shadow. */
    val sectionFill: Color,
    val sectionText: Color,
    val sectionShadow: Color,
    /** A drawn ▶ at the cursor row. */
    val arrow: Color?,
    /** The summary's separator: a dark line over a light one. */
    val separatorDark: Color,
    val separatorLight: Color,
    /** A picture's well (the DEX's sprite, the summary's icon): set off from [listFill]. */
    val well: Color,
    /** Free-standing buttons ([KeyColors]): null = the title window's look. */
    val button: KeyColors? = null,
    /** The tab bar's chips, idle and open: null = the classic chips. */
    val tab: KeyColors? = null,
    val tabOpen: KeyColors? = null,
    /** The other side's band (battle INFO's FOE, SUGGESTIONS' VS): fill, text, shadow; null = [sectionFill]'s. */
    val foeFill: Color? = null,
    val foeShadow: Color? = null,
    /** Connected cells (HG / Black's data rows): your side's and the foe's; null = drawn from the list's colours. */
    val cells: CellColors? = null,
    val foeCells: CellColors? = null,
)

/**
 * A block of connected cells (HG's stats and moves, Black's move plates): every cell ruled in [line], shared edges drawn
 * once, rows in [a] and [b] by turn; a heading cell in [head] with [headText] on [headShadow].
 */
class CellColors(
    val line: Color,
    val a: Color,
    val b: Color,
    val head: Color,
    val headText: Color,
    val headShadow: Color,
)

/**
 * A key: a pressable face in two tones ([face] over [low]) inside an [outline], standing on a [depth] edge along its
 * bottom, so it reads as something to press (windows have no such edge). [pressed] keys (the open tab) sit down on it.
 */
class KeyColors(
    val outline: Color,
    val face: Color,
    val low: Color,
    val depth: Color,
    val text: Color,
    val shadow: Color,
    /** A thin line just inside the outline's top (the face's light), null for none. */
    val shine: Color? = null,
    val pressed: Boolean = false,
)

fun uiStylePalette(style: UiStyle, dark: Boolean): UiStylePalette? = when (style) {
    UiStyle.CLASSIC -> null
    UiStyle.SUMMARY_MINT -> if (!dark) UiStylePalette(
        titleFill = Color(0xFF318C73), titleText = Color(0xFFFFFFFF), titleShadow = Color(0xFF006B63),
        titleLayers = listOf(Color(0xFF00524A) to 1, Color(0xFF9CDEA5) to 1),
        listFill = Color(0xFFF7FFFF),
        listLayers = listOf(Color(0xFF006B63) to 1, Color(0xFF9CDEA5) to 3, Color(0xFF63B58C) to 1),
        stripe = Color(0xFFE6F6EC), rule = null, selected = Color(0xFFFFE7FF), 
        label = Color(0xFF4A4239), labelShadow = Color(0xFFD6D6BD),
        sectionFill = Color(0xFF63B58C), sectionText = Color(0xFFFFFFFF), sectionShadow = Color(0xFF006B63),
        arrow = Color(0xFF4A4239),
        separatorDark = Color(0xFF9CDEA5), separatorLight = Color(0xFFF7FFFF), well = Color(0xFFDEF0E4),
        button = KeyColors(
            outline = Color(0xFF00524A), face = Color(0xFFFFFFFF), low = Color(0xFFDDF2E4), depth = Color(0xFF4A9E7C),
            text = Color(0xFF2E4A42), shadow = Color(0xFFC6E4D0), shine = null,
        ),
        tab = KeyColors(
            outline = Color(0xFF00524A), face = Color(0xFFC8EED2), low = Color(0xFF9CDEA5), depth = Color(0xFF318C73),
            text = Color(0xFF1F4A40), shadow = Color(0xFFE6F8EA), shine = Color(0xFFE8FAEC),
        ),
        tabOpen = KeyColors(
            outline = Color(0xFF00524A), face = Color(0xFF3FA084), low = Color(0xFF318C73), depth = Color(0xFF00524A),
            text = Color(0xFFFFFFFF), shadow = Color(0xFF00524A), shine = Color(0xFF7CC8A8), pressed = true,
        ),
        foeFill = Color(0xFFC86478), foeShadow = Color(0xFF7A2E40),
        cells = CellColors(
            line = Color(0xFF6FB892), a = Color(0xFFF7FFFF), b = Color(0xFFDDF2E4),
            head = Color(0xFF4FA07C), headText = Color(0xFFFFFFFF), headShadow = Color(0xFF1F5C48),
        ),
        foeCells = CellColors(
            line = Color(0xFFC98494), a = Color(0xFFFFF7F8), b = Color(0xFFF9E3E8),
            head = Color(0xFFC05A70), headText = Color(0xFFFFFFFF), headShadow = Color(0xFF6A2234),
        ),
    ) else UiStylePalette(
        titleFill = Color(0xFF1E5A4A), titleText = Color(0xFFFFFFFF), titleShadow = Color(0xFF062A24),
        titleLayers = listOf(Color(0xFF02120F) to 1, Color(0xFF3E8A6E) to 1),
        listFill = Color(0xFF1C2A27),
        listLayers = listOf(Color(0xFF020807) to 1, Color(0xFF2E6A54) to 3, Color(0xFF214A3C) to 1),
        stripe = Color(0xFF22332F), rule = null, selected = Color(0xFF4A3450), 
        label = Color(0xFFDCEEE6), labelShadow = Color(0xFF2A3E38),
        sectionFill = Color(0xFF2E6A54), sectionText = Color(0xFFFFFFFF), sectionShadow = Color(0xFF062A24),
        arrow = Color(0xFFDCEEE6),
        separatorDark = Color(0xFF020807), separatorLight = Color(0xFF2E4A40), well = Color(0xFF243632),
        button = KeyColors(
            outline = Color(0xFF020807), face = Color(0xFF2E4440), low = Color(0xFF263A36), depth = Color(0xFF14302A),
            text = Color(0xFFDCEEE6), shadow = Color(0xFF14201D), shine = Color(0xFF3E5A54),
        ),
        tab = KeyColors(
            outline = Color(0xFF020807), face = Color(0xFF26403A), low = Color(0xFF1F3530), depth = Color(0xFF0E241F),
            text = Color(0xFFBFDCCF), shadow = Color(0xFF0E1A17), shine = Color(0xFF36564E),
        ),
        tabOpen = KeyColors(
            outline = Color(0xFF020807), face = Color(0xFF2E7A60), low = Color(0xFF256A53), depth = Color(0xFF0E241F),
            text = Color(0xFFFFFFFF), shadow = Color(0xFF062A24), shine = Color(0xFF4E9A80), pressed = true,
        ),
        foeFill = Color(0xFF8A3E50), foeShadow = Color(0xFF3A141E),
        cells = CellColors(
            line = Color(0xFF3E7A64), a = Color(0xFF1C2A27), b = Color(0xFF233632),
            head = Color(0xFF2E6A54), headText = Color(0xFFFFFFFF), headShadow = Color(0xFF062A24),
        ),
        foeCells = CellColors(
            line = Color(0xFF7A3A4A), a = Color(0xFF2A1E22), b = Color(0xFF34252A),
            head = Color(0xFF7E3A4C), headText = Color(0xFFFFFFFF), headShadow = Color(0xFF2A0E16),
        ),
    )
}

/**
 * SUMMARY MINT's build in Unbound's colours (the user's call: Unbound purple with gold touches), sampled headless from its
 * START menu (native-capture/menu-shots/unbound/start.png): windows #946BC6 inside #845ABD, a gold line (#F7D66B over
 * #D69410) and a #292929 outline, white text on a #4A4A4A shadow; its Pokédex's #A54AA5 header.
 */
fun unboundStylePalette(dark: Boolean): UiStylePalette = if (!dark) UiStylePalette(
    titleFill = Color(0xFF946BC6), titleText = Color(0xFFFFFFFF), titleShadow = Color(0xFF4A4A4A),
    titleLayers = listOf(Color(0xFF292929) to 1, Color(0xFFD69410) to 1, Color(0xFFF7D66B) to 1, Color(0xFF845ABD) to 1),
    listFill = Color(0xFFFBF8FF),
    listLayers = listOf(Color(0xFF292929) to 1, Color(0xFF845ABD) to 1, Color(0xFF946BC6) to 2, Color(0xFFF7D66B) to 1),
    stripe = Color(0xFFEFE7F9), rule = null, selected = Color(0xFFFFF0B8), 
    label = Color(0xFF3E3450), labelShadow = Color(0xFFDCD2E8),
    sectionFill = Color(0xFF946BC6), sectionText = Color(0xFFFFFFFF), sectionShadow = Color(0xFF4A4A4A),
        arrow = Color(0xFF3E3450),
    separatorDark = Color(0xFFC6B2E2), separatorLight = Color(0xFFFBF8FF), well = Color(0xFFEDE5F8),
    button = KeyColors(
        outline = Color(0xFF292929), face = Color(0xFFFFFFFF), low = Color(0xFFEDE5F8), depth = Color(0xFF845ABD),
        text = Color(0xFF3E3450), shadow = Color(0xFFDCD2E8),
    ),
    tab = KeyColors(
        outline = Color(0xFF292929), face = Color(0xFFCDB8EC), low = Color(0xFFB79CE0), depth = Color(0xFF845ABD),
        text = Color(0xFF2E2440), shadow = Color(0xFFE8DEF6), shine = Color(0xFFE4D8F6),
    ),
    tabOpen = KeyColors(
        outline = Color(0xFF292929), face = Color(0xFFF7D66B), low = Color(0xFFEBC253), depth = Color(0xFFD69410),
        text = Color(0xFF4A3410), shadow = Color(0xFFFBE9AE), shine = Color(0xFFFBEBB0), pressed = true,
    ),
    foeFill = Color(0xFFC0506A), foeShadow = Color(0xFF6A2034),
    cells = CellColors(
        line = Color(0xFFA98BD4), a = Color(0xFFFBF8FF), b = Color(0xFFEDE4F8),
        head = Color(0xFF946BC6), headText = Color(0xFFFFFFFF), headShadow = Color(0xFF4A4A4A),
    ),
    foeCells = CellColors(
        line = Color(0xFFC98494), a = Color(0xFFFFF7F8), b = Color(0xFFF9E3E8),
        head = Color(0xFFC05A70), headText = Color(0xFFFFFFFF), headShadow = Color(0xFF6A2234),
    ),
) else UiStylePalette(
    titleFill = Color(0xFF5A3E86), titleText = Color(0xFFFFFFFF), titleShadow = Color(0xFF1A1226),
    titleLayers = listOf(Color(0xFF0A0810) to 1, Color(0xFF9A6A10) to 1, Color(0xFFC8A040) to 1, Color(0xFF4A3270) to 1),
    listFill = Color(0xFF221C2C),
    listLayers = listOf(Color(0xFF0A0810) to 1, Color(0xFF4A3270) to 1, Color(0xFF5A3E86) to 2, Color(0xFFB08A30) to 1),
    stripe = Color(0xFF2A2236), rule = null, selected = Color(0xFF4A3E1E), 
    label = Color(0xFFE4DCF0), labelShadow = Color(0xFF3A3048),
    sectionFill = Color(0xFF6A4A9A), sectionText = Color(0xFFFFFFFF), sectionShadow = Color(0xFF1A1226),
        arrow = Color(0xFFE4DCF0),
    separatorDark = Color(0xFF0A0810), separatorLight = Color(0xFF3A3048), well = Color(0xFF2C2438),
    button = KeyColors(
        outline = Color(0xFF0A0810), face = Color(0xFF3A3048), low = Color(0xFF31283E), depth = Color(0xFF1E1628),
        text = Color(0xFFE4DCF0), shadow = Color(0xFF161020), shine = Color(0xFF4A3E5C),
    ),
    tab = KeyColors(
        outline = Color(0xFF0A0810), face = Color(0xFF3A2E50), low = Color(0xFF322842), depth = Color(0xFF1E1628),
        text = Color(0xFFD8CCEC), shadow = Color(0xFF161020), shine = Color(0xFF4C3E66),
    ),
    tabOpen = KeyColors(
        outline = Color(0xFF0A0810), face = Color(0xFFC8A040), low = Color(0xFFB08A30), depth = Color(0xFF6A5010),
        text = Color(0xFF2A1E06), shadow = Color(0xFFE0C670), shine = Color(0xFFE0C670), pressed = true,
    ),
    foeFill = Color(0xFF8A3E50), foeShadow = Color(0xFF3A141E),
    cells = CellColors(
        line = Color(0xFF4A3A62), a = Color(0xFF221C2C), b = Color(0xFF2A2236),
        head = Color(0xFF5A3E86), headText = Color(0xFFFFFFFF), headShadow = Color(0xFF1A1226),
    ),
    foeCells = CellColors(
        line = Color(0xFF7A3A4A), a = Color(0xFF2A1E22), b = Color(0xFF34252A),
        head = Color(0xFF7E3A4C), headText = Color(0xFFFFFFFF), headShadow = Color(0xFF2A0E16),
    ),
)
