package com.pokedaisy.app

import android.content.Context
import com.pokedaisy.app.companion.FfMode
import com.pokedaisy.app.companion.GridStrength
import com.pokedaisy.app.companion.ScreenFilter
import com.pokedaisy.app.companion.FfMusicMode

/** Tiny SharedPreferences wrapper for app-level state. */
class Prefs(context: Context) {
    private val p = context.getSharedPreferences("pokedaisy", Context.MODE_PRIVATE)

    var lastRomPath: String?
        get() = p.getString("last_rom", null)
        set(v) = p.edit().putString("last_rom", v).apply()

    /**
     * Play history, most-recent-first, capped at [RECENT_CAP] ROM paths —
     * backs the Library screen's "Recently Played" row. Deliberately kept
     * separate from [lastRomPath] (which only ever tracks the single most
     * recent play, for the row's "PLAYING" badge) since the two have
     * different callers and different lifetimes.
     */
    fun recentRomPaths(): List<String> =
        p.getString("recent_roms", null)?.split("\n")?.filter { it.isNotBlank() } ?: emptyList()

    fun pushRecentRom(path: String) {
        val updated = (listOf(path) + recentRomPaths().filter { it != path }).take(RECENT_CAP)
        p.edit().putString("recent_roms", updated.joinToString("\n")).apply()
    }

    /** 0 = list, 1 = grid — Library screen's view-mode toggle. */
    var libraryViewMode: Int
        get() = p.getInt("library_view_mode", 0)
        set(v) = p.edit().putInt("library_view_mode", v).apply()

    /** Whether a ROM's cover (keyed by file name, like [romDisplayName]) was
     * explicitly picked by the user rather than auto-captured — a manual
     * cover is never overwritten by a later auto-capture backfill. */
    fun romCoverManual(file: java.io.File): Boolean = p.getBoolean("cover_manual_${file.name}", false)
    fun setRomCoverManual(file: java.io.File, manual: Boolean) =
        p.edit().putBoolean("cover_manual_${file.name}", manual).apply()

    /** Where an automatic cover came from ([CoverArtSync.Source] name), for INFO; null = unknown (older covers). */
    fun romCoverSource(file: java.io.File): String? = p.getString("cover_source_${file.name}", null)
    fun setRomCoverSource(file: java.io.File, source: String?) = p.edit().apply {
        if (source == null) remove("cover_source_${file.name}") else putString("cover_source_${file.name}", source)
    }.apply()

    /** RetroAchievements account (achievements/RetroAchievements.kt): the username and the
     * login token the server returned - never the password. Both null = signed out. */
    var raUsername: String?
        get() = p.getString("ra_username", null)
        set(v) = p.edit().putString("ra_username", v).apply()
    var raToken: String?
        get() = p.getString("ra_token", null)
        set(v) = p.edit().putString("ra_token", v).apply()

    /** Fast-forward speed cap. 0 = unlimited. */
    var ffMaxSpeed: Float
        get() = p.getFloat("ff_max_speed", 2f)
        set(v) = p.edit().putFloat("ff_max_speed", v).apply()

    /** What fast-forward sounds like. Before the modes it was an on/off
     * "ff_music_enabled": on was today's STEADY, off stays OFF. */
    var ffMusicMode: FfMusicMode
        get() = p.getString("ff_music_mode", null)?.let { n -> FfMusicMode.entries.firstOrNull { it.name == n } }
            ?: if (p.getBoolean("ff_music_enabled", true)) FfMusicMode.STEADY else FfMusicMode.OFF
        set(v) = p.edit().putString("ff_music_mode", v.name).apply()

    /** Where fast-forward applies: SMART (1x on menus and the region map) or NORMAL. */
    /** REWIND: the last ~20 s kept for the rewind hotkey (memory and a little CPU while on). */
    var rewind: Boolean
        get() = p.getBoolean("rewind", false)
        set(v) = p.edit().putBoolean("rewind", v).apply()

    var ffMode: FfMode
        get() = p.getString("ff_mode", null)?.let { n -> FfMode.entries.firstOrNull { it.name == n } } ?: FfMode.SMART
        set(v) = p.edit().putString("ff_mode", v.name).apply()

    /** What FfMenuWatch learned for a ROM (by CRC): its field's and battle's gMain.callback2, 0 = not yet. */
    fun ffMenuCallbacks(romKey: String): Pair<Long, Long> {
        // v2: before it, the scan could settle on a task's slot instead of gMain (FireRed),
        // and what it learned there is wrong - not carried over.
        val v = p.getString("ff_menu_cb2_v2_$romKey", null)?.split(',') ?: return 0L to 0L
        return (v.getOrNull(0)?.toLongOrNull() ?: 0L) to (v.getOrNull(1)?.toLongOrNull() ?: 0L)
    }

