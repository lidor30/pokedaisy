package com.pokedaisy.app.companion.ui.theme

import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.pokedaisy.app.companion.data.GameKind
import com.pokedaisy.app.companion.ui.GameArt
import com.pokedaisy.app.companion.ui.PixelRoundedShape
import com.pokedaisy.app.companion.ui.rememberArtGeneration
import com.pokedaisy.app.companion.ui.soundClickable
import com.pokedaisy.app.companion.data.isGameBoyGame
import com.pokedaisy.app.companion.i18n.tk

/**
 * A theme (Settings > THEME on both screens): the app's and the companion's look in its colours, the way DARK MODE
 * turns it dark. The OPTION windows' greys and whites take its [hue] ([themed]), the rows' red values its [value],
 * the top screen's backdrop its [page] and [blobs] (DaisyBackdrop) and a game's own backdrop a wash of [gameWash].
 * Game art (party slots, the map, the card) and colours that mean something (HP, types, statuses) stay as they are,
 * and Gen 1 / Gen 2's black-on-white windows stay the game's. Picked by [id] (Prefs.appTheme), not list position.
 */
data class ThemeSpec(
    val id: Int,
    /** The theme's name, English (a [tk] key, translated where it's drawn). */
    val label: String,
    /** The hue (0-360) the windows' greys take; null = FireRed's OPTION greys as they are (POKéDAISY). */
    val hue: Float? = null,
    /** How much of [hue] the greys take, 0..1 (mid-greys most, the lightest and darkest less). */
    val wash: Float = 0f,
    /** The rows' values and group titles: light mode's, dark mode's. */
    val value: Color = Color(0xFFCB0707),
    val valueNight: Color = Color(0xFFFF6A5C),
    /** The top screen's backdrop: its page colour and three or more soft blobs drifting over it. */
    val page: Color = Color(0xFFF7F6FB),
    val blobs: List<Color> = listOf(Color(0xFFFFD2CC), Color(0xFFFFF0B0), Color(0xFFD6E2FF)),
    /** A page dark enough that text on it is white (HALLOWEEN, NEW YEAR), in light mode too. */
    val darkPage: Boolean = false,
    /** Multiplied over a game's party backdrop in the companion; null = the game's own colours. */
    val gameWash: Color? = null,
    /** Floats over the Library's backdrop instead of the PokeDaisy mark; null = the mark. */
    val motif: ThemeMotif? = null,
    /** The tab chips and buttons each in a colour of the rainbow (OptionColors.rainbow); its windows are the default's. */
    val rainbow: Boolean = false,
    /** A limited-time theme: offered only in its [Season]'s window. */
    val season: Season? = null,
)

/** [ThemeSpec.id] of the PokéDaisy theme, the default: FireRed's OPTION windows over the website's backdrop. */
const val DAISY_THEME_ID = 8

/** The default theme is on (its look is the OPTION screen's own, untinted). */
val isDaisyTheme: Boolean get() = AppColors.theme.hue == null && !AppColors.theme.darkPage && !AppColors.theme.rainbow

/** The theme stored as [id], or the default for an unknown one. */
fun themeById(id: Int): ThemeSpec = (APP_THEMES + SEASONAL_THEMES).firstOrNull { it.id == id } ?: APP_THEMES.first()

/** The default and the themes that are always there. Ids 0-7 were the old game-named themes (FireRed, LeafGreen,
 * Emerald, Ruby, Sapphire, Platinum, HeartGold, SoulSilver): Prefs reads them as the default now, so never reuse them. */
val APP_THEMES = listOf(
    ThemeSpec(DAISY_THEME_ID, "POKéDAISY"),
    ThemeSpec(
        20, tk("CHERRY"), hue = 345f, wash = 0.5f,
        value = Color(0xFFC8143C), valueNight = Color(0xFFFF6A88),
        page = Color(0xFFFFF3F6), blobs = listOf(Color(0xFFFFC2D4), Color(0xFFFFE3EC), Color(0xFFF7B0C8)),
        gameWash = Color(0xFFFFD6E0), motif = ThemeMotifs.BLOSSOM,
    ),
    ThemeSpec(
        21, tk("RAINBOW"),
        page = Color(0xFFFBF9FF),
        blobs = listOf(Color(0xFFFFC4C4), Color(0xFFFFEFA8), Color(0xFFC8F0C8), Color(0xFFC4DAFF), Color(0xFFE2CCFF)),
        motif = ThemeMotifs.RAINBOW, rainbow = true,
    ),
    ThemeSpec(
        22, tk("OCEAN"), hue = 200f, wash = 0.5f,
        value = Color(0xFF0062B0), valueNight = Color(0xFF6CC0FF),
        page = Color(0xFFEFF7FC), blobs = listOf(Color(0xFFB4E2F8), Color(0xFFC4F0E8), Color(0xFFCCD8FF)),
        gameWash = Color(0xFFCCE6FF),
    ),
    ThemeSpec(
        23, tk("MINT"), hue = 155f, wash = 0.42f,
        value = Color(0xFF0F8A64), valueNight = Color(0xFF5EE0B0),
        page = Color(0xFFF1FBF6), blobs = listOf(Color(0xFFC0F0DA), Color(0xFFE6F8C4), Color(0xFFC4EAF0)),
        gameWash = Color(0xFFD2F4E4),
    ),
    ThemeSpec(
        24, tk("LAVENDER"), hue = 265f, wash = 0.45f,
        value = Color(0xFF7434C4), valueNight = Color(0xFFC4A0FF),
        page = Color(0xFFF7F4FD), blobs = listOf(Color(0xFFDDCCFF), Color(0xFFFFD6EC), Color(0xFFD0DAFF)),
        gameWash = Color(0xFFE2D8FF),
    ),
    ThemeSpec(
        25, tk("SUNSET"), hue = 22f, wash = 0.55f,
        value = Color(0xFFD0440C), valueNight = Color(0xFFFF9858),
        page = Color(0xFFFFF5EC), blobs = listOf(Color(0xFFFFC4A0), Color(0xFFFFE49A), Color(0xFFFFB4C2)),
        gameWash = Color(0xFFFFDCC4),
    ),
    ThemeSpec(
        26, tk("FOREST"), hue = 110f, wash = 0.4f,
        value = Color(0xFF2E7A28), valueNight = Color(0xFF8AD878),
        page = Color(0xFFF3F8EE), blobs = listOf(Color(0xFFCCE6B4), Color(0xFFE8E2B8), Color(0xFFB4DAC0)),
        gameWash = Color(0xFFD8ECCC),
    ),
    ThemeSpec(
        27, tk("LEMON"), hue = 50f, wash = 0.6f,
        value = Color(0xFFB86E00), valueNight = Color(0xFFFFCC4A),
        page = Color(0xFFFFFCEA), blobs = listOf(Color(0xFFFFEE98), Color(0xFFFFF6C4), Color(0xFFE6F2A4)),
        gameWash = Color(0xFFFFF2B8),
    ),
)

