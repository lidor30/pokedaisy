package com.pokedaisy.app

import android.content.Context
import com.pokedaisy.app.companion.FfMode
import com.pokedaisy.app.companion.GameAspect
import com.pokedaisy.app.companion.GridStrength
import com.pokedaisy.app.companion.ScreenFilter
import com.pokedaisy.app.companion.Smoothing
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
        p.edit().putString("recent_roms", updated.joinToString("\n")).putLong("played_$path", System.currentTimeMillis()).apply()
    }

    /** When the ROM at [path] was last started (the library's LAST PLAYED order); 0 = never, or before this was kept. */
    fun romLastPlayed(path: String): Long = p.getLong("played_$path", 0L)

    /** The library's order: a [LibrarySort] id (NAME, LAST PLAYED, DATE ADDED). */
    var librarySort: Int
        get() = p.getInt("library_sort", 0)   // LibrarySort.NAME
        set(v) = p.edit().putInt("library_sort", v).apply()

    /** The library's quick view: a [LibraryView] id (ALL, FAVORITES, RECENT, a console, HACKS). */
    var libraryView: Int
        get() = p.getInt("library_view", 0)   // LibraryView.ALL
        set(v) = p.edit().putInt("library_view", v).apply()

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

    /** SCENE SPEEDS (GitHub #43 / #85): a battle's / a field text box's / a menu screen's speed while fast-forward is
     * off; 0 = SAME (GAME SPEED's, like the field). Every game. */
    var battleSpeed: Float
        get() = p.getFloat("scene_speed_battle", 0f)
        set(v) = p.edit().putFloat("scene_speed_battle", v).apply()
    var textSpeed: Float
        get() = p.getFloat("scene_speed_text", 0f)
        set(v) = p.edit().putFloat("scene_speed_text", v).apply()
    var menuSpeed: Float
        get() = p.getFloat("scene_speed_menu", 0f)
        set(v) = p.edit().putFloat("scene_speed_menu", v).apply()

    /** SCENE SPEEDS' BIKE / SURF SPEED: riding a bike, surfing (0 = SAME). */
    var bikeSpeed: Float
        get() = p.getFloat("scene_speed_bike", 0f)
        set(v) = p.edit().putFloat("scene_speed_bike", v).apply()
    var surfSpeed: Float
        get() = p.getFloat("scene_speed_surf", 0f)
        set(v) = p.edit().putFloat("scene_speed_surf", v).apply()

    /** AUTO-RUN: B held for the player while walking on the field (holding it walks). */
    var autoRun: Boolean
        get() = p.getBoolean("auto_run", false)
        set(v) = p.edit().putBoolean("auto_run", v).apply()

    /** RUN INDOORS: every map lets the player run (gMapHeader.allowRunning). */
    var runIndoors: Boolean
        get() = p.getBoolean("run_indoors", false)
        set(v) = p.edit().putBoolean("run_indoors", v).apply()

    /** WILD ENCOUNTERS: the percent of wild encounters left - 100 = the game's own rate (ON), 0 = none (OFF). The
     * setting is ON / OFF now (the user's call): a 75 / 50 / 25 % saved before reads as ON, with nothing left to change it. */
    var wildEncounters: Int
        get() = if (p.getInt("wild_encounters", 100) == 0) 0 else 100
        set(v) = p.edit().putInt("wild_encounters", v).apply()

    /** REUSABLE TMS: a TM stays in the bag after it teaches a move, as an HM does (ReusableTms.kt). */
    var reusableTms: Boolean
        get() = p.getBoolean("reusable_tms", false)
        set(v) = p.edit().putBoolean("reusable_tms", v).apply()

    /** SHINY ODDS: a wild foe is shiny 1 in this many ([com.pokedaisy.app.companion.data.ShinyOdds]; NORMAL 8192 = the
     * game's own odds). Read through [com.pokedaisy.app.companion.data.ShinyOdds.choices]: a debug build's 1 in 4 / 1 in 1
     * count as NORMAL in a release build (PokeDaisyActivity.shinyOddsPref). */
    var shinyOdds: Int
        get() = p.getInt("shiny_odds", com.pokedaisy.app.companion.data.ShinyOdds.NORMAL)
        set(v) = p.edit().putInt("shiny_odds", v).apply()

    /** PLAYER LOOK per ROM (keyed by CRC32): the hero the player wears ([com.pokedaisy.app.companion.data.LookChar]'s
     * name), null = the game's own. */
    fun playerLookFor(romKey: String): String? = p.getString("player_look_$romKey", null)
    fun setPlayerLookFor(romKey: String, look: String?) = p.edit().putString("player_look_$romKey", look).apply()

    /** FOLLOWER: the party's lead Pokémon walks behind the player (every game it works in; off to start with). */
    var follower: Boolean
        get() = p.getBoolean("follower", false)
        set(v) = p.edit().putBoolean("follower", v).apply()

    /** DAY AND NIGHT: the overworld tinted by the device's clock (every GBA game; off to start with). */
    var dayNight: Boolean
        get() = p.getBoolean("day_night", false)
        set(v) = p.edit().putBoolean("day_night", v).apply()

    /** TEXT LOOK per ROM: the style worn (a LookEngine name, "FIRERED" / "EMERALD"), null = the game's own. */
    fun textLookFor(romKey: String): String? = p.getString("text_look_$romKey", null)
    fun setTextLookFor(romKey: String, style: String?) = p.edit().putString("text_look_$romKey", style).apply()

    /** RIVAL LOOK: the rival of the other gender wears the other hero of the player's look's game (every game). */
    var rivalLook: Boolean
        get() = p.getBoolean("rival_look", true)
        set(v) = p.edit().putBoolean("rival_look", v).apply()

    /** Remembered speed-cycle index per ROM (keyed by CRC32). */
    fun speedIndexFor(romKey: String): Int = p.getInt("speed_$romKey", 0)
    fun setSpeedIndexFor(romKey: String, index: Int) = p.edit().putInt("speed_$romKey", index).apply()

    /** The ZOOM step the player last picked on this ROM (WideCamera.level: 0 = 1x), put back when it's played again. */
    fun zoomLevelFor(romKey: String): Int = p.getInt("zoom_$romKey", 0)
    fun setZoomLevelFor(romKey: String, level: Int) = p.edit().putInt("zoom_$romKey", level).apply()

    /** The ITEMS tab's starred items per ROM (keyed by CRC32, like [speedIndexFor]): the game's item ids. */
    fun favoriteItemsFor(romKey: String): Set<Int> =
        p.getString("favorite_items_$romKey", null)?.split(",")?.mapNotNull { it.toIntOrNull() }?.toSet() ?: emptySet()
    fun setFavoriteItemsFor(romKey: String, ids: Set<Int>) =
        p.edit().putString("favorite_items_$romKey", ids.sorted().joinToString(",")).apply()

    /** SETTINGS > NUZLOCKE per ROM (keyed by CRC32), and its run ([com.pokedaisy.app.companion.data.NuzlockeRun.encode]). */
    fun nuzlockeFor(romKey: String): Boolean = p.getBoolean("nuzlocke_$romKey", false)
    fun setNuzlockeFor(romKey: String, on: Boolean) = p.edit().putBoolean("nuzlocke_$romKey", on).apply()
    fun nuzlockeRunFor(romKey: String): String? = p.getString("nuzlocke_run_$romKey", null)
    fun setNuzlockeRunFor(romKey: String, run: String) = p.edit().putString("nuzlocke_run_$romKey", run).apply()

    /** On-screen controls: 0 = auto (only if no gamepad), 1 = always, 2 = never. */
    var touchControlsMode: Int
        get() = p.getInt("touch_controls", 0)
        set(v) = p.edit().putInt("touch_controls", v).apply()

    /** SETTINGS > TOUCH CONTROLS: when they show ([touchControlsMode]), the layout, size, opacity, extras and the
     * player's own layouts per orientation ([com.pokedaisy.app.companion.TouchLayout.encode]). */
    var touchControlsStyle: com.pokedaisy.app.companion.TouchControlsStyle
        get() {
            val d = com.pokedaisy.app.companion.TouchControlsStyle()
            fun <T : Enum<T>> pick(all: Array<T>, key: String, default: T) = p.getString(key, null)?.let { n -> all.firstOrNull { it.name == n } } ?: default
            return com.pokedaisy.app.companion.TouchControlsStyle(
                show = com.pokedaisy.app.companion.TouchShow.entries.getOrElse(touchControlsMode) { d.show },
                preset = pick(com.pokedaisy.app.companion.TouchPreset.values(), "touch_preset", d.preset),
                size = pick(com.pokedaisy.app.companion.TouchSize.values(), "touch_size", d.size),
                position = pick(com.pokedaisy.app.companion.TouchPosition.values(), "touch_position", d.position),
                opacity = p.getInt("touch_opacity", d.opacity),
                extras = p.getString("touch_extras", null)?.let { s ->
                    s.split(',').mapNotNull { n -> com.pokedaisy.app.companion.TouchButton.values().firstOrNull { it.name == n && it.extra } }.toSet()
                } ?: d.extras,
                eightWay = p.getBoolean("touch_eight_way", d.eightWay),
                fadeIdle = p.getBoolean("touch_fade_idle", d.fadeIdle),
                vibration = p.getBoolean("touch_vibration", d.vibration),
                useCustom = p.getBoolean("touch_use_custom", d.useCustom),
                portrait = com.pokedaisy.app.companion.TouchLayout.decode(p.getString("touch_layout_portrait", null)),
                landscape = com.pokedaisy.app.companion.TouchLayout.decode(p.getString("touch_layout_landscape", null)),
            )
        }
        set(v) {
            touchControlsMode = v.show.ordinal
            p.edit()
                .putString("touch_preset", v.preset.name)
                .putString("touch_size", v.size.name)
                .putString("touch_position", v.position.name)
                .putInt("touch_opacity", v.opacity)
                .putString("touch_extras", v.extras.joinToString(",") { it.name })
                .putBoolean("touch_eight_way", v.eightWay)
                .putBoolean("touch_fade_idle", v.fadeIdle)
                .putBoolean("touch_vibration", v.vibration)
                .putBoolean("touch_use_custom", v.useCustom)
                .putString("touch_layout_portrait", v.portrait.encode())
                .putString("touch_layout_landscape", v.landscape.encode())
                .apply()
        }

    /** Whether battle-only strategic hints (move effectiveness chips, the
     * foe's Weak-to/Resists/Immune-to summary) are shown. On by default -
     * this is an opt-out for players who'd rather figure matchups out
     * themselves. Doesn't affect the Party tab's own matchup display (that's
     * dex info, not battle metagaming) or move Power (shown regardless). */
    var showHints: Boolean
        get() = p.getBoolean("show_hints", true)
        set(v) = p.edit().putBoolean("show_hints", v).apply()

    /** ONE-TAP MOVES: the battle controls' action menu shows the moves where FIGHT is, a tap
     * picking FIGHT and the move in one go. Off by default. */
    var oneTapMoves: Boolean
        get() = p.getBoolean("one_tap_moves", false)
        set(v) = p.edit().putBoolean("one_tap_moves", v).apply()

    /** Whether battle INFO offers the foe's IVs / EVs / nature (its STATS page).
     * Off by default - it's the one thing the game itself never tells you.
     * The party summary's own STATS page shows regardless. */
    var showFoeIvs: Boolean
        get() = p.getBoolean("show_foe_ivs", false)
        set(v) = p.edit().putBoolean("show_foe_ivs", v).apply()

    /** Whether battle INFO opens with FOE DETAILS (the foe's side widened, GitHub #34) -
     * its button's last state, kept across battles. */
    var foeDetails: Boolean
        get() = p.getBoolean("foe_details", false)
        set(v) = p.edit().putBoolean("foe_details", v).apply()

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

    /** SETTINGS > FPS / CPU: the game's frames per second and the app's CPU share over the game's corner. */
    var showPerformance: Boolean
        get() = p.getBoolean("show_performance", false)
        set(v) = p.edit().putBoolean("show_performance", v).apply()

    /** When each NEW-tagged feature was first seen (companion.NewFeatures), by id: "id:millis,id:millis". */
    var newFeatureSince: Map<String, Long>
        get() = p.getString("new_feature_since", null).orEmpty().split(',').mapNotNull { e ->
            val i = e.lastIndexOf(':')
            if (i <= 0) null else e.substring(i + 1).toLongOrNull()?.let { e.substring(0, i) to it }
        }.toMap()
        set(v) = p.edit().putString("new_feature_since", v.entries.joinToString(",") { "${it.key}:${it.value}" }).apply()

    /** TIPS (SETTINGS > APP, companion.FeatureTips): a card now and then about a feature the player hasn't found. */
    var tips: Boolean
        get() = p.getBoolean("tips", true)
        set(v) = p.edit().putBoolean("tips", v).apply()

    /** What companion.TipScheduler remembers: field play time, when the last card went, each tip's history. */
    val tipStore: com.pokedaisy.app.companion.TipStore = object : com.pokedaisy.app.companion.TipStore {
        override var fieldMillis: Long
            get() = p.getLong("tip_field_ms", 0)
            set(v) = p.edit().putLong("tip_field_ms", v).apply()
        override var lastClosedAt: Long
            get() = p.getLong("tip_last_closed", -1)
            set(v) = p.edit().putLong("tip_last_closed", v).apply()
        override var records: Map<com.pokedaisy.app.companion.TipId, com.pokedaisy.app.companion.TipRecord>
            // "key:shows:lastShownAt:done" per tip.
            get() = p.getString("tip_records", null).orEmpty().split(',').mapNotNull { e ->
                val f = e.split(':')
                val id = com.pokedaisy.app.companion.TipId.of(f[0]) ?: return@mapNotNull null
                if (f.size != 4) return@mapNotNull null
                id to com.pokedaisy.app.companion.TipRecord(f[1].toIntOrNull() ?: 0, f[2].toLongOrNull() ?: -1, f[3] == "1")
            }.toMap()
            set(v) = p.edit().putString(
                "tip_records",
                v.entries.joinToString(",") { (id, r) -> "${id.key}:${r.shows}:${r.lastShownAt}:${if (r.done) 1 else 0}" },
            ).apply()
    }

    /** BATTLE BUTTONS (companion.ui.BattleButtons): one screen, FIGHT, POKéMON and RUN beside a battle. */
    var battleButtons: Boolean
        get() = p.getBoolean("battle_buttons", true)
        set(v) = p.edit().putBoolean("battle_buttons", v).apply()

    /** FIELD HUD (companion.ui.FieldHud): the party, minimap, Poké Balls, BAG and WILD buttons over the overworld. */
    var fieldHud: Boolean
        get() = p.getBoolean("field_hud", true)
        set(v) = p.edit().putBoolean("field_hud", v).apply()

    /** The QUICK MENU's actions the player keeps (SETTINGS > QUICK MENU), by name; every one by default. */
    var quickMenuActions: Set<com.pokedaisy.app.companion.QuickAction>
        // Stored as the ones switched off, so an action added later shows by itself.
        get() {
            val off = p.getString("quick_menu_hidden", null)?.split(',').orEmpty()
            return com.pokedaisy.app.companion.QuickAction.entries.filter { it.name !in off }.toSet()
        }
        set(v) = p.edit().putString("quick_menu_hidden", com.pokedaisy.app.companion.QuickAction.entries.filter { it !in v }.joinToString(",") { it.name }).apply()

    /** QUICK MENU > STYLE: RING (default) / GRID; anything else stored (an AUTO from a test build) reads as RING. */
    var quickMenuStyle: com.pokedaisy.app.companion.QuickMenuStyle
        get() = p.getString("quick_menu_style", null)?.let { n -> com.pokedaisy.app.companion.QuickMenuStyle.entries.firstOrNull { it.name == n } }
            ?: com.pokedaisy.app.companion.QuickMenuStyle.RING
        set(v) = p.edit().putString("quick_menu_style", v.name).apply()

    /** QUICK MENU > LABELS: each action's name under its icon (ON) or icons alone. */
    var quickMenuLabels: Boolean
        get() = p.getBoolean("quick_menu_labels", true)
        set(v) = p.edit().putBoolean("quick_menu_labels", v).apply()

    /** WILD ENCOUNTERS as it was before the QUICK MENU turned it OFF, to put back. */
    var wildEncountersBeforeOff: Int
        get() = p.getInt("wild_encounters_before_off", 100)
        set(v) = p.edit().putInt("wild_encounters_before_off", v).apply()

    /** The FIELD HUD's LAYOUT / LOOK / SIZE and the parts switched off (SETTINGS > HUD), by name. */
    var fieldHudStyle: com.pokedaisy.app.companion.FieldHudStyle
        get() {
            fun <T : Enum<T>> pick(all: Array<T>, key: String, default: T) = p.getString(key, null)?.let { n -> all.firstOrNull { it.name == n } } ?: default
            val d = com.pokedaisy.app.companion.FieldHudStyle()
            return com.pokedaisy.app.companion.FieldHudStyle(
                layout = pick(com.pokedaisy.app.companion.FieldHudLayout.values(), "field_hud_layout", d.layout),
                look = pick(com.pokedaisy.app.companion.FieldHudLook.values(), "field_hud_look", d.look),
                size = pick(com.pokedaisy.app.companion.FieldHudSize.values(), "field_hud_size", d.size),
                hidden = p.getString("field_hud_hidden", null).orEmpty().split(',')
                    .mapNotNull { n -> com.pokedaisy.app.companion.FieldHudPart.values().firstOrNull { it.name == n } }.toSet(),
            )
        }
        set(v) = p.edit()
            .putString("field_hud_layout", v.layout.name)
            .putString("field_hud_look", v.look.name)
            .putString("field_hud_size", v.size.name)
            .putString("field_hud_hidden", v.hidden.joinToString(",") { it.name })
            .apply()

    /** Whether the game has a status bar on top (game, location, money, clock, battery). */
    var statusBar: Boolean
        get() = p.getBoolean("status_bar", false)
        set(v) = p.edit().putBoolean("status_bar", v).apply()

    /** The status bar over the companion's tabs instead of over the game (CompanionStatusBar). */
    var statusBarOnCompanion: Boolean
        get() = p.getBoolean("status_bar_on_companion", false)
        set(v) = p.edit().putBoolean("status_bar_on_companion", v).apply()

    /** How the game sits on the top screen: the GBA's 3:2, stretched to fill it, or WIDE (more of the
     * map at the sides). Before WIDE this was a boolean, "stretch_game", which still picks STRETCH. */
    var gameAspect: GameAspect
        get() = GameAspect.entries.firstOrNull { it.name == p.getString("game_aspect", null) }
            ?: if (p.getBoolean("stretch_game", false)) GameAspect.STRETCH else GameAspect.ORIGINAL
        set(v) = p.edit().putString("game_aspect", v.name).remove("stretch_game").apply()

    /** SCREEN > DS RESOLUTION: a DS game's 3D at this many times the DS's own (2-8: the GPU renderer,
     * nds/pk_nds_gl.h) or the DS's own on the CPU (1, the default: melonDS's software renderer, pixel for pixel). */
    var dsResolution: Int
        get() = p.getInt("ds_resolution", 1).coerceIn(1, DS_RESOLUTION_MAX)
        set(v) = p.edit().putInt("ds_resolution", v.coerceIn(1, DS_RESOLUTION_MAX)).apply()

    /** EXPAND > AUTO ZOOM IN: whether a conversation, a sign, a menu or a picture over the field pulls the zoomed-out
     * view in to 1x (battles always do). On by default. */
    var expandZoomOnText: Boolean
        get() = p.getBoolean("expand_zoom_on_text", true)
        set(v) = p.edit().putBoolean("expand_zoom_on_text", v).apply()

    /** SCREEN > OVERLAY: the frame around the game, an [com.pokedaisy.app.overlay.OverlayChoice.key] - "" (none, the
     * default), a built-in frame ("builtin:DAISY") or an imported overlay ("user:<its folder>"). */
    var overlay: String
        get() = p.getString("overlay", null) ?: ""
        set(v) = p.edit().putString("overlay", v).apply()

    /** Whether the game's colours are shown as the GBA's own LCD showed them ([ScreenShaders.GBA_COLOR]). */
    var gbaColors: Boolean
        get() = p.getBoolean("gba_colors", false)
        set(v) = p.edit().putBoolean("gba_colors", v).apply()

    /** SHADERS > UNLIT SCREEN: the GBA's screen with no light of its own - white is its backing ([ScreenShaders.UNLIT]). */
    var unlitScreen: Boolean
        get() = p.getBoolean("unlit_screen", false)
        set(v) = p.edit().putBoolean("unlit_screen", v).apply()

    /** The screen effect over the game: LCD grid, scanlines or CRT ([ScreenShaders.effectFor]). */
    var screenFilter: ScreenFilter
        get() = ScreenFilter.entries.firstOrNull { it.name == p.getString("screen_filter", null) } ?: ScreenFilter.NONE
        set(v) = p.edit().putString("screen_filter", v.name).apply()

    /** DISPLAY OPTIONS > LIGHT COMPANION: AUTO / ON / OFF ([com.pokedaisy.app.companion.LightCompanionMode.id]). */
    var lightCompanion: Int
        get() = p.getInt("light_companion", 0)
        set(v) = p.edit().putInt("light_companion", v).apply()

    /** LIGHT COMPANION's AUTO saw this device's buttons come late ([com.pokedaisy.app.companion.LightCompanion.noteKeyDelay]):
     * it starts light from then on. A new key: the old one was also set by the volume keys, which Android always holds back
     * (a fast device read as slow). */
    var lightCompanionMeasured: Boolean
        get() = p.getBoolean("light_companion_measured_v2", false)
        set(v) = p.edit().putBoolean("light_companion_measured_v2", v).apply()

    /** Whether SHADERS (filter and GBA COLORS) also draw over the companion and status bar ([CompanionColors]). */
    var companionShaders: Boolean
        get() = p.getBoolean("companion_shaders", true)
        set(v) = p.edit().putBoolean("companion_shaders", v).apply()

    /** LCD / LCD PAPER's grid strength ([ScreenShaders.gridFor]). */
    var gridStrength: GridStrength
        get() = GridStrength.entries.firstOrNull { it.name == p.getString("grid_strength", null) } ?: GridStrength.MEDIUM
        set(v) = p.edit().putString("grid_strength", v.name).apply()

    /** SHADERS > SMOOTHING: xBRZ / xBR / ScaleFX over the game ([ScreenShaders.smoothingPasses]). */
    var smoothing: Smoothing
        get() = Smoothing.entries.firstOrNull { it.name == p.getString("smoothing", null) } ?: Smoothing.OFF
        set(v) = p.edit().putString("smoothing", v.name).apply()

    /** Two screens: the game on the second display and the companion on the main one -
     * for a device whose main display is its bottom screen (see PokeDaisyActivity.syncPresentation). */
    var swapScreens: Boolean
        get() = p.getBoolean("swap_screens", false)
        set(v) = p.edit().putBoolean("swap_screens", v).apply()

    /** SWAP SCREENS while the second screen is an external one (a TV over HDMI or cast - [Screens.isExternal]): its own
     * setting, on by default - the game on the TV, the companion in hand (GitHub #138: Smart View on a Galaxy Flip put
     * the companion on the TV). Kept apart, so a Thor's choice and a TV's don't overwrite each other. */
    var swapScreensExternal: Boolean
        get() = p.getBoolean("swap_screens_external", true)
        set(v) = p.edit().putBoolean("swap_screens_external", v).apply()

    /** Two screens: play on one anyway - the second is left free for other apps and the companion opens over the
     * game, as on a one-screen device (see PokeDaisyActivity.syncPresentation). A player's request: multitask
     * without closing PokéDaisy. */
    var singleScreen: Boolean
        get() = p.getBoolean("single_screen", false)
        set(v) = p.edit().putBoolean("single_screen", v).apply()

    /** A DS game on two screens: the bottom display shows the companion instead of the DS touch screen (its own
     * one button under both, COMPANION / GAME SCREEN, or BACK switch back and forth; kept). */
    var dsBottomCompanion: Boolean
        get() = p.getBoolean("ds_bottom_companion", false)
        set(v) = p.edit().putBoolean("ds_bottom_companion", v).apply()

    /** Single-screen devices: the companion's side panel is locked beside the game (see [SidePanel]). */
    var sidePanelDocked: Boolean
        get() = p.getBoolean("side_panel_docked", false)
        set(v) = p.edit().putBoolean("side_panel_docked", v).apply()

    /** Single-screen devices: the side panel's width, as a fraction of the screen's (see [SidePanel]). */
    var sidePanelWidth: Float
        get() = p.getFloat("side_panel_width", 0.5f)
        set(v) = p.edit().putFloat("side_panel_width", v).apply()

    /** One screen held sideways: the companion opens as a window in the middle, over the game (true), or in the side
     * panel on the right (SETTINGS > COMPANION VIEW; see [SidePanel]). */
    var companionWindow: Boolean
        get() = p.getBoolean("companion_window", true)
        set(v) = p.edit().putBoolean("companion_window", v).apply()

    /** One screen held sideways: the white tab at the bottom of the screen's right edge that opens the companion (SETTINGS >
     * COMPANION BUTTON). Off, BACK and the HUD's COMPANION button still open it. */
    var companionTab: Boolean
        get() = p.getBoolean("companion_tab", true)
        set(v) = p.edit().putBoolean("companion_tab", v).apply()

    /** A phone held upright: the companion right under the game, the touch controls at the screen's bottom, instead of
     * the companion along the bottom (SETTINGS > COMPANION; see [PortraitPanel]). A DS game's touch screen takes the
     * companion's place. On by default since 1.3.2 (the user's call): only a player who picked AT BOTTOM keeps it. */
    var portraitCompanionUnderGame: Boolean
        get() = p.getBoolean("portrait_companion_under_game", true)
        set(v) = p.edit().putBoolean("portrait_companion_under_game", v).apply()

    /** A phone held upright in a DS game: the touch screen's slot (where the companion goes) as a ratio of the screen's
     * width, kept apart from [portraitCompanionRatio]. 0.76: the DS screen (4:3) over its COMPANION switch, with the touch
     * controls still getting the space below on a 18.5:9 phone (PortraitLayout.padInGap; 0.8 missed it by a few px). */
    var portraitDsRatio: Float
        get() = p.getFloat("portrait_ds_ratio", 0.76f)
        set(v) = p.edit().putFloat("portrait_ds_ratio", v).apply()

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

    /** The player's own ROMs folders (first-time setup, the library's +, Settings > FOLDERS > ROMS FOLDERS): their
     * supported games are listed in the library and played in place, and they are rescanned for new ones whenever
     * the library opens. Several, so a player can link just `gba`, `gbc`, `nds` instead of a whole frontend tree,
     * which takes long to look through. Before there could be more than one it was `roms_folder`, read once here. */
    var romsFolders: List<String>
        get() = p.getString("roms_folders", null)?.split('\n')?.filter { it.isNotBlank() }
            ?: listOfNotNull(p.getString("roms_folder", null))
        set(v) = p.edit().putString("roms_folders", v.distinct().joinToString("\n")).remove("roms_folder").apply()

    /** ROMs the player hid from the library (by path; the library menu's HIDE,
     * undone in Settings > HIDDEN GAMES). The file stays where it is, its name,
     * cover and saves too; a folder rescan just doesn't add it back. */
    var hiddenRoms: Set<String>
        get() = p.getString("hidden_roms", null)?.split("\n")?.filter { it.isNotBlank() }?.toSet() ?: emptySet()
        set(v) = p.edit().putString("hidden_roms", v.joinToString("\n")).apply()

    /** The player's favorite games (by path): first in the library whatever its order, a star on each. */
    var favoriteRoms: Set<String>
        get() = p.getString("favorite_roms", null)?.split("\n")?.filter { it.isNotBlank() }?.toSet() ?: emptySet()
        set(v) = p.edit().putString("favorite_roms", v.joinToString("\n")).apply()

    /** Crash reports (CrashReports.kt): off until the player opts in. */
    var crashReportsEnabled: Boolean
        get() = p.getBoolean("crash_reports_enabled", false)
        set(v) = p.edit().putBoolean("crash_reports_enabled", v).apply()

    /** The one-time "send crash reports?" ask was shown (answered or not) - never again. */
    var crashReportsAsked: Boolean
        get() = p.getBoolean("crash_reports_asked", false)
        set(v) = p.edit().putBoolean("crash_reports_asked", v).apply()

    /** App opens (process starts) so far - the ask waits for the third. */
    var appOpenCount: Int
        get() = p.getInt("app_open_count", 0)
        set(v) = p.edit().putInt("app_open_count", v).apply()

    /** First-time setup finished or skipped; it only shows while this is false and the library is empty. */
    /** The version whose WHAT'S NEW ([com.pokedaisy.app.companion.WhatsNew]) the Library last showed, or that was
     *  installed fresh (nothing to tell then); null before any. */
    var whatsNewSeen: String?
        get() = p.getString("whats_new_seen", null)
        set(v) = p.edit().putString("whats_new_seen", v).apply()

    /** How long games have run in front, not asleep, on this device (ms): only for the support window's timing. */
    var playMillis: Long
        get() = p.getLong("play_millis", 0L)
        set(v) = p.edit().putLong("play_millis", v).apply()

    /** [playMillis] at which the support window ([com.pokedaisy.app.companion.SupportAsk]) is due; REMIND ME LATER moves it. */
    var supportAskAt: Long
        get() = p.getLong("support_ask_at", com.pokedaisy.app.companion.SupportAsk.FIRST_ASK)
        set(v) = p.edit().putLong("support_ask_at", v).apply()

    /** The support window was answered for good: NO THANKS, or a site picked. */
    var supportAskDone: Boolean
        get() = p.getBoolean("support_ask_done", false)
        set(v) = p.edit().putBoolean("support_ask_done", v).apply()

    /** The Library's one-time EXPAND introduction ([com.pokedaisy.app.companion.ui.ExpandIntro]) has been answered. */
    var expandIntroSeen: Boolean
        get() = p.getBoolean("expand_intro_seen", false)
        set(v) = p.edit().putBoolean("expand_intro_seen", v).apply()

    /** EXPAND's one-time offer in a game it knows (the companion's ExpandIntro) has been answered. */
    var expandGameOfferSeen: Boolean
        get() = p.getBoolean("expand_game_offer_seen", false)
        set(v) = p.edit().putBoolean("expand_game_offer_seen", v).apply()

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

    /** SETTINGS > DARK MODE: 0 off (the default: the OPTION screen's white windows), 1 on, 2 AUTO (the device's
     * own light / dark setting). See DarkMode (companion/ui/theme/DarkMode.kt). */
    var darkMode: Int
        get() = p.getInt("dark_mode", 0)
        set(v) = p.edit().putInt("dark_mode", v).apply()

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

    /** The player's screenscraper.fr account (Settings > ScreenScraper, setup, the update's popup;
     * read as `screenScraperAccount`, ScreenScraperClient.kt). Its API has no login token, so the
     * password is kept here (private prefs) and sent with each call; never logged. */
    var screenScraperUser: String?
        get() = p.getString("ss_user", null)
        set(v) = p.edit().putString("ss_user", v?.trim()?.takeIf { it.isNotEmpty() }).apply()
    var screenScraperPassword: String?
        get() = p.getString("ss_password", null)
        set(v) = p.edit().putString("ss_password", v?.takeIf { it.isNotEmpty() }).apply()

    /** ScreenScraper also downloads each game's video (a few MB each) for the launcher's media screen. */
    var screenScraperVideos: Boolean
        get() = p.getBoolean("ss_videos", true)
        set(v) = p.edit().putBoolean("ss_videos", v).apply()

    /** The update's "set up ScreenScraper?" popup has been shown (whatever the answer): only once. */
    var screenScraperAsked: Boolean
        get() = p.getBoolean("ss_asked", false)
        set(v) = p.edit().putBoolean("ss_asked", v).apply()

    /** With a second screen, the library is the launcher: the game list on one screen, the focused
     * game's media on the other ([LauncherScreen]). Off = the classic library on the main screen. */
    var dualScreenLauncher: Boolean
        get() = p.getBoolean("dual_screen_launcher", true)
        set(v) = p.edit().putBoolean("dual_screen_launcher", v).apply()

    /** The launcher's X: the game list on the second screen and the media on the main one. */
    var launcherSwapped: Boolean
        get() = p.getBoolean("launcher_swapped", false)
        set(v) = p.edit().putBoolean("launcher_swapped", v).apply()

    private companion object {
        const val RECENT_CAP = 10   // more than the 3 shown, so deleted ROMs don't starve the row
    }
}

/** DS RESOLUTION's highest scale ([Prefs.dsResolution]). */
const val DS_RESOLUTION_MAX = 8