    fun setFfMenuCallbacks(romKey: String, field: Long, battle: Long) =
        p.edit().putString("ff_menu_cb2_v2_$romKey", "$field,$battle").apply()

    /** Fast-forward on/off, remembered across launches (restored alongside
     * [ffMaxSpeed] on every engine creation - fresh boot or resume-from-state
     * alike, since neither carries this Kotlin-side field on its own). */
    var ffToggled: Boolean
        get() = p.getBoolean("ff_toggled", false)
        set(v) = p.edit().putBoolean("ff_toggled", v).apply()

    /** Remembered speed-cycle index per ROM (keyed by CRC32). */
    fun speedIndexFor(romKey: String): Int = p.getInt("speed_$romKey", 0)
    fun setSpeedIndexFor(romKey: String, index: Int) = p.edit().putInt("speed_$romKey", index).apply()

    /** On-screen controls: 0 = auto (only if no gamepad), 1 = always, 2 = never. */
    var touchControlsMode: Int
        get() = p.getInt("touch_controls", 0)
        set(v) = p.edit().putInt("touch_controls", v).apply()

    /** Whether battle-only strategic hints (move effectiveness chips, the
     * foe's Weak-to/Resists/Immune-to summary) are shown. On by default -
     * this is an opt-out for players who'd rather figure matchups out
     * themselves. Doesn't affect the Party tab's own matchup display (that's
     * dex info, not battle metagaming) or move Power (shown regardless). */
    var showHints: Boolean
        get() = p.getBoolean("show_hints", true)
        set(v) = p.edit().putBoolean("show_hints", v).apply()

    /** Whether battle INFO offers the foe's IVs / EVs / nature (its STATS page).
     * Off by default - it's the one thing the game itself never tells you.
     * The party summary's own STATS page shows regardless. */
    var showFoeIvs: Boolean
        get() = p.getBoolean("show_foe_ivs", false)
        set(v) = p.edit().putBoolean("show_foe_ivs", v).apply()

    /** The CHEATS master switch (cheats/Cheats.kt): off, no cheat reaches the game,
     * whatever each one is set to. Off by default. */
    var cheatsEnabled: Boolean
        get() = p.getBoolean("cheats_enabled", false)
        set(v) = p.edit().putBoolean("cheats_enabled", v).apply()

    /** The app's language: "AUTO" (the ROM's, or the device's in the Library) or
     * an AppLanguage code ("EN", "JA", ...) - see [com.pokedaisy.app.companion.i18n.L10n]. */
    var appLanguage: String
        get() = p.getString("app_language", null) ?: com.pokedaisy.app.companion.i18n.LANGUAGE_AUTO
        set(v) = p.edit().putString("app_language", v).apply()

    /**
     * Whether launching a game picks up where it was left (the suspend state written when it
     * closed, else the newest slot). Off, a launch boots the cart from its save - the title
     * screen - and only a game that's still open (back from HOME) carries on from memory.
     */
    var autoResume: Boolean
        get() = p.getBoolean("auto_resume", true)
        set(v) = p.edit().putBoolean("auto_resume", v).apply()

    /** A SETTINGS > TWEAKS switch (CompanionTweaks), on unless turned off. */
    fun tweak(key: String): Boolean = p.getBoolean("tweak_$key", true)
    fun setTweak(key: String, on: Boolean) = p.edit().putBoolean("tweak_$key", on).apply()

    /** Whether the companion's buttons play the game's click (GameClickSound). */
    var clickSound: Boolean
        get() = p.getBoolean("click_sound", true)
        set(v) = p.edit().putBoolean("click_sound", v).apply()

    /** Whether the emulator hotkeys ([com.pokedaisy.app.Hotkeys]) fire at all; off, every key goes to the game. */
    var hotkeysEnabled: Boolean
        get() = p.getBoolean("hotkeys_enabled", true)
        set(v) = p.edit().putBoolean("hotkeys_enabled", v).apply()

    /** Whether the game has a status bar on top (game, location, money, clock, battery). */
    var statusBar: Boolean
        get() = p.getBoolean("status_bar", false)
        set(v) = p.edit().putBoolean("status_bar", v).apply()

    /** The status bar over the companion's tabs instead of over the game (CompanionStatusBar). */
    var statusBarOnCompanion: Boolean
        get() = p.getBoolean("status_bar_on_companion", false)
        set(v) = p.edit().putBoolean("status_bar_on_companion", v).apply()

    /** Whether the game is stretched to fill the top screen (16:9) instead of kept at the GBA's 3:2. */
    var stretchGame: Boolean
        get() = p.getBoolean("stretch_game", false)
        set(v) = p.edit().putBoolean("stretch_game", v).apply()