/** The limited-time themes, each picked only in its [Season]'s window (see [availableThemes]). */
val SEASONAL_THEMES = listOf(
    ThemeSpec(
        40, tk("LUNAR NEW YEAR"), hue = 4f, wash = 0.5f,
        value = Color(0xFFC20E2A), valueNight = Color(0xFFFF6E6E),
        page = Color(0xFFFFF4EA), blobs = listOf(Color(0xFFFFBCAC), Color(0xFFFFDE86), Color(0xFFFFAC9C)),
        gameWash = Color(0xFFFFD4C4), motif = ThemeMotifs.LANTERN, season = Season.LUNAR_NEW_YEAR,
    ),
    ThemeSpec(
        41, tk("VALENTINE'S DAY"), hue = 340f, wash = 0.6f,
        value = Color(0xFFDA1E58), valueNight = Color(0xFFFF7099),
        page = Color(0xFFFFF0F4), blobs = listOf(Color(0xFFFFB4CA), Color(0xFFFFD4E0), Color(0xFFF0BCE6)),
        gameWash = Color(0xFFFFD0DE), motif = ThemeMotifs.HEART, season = Season.VALENTINES,
    ),
    ThemeSpec(
        42, tk("ST. PATRICK'S DAY"), hue = 135f, wash = 0.55f,
        value = Color(0xFF108436), valueNight = Color(0xFF66DC86),
        page = Color(0xFFF0FAF2), blobs = listOf(Color(0xFFB4E6BC), Color(0xFFE0F4AC), Color(0xFFC4F0D6)),
        gameWash = Color(0xFFCCF0D4), motif = ThemeMotifs.CLOVER, season = Season.ST_PATRICKS,
    ),
    ThemeSpec(
        43, tk("EASTER"), hue = 290f, wash = 0.3f,
        value = Color(0xFFA43CB8), valueNight = Color(0xFFE6A0FF),
        page = Color(0xFFFCF8FF),
        blobs = listOf(Color(0xFFFFE6A4), Color(0xFFD4F4CC), Color(0xFFD4E2FF), Color(0xFFFFD4EA)),
        gameWash = Color(0xFFF0E0FF), motif = ThemeMotifs.EGG, season = Season.EASTER,
    ),
    ThemeSpec(
        44, tk("HALLOWEEN"), hue = 28f, wash = 0.6f,
        value = Color(0xFFD45A00), valueNight = Color(0xFFFF9A3C),
        page = Color(0xFF22162E), blobs = listOf(Color(0xFF6E3A0E), Color(0xFF4A2268), Color(0xFF2C561C)),
        darkPage = true, gameWash = Color(0xFFFFC890), motif = ThemeMotifs.PUMPKIN, season = Season.HALLOWEEN,
    ),
    ThemeSpec(
        46, tk("CHRISTMAS"), hue = 140f, wash = 0.4f,
        value = Color(0xFFC41C26), valueNight = Color(0xFFFF6A6A),
        page = Color(0xFFF4FAF5), blobs = listOf(Color(0xFFFFC4C4), Color(0xFFC4EACA), Color(0xFFFFEEBC)),
        gameWash = Color(0xFFE0F0E0), motif = ThemeMotifs.SNOWFLAKE, season = Season.CHRISTMAS,
    ),
    ThemeSpec(
        47, tk("NEW YEAR"), hue = 45f, wash = 0.38f,
        value = Color(0xFFA86400), valueNight = Color(0xFFFFCC50),
        page = Color(0xFF131A32), blobs = listOf(Color(0xFF6A5016), Color(0xFF5A1E50), Color(0xFF1C3A7A)),
        darkPage = true, gameWash = Color(0xFFFFF0C8), motif = ThemeMotifs.SPARKLE, season = Season.NEW_YEAR,
    ),
)

/** The themes the THEME row offers on [date]: every one that's always there, then the limited ones in their window. */
fun availableThemes(date: java.time.LocalDate = ThemeClock.today()): List<ThemeSpec> =
    APP_THEMES + SEASONAL_THEMES.filter { it.season!!.windowOn(date) != null }

/** A limited-time theme's window around [date] (null: not a limited one, or not now). */
fun ThemeSpec.windowOn(date: java.time.LocalDate = ThemeClock.today()): ClosedRange<java.time.LocalDate>? = season?.windowOn(date)

