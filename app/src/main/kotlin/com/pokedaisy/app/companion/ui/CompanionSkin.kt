package com.pokedaisy.app.companion.ui

import kotlin.concurrent.Volatile

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import com.pokedaisy.app.companion.data.GameKind
import com.pokedaisy.app.companion.data.activeGame
import com.pokedaisy.app.companion.data.romHeaderCode
import com.pokedaisy.app.companion.ui.theme.DarkMode

/*
 * A game's look for the whole companion (the user's call, 2026-11-15: the companion in the running game's own style -
 * Pokemon X first, the DS games and some GBA games next). The companion is built from a few shared pieces, and a skin
 * changes those, so every tab follows without each screen knowing:
 * - corners: [pixelCornersPath] (every PixelRoundedShape, drawPixelRoundRect / Frame, drawLayeredBox) - stepped in GBA
 *   pixels, or smooth ([CompanionSkin.smooth]);
 * - text: [pixelFontFamily] (the font), [gbaTextStyle] (its size, [CompanionSkin.fontScale]), [GbaText]'s shadow
 *   ([CompanionSkin.shadow]);
 * - button faces: [drawTwoTone] - HeartGold's two tones, a smooth gradient ([CompanionSkin.gradientFaces]) or flat
 *   ([CompanionSkin.flatFaces]);
 * - colours: [OptionColors]' window / text getters, from [CompanionSkin.colors] where a skin has its own.
 * [CompanionSkins.PIXEL] is the app's own look (FireRed's OPTION screen), what every game gets unless [CompanionSkins.forGame]
 * names another. A skin applies only while a game is open ([OptionColors.inGame]), like Gen 1's windows: the top-screen
 * Library / Settings keep the app's look.
 *
 * A new game's skin: a [CompanionSkin] (sample its colours off the game's own menus), a line in [CompanionSkins.forGame],
 * and a look at every tab (ui-preview `-Pgame=`, Paparazzi); screens that draw the game's own art (party slots, bag,
 * map) keep their per-game drawing as before.
 */

/** How a skin's text is shadowed: the GBA's three copies one font pixel right / down, one soft copy, or none. */
enum class SkinShadow { PIXEL, SOFT, NONE }

/** A skin's palette: the [OptionColors] it replaces (null keeps OptionColors' own). Light mode only for now. */
class SkinColors(
    val titleLayers: List<Pair<Color, Int>>,
    val titleFill: Color,
    val titleText: Color,
    val titleShadow: Color,
    val listLayers: List<Pair<Color, Int>>,
    val listFill: Color,
    val rowSelected: Color,
    val label: Color,
    val labelShadow: Color,
    val value: Color,
    val valueShadow: Color,
    val tabSelectedFill: Color,
    val tabIdleFill: Color,
    val onBackdrop: Color,
    val onBackdropShadow: Color,
    val muted: Color,
    val mutedShadow: Color,
    val divider: Color,
    val frameDark: Color,
    val frameLight: Color,
    /** The buttons' own frame / face / label ([OptionButton]), when they aren't the title window's (HeartGold's orange
     * menu buttons, Black's charcoal plates); null keeps the title window's. */
    val buttonLayers: List<Pair<Color, Int>>? = null,
    val buttonFill: Color? = null,
    val buttonText: Color? = null,
    val buttonShadow: Color? = null,
)

/**
 * A skin's HUD (FieldHud's LOOK GAME): its windows ([fill] in [layers], drawn like the HP box with [dropShadow] under it),
 * the buttons inside them ([buttonTop] over [buttonLow] in [buttonLayers]), the text and the "this one" rim ([selected]).
 * The rest is [HudInk]'s.
 */
class SkinHud(
    val fill: Color,
    val layers: List<Pair<Color, Int>>,
    val buttonTop: Color,
    val buttonLow: Color,
    val buttonLayers: List<Pair<Color, Int>>,
    val text: Color,
    val shadow: Color,
    val accent: Color,
    val accentShadow: Color,
    val onAccent: Color,
    val muted: Color,
    val cell: Color,
    val cellOn: Color,
    val divider: Color,
    val selected: Color,
    val dropShadow: Color,
)

