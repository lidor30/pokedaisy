package com.pokedaisy.app.companion

import com.pokedaisy.app.Prefs
import com.pokedaisy.app.companion.i18n.tk

/**
 * The NEW tag on a just-shipped feature's settings row (both screens: `SettingRow(labelBadge = NewFeatures.badge(id))`,
 * drawn green like the Library's NEW), for [DAYS] days from the first launch of a build that has the feature - every
 * id in [ALL] is dated at app start ([init]), so a feature that comes with an update is new to that player for a week
 * from the update. Every new feature gets one (CLAUDE.md): add its id to [ALL] and the badge to its row.
 */
object NewFeatures {
    /** SETTINGS > HUD (companion.ui.FieldHud). */
    const val HUD = "hud"

    /** SETTINGS > BATTLE BUTTONS (companion.ui.BattleButtons). */
    const val BATTLE_BUTTONS = "battle_buttons"

    /** HOTKEYS > RESTART GAME and CLOSE APP (Hotkeys.Action.badge). */
    const val GAME_HOTKEYS = "game_hotkeys"

    /** SETTINGS > COMPANION VIEW: the companion as a window over the game on one screen (SidePanel). */
    const val COMPANION_WINDOW = "companion_window"

    /** SETTINGS > HUD > PC: the game's PC from a button beside BAG. */
    const val HUD_PC = "hud_pc"

    /** SETTINGS > QUICK MENU and HOTKEYS > QUICK MENU (companion.ui.QuickMenu). */
    const val QUICK_MENU = "quick_menu"

    /** SETTINGS > HUD > REPEL STEPS: a REPEL's steps left, counting down beside the balls. */
    const val HUD_REPEL = "hud_repel"

    /** SETTINGS > SINGLE SCREEN: a two-screen device played on one, the companion over the game. */
    const val SINGLE_SCREEN = "single_screen"

    /** The MORE tab right of the gear (companion.ui.MoreScreen): its row is SETTINGS > TAB BAR. */
    const val MORE_TAB = "more_tab"

    /** The BOXES tab (companion.ui.BoxesScreen): the PC's boxes, retail FireRed first. Its rows are SETTINGS > TAB BAR's. */
    const val BOXES_TAB = "boxes_tab"

    /** SETTINGS > TOUCH CONTROLS: layouts, size, opacity, extra buttons and EDIT LAYOUT (companion.ui.TouchControls). */
    const val TOUCH_CONTROLS = "touch_controls"

    /** SETTINGS > FOLD LAYOUT: a foldable half open puts the game on one side of its hinge, the companion on the other. */
    const val FOLD_LAYOUT = "fold_layout"

    /** TOUCH CONTROLS > LOOK / COLORS (and the MIRRORED / RAISED layouts): how the drawn buttons look. */
    const val TOUCH_STYLES = "touch_styles"

    /** TOUCH CONTROLS > SKINS: Delta skins (.deltaskin) in place of the drawn buttons (companion.DeltaSkin). */
    const val TOUCH_SKINS = "touch_skins"

    /** SETTINGS > COMPANION BUTTON: hide the edge button that opens the companion on one screen (SidePanel). */
    const val COMPANION_TAB = "companion_tab"

    /** SETTINGS > REUSABLE TMS: a TM stays in the bag after it teaches a move (companion.data.ReusableTms). */
    const val REUSABLE_TMS = "reusable_tms"
    /** SETTINGS > TRADE EVOLUTIONS: a trade evolution from the Pokémon's summary (companion.data.TradeEvolution). */
    const val TRADE_EVOLUTIONS = "trade_evolutions"
    /** SETTINGS > SHINY ODDS: a wild foe shiny at the odds picked (companion.data.ShinyOdds). */
    const val SHINY_ODDS = "shiny_odds"

    /** SETTINGS > SHADERS > UNLIT SCREEN: the GBA's screen with no light of its own (ScreenShaders.UNLIT). */
    const val UNLIT_SCREEN = "unlit_screen"

    /** SETTINGS > DISPLAY OPTIONS > LIGHT COMPANION: a lighter companion for slow devices (LightCompanion). */
    const val LIGHT_COMPANION = "light_companion"

    /** GAME BUTTONS > TO COMPANION (X by default): on the field of a two-screen device it takes the buttons to the
     * companion, like a DS game's menu. */
    const val X_COMPANION = "x_companion"

    /** GAME BUTTONS > START 2 / SELECT 2: the GBA / Game Boy games' spare buttons, apart from the DS games' X / Y. */
    const val GBA_BUTTONS = "gba_buttons"
    /** GAME BUTTONS' UP / DOWN / LEFT / RIGHT: more keys for the D-pad (a controller whose D-pad sends letters). */
    const val DPAD_KEYS = "dpad_keys"
    /** SETTINGS > CHALLENGE ICONS: RetroAchievements' corner icons on or off. */
    const val RA_ICONS = "ra_icons"
    /** SETTINGS > DS TOUCH SCREEN: a DS game's touch screen stretched over the second display. */
    const val DS_TOUCH_FILL = "ds_touch_fill"

