package com.pokedaisy.app.companion.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import com.pokedaisy.app.companion.data.GameKind
import com.pokedaisy.app.companion.data.activeGame

/*
 * A game's look for the whole companion (the user's call, 2026-11-15: the companion in the running game's own style -
 * Pokemon X first, the DS games and some GBA games next). The companion is built from a few shared pieces, and a skin
 * changes those, so every tab follows without each screen knowing:
 * - corners: [pixelCornersPath] (every PixelRoundedShape, drawPixelRoundRect / Frame, drawLayeredBox) - stepped in GBA
 *   pixels, or smooth ([CompanionSkin.smooth]);
 * - text: [pixelFontFamily] (the font), [gbaTextStyle] (its size, [CompanionSkin.fontScale]), [GbaText]'s shadow
 *   ([CompanionSkin.shadow]);
 * - button faces: [drawTwoTone] - HeartGold's two tones, or a smooth gradient ([CompanionSkin.gradientFaces]);
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
    val colors: SkinColors? = null,
)

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

    /** The skin a game's companion wears. */
    fun forGame(game: GameKind?): CompanionSkin = when (game) {
        GameKind.POKEMON_X, GameKind.POKEMON_Y -> POKEMON_XY
        else -> PIXEL
    }

    /** The skin in use now: the running game's while one is open ([OptionColors.inGame], Compose state), else [PIXEL].
     * [ui-preview / Paparazzi] can set [override]. */
    val current: CompanionSkin
        get() = override ?: if (OptionColors.inGame) forGame(activeGame) else PIXEL

    /** Previews: this skin whatever the game (null = the rule above). */
    @Volatile var override: CompanionSkin? = null
}