class CompanionSkin(
    val id: String,
    /** Corners and frames drawn smooth instead of stepped in GBA pixels. */
    val smooth: Boolean = false,
    /** The text's font; null = the app's (Pixel Operator, or a Game Boy game's own). */
    val font: FontFamily? = null,
    /** [font]'s size against Pixel Operator's at the same metrics (a sans-serif's capitals run taller). */
    val fontScale: Float = 1f,
    val shadow: SkinShadow = SkinShadow.PIXEL,
    /** Buttons' faces a smooth top-to-bottom gradient instead of HeartGold's two tones. */
    val gradientFaces: Boolean = false,
    /** Buttons' faces one flat colour (the GBA games' and Platinum's menus: HeartGold's two tones clashed there). */
    val flatFaces: Boolean = false,
    val colors: SkinColors? = null,
    /** [colors] in DARK MODE too: a skin drawn dark already (Black's navy) keeps them; a light one gives way to the app's
     * own dark windows. */
    val darkToo: Boolean = true,
    /** The HUD in this game's look (LOOK GAME); null = GAME is GLASS for this game. */
    val hud: SkinHud? = null,
) {
    /** [colors] as they apply now (DARK MODE, [darkToo]). */
    val activeColors: SkinColors? get() = if (!darkToo && DarkMode.on) null else colors
}

object CompanionSkins {
    /** The app's own look. */
    val PIXEL = CompanionSkin("pixel")

    /** Pokemon X / Y: white-grey windows in a dark green rim, dark text, green values, smooth corners and gradients,
     * a sans-serif (sampled from the game's party screen and menus, 2026-11-15; XyParty's colours). */
    val POKEMON_XY = CompanionSkin(
        id = "pokemon-xy",
        smooth = true,
        font = FontFamily.SansSerif,
        fontScale = 0.82f,
        shadow = SkinShadow.SOFT,
        gradientFaces = true,
        colors = SkinColors(
            titleLayers = listOf(Color(0xFF2B8E26) to 1, Color(0xFF8ED46A) to 2),
            titleFill = Color(0xFFF6F7F1),
            titleText = Color(0xFF2A2A2A),
            titleShadow = Color(0x33000000),
            listLayers = listOf(Color(0xFF2B8E26) to 1, Color(0xFF8ED46A) to 2, Color(0xFFF2F7EC) to 1),
            listFill = Color(0xFFE6EEDF),
            rowSelected = Color(0xFFFFFFFF),
            label = Color(0xFF353535),
            labelShadow = Color(0x26000000),
            value = Color(0xFF2E8C1E),
            valueShadow = Color(0x26000000),
            tabSelectedFill = Color(0xFFFFFFFF),
            tabIdleFill = Color(0xFFD4E8C6),
            onBackdrop = Color(0xFFFFFFFF),
            onBackdropShadow = Color(0xFF2B6E26),
            muted = Color(0xFF6E7868),
            mutedShadow = Color(0x1A000000),
            divider = Color(0xFFB8C8AE),
            frameDark = Color(0xFF2B8E26),
            frameLight = Color(0xFF8ED46A),
        ),
    )

    // The DS and GBA skins (2026-11-16): each family's colours sampled off its own menus, headless (nds_dump / mgba_dump on
    // the player's saves): windows, text, buttons and the HUD's panels. They keep the app's pixel corners, Pixel Operator and
    // the GBA shadow (the user's rule for the HUD: the font stays Pixel Operator on GBA and DS).