    /** SETTINGS > PLAYER LOOK: play as another game's hero (companion.data.PlayerLook). */
    const val PLAYER_LOOK = "player_look"

    /** SETTINGS > RIVAL LOOK: the rival dressed up along with the player (companion.data.PlayerLook.build's rival). */
    const val RIVAL_LOOK = "rival_look"

    /** SETTINGS > TEXT LOOK: another game's dialogue box and font (companion.data.TextLook). */
    const val TEXT_LOOK = "text_look"

    /** SETTINGS > DAY AND NIGHT: the overworld tinted by the device's clock (companion.data.DayNight). */
    const val DAY_NIGHT = "day_night"

    /** SETTINGS > FOLLOWER: the party's lead walks behind the player (pk_wide.c's follower). */
    const val FOLLOWER = "follower"

    /** HOTKEYS > COMPANION: opens the companion over the game on one screen (Hotkeys.Action.COMPANION). */
    const val COMPANION_HOTKEY = "companion_hotkey"

    /** SETTINGS > NUZLOCKE: the NUZLOCKE tab, a run's encounters and fainted Pokémon (companion.Nuzlocke). */
    const val NUZLOCKE = "nuzlocke"

    /** SETTINGS > DS RESOLUTION: a DS game's 3D drawn at 2x to 8x on the GPU (nds/pk_nds_gl.h). */
    const val DS_RESOLUTION = "ds_resolution"
    const val DS_CPU_BOOST = "ds_cpu_boost"

    /** SETTINGS > DS CORE: DSperate, a second DS emulator for weak devices (companion.DsCore). */
    const val DS_CORE = "ds_core"

    /** SETTINGS > APP > TIPS: a card now and then about a feature the player hasn't found (FeatureTips). */
    const val TIPS = "tips"

    /** Settings > FOLDERS > ROMS FOLDERS and the library's + menu: several ROMs folders linked at once (RomFolder). */
    const val ROMS_FOLDERS = "roms_folders"

    /** Settings > FOLDERS > SAVE BACKUPS: turn the start's save backups off, keep more or fewer, one folder for all. */
    const val SAVE_BACKUPS = "save_backups"

    /** SETTINGS > DISCORD: PokeDaisy's Discord server, for help, bug reports, ideas and chat (companion.Discord). */
    const val DISCORD = "discord"

    /** SETTINGS > SOUND: GAME VOLUME, MUTE GAME and HOTKEYS > MUTE (GameVolume). */
    const val GAME_VOLUME = "game_volume"

    /** SETTINGS > SOUND > AUDIO EFFECTS: the device's audio effects on the game's sound (AudioEffects). */
    const val AUDIO_EFFECTS = "audio_effects"

    /** Settings > LIBRARY > PATCH A ROM and the library's + menu: a .bps patch applied to a ROM the library has (RomPatchFlow). */
    const val ROM_PATCH = "rom_patch"
    /** Settings > TOOLS > LINK PLAY (and a GBA game's library menu): two to four GBA games on one link cable (LinkActivity). */
    const val LINK_PLAY = "link_play"
    /** TOOLS > SAVE EDITOR / TRADE SAVES and a game's library menu > SAVE EDITOR: the save editor, out from behind its flag. */
    const val SAVE_TRADE = "save_trade"

    /** SETTINGS > DS LAYOUT: a DS game's two screens on one (companion.DsLayout), its touch screen floating in over the
     * game when wanted; the TOUCH SCREEN hotkey and the HUD's TOUCH button open it by hand. */
    const val DS_LAYOUT = "ds_layout"

    /** SETTINGS > BATTLE FOCUS (Prefs.battleFocus). */
    const val BATTLE_FOCUS = "battle_focus"

    /** SETTINGS > THEME on both screens: themes colour the app and the companion, limited-time ones by date (ui.theme.ThemeSpec). */
    const val THEMES = "themes"

    /**
     * SETTINGS > CO-OP > PAIR: play Emerald with a friend (companion.coop). Kept OUT of [ALL] while the co-op feature flag
     * is off (FeatureFlags.coop): it joins [ALL] when the flag goes. Until then [isNew] is true only before [init]
     * (previews), so a flagged-on build shows no tag - a tag that never expires would be wrong too.
     */
    const val COOP = "coop"