/**
 * The limited-time theme to announce on [date], once per window: in its window, not already worn, and not in [shown]
 * (Prefs.seasonalThemesShown, [announceKey]s). Several at once (LUNAR NEW YEAR and VALENTINE'S DAY): the one that began first.
 */
fun seasonalThemeToAnnounce(date: java.time.LocalDate, shown: Set<String>, currentId: Int): ThemeSpec? =
    SEASONAL_THEMES.mapNotNull { t -> t.windowOn(date)?.let { t to it } }
        .filter { (t, w) -> t.id != currentId && announceKey(t, w) !in shown }
        .minByOrNull { (_, w) -> w.start }?.first

/** The key a theme's window is remembered by once its popup has shown: the theme and the window's first day. */
fun announceKey(theme: ThemeSpec, window: ClosedRange<java.time.LocalDate>): String = "${theme.id}:${window.start}"

/**
 * The theme to wear on [date] for the stored [id]: itself, unless it's a limited-time theme past its window - then
 * the theme worn before it ([before], if that's still a theme), else the default.
 */
fun themeToWear(id: Int, before: Int, date: java.time.LocalDate = ThemeClock.today()): ThemeSpec {
    val t = themeById(id)
    if (t.season == null || t.windowOn(date) != null) return t
    return themeById(before).takeIf { it.season == null } ?: themeById(DAISY_THEME_ID)
}

/** Puts on [spec] on both screens and saves it; a limited-time one remembers the theme it replaced, to go back to. */
fun pickTheme(context: android.content.Context, spec: ThemeSpec) {
    runCatching {
        val prefs = com.pokedaisy.app.Prefs(context)
        val was = themeById(prefs.appTheme)
        if (spec.season != null && was.season == null) prefs.themeBeforeSeasonal = was.id
        prefs.appTheme = spec.id
    }
    AppColors.applyTheme(spec)
}

/** The saved theme ([themeToWear]: a limited one that has ended gives way), put on and saved again if it changed. */
fun loadTheme(context: android.content.Context) {
    val prefs = runCatching { com.pokedaisy.app.Prefs(context) }.getOrNull()
    val spec = prefs?.let { themeToWear(it.appTheme, it.themeBeforeSeasonal) } ?: themeById(DAISY_THEME_ID)
    if (prefs != null && prefs.appTheme != spec.id) runCatching { prefs.appTheme = spec.id }
    AppColors.applyTheme(spec)
}

/**
 * FireRed / LeafGreen menu chrome: cream windows with a double frame, teal party slots with an orange "selected"
 * border, a beige HP capsule, and a pixel font throughout. The chrome colours are FireRed's whatever the theme
 * (they're the companion's in-game bits); the theme itself ([theme]) is Compose state in this process-wide object,
 * so a pick redraws the top screen and the Screen-2 companion (a Presentation in the same process) at once.
 */
object AppColors {
    var theme by mutableStateOf(APP_THEMES[0])
        private set

    val bgTop = Color(0xFF3AA890)
    val bgBottom = Color(0xFF2C7868)
    val windowFrame = Color(0xFF3860A8)
    val windowInner = Color(0xFF98C0F8)
    val windowGold = Color(0xFFF8D060)
    val accent = Color(0xFF3860A8)
    val slotFill = Color(0xFF48B8A8)
    val slotFillDark = Color(0xFF308878)
    val slotFrame = Color(0xFF206858)
    val slotInner = Color(0xFF90E0D0)

    val currentThemeId: Int get() = theme.id
    fun applyTheme(spec: ThemeSpec) { theme = spec }

    // Cream window ("Text box"): fill + double frame. Constant across themes.
    val panel = Color(0xFFF8F8F0)
    val panelAlt = Color(0xFFFFFFF8)
    val borderOuter get() = windowFrame   // legacy alias
    val borderInner get() = windowGold    // legacy alias

    val text = Color(0xFF404850)          // near-black on cream
    val muted = Color(0xFF8890A0)

    // Party slot (see the party-menu screenshot). slotSelected/slotSelectedInner
    // deliberately do NOT vary per theme — "this is the active/chosen item"
    // needs to stay recognizable regardless of the chrome color.
    val slotSelected = Color(0xFFF87838)
    val slotSelectedInner = Color(0xFFFFC098)
    val onSlot = Color(0xFFFCFCFC)
    val onSlotOutline = Color(0xFF404850)

    // HP capsule.
    val hpPill = Color(0xFFE8D8A0)
    val hpPillEdge = Color(0xFF807038)
    val hpTrack = Color(0xFF283830)
    val hpHigh = Color(0xFF60D048)
    val hpMid = Color(0xFFF8C838)
    val hpLow = Color(0xFFF04838)

    // D-pad / keyboard focus ring: a bright orange (the user's call - it was cyan), a
    // lighter, yellower one than slotSelected's so the cursor and "what's already
    // picked" stay apart; the screens' chosen items now mostly use the white cursor row.
    // Monomyth's party backdrop is that orange too (the ring vanished on it): there it's a deep blue, which reads on the
    // orange, its teal slots and the white windows.
    val focusRing: Color get() =
        if (com.pokedaisy.app.companion.ui.OptionColors.inGame &&
            com.pokedaisy.app.companion.data.activeGame == com.pokedaisy.app.companion.data.GameKind.MONOMYTH
        ) Color(0xFF1F4FC8) else Color(0xFFFFB238)

    val statusSlp = Color(0xFF8A8A8A)
    val statusPsn = Color(0xFFA259D9)
    val statusBrn = Color(0xFFE0693E)
    val statusPar = Color(0xFFD9C341)
    val statusFrz = Color(0xFF56C2E6)
    val statusTox = Color(0xFF7A3EA1)