    /** HeartGold / SoulSilver: the summary's grey-blue windows in their dark grey line, dark text over a light shadow, red
     * values, the party menu's orange buttons; the HUD is the X menu's green window with white text and its red cursor. */
    val HEARTGOLD = CompanionSkin(
        id = "heartgold",
        darkToo = false,
        colors = SkinColors(
            titleLayers = listOf(Color(0xFF414141) to 1, Color(0xFF8A9AAA) to 1),
            titleFill = Color(0xFFFBFBFB),
            titleText = Color(0xFF414141),
            titleShadow = Color(0xFFB2B2B2),
            listLayers = listOf(Color(0xFF414141) to 1, Color(0xFF61798A) to 1, Color(0xFF8A9AAA) to 2),
            listFill = Color(0xFFE3EBEB),
            rowSelected = Color(0xFFFBFBFB),
            label = Color(0xFF414141),
            labelShadow = Color(0xFFB2B2B2),
            value = Color(0xFFFB3820),
            valueShadow = Color(0xFFE39A92),
            tabSelectedFill = Color(0xFFFBFBFB),
            tabIdleFill = Color(0xFFD3DBDB),
            onBackdrop = Color(0xFFFBFBFB),
            onBackdropShadow = Color(0xFF414141),
            muted = Color(0xFF798A9A),
            mutedShadow = Color(0xFFD3DBDB),
            divider = Color(0xFFAABAC3),
            frameDark = Color(0xFF414141),
            frameLight = Color(0xFF8A9AAA),
            buttonLayers = listOf(Color(0xFF414141) to 2),
            buttonFill = Color(0xFFFBC338),
            buttonText = Color(0xFF515151),
            buttonShadow = Color(0xFFFBFBFB),
        ),
        hud = SkinHud(
            fill = Color(0xB8146848),
            layers = listOf(Color(0xFF0C3A28) to 1, Color(0xFF2E8A60) to 1),
            buttonTop = Color(0xD83E9470), buttonLow = Color(0xD8226A4C),
            buttonLayers = listOf(Color(0xFF0C3A28) to 1),
            text = Color(0xFFFBFBFB), shadow = Color(0xFF0C3A28),
            accent = Color(0xFFFBDB69), accentShadow = Color(0xFF8A6A10), onAccent = Color(0xFF414141),
            muted = Color(0xFFC8E2D4),
            cell = Color(0x33FFFFFF), cellOn = Color(0x66FFFFFF), divider = Color(0xFF4C9E78),
            selected = Color(0xFFFB3820),
            dropShadow = Color(0x800A2618),
        ),
    )

    /** Platinum: the message box's white in its brown wooden edge, the move cards' cream for lists, dark text; the HUD is
     * the party screen's dark slate with its silver line and the orange of a picked slot. */
    val PLATINUM = CompanionSkin(
        id = "platinum",
        darkToo = false,
        flatFaces = true,
        colors = SkinColors(
            titleLayers = listOf(Color(0xFF303030) to 1, Color(0xFF927159) to 1, Color(0xFFB2A292) to 1),
            titleFill = Color(0xFFFBFBFB),
            titleText = Color(0xFF494961),
            titleShadow = Color(0xFFB2B2C3),
            listLayers = listOf(Color(0xFF303030) to 1, Color(0xFF927159) to 2, Color(0xFFDBD3CB) to 1),
            listFill = Color(0xFFF3EBAA),
            rowSelected = Color(0xFFFBF7DB),
            label = Color(0xFF383830),
            labelShadow = Color(0xFFCBC38A),
            value = Color(0xFF1858B8),
            valueShadow = Color(0xFFB0C8E8),
            tabSelectedFill = Color(0xFFFBFBFB),
            tabIdleFill = Color(0xFFDBD3CB),
            onBackdrop = Color(0xFFFBFBFB),
            onBackdropShadow = Color(0xFF383849),
            muted = Color(0xFF82786A),
            mutedShadow = Color(0xFFDBD3CB),
            divider = Color(0xFFE3D369),
            frameDark = Color(0xFF303030),
            frameLight = Color(0xFFB2A292),
        ),
        hud = SkinHud(
            fill = Color(0xB8383849),
            layers = listOf(Color(0xFF181820) to 1, Color(0xFF798292) to 1),
            buttonTop = Color(0xD85982D3), buttonLow = Color(0xD841699A),
            buttonLayers = listOf(Color(0xFF181820) to 1, Color(0xFF9AC3F3) to 1),
            text = Color(0xFFFBFBFB), shadow = Color(0xFF181820),
            accent = Color(0xFFF3EBAA), accentShadow = Color(0xFF5C5430), onAccent = Color(0xFF383830),
            muted = Color(0xFFB2BACB),
            cell = Color(0x26FFFFFF), cellOn = Color(0x599AC3F3), divider = Color(0xFF798292),
            selected = Color(0xFFEB5900),
            dropShadow = Color(0x80060810),
        ),
    )