    /** Every feature that gets the tag. */
    /** SETTINGS > BATTLE INFO: off, only the battle buttons. */
    const val BATTLE_INFO = "battle_info"
    /** SETTINGS > BATTLE VIEW: the BATTLE tab opens on INFO. */
    const val BATTLE_VIEW = "battle_view"
    /** Settings > GAME NAMES: the library by file name. */
    const val GAME_NAMES = "game_names"
    /** Settings > SUPPORTED ONLY: the library without the games the companion can't read. */
    const val SUPPORTED_ONLY = "supported_only"
    /** SETTINGS > INSTANT TEXT: a text box's words at once (companion.data.InstantText). */
    const val INSTANT_TEXT = "instant_text"
    /** SETTINGS > LOOK AROUND: EXPAND's right stick switched off. */
    const val LOOK_AROUND = "look_around"
    /** SETTINGS > BATTLE STYLE: the Gen 5 battle buttons (companion.ui.BattleControlsGen5). */
    const val BATTLE_STYLE = "battle_style"
    /** QUICK MENU > FLASH: the field move from the quick menu (companion.data.Flash); the QUICK MENU row carries it too. */
    const val QUICK_FLASH = "quick_flash"
    /** FREE FLIGHT: TAKE OFF / LAND (the QUICK MENU's tile and its row, the MAP tab, the FREE FLIGHT hotkey). */
    const val FREE_FLIGHT = "free_flight"
    /** SETTINGS > FLY STYLE: SCENIC flies over the field to the town. */
    const val SCENIC_FLY = "scenic_fly"

    /** MAKEOVER > RIDE ON: a motorcycle or a scooter in place of the bike (companion.data.RideOn). */
    const val RIDE_ON = "ride_on"

    /** SETTINGS > FLIGHT AREA: flying only over the maps the player has seen (companion.data.FlightArea). */
    const val FLIGHT_AREA = "flight_area"
    /** MAKEOVER > FLY ON: a balloon, a helicopter or a zeppelin in place of the bird. */
    const val FLY_ON = "fly_on"
    /** SETTINGS > ZOOM: EXPAND's zoom picked from a list, without a right stick. */
    const val ZOOM_SETTING = "zoom_setting"
    /** TOUCH CONTROLS > VIBRATION as a switch with its STRENGTH under it (TouchHaptics). */
    const val TOUCH_VIBRATION = "touch_vibration"
    /** TOUCH CONTROLS' ZOOM / REWIND extras and X AND Y for GBA / Game Boy games. */
    const val TOUCH_MORE_BUTTONS = "touch_more_buttons"

    val ALL = listOf(
        HUD, BATTLE_BUTTONS, GAME_HOTKEYS, COMPANION_WINDOW, HUD_PC, QUICK_MENU, HUD_REPEL, SINGLE_SCREEN, MORE_TAB, TOUCH_CONTROLS,
        COMPANION_TAB, REUSABLE_TMS, SHINY_ODDS, UNLIT_SCREEN, LIGHT_COMPANION, X_COMPANION, PLAYER_LOOK, RIVAL_LOOK, TEXT_LOOK, DAY_NIGHT,
        FOLLOWER, COMPANION_HOTKEY, NUZLOCKE, GBA_BUTTONS, DS_RESOLUTION, TIPS, ROMS_FOLDERS, SAVE_BACKUPS, DISCORD, BOXES_TAB,
        GAME_VOLUME, ROM_PATCH, TRADE_EVOLUTIONS, DS_LAYOUT, DS_CPU_BOOST, LINK_PLAY, BATTLE_FOCUS, THEMES, SAVE_TRADE, FOLD_LAYOUT, TOUCH_STYLES, TOUCH_SKINS,
        AUDIO_EFFECTS, BATTLE_INFO, BATTLE_VIEW, GAME_NAMES, SUPPORTED_ONLY, LOOK_AROUND, INSTANT_TEXT, BATTLE_STYLE,
        QUICK_FLASH, ZOOM_SETTING, TOUCH_VIBRATION, TOUCH_MORE_BUTTONS, FREE_FLIGHT, SCENIC_FLY, FLIGHT_AREA, FLY_ON, RIDE_ON,
        DPAD_KEYS, RA_ICONS, DS_TOUCH_FILL, DS_CORE,
    )

    private const val DAYS = 7
    private const val DAY_MS = 24 * 60 * 60 * 1000L

    @Volatile
    private var since: Map<String, Long> = emptyMap()

    @Volatile
    private var started = false

    /** App start: date every feature not seen before (now); the ones seen keep their date. */
    fun init(prefs: Prefs, now: Long = System.currentTimeMillis()) {
        val seen = prefs.newFeatureSince
        val all = seen + ALL.filter { it !in seen }.associateWith { now }
        if (all != seen) prefs.newFeatureSince = all
        since = all
        started = true
    }

    /** Still new: dated less than [DAYS] days ago. Before [init] (screenshot tests, ui-preview) every feature is. */
    fun isNew(id: String, now: Long = System.currentTimeMillis()): Boolean {
        if (!started) return true
        val at = since[id] ?: return false
        return now - at < DAYS * DAY_MS
    }

    /** The row's label badge: NEW while [isNew], else none. */
    fun badge(id: String): String? = if (isNew(id)) NEW_TAG else null

    /** The tag's text, which OptionLine draws green (OptionColors.newBadge). */
    val NEW_TAG = tk("NEW")
}