    private val typeColorsTitled = mapOf(
        "Normal" to Color(0xFFA8A878), "Fighting" to Color(0xFFC03028), "Flying" to Color(0xFFA890F0),
        "Poison" to Color(0xFFA040A0), "Ground" to Color(0xFFE0C068), "Rock" to Color(0xFFB8A038),
        "Bug" to Color(0xFFA8B820), "Ghost" to Color(0xFF705898), "Steel" to Color(0xFFB8B8D0),
        "???" to Color(0xFF68A090), "Fire" to Color(0xFFF08030), "Water" to Color(0xFF6890F0),
        "Grass" to Color(0xFF78C850), "Electric" to Color(0xFFF8D030), "Psychic" to Color(0xFFF85888),
        "Ice" to Color(0xFF98D8D8), "Dragon" to Color(0xFF7038F8), "Dark" to Color(0xFF705848),
        "Fairy" to Color(0xFFEE99AC),
        // Pokémon Prism's own types (none of the official games has them): colours of ours.
        "Gas" to Color(0xFF9C8CB4), "Sound" to Color(0xFF58A8C0), "Tri" to Color(0xFFC89878),
        "Prism" to Color(0xFFE070D8),
        // Pokémon Odyssey's AETHER (type 9): ours too.
        "Aether" to Color(0xFF9DB7F5),
        // PureRGB's own: NONE (STRUGGLE's), CRYSTAL (a hardened rock), FLOATING, MAGMA - ours (TRI is Prism's above).
        "None" to Color(0xFF68A090), "Crystal" to Color(0xFF8CC8D8), "Floating" to Color(0xFFB8A0D8),
        "Magma" to Color(0xFFC8481C),
    )

    /** By the game's own type name: title-cased ("Fire"), or Gen 1's capitals ("FIRE", and its unused BIRD as Flying). */
    val typeColors = typeColorsTitled + typeColorsTitled.mapKeys { it.key.uppercase() } +
        ("BIRD" to typeColorsTitled.getValue("Flying"))

    fun statusColor(label: String): Color = when (label) {
        "SLP" -> statusSlp; "PSN" -> statusPsn; "BRN" -> statusBrn
        "PAR" -> statusPar; "FRZ" -> statusFrz; "TOX" -> statusTox
        else -> muted
    }

    fun hpColor(hp: Int, maxHp: Int): Color {
        if (maxHp == 0) return hpHigh
        val pct = hp.toFloat() / maxHp
        return when {
            pct > 0.5f -> hpHigh
            pct > 0.2f -> hpMid
            else -> hpLow
        }
    }

    fun multiplierColor(pct: Int): Color = when {
        pct == 0 -> Color(0xFF585048)
        pct > 100 -> hpHigh
        pct < 100 -> hpLow
        else -> muted
    }
}

/**
 * Bundled "Pixel Operator" (CC0, Jayvee Enaguas) - the app's one font. A
 * proportional pixel font close to the GBA games' own text: 16px em with 9px
 * caps, the same proportions as FireRed's menu font. Crisp only at integer
 * multiples of 16px (see [com.pokedaisy.app.companion.ui.GbaTextMetrics]);
 * elsewhere it's slightly soft, which is fine for body text. Its caps are
 * ~0.56em (Press Start 2P's, which it replaced, were ~0.88em), so sizes run
 * ~1.5x what the old font needed for the same visual height.
 */
@Composable
fun pixelFontFamily(): FontFamily {
    LocalGameFont.current?.let { return it }
    val context = LocalContext.current
    // Pixel Operator + its Japanese fallback (PixelTypeface.kt), one per process.
    return com.pokedaisy.app.pixelFontFamily(context)
}

/**
 * The running game's own font, when it has one the app can use instead of Pixel
 * Operator: a Game Boy game's, cut from its ROM ([com.pokedaisy.app.companion.data.Gen1Art]).
 * Provided by CompanionScreen only, so the top-screen Library / Settings keep Pixel Operator.
 */
val LocalGameFont = androidx.compose.runtime.staticCompositionLocalOf<FontFamily?> { null }

/**
 * How much larger than Pixel Operator text [LocalGameFont] is set, so its letters come out
 * as tall: Gen 1's capitals are 7 pixels to Pixel Operator's 9 (rememberGbaTextMetrics still
 * rounds to whole screen pixels per font pixel). 1 with no game font.
 */
val LocalGameTextScale = androidx.compose.runtime.staticCompositionLocalOf { 1f }

/**
 * How wide [LocalGameFont]'s pixels are drawn, relative to their height: Gen 1's letters fill
 * 8px cells, far wider than Pixel Operator's, so they're condensed (GbaText rounds it per size,
 * so a font pixel stays a whole number of screen pixels wide). 1 with no game font.
 */
val LocalGameFontWidth = androidx.compose.runtime.staticCompositionLocalOf { 1f }

/** [LocalGameFontWidth] for [game]'s own font. */
fun gameFontWidth(game: GameKind?): Float = if (game.isGameBoyGame) 0.75f else 1f

/** [LocalGameTextScale] for [game]'s own font. */
fun gameTextScale(game: GameKind?): Float = if (game.isGameBoyGame) 9f / 7f else 1f