    /** Black / White: the party screen's navy windows in their cyan rim, white text, cyan values, the X menu's charcoal
     * plates for lists and buttons; the HUD is the navy windows with Black's magenta cursor. Drawn dark: the same in DARK MODE. */
    val UNOVA = CompanionSkin(
        id = "unova",
        colors = SkinColors(
            titleLayers = listOf(Color(0xFF101820) to 1, Color(0xFF0C719E) to 1, Color(0xFF96FFFF) to 1, Color(0xFF0C719E) to 1),
            titleFill = Color(0xFF0C304D),
            titleText = Color(0xFFFBFBFB),
            titleShadow = Color(0xFF0C1820),
            listLayers = listOf(Color(0xFF000000) to 1, Color(0xFF515151) to 1),
            listFill = Color(0xFF2C2C2C),
            rowSelected = Color(0xFF0C496D),
            label = Color(0xFFFBFBFB),
            labelShadow = Color(0xFF717171),
            value = Color(0xFF7AF0FF),
            valueShadow = Color(0xFF0C496D),
            tabSelectedFill = Color(0xFF0C5175),
            tabIdleFill = Color(0xFF0C304D),
            onBackdrop = Color(0xFFFBFBFB),
            onBackdropShadow = Color(0xFF101820),
            muted = Color(0xFFA2A2AA),
            mutedShadow = Color(0xFF202020),
            divider = Color(0xFF515151),
            frameDark = Color(0xFF101820),
            frameLight = Color(0xFF0C719E),
            buttonLayers = listOf(Color(0xFF000000) to 1, Color(0xFF515151) to 1),
            buttonFill = Color(0xFF383838),
            buttonText = Color(0xFFFBFBFB),
            buttonShadow = Color(0xFF717171),
        ),
        hud = SkinHud(
            fill = Color(0xB80C304D),
            layers = listOf(Color(0xFF101820) to 1, Color(0xFF0C719E) to 1, Color(0xFF96FFFF) to 1),
            buttonTop = Color(0xD8383838), buttonLow = Color(0xD8202020),
            buttonLayers = listOf(Color(0xFF000000) to 1, Color(0xFF515151) to 1),
            text = Color(0xFFFBFBFB), shadow = Color(0xFF101820),
            accent = Color(0xFF96FFFF), accentShadow = Color(0xFF0C304D), onAccent = Color(0xFF101820),
            muted = Color(0xFFA2B2C3),
            cell = Color(0x2696FFFF), cellOn = Color(0x5996FFFF), divider = Color(0xFF0C719E),
            selected = Color(0xFFFB0092),
            dropShadow = Color(0x80000000),
        ),
    )

    /** Black 2 / White 2: the party screen's violet windows in their white rim, the options' teal on dark slate for lists
     * and values; the HUD is the violet windows with Black 2's pink cursor. Drawn dark: the same in DARK MODE. */
    val UNOVA2 = CompanionSkin(
        id = "unova2",
        colors = SkinColors(
            titleLayers = listOf(Color(0xFF101820) to 1, Color(0xFF6D6996) to 1, Color(0xFFFFFFFF) to 1, Color(0xFF6D6996) to 1),
            titleFill = Color(0xFF3C4165),
            titleText = Color(0xFFFBFBFB),
            titleShadow = Color(0xFF242045),
            listLayers = listOf(Color(0xFF181818) to 1, Color(0xFF282838) to 1, Color(0xFF61B2B2) to 1, Color(0xFF282838) to 1),
            listFill = Color(0xFF283030),
            rowSelected = Color(0xFF495151),
            label = Color(0xFFFBFBFB),
            labelShadow = Color(0xFF181818),
            value = Color(0xFF41DBE3),
            valueShadow = Color(0xFF205058),
            tabSelectedFill = Color(0xFF515579),
            tabIdleFill = Color(0xFF3C4165),
            onBackdrop = Color(0xFFFBFBFB),
            onBackdropShadow = Color(0xFF101820),
            muted = Color(0xFF9A9AB2),
            mutedShadow = Color(0xFF202028),
            divider = Color(0xFF515159),
            frameDark = Color(0xFF101820),
            frameLight = Color(0xFF6D6996),
            buttonLayers = listOf(Color(0xFF000000) to 1, Color(0xFF696969) to 1),
            buttonFill = Color(0xFF454545),
            buttonText = Color(0xFFFBFBFB),
            buttonShadow = Color(0xFF202020),
        ),
        hud = SkinHud(
            fill = Color(0xB83C4165),
            layers = listOf(Color(0xFF101820) to 1, Color(0xFF6D6996) to 1, Color(0xFFFFFFFF) to 1),
            buttonTop = Color(0xD8454545), buttonLow = Color(0xD8282828),
            buttonLayers = listOf(Color(0xFF000000) to 1, Color(0xFF696969) to 1),
            text = Color(0xFFFBFBFB), shadow = Color(0xFF181830),
            accent = Color(0xFF41DBE3), accentShadow = Color(0xFF203040), onAccent = Color(0xFF101820),
            muted = Color(0xFFB2B2CB),
            cell = Color(0x26FFFFFF), cellOn = Color(0x5941DBE3), divider = Color(0xFF6D6996),
            selected = Color(0xFFFB6DA2),
            dropShadow = Color(0x80000000),
        ),
    )