    /** SCREEN > OVERLAY: the frame around the game, an [com.pokedaisy.app.overlay.OverlayChoice.key] - "" (none, the
     * default), a built-in frame ("builtin:DAISY") or an imported overlay ("user:<its folder>"). */
    var overlay: String
        get() = p.getString("overlay", null) ?: ""
        set(v) = p.edit().putString("overlay", v).apply()

    /** Whether the game's colours are shown as the GBA's own LCD showed them ([ScreenShaders.GBA_COLOR]). */
    var gbaColors: Boolean
        get() = p.getBoolean("gba_colors", false)
        set(v) = p.edit().putBoolean("gba_colors", v).apply()

    /** The screen effect over the game: LCD grid, scanlines or CRT ([ScreenShaders.effectFor]). */
    var screenFilter: ScreenFilter
        get() = ScreenFilter.entries.firstOrNull { it.name == p.getString("screen_filter", null) } ?: ScreenFilter.NONE
        set(v) = p.edit().putString("screen_filter", v.name).apply()

    /** Whether SHADERS (filter and GBA COLORS) also draw over the companion and status bar ([CompanionColors]). */
    var companionShaders: Boolean
        get() = p.getBoolean("companion_shaders", true)
        set(v) = p.edit().putBoolean("companion_shaders", v).apply()

    /** LCD / LCD PAPER's grid strength ([ScreenShaders.gridFor]). */
    var gridStrength: GridStrength
        get() = GridStrength.entries.firstOrNull { it.name == p.getString("grid_strength", null) } ?: GridStrength.MEDIUM
        set(v) = p.edit().putString("grid_strength", v.name).apply()

    /** Two screens: the game on the second display and the companion on the main one -
     * for a device whose main display is its bottom screen (see PokeDaisyActivity.syncPresentation). */
    var swapScreens: Boolean
        get() = p.getBoolean("swap_screens", false)
        set(v) = p.edit().putBoolean("swap_screens", v).apply()

    /** Single-screen devices: the companion's side panel is locked beside the game (see [SidePanel]). */
    var sidePanelDocked: Boolean
        get() = p.getBoolean("side_panel_docked", false)
        set(v) = p.edit().putBoolean("side_panel_docked", v).apply()

    /** Single-screen devices: the side panel's width, as a fraction of the screen's (see [SidePanel]). */
    var sidePanelWidth: Float
        get() = p.getFloat("side_panel_width", 0.5f)
        set(v) = p.edit().putFloat("side_panel_width", v).apply()

    /** A phone held upright: the companion right under the game, the touch pad at the screen's bottom, instead of
     * the companion along the bottom (SETTINGS > COMPANION; see [PortraitPanel]). */
    var portraitCompanionUnderGame: Boolean
        get() = p.getBoolean("portrait_companion_under_game", false)
        set(v) = p.edit().putBoolean("portrait_companion_under_game", v).apply()

    /** A phone held upright: the companion's height, as a ratio of the screen's width (see [PortraitPanel]). */
    var portraitCompanionRatio: Float
        // PortraitLayout.DEFAULT_RATIO: the Thor bottom screen's shape (spelled out: ui-preview's data module has no UI).
        get() = p.getFloat("portrait_companion_ratio", 1080f / 1240f)
        set(v) = p.edit().putFloat("portrait_companion_ratio", v).apply()

    /** The companion tabs shown in the bottom screen's tab bar (see
     * [com.pokedaisy.app.companion.COMPANION_TABS]); the rest are
     * reached from its SETTINGS tab. */
    var companionTabs: List<String>
        get() = p.getString("companion_tabs", null)?.split(",")?.filter { it.isNotBlank() }
            ?: com.pokedaisy.app.companion.DEFAULT_COMPANION_TABS
        set(v) = p.edit().putString("companion_tabs", v.joinToString(",")).apply()

    /** Games whose GUIDE notice ("written with AI help, may be wrong") was accepted. */
    fun guideNoticeAccepted(game: String): Boolean = game in guideNoticeGames()
    fun acceptGuideNotice(game: String) =
        p.edit().putString("guide_notice_ok", (guideNoticeGames() + game).joinToString(",")).apply()
    private fun guideNoticeGames(): Set<String> =
        p.getString("guide_notice_ok", null)?.split(",")?.filter { it.isNotBlank() }?.toSet() ?: emptySet()

    /** Display-only ROM name override (keyed by file name); null = use the file name. */
    fun romDisplayName(file: java.io.File): String? = p.getString("romname_${file.name}", null)
    fun setRomDisplayName(file: java.io.File, name: String?) = p.edit().apply {
        if (name.isNullOrBlank()) remove("romname_${file.name}") else putString("romname_${file.name}", name.trim())
    }.apply()