/** [game]'s own font from the rom-art cache, once a launch of it has written it; else null. */
@Composable
fun rememberGameFont(game: GameKind?): FontFamily? {
    val context = LocalContext.current
    val gen = com.pokedaisy.app.companion.ui.rememberArtGeneration()
    val language = com.pokedaisy.app.companion.data.romLanguage
    return remember(game, gen, language) {
        val path = when (game) {
            GameKind.YELLOW -> com.pokedaisy.app.companion.data.Gen1Art.yellowFont(com.pokedaisy.app.companion.data.romLanguage)
            GameKind.RED, GameKind.BLUE -> com.pokedaisy.app.companion.data.Gen1Art.redBlueFont(com.pokedaisy.app.companion.data.romLanguage)
            GameKind.PURE_RED, GameKind.PURE_BLUE -> com.pokedaisy.app.companion.data.Gen1Art.PURE_FONT
            GameKind.YELLOW_LEGACY -> com.pokedaisy.app.companion.data.Gen1Art.YELLOW_LEGACY_FONT
            GameKind.CRYSTAL_LEGACY -> com.pokedaisy.app.companion.data.Gen2Art.CRYSTAL_LEGACY_FONT
            GameKind.CRYSTAL -> com.pokedaisy.app.companion.data.Gen2Art.CRYSTAL_FONT
            GameKind.CRYSTAL_CLEAR -> com.pokedaisy.app.companion.data.Gen2Art.CRYSTAL_CLEAR_FONT
            GameKind.PRISM -> com.pokedaisy.app.companion.data.Gen2Art.PRISM_FONT
            GameKind.POLISHED_CRYSTAL -> com.pokedaisy.app.companion.data.Gen2Art.POLISHED_FONT
            GameKind.GOLD, GameKind.SILVER -> com.pokedaisy.app.companion.data.Gen2Art.GOLD_SILVER_FONT
            else -> null
        } ?: return@remember null
        com.pokedaisy.app.companion.data.RomArt.file(context.filesDir, path)?.let { com.pokedaisy.app.gameFontFamily(context, it) }
    }
}

@Composable
fun AppTheme(content: @Composable () -> Unit) {
    // Pick up the persisted theme choice ONCE per composition (inside the
    // remember{} block itself, not just the id lookup) — this function reads
    // AppColors.accent/bgBottom/panel/text below to build darkColorScheme(),
    // so it recomposes every time the theme changes, including from a LIVE
    // pick on this exact screen (e.g. SettingsActivity's Theme page calls
    // AppColors.applyTheme() directly for instant feedback). A prior version
    // called applyTheme() unconditionally in the composable body on every
    // recomposition, which re-applied the stale remembered Prefs value and
    // stomped the just-picked theme back to whatever it was on first launch —
    // selecting a theme visibly never stuck. remember{} only runs its block
    // once (until its key changes), so this only "loads from disk" at startup.
    val context = LocalContext.current
    remember(context) {
        // runCatching: previews / screenshot tests may have no real prefs.
        runCatching { loadTheme(context) }
        runCatching { DarkMode.load(context) }
    }

    val pixel = pixelFontFamily()
    // lineHeight in em so a Text() that overrides fontSize keeps a
    // proportional line height instead of the style's fixed one.
    fun s(size: Int) = TextStyle(fontFamily = pixel, fontSize = size.sp, lineHeight = 1.2.em)
    val typography = Typography(
        displayLarge = s(30), displayMedium = s(27), displaySmall = s(24),
        headlineLarge = s(24), headlineMedium = s(21), headlineSmall = s(20),
        titleLarge = s(21), titleMedium = s(18), titleSmall = s(17),
        bodyLarge = s(17), bodyMedium = s(15), bodySmall = s(14),
        labelLarge = s(15), labelMedium = s(14), labelSmall = s(12),
    )
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = AppColors.accent,
            onPrimary = Color.White,
            background = AppColors.bgBottom,
            surface = AppColors.panel,
            onSurface = AppColors.text,
            onSurfaceVariant = AppColors.text,
            onBackground = AppColors.text,
        ),
        typography = typography,
    ) {
        // Left to right everywhere, whatever the device's language: every text the app has reads that way (on a Hebrew
        // phone the English windows came out right-aligned, the periods at the start of lines). The manifest's
        // supportsRtl="false" does the same for plain views.
        androidx.compose.runtime.CompositionLocalProvider(
            androidx.compose.ui.platform.LocalLayoutDirection provides androidx.compose.ui.unit.LayoutDirection.Ltr,
            content = content,
        )
    }
}

/**
 * The per-game party-menu background: a whole 240x160 screen (teal for
 * FireRed and most hacks, green for Emerald, grey for Heart and Soul) cropped to fill, or - for the
 * CFRU hacks, whose navy grid repeats every 32px - one grid cell to tile
 * ([GameBackground.tiled]).
 */
class GameBackground(val image: androidx.compose.ui.graphics.ImageBitmap, val tiled: Boolean)

/**
 * FireRed's party backdrop in another game's colors - its three colors (the
 * two stripes, the darker band) mapped to the game's, sampled where only the
 * backdrop shows on each game's party menu (headless mGBA). Those hacks kept
 * FireRed's backdrop layout (Gaia, Too Many Types 2, Emerald Rogue) or draw a
 * flat one (Seaglass, Lazarus, R.O.W.E. - whose grid lines are left out), or
 * stripes of their own (SoulGold).
 */
private val FIRERED_BACKDROP = intArrayOf(0xFF4AADA5.toInt(), 0xFF398C8C.toInt(), 0xFF216B63.toInt())