    /** FireRed / LeafGreen: the OPTION screen's white title windows (the app's own), the BAG's cream list in its gold frame,
     * grey text; the HUD is the BAG's window with the party screen's orange. */
    val FIRERED = CompanionSkin(
        id = "firered",
        darkToo = false,
        flatFaces = true,
        colors = SkinColors(
            titleLayers = listOf(Color(0xFF63737B) to 2, Color(0xFFCED6D6) to 1),
            titleFill = Color(0xFFFFFFFF),
            titleText = Color(0xFF636363),
            titleShadow = Color(0xFFD6D6CE),
            listLayers = listOf(Color(0xFF6B6B6B) to 2, Color(0xFFEFE7AD) to 1, Color(0xFFF7CE73) to 2, Color(0xFFD6B552) to 2),
            listFill = Color(0xFFFFFFCE),
            rowSelected = Color(0xFFFFFFFF),
            label = Color(0xFF636363),
            labelShadow = Color(0xFFD6D6CE),
            value = Color(0xFFCB0707),
            valueShadow = Color(0xFFE0A564),
            tabSelectedFill = Color(0xFFFFFFCE),
            tabIdleFill = Color(0xFFEFE7AD),
            onBackdrop = Color(0xFFFFFFFF),
            onBackdropShadow = Color(0xFF404850),
            muted = Color(0xFF8C8C94),
            mutedShadow = Color(0xFFD6D6CE),
            divider = Color(0xFFEFE7AD),
            frameDark = Color(0xFF6B6B6B),
            frameLight = Color(0xFFF7CE73),
        ),
        hud = SkinHud(
            fill = Color(0xC0FFFFCE),
            layers = listOf(Color(0xFF6B6B6B) to 1, Color(0xFFF7CE73) to 1, Color(0xFFD6B552) to 1),
            buttonTop = Color(0xE0FFFFFF), buttonLow = Color(0xE0EFE7AD),
            buttonLayers = listOf(Color(0xFF6B6B6B) to 1, Color(0xFFD6B552) to 1),
            text = Color(0xFF383838), shadow = Color(0xFFD6D6CE),
            accent = Color(0xFFCB0707), accentShadow = Color(0xFFE0A564), onAccent = Color.White,
            muted = Color(0xFF4E4E56),
            cell = Color(0x1F6B5A20), cellOn = Color(0x66F7CE73), divider = Color(0xFFEFE7AD),
            selected = Color(0xFFD83020),
            dropShadow = Color(0x4C141820),
        ),
    )

    /** Emerald: white windows in its dark line, black text over a light shadow, the BAG's cream list in its steel-blue frame
     * under a yellow band; the HUD is that window. */
    val EMERALD = emeraldSkin("emerald", band = Color(0xFFFFE78C), fill = Color(0xFFFFFFCE), divider = Color(0xFFD6D6CE))

    /** Ruby / Sapphire: Emerald's windows with their BAG's warmer list (an orange band, peach rows, a red rule). */
    val RUBY_SAPPHIRE = emeraldSkin("ruby-sapphire", band = Color(0xFFFFC65A), fill = Color(0xFFFFE7AD), divider = Color(0xFFFF9A84))