    /**
     * Where the ROM was imported *from* (its original on-device path, best-effort
     * resolved from the picker's content:// URI), so the library can show that
     * instead of the copy under this app's private storage. Null if unknown (e.g.
     * a file dropped directly into the roms/ folder, or picked from a provider
     * with no real filesystem path, like Drive).
     */
    fun romSourcePath(file: java.io.File): String? = p.getString("romsrc_${file.name}", null)
    fun setRomSourcePath(file: java.io.File, path: String?) = p.edit().apply {
        if (path.isNullOrBlank()) remove("romsrc_${file.name}") else putString("romsrc_${file.name}", path)
    }.apply()

    /** Custom folder for game saves (.sav/.srm); null = the app's own files/saves/. */
    var savesDirOverride: String?
        get() = p.getString("saves_dir_override", null)
        set(v) = p.edit().putString("saves_dir_override", v).apply()

    /** More folders to look for a game's save in, after [savesDirOverride] (Settings > FOLDERS >
     * ALSO LOOK IN; see [SavesLocation.saveFor]). New saves still go to the saves folder. */
    var extraSaveDirs: List<String>
        get() = p.getString("extra_save_dirs", null)?.split('\n')?.filter { it.isNotBlank() }.orEmpty()
        set(v) = p.edit().putString("extra_save_dirs", v.distinct().joinToString("\n")).apply()

    /** [file]'s own save folder (the library menu's SAVE FOLDER); null = [SavesLocation.saveFor]'s search. */
    fun romSaveDir(file: java.io.File): String? = p.getString("romsavedir_${file.name}", null)
    fun setRomSaveDir(file: java.io.File, path: String?) = p.edit().apply {
        if (path.isNullOrBlank()) remove("romsavedir_${file.name}") else putString("romsavedir_${file.name}", path)
    }.apply()

    /** The player's own ROMs folder (first-time setup / Settings > Folders): its
     * supported games are listed in the library and played in place, and it is
     * rescanned for new ones whenever the library opens. Null = not linked. */
    var romsFolder: String?
        get() = p.getString("roms_folder", null)
        set(v) = p.edit().putString("roms_folder", v).apply()

    /** ROMs the player hid from the library (by path; the library menu's HIDE,
     * undone in Settings > HIDDEN GAMES). The file stays where it is, its name,
     * cover and saves too; a folder rescan just doesn't add it back. */
    var hiddenRoms: Set<String>
        get() = p.getString("hidden_roms", null)?.split("\n")?.filter { it.isNotBlank() }?.toSet() ?: emptySet()
        set(v) = p.edit().putString("hidden_roms", v.joinToString("\n")).apply()

    /** First-time setup finished or skipped; it only shows while this is false and the library is empty. */
    var setupDone: Boolean
        get() = p.getBoolean("setup_done", false)
        set(v) = p.edit().putBoolean("setup_done", v).apply()

    /** Settings' RUN SETUP: the library shows setup again the next time it comes back. */
    var setupRequested: Boolean
        get() = p.getBoolean("setup_requested", false)
        set(v) = p.edit().putBoolean("setup_requested", v).apply()

    /** A ThemeSpec.id from APP_THEMES (Theme.kt): 8 = PokéDaisy (the default, DAISY_THEME_ID - spelled out:
     * ui-preview's data module has no UI), 0 = FireRed (the default before it; kept for whoever picked it). */
    var appTheme: Int
        get() = p.getInt("app_theme", 8)
        set(v) = p.edit().putInt("app_theme", v).apply()

    /** User-supplied SteamGridDB API key (Settings > Cover Art) — see
     * [SteamGridDbClient]. Null/blank = cover-art fetching is off; nothing is
     * ever bundled in the APK, only fetched live and cached under
     * `files/covers/` once a key is set. */
    var steamGridDbApiKey: String?
        get() = p.getString("steamgriddb_api_key", null)
        set(v) = p.edit().putString("steamgriddb_api_key", v?.trim()?.takeIf { it.isNotEmpty() }).apply()

    /** The player's RetroAchievements *Web API* key (retroachievements.org > Settings - not
     * the sign-in token), entered in Settings > Cover Art: box art covers
     * ([RetroAchievements.gameBoxArtUrl]). Null = none. */
    var raWebApiKey: String?
        get() = p.getString("ra_web_api_key", null)
        set(v) = p.edit().putString("ra_web_api_key", v?.trim()?.takeIf { it.isNotEmpty() }).apply()

    private companion object {
        const val RECENT_CAP = 10   // more than the 3 shown, so deleted ROMs don't starve the row
    }
}