private fun backdropColors(game: GameKind): IntArray? = when (game) {
    GameKind.EMERALD_ROGUE -> intArrayOf(0xFF080029.toInt(), 0xFF100042.toInt(), 0xFF080029.toInt())
    GameKind.GAIA -> intArrayOf(0xFF63BD7B.toInt(), 0xFF529C6B.toInt(), 0xFF42845A.toInt())
    // Glazed's and Quetzal's party menus have the same olive stripes.
    GameKind.TMT2, GameKind.GLAZED, GameKind.QUETZAL -> intArrayOf(0xFFCED67B.toInt(), 0xFFB5B55A.toInt(), 0xFF8C9C29.toInt())
    GameKind.EMERALD_SEAGLASS -> IntArray(3) { 0xFF4A4A63.toInt() }
    GameKind.LAZARUS -> IntArray(3) { 0xFFFFFFFF.toInt() }
    // Imperium's party grid: grey / near-black rows.
    GameKind.IMPERIUM -> intArrayOf(0xFF424242.toInt(), 0xFF101821.toInt(), 0xFF101821.toInt())
    // Dreamstone Mysteries' party grid: Imperium's rows, the same two colours.
    GameKind.DREAMSTONE -> intArrayOf(0xFF424242.toInt(), 0xFF101821.toInt(), 0xFF101821.toInt())
    GameKind.ROWE -> IntArray(3) { 0xFF292929.toInt() }
    // Pokémon Monomyth's party menu: orange behind its slots (its dots left out).
    GameKind.MONOMYTH -> IntArray(3) { 0xFFFFBD52.toInt() }
    // Super Mariomon's party menu: flat brown behind its wood-plank slots.
    GameKind.MARIOMON -> IntArray(3) { 0xFF845A4A.toInt() }
    // SoulGold's party list: light-blue stripes, a deeper blue edge.
    GameKind.SOULGOLD -> intArrayOf(0xFF84CEEF.toInt(), 0xFF7BC6EF.toInt(), 0xFF429CD6.toInt())
    // Orange Islands' party menu: FireRed's layout in cream stripes (sampled headless).
    GameKind.ORANGE_ISLANDS -> intArrayOf(0xFFFFF7CE.toInt(), 0xFFEFEFB5.toInt(), 0xFFE7DE9C.toInt())
    // Sword and Shield Ultimate Plus' party grid until its own cell (RomArt.SWSH_BACKDROP) is there:
    // red, its darker red accents (its grid lines left out).
    GameKind.SWORD_SHIELD -> intArrayOf(0xFFCE3131.toInt(), 0xFFCE3131.toInt(), 0xFF9C2121.toInt())
    // HeartGold / SoulSilver's party screen: teal behind its Poké Ball, a deeper teal below.
    GameKind.HEARTGOLD, GameKind.SOULSILVER -> intArrayOf(0xFF8ADBDB.toInt(), 0xFF69CBCB.toInt(), 0xFF38B2BA.toInt())
    // Wishes of Tomorrow's party menu: FireRed's lined backdrop in its own blues (sampled headless).
    GameKind.WISHES -> intArrayOf(0xFF63A5BD.toInt(), 0xFF186B8C.toInt(), 0xFF213163.toInt())
    else -> null
}

/** Emerald's party backdrop's three colours (light / mid stripe, the border), as RomArt rebuilds it. */
private val EMERALD_BACKDROP = intArrayOf(0xFFCED67B.toInt(), 0xFFB5B55A.toInt(), 0xFF8C9C29.toInt())

/** [bmp] (FireRed's backdrop, or another whose colours are [from]) with [from] swapped for [to]; or, with no art yet,
 * its stripes alone. */
private fun recolorBackdrop(bmp: android.graphics.Bitmap?, to: IntArray, from: IntArray = FIRERED_BACKDROP): android.graphics.Bitmap {
    val w = bmp?.width ?: 240
    val h = bmp?.height ?: 160
    val px = IntArray(w * h)
    if (bmp != null) {
        bmp.getPixels(px, 0, w, 0, 0, w, h)
        for (i in px.indices) {
            val k = from.indexOf(px[i] or (0xFF shl 24))
            if (k >= 0) px[i] = to[k]
        }
    } else {
        for (y in 0 until h) px.fill(to[y and 1], y * w, (y + 1) * w)
    }
    return android.graphics.Bitmap.createBitmap(px, w, h, android.graphics.Bitmap.Config.ARGB_8888)
}

@Composable
fun rememberGameBackground(game: GameKind): GameBackground? {
    val context = LocalContext.current
    val gen = rememberArtGeneration()
    // Recharged Emerald runs as EMERALD and Ghost Grey and Let's Go as FIRERED, each with its own backdrop.
    val hack = com.pokedaisy.app.companion.data.runningRechargedEmerald || com.pokedaisy.app.companion.data.runningGhostGrey ||
        com.pokedaisy.app.companion.data.runningLetsGo
    return remember(game, gen, hack) {
        // Kept across visits: the ITEMS backdrop crossfade unmounts this one, and
        // rebuilding it recoloured 38k pixels on the main thread each time.
        backgroundCache[Triple(game, hack, gen)]?.let { return@remember it }
        buildGameBackground(context, game)?.also {
            backgroundCache.keys.removeAll { (_, _, g) -> g != gen }
            backgroundCache[Triple(game, hack, gen)] = it
        }
    }
}

/** [rememberGameBackground]'s backdrops by game and art generation (main thread only). */
private val backgroundCache = HashMap<Triple<GameKind, Boolean, Int>, GameBackground>()