    private fun emeraldSkin(id: String, band: Color, fill: Color, divider: Color) = CompanionSkin(
        id = id,
        darkToo = false,
        flatFaces = true,
        colors = SkinColors(
            titleLayers = listOf(Color(0xFF293942) to 1, Color(0xFF636373) to 1, Color(0xFFD6D6CE) to 1),
            titleFill = Color(0xFFFFFFFF),
            titleText = Color(0xFF202020),
            titleShadow = Color(0xFFD6D6CE),
            listLayers = listOf(
                Color(0xFF295273) to 1, Color(0xFF396B8C) to 1, Color(0xFF293942) to 1, Color(0xFF636373) to 1, band to 2,
            ),
            listFill = fill,
            rowSelected = Color(0xFFFFFFFF),
            label = Color(0xFF202020),
            labelShadow = Color(0xFFD6D6CE),
            value = Color(0xFFE03018),
            valueShadow = Color(0xFFF8B8A0),
            tabSelectedFill = fill,
            tabIdleFill = band,
            onBackdrop = Color(0xFFFFFFFF),
            onBackdropShadow = Color(0xFF293942),
            muted = Color(0xFF7B7B7B),
            mutedShadow = Color(0xFFD6D6CE),
            divider = divider,
            frameDark = Color(0xFF293942),
            frameLight = Color(0xFF396B8C),
        ),
        hud = SkinHud(
            fill = Color(fill.red, fill.green, fill.blue, 0.75f),
            layers = listOf(Color(0xFF293942) to 1, Color(0xFF396B8C) to 1, band to 1),
            buttonTop = Color(0xE0FFFFFF), buttonLow = Color(band.red, band.green, band.blue, 0.88f),
            buttonLayers = listOf(Color(0xFF293942) to 1, Color(0xFF636373) to 1),
            text = Color(0xFF202020), shadow = Color(0xFFD6D6CE),
            accent = Color(0xFFE03018), accentShadow = Color(0xFFF8B8A0), onAccent = Color.White,
            muted = Color(0xFF4A4A4A),
            cell = Color(0x1F293942), cellOn = Color(band.red, band.green, band.blue, 0.6f), divider = divider,
            selected = Color(0xFFD83020),
            dropShadow = Color(0x4C141820),
        ),
    )

    /** The skin a game's companion wears: each game by its own menus' look ([GameKind] alone can't tell Ruby / Sapphire,
     * which run as EMERALD, nor LeafGreen, which runs as FIRERED: the cart's header code does). Hacks of a retail game
     * that run as it ([GameKind.FIRERED] / [GameKind.EMERALD] / the DS kinds) wear its skin; the others keep [PIXEL]. */
    fun forGame(game: GameKind?, headerCode: String = romHeaderCode): CompanionSkin = when (game) {
        GameKind.POKEMON_X, GameKind.POKEMON_Y -> POKEMON_XY
        GameKind.HEARTGOLD, GameKind.SOULSILVER -> HEARTGOLD
        GameKind.PLATINUM -> PLATINUM
        GameKind.BLACK, GameKind.WHITE -> UNOVA
        GameKind.BLACK2, GameKind.WHITE2 -> UNOVA2
        GameKind.FIRERED -> FIRERED
        GameKind.EMERALD -> if (headerCode.startsWith("AXV") || headerCode.startsWith("AXP")) RUBY_SAPPHIRE else EMERALD
        else -> PIXEL
    }

    /** GAME LOOK (SETTINGS > SCREEN > DISPLAY OPTIONS, Prefs.gameLook, ON): off, every game keeps [PIXEL]. Compose state. */
    var enabled: Boolean
        get() = enabledState.value
        set(v) { enabledState.value = v }
    private val enabledState = androidx.compose.runtime.mutableStateOf(true)

    /** Where a change of [enabled] is kept (PokeDaisyApp: Prefs.gameLook). */
    var onChange: ((Boolean) -> Unit)? = null

    fun change(on: Boolean) {
        enabled = on
        onChange?.invoke(on)
    }

    /** The skin in use now: the running game's while one is open ([OptionColors.inGame], Compose state) and GAME LOOK is
     * on, else [PIXEL]. [ui-preview / Paparazzi] can set [override]. Pokemon X / Y keep theirs either way: their companion
     * was only ever drawn in it. */
    val current: CompanionSkin
        get() = override ?: if (!OptionColors.inGame) PIXEL else forGame(activeGame).let {
            if (enabled) it else PIXEL
        }

    /** Previews: this skin whatever the game (null = the rule above). */
    @Volatile var override: CompanionSkin? = null
}