private fun buildGameBackground(context: android.content.Context, game: GameKind): GameBackground? {
    // Gen 1's screens are plain white: one pixel, stretched over the tab in a single draw.
    // (Never tiled - GameBackdrop draws a tiled backdrop tile by tile, and a 1px tile at
    // the Thor's 5x was ~53,000 draws a frame: 77 ms per frame on the RenderThread.)
    // Ghost Grey's party menu is plain white too.
    val ghostGrey = game == GameKind.FIRERED && com.pokedaisy.app.companion.data.runningGhostGrey
    if (game.isGameBoyGame || ghostGrey) {
        val paper = if (ghostGrey) -1 else com.pokedaisy.app.companion.ui.OptionColors.gbPaper(game).toArgb()
        return GameBackground(android.graphics.Bitmap.createBitmap(intArrayOf(paper), 1, 1, android.graphics.Bitmap.Config.ARGB_8888).asImageBitmap(), false)
    }
    // Let's Go Pikachu / Eevee: CFRU's party menu with its own grid (RomArt), else CFRU's own.
    if (game == GameKind.FIRERED && com.pokedaisy.app.companion.data.runningLetsGo) {
        sequenceOf(com.pokedaisy.app.companion.data.RomArt.LETS_GO_BACKDROP, com.pokedaisy.app.companion.data.RomArt.CFRU_BACKDROP)
            .firstNotNullOfOrNull { GameArt.get(context, it) }?.let { return GameBackground(it.asImageBitmap(), true) }
    }
    // Sword and Shield Ultimate Plus: CFRU's grid in its own red, one 32px cell tiled (RomArt).
    if (game == GameKind.SWORD_SHIELD) {
        GameArt.get(context, com.pokedaisy.app.companion.data.RomArt.SWSH_BACKDROP)?.let { return GameBackground(it.asImageBitmap(), true) }
    }
    backdropColors(game)?.let { to ->
        return GameBackground(recolorBackdrop(GameArt.get(context, "partybg/firered.png"), to).asImageBitmap(), false)
    }
    // Team Rocket Edition: Dragonsden's party menu is Emerald's backdrop in FireRed's three teals (sampled headless):
    // FireRed's own art where a FireRed ROM has made it, else Emerald's recoloured.
    if (game == GameKind.TEAM_ROCKET) {
        GameArt.get(context, "partybg/firered.png")?.let { return GameBackground(it.asImageBitmap(), false) }
        GameArt.get(context, "partybg/emerald.png")?.let {
            return GameBackground(recolorBackdrop(it, FIRERED_BACKDROP, from = EMERALD_BACKDROP).asImageBitmap(), false)
        }
    }
    val (asset, tiled) = when (game) {
        // Recharged Emerald's own (teal stripes), from its ROM.
        GameKind.EMERALD -> (if (com.pokedaisy.app.companion.data.runningRechargedEmerald) com.pokedaisy.app.companion.data.RomArt.RE_BACKDROP
            else "partybg/emerald.png") to false
        GameKind.HEART_AND_SOUL -> "partybg/hns.png" to false
        // Elite Redux kept Emerald's party backdrop (and its ROM has the art).
        GameKind.ELITE_REDUX -> "partybg/emerald.png" to false
        GameKind.UNBOUND, GameKind.RADICAL_RED, GameKind.ODYSSEY, GameKind.AMETHYST, GameKind.GS_CHRONICLES -> "partybg/cfru_tile.png" to true
        else -> "partybg/firered.png" to false
    }
    // Every backdrop comes from a ROM the player has run (RomArt): until the
    // game's own is there, FireRed's or Emerald's if one of those is (untiled).
    val bmp = sequenceOf(asset, "partybg/firered.png", "partybg/emerald.png").distinct()
        .firstNotNullOfOrNull { path -> GameArt.get(context, path)?.let { it to (tiled && path == asset) } }
    return bmp?.let { (b, tile) -> GameBackground(b.asImageBitmap(), tile) }
}

/**
 * A thick, high-contrast [AppColors.focusRing] ring drawn just inside whatever
 * shape the element already uses (over its own edge) — the shared D-pad/keyboard
 * "here's your cursor" indicator. [shape] is the element's own outline (its frame's
 * corner radius): the ring's outer edge is that outline, so none of the frame peeks out
 * at the corners. Drawing only: the element keeps its size and place as the cursor moves
 * (a border + padding used to shrink the focused one by 3 dp a side).
 */
fun Modifier.gbaFocusRing(focused: Boolean, shape: androidx.compose.ui.graphics.Shape): Modifier =
    if (!focused) this else drawWithContent {
        drawContent()
        val width = 4.dp.toPx()
        // The element's outline, minus the same shape [width] smaller: a ring flush with its edge.
        val outer = shape.createOutline(size, layoutDirection, this).asPath()
        val inner = shape.createOutline(
            androidx.compose.ui.geometry.Size((size.width - 2 * width).coerceAtLeast(0f), (size.height - 2 * width).coerceAtLeast(0f)),
            layoutDirection, this,
        ).asPath().apply { translate(androidx.compose.ui.geometry.Offset(width, width)) }
        val ring = androidx.compose.ui.graphics.Path().apply {
            op(outer, inner, androidx.compose.ui.graphics.PathOperation.Difference)
        }
        drawPath(ring, AppColors.focusRing)
    }

/** An [androidx.compose.ui.graphics.Outline] as a path. */
private fun androidx.compose.ui.graphics.Outline.asPath(): androidx.compose.ui.graphics.Path = when (this) {
    is androidx.compose.ui.graphics.Outline.Generic -> androidx.compose.ui.graphics.Path().apply { addPath(path) }
    is androidx.compose.ui.graphics.Outline.Rounded -> androidx.compose.ui.graphics.Path().apply { addRoundRect(roundRect) }
    is androidx.compose.ui.graphics.Outline.Rectangle -> androidx.compose.ui.graphics.Path().apply { addRect(rect) }
}

/**
 * Translucent orange wash over an item's own fill when it has D-pad/keyboard
 * focus — paired with [gbaFocusRing] so focus reads as a highlighted
 * background too, not just an outline. Apply last, right before `.clickable`,
 * over the item's innermost (already-drawn) fill color.
 */
fun Modifier.gbaFocusTint(focused: Boolean, shape: androidx.compose.ui.graphics.Shape): Modifier =
    if (focused) this.background(AppColors.focusRing.copy(alpha = 0.30f), shape) else this

/** A FR/LG "window": cream fill, blue outer frame, gold hairline, light inner edge. */
@Composable
fun GbaWindow(
    modifier: Modifier = Modifier,
    padding: PaddingValues = PaddingValues(10.dp),
    fill: Color = AppColors.panel,
    content: @Composable () -> Unit,
) {
    Box(
        modifier = modifier
            .background(AppColors.windowFrame, PixelRoundedShape(8.dp))
            .padding(2.dp)
            .background(AppColors.windowGold, PixelRoundedShape(6.dp))
            .padding(1.dp)
            .background(AppColors.windowInner, PixelRoundedShape(5.dp))
            .padding(2.dp)
            .background(fill, PixelRoundedShape(4.dp))
            .padding(padding),
    ) { content() }
}

/**
 * FR/LG "menu button": a cream key with the blue double-frame, pressable. Used
 * everywhere in place of Material `Button`/`OutlinedButton`, which vanish on the
 * teal backdrop. [selected] gives it the blue "highlighted option" fill; keyboard
 * / D-pad focus draws a thick orange [AppColors.focusRing] ring around the whole
 * key, kept a different color from "selected" so the two are never ambiguous.
 * [danger] tints it red for destructive actions (e.g. the confirm button on a
 * delete dialog).
 */
@Composable
fun GbaButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    enabled: Boolean = true,
    danger: Boolean = false,
    contentPadding: PaddingValues = PaddingValues(horizontal = 14.dp, vertical = 9.dp),
    content: @Composable () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val frame = when {
        !enabled -> AppColors.muted
        danger -> AppColors.hpLow
        else -> AppColors.windowFrame
    }
    val fill = when {
        !enabled -> AppColors.panelAlt
        danger -> Color(0xFFFCE4E0)
        selected -> AppColors.windowInner
        else -> AppColors.panel
    }
    val contentColor = when {
        !enabled -> AppColors.muted
        danger -> AppColors.hpLow
        else -> AppColors.text
    }
    Box(
        modifier = modifier
            .gbaFocusRing(focused, PixelRoundedShape(10.dp))
            .background(frame, PixelRoundedShape(7.dp))
            .padding(2.dp)
            .background(if (danger) Color(0xFFF8B8A8) else AppColors.windowGold, PixelRoundedShape(5.dp))
            .padding(1.dp)
            .background(fill, PixelRoundedShape(4.dp))
            .gbaFocusTint(focused, PixelRoundedShape(4.dp))
            .soundClickable(enabled = enabled, interactionSource = interaction, indication = null, onClick = onClick)
            .padding(contentPadding),
        contentAlignment = Alignment.Center,
    ) {
        androidx.compose.runtime.CompositionLocalProvider(
            androidx.compose.material3.LocalContentColor provides contentColor,
        ) { content() }
    }
}

/** White pixel text with a 1px dark outline (as drawn on the party slots). */
@Composable
fun OnSlotText(
    text: String,
    fontSize: Int,
    modifier: Modifier = Modifier,
    color: Color = AppColors.onSlot,
    weight: FontWeight = FontWeight.Normal,
) {
    // NOTE: `style = TextStyle(shadow = ...)` looked right but silently threw
    // away the ambient pixel-font Typography AppTheme sets — Text() merges its
    // own `style` param wholesale over LocalTextStyle.current rather than
    // layering just the one field, so every slot label/HP/Lv on the party
    // screen rendered in the platform default font instead of the pixel font.
    // fontFamily as its own direct Text() param always wins over `style`, so
    // set it there explicitly instead of relying on the merge.
    Text(
        text = text,
        modifier = modifier,
        color = color,
        fontWeight = weight,
        fontSize = fontSize.sp,
        fontFamily = pixelFontFamily(),
        style = TextStyle(
            shadow = Shadow(AppColors.onSlotOutline, Offset(1.5f, 1.5f), 0f),
        ),
    )
}

/**
 * App-styled confirmation modal — an in-composition overlay (NOT a Compose
 * `Dialog`/system `AlertDialog`: those default to Material's own colors, which
 * is what made the old delete confirmation read as generic light-gray-on-white,
 * and a real `Dialog` throws "window type mismatch" inside the Screen-2
 * Presentation anyway — see OptionOverlay). Safe to use from any screen,
 * including the companion.
 */
@Composable
fun GbaConfirmDialog(
    title: String,
    message: String,
    confirmLabel: String = "Delete",
    dismissLabel: String = "Cancel",
    danger: Boolean = true,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val noRipple = remember { MutableInteractionSource() }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xCC000000))
            .soundClickable(interactionSource = noRipple, indication = null, onClick = onDismiss),
        contentAlignment = Alignment.Center,
    ) {
        GbaWindow(
            modifier = Modifier
                .widthIn(max = 420.dp)
                .padding(24.dp)
                .clickable(interactionSource = noRipple, indication = null, onClick = {}), // swallow taps
            padding = PaddingValues(16.dp),
        ) {
            Column {
                Text(title, color = AppColors.text, fontWeight = FontWeight.Bold, fontSize = 21.sp)
                Spacer(Modifier.padding(top = 8.dp))
                Text(message, color = AppColors.muted, fontSize = 16.sp)
                Spacer(Modifier.padding(top = 16.dp))
                Row {
                    GbaButton(onClick = onDismiss, modifier = Modifier.weight(1f)) { Text(dismissLabel) }
                    Spacer(Modifier.padding(start = 8.dp))
                    GbaButton(onClick = onConfirm, danger = danger, modifier = Modifier.weight(1f)) { Text(confirmLabel) }
                }
            }
        }
    }
}
