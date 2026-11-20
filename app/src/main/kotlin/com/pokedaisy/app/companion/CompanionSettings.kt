package com.pokedaisy.app.companion

import com.pokedaisy.app.companion.data.fixed
import com.pokedaisy.app.GbaControls
import com.pokedaisy.app.Hotkeys
import com.pokedaisy.app.companion.i18n.tk

/**
 * Bridge for the bottom-screen SETTINGS tab: lets the player tweak settings
 * from Screen-2 without leaving the game. Implemented by PokeDaisyActivity,
 * which also live-applies changes to the running
 * [com.pokedaisy.app.EmulatorEngine] / touch-controls visibility,
 * not just persisting them to [com.pokedaisy.app.Prefs].
 *
 * Key/button rebinding is by pressing the keys, never by picking a key's name: the Presentation on Screen-2
 * never receives physical `KeyEvent`s (they go to the game's Activity), so the capture runs there
 * ([keyCapture]) and the game's screen asks for the keys while a row here waits.
 */
interface CompanionSettings : StoredCompanionSettings {
    /** Application settings can use the shared pages without a running game or game-only actions. */
    val hasRunningGame: Boolean get() = true
    /** Optional NEW tag when a host first gains the field HUD. */
    val fieldHudBadge: String? get() = null
    /** Optional NEW tag when a host first gains state slots. */
    val statesBadge: String? get() = null

    /** Optional NEW tag when a host first gains playback controls. */
    val playbackBadge: String? get() = null


    /** GAME SPEED: the running game's speed step (1x normal; slower or faster until changed), kept per game. */
    val gameSpeed: Float get() = 1f
    val gameSpeeds: List<Float> get() = PlaybackPolicy.SPEED_STEPS.sorted()
    fun setGameSpeed(v: Float) {}

    /** SCENE SPEEDS (GitHub #43 / #85): a battle's, a field text box's and a menu screen's speed while FF is off;
     * 0 = SAME (GAME SPEED's) - see [com.pokedaisy.app.Prefs.battleSpeed]. */
    val speedOptionsBadge: String? get() = null
    val ffMusicModes: List<FfMusicMode> get() = FfMusicMode.entries
    val fieldMovesReadable: Boolean get() = false

    /** Whether the running game's field can be read for TEXT SPEED and AUTO-RUN (WIDE's tables know it). */
    val fieldReadable: Boolean get() = false

    /** AUTO-RUN: B held while walking (holding it walks) - see [com.pokedaisy.app.Prefs.autoRun]. */
    val fieldOptionsBadge: String? get() = null
    /** The game has its own, on by default ([com.pokedaisy.app.companion.data.GAMES_WITH_OWN_AUTO_RUN]): the row says so. */
    val autoRunBuiltIn: Boolean get() = false

    val runIndoorsSupported: Boolean get() = false

    val wildEncountersSupported: Boolean get() = false

    /** FLY STYLE - see [com.pokedaisy.app.Prefs.flyStyleId]; [flyStyleSupported]: this game has FREE FLIGHT (Flight.kt). */
    val flyStyle: FlyStyle get() = FlyStyle.CLASSIC
    fun setFlyStyle(style: FlyStyle) {}
    val flyStyleSupported: Boolean get() = false
    /** FLIGHT AREA (where [flyStyleSupported]) - see [com.pokedaisy.app.Prefs.flightAreaId]. */
    val flightArea: FlightAreaMode get() = FlightAreaMode.SEEN
    fun setFlightArea(mode: FlightAreaMode) {}
    /** MAKEOVER > FLY ON (where [flyStyleSupported]): the bird, or a vehicle with the player in it. */
    val flightVehicle: com.pokedaisy.app.companion.data.FlightVehicle get() = com.pokedaisy.app.companion.data.FlightVehicle.BIRD
    fun setFlightVehicle(v: com.pokedaisy.app.companion.data.FlightVehicle) {}
    /** The first scenic FLY's question (ui.FlyStyleAsk) answered: CLASSIC switches, SCENIC / null (it timed out) keeps it. */
    fun answerFlyStyle(style: FlyStyle?) {}
    /** BOSS RETRY's card answered: true = back to the lost battle's start ([com.pokedaisy.app.companion.ui.BattleRetryAsk]). */
    fun answerBattleRetry(retry: Boolean) {}

    /** REUSABLE TMS - see [com.pokedaisy.app.Prefs.reusableTms]; [reusableTmsSupported]: the ROM's code that uses a TM
     * up is known ([com.pokedaisy.app.companion.data.ReusableTms]). */
    val gameOptionsBadge: String? get() = null
    val reusableTmsSupported: Boolean get() = false
    /** The game's own TMs never run out ([com.pokedaisy.app.companion.data.ReusableTms.BUILT_IN]): the row says so. */
    val reusableTmsBuiltIn: Boolean get() = false
    /** EXP SHARE - see [com.pokedaisy.app.Prefs.expShare]; [expShareSupported]: the game's getexp is known
     * ([com.pokedaisy.app.companion.data.ExpShare]). */
    val expShareSupported: Boolean get() = false
    /** LEVEL CAP - see [com.pokedaisy.app.Prefs.levelCap]; [levelCapSupported]: the game's getexp is known
     * ([com.pokedaisy.app.companion.data.LevelCap]); [levelCapLevel]: the cap now (null = none worked out yet, or no
     * boss left). */
    val levelCapSupported: Boolean get() = false
    val levelCapLevel: Int? get() = null
    /** BOSS RETRY - see [com.pokedaisy.app.Prefs.battleRetry]. */
    val battleRetry: Boolean get() = false
    fun setBattleRetry(on: Boolean) {}
    /** SHINY ALERT - see [com.pokedaisy.app.Prefs.shinyAlert]; [shinyHuntCount]: this game's wild battles since the last
     * shiny (or since it was turned on). */
    val shinyAlert: Boolean get() = false
    fun setShinyAlert(on: Boolean) {}
    val shinyHuntCount: Int get() = 0
    /** Battles read a foe's shininess here (the games SHINY ODDS works in). */
    val shinyAlertSupported: Boolean get() = false
    /** LOW HP BEEP / FIELD POISON - see [com.pokedaisy.app.Prefs.lowHpBeep]; supported = the ROM's code is known
     * ([com.pokedaisy.app.companion.data.QuietPatches]). */
    val lowHpBeep: Boolean get() = true
    fun setLowHpBeep(on: Boolean) {}
    val lowHpBeepSupported: Boolean get() = false
    val fieldPoison: Boolean get() = true
    fun setFieldPoison(on: Boolean) {}
    val fieldPoisonSupported: Boolean get() = false
    /** RUNNING SHOES (a Game Boy game): B held while walking moves at the bike's speed. Off by default. */
    val runningShoes: Boolean get() = false
    fun setRunningShoes(on: Boolean) {}
    val runningShoesSupported: Boolean get() = false
    /** AUTO REFILL - see [com.pokedaisy.app.Prefs.repelRefill]; [repelRefillSupported]: the game can take a REPEL from the
     * companion (the quick menu's REPEL). */
    val repelRefill: Boolean get() = false
    fun setRepelRefill(on: Boolean) {}
    val repelRefillSupported: Boolean get() = false
    /** NO HMS NEEDED - see [com.pokedaisy.app.Prefs.hmFree]; [hmFreeSupported]: the ROM's field move check is known
     * ([com.pokedaisy.app.companion.data.HmFree]). */
    val hmFreeSupported: Boolean get() = false
    val instantTextSupported: Boolean get() = false

    val tradeEvolutionsSupported: Boolean get() = false

    /** DS RESOLUTION ([com.pokedaisy.app.Prefs.dsResolution]): the scale, null when the game isn't a DS one (no row);
     * [dsGpuAvailable] false = this device can't run the GPU renderer (no OpenGL ES 3.2), so the DS's own it is. */
    val dsResolution: Int? get() = null
    val dsGpuAvailable: Boolean get() = true
    fun setDsResolution(scale: Int) {}

    /** DS CORE ([com.pokedaisy.app.Prefs.dsCore]): null when the game isn't a DS one (no row); taken at the game's next
     * start. [dsMelonDs]: the game runs on melonDS - on DSperate DS RESOLUTION and DS CPU BOOST are greyed out. */
    val dsCore: DsCore? get() = null
    fun setDsCore(core: DsCore) {}
    val dsMelonDs: Boolean get() = true
    /** The DS core the game runs on (DsCore.CORE_*). */
    val dsCoreRunning: Int get() = DsCore.CORE_MELONDS

    /** DS CPU BOOST ([com.pokedaisy.app.Prefs.dsCpuBoost]): null when the game isn't a DS one (no row). */
    val dsCpuBoost: Boolean? get() = null
    fun setDsCpuBoost(on: Boolean) {}

    /** DS TOUCH SCREEN ([com.pokedaisy.app.Prefs.dsTouchFill]): null when there's no row (not a DS game on two screens). */
    val dsTouchFill: Boolean? get() = null
    fun setDsTouchFill(on: Boolean) {}

    /** DS LAYOUT ([com.pokedaisy.app.Prefs.dsLayout]): null when there's no row (not a DS game, or one on two screens). */
    val dsLayout: DsLayout? get() = null
    fun setDsLayout(layout: DsLayout) {}

    /** NUZLOCKE ([Nuzlocke]): [nuzlockeSupported] = a game whose party and battles are read (GBA, Game Boy, DS); the switch is per game. */
    val nuzlockeSupported: Boolean get() = false
    val nuzlocke: Boolean get() = false
    fun setNuzlocke(on: Boolean) {}
    /** SHINY ODDS: a wild foe is shiny 1 in [shinyOdds] - see [com.pokedaisy.app.Prefs.shinyOdds]; [shinyOddsChoices] the
     * pick-list's (a debug build's has 1 in 4 and 1 in 1); [shinyOddsSupported]: a version the Poller knows by
     * its hash ([com.pokedaisy.app.companion.data.ShinyOdds.targetFor]). */
    val shinyOdds: Int get() = com.pokedaisy.app.companion.data.ShinyOdds.NORMAL
    fun setShinyOdds(odds: Int) {}
    val shinyOddsChoices: List<Int> get() = com.pokedaisy.app.companion.data.ShinyOdds.CHOICES
    val shinyOddsSupported: Boolean get() = false

    /** PLAYER LOOK ([com.pokedaisy.app.companion.data.PlayerLook]): [playerLookSupported] = a FireRed / LeafGreen /
     * Emerald game; [playerLooks] = the heroes it can wear now (another game's, kept from the player's own ROMs);
     * [playerLook] = the one worn, by name (null = the game's own). */
    val playerLookSupported: Boolean get() = false
    val playerLooks: List<String> get() = emptyList()
    val playerLook: String? get() = null
    /** The player's own hero here, by name (the picker's OFF tile); null before the save has a gender. */
    val playerLookOwn: String? get() = null
    fun setPlayerLook(look: String?) {}
    /** A PLAYER LOOK pick's name as the row and its tile show it (a hero's, or a Pokémon's in the game). */
    fun playerLookLabel(name: String): String = com.pokedaisy.app.companion.data.LookChar.byName(name)?.label ?: name
    /** MAKEOVER > RIDE ON (where [playerLookSupported]): the game's bike, or one of our vehicles in its place. */
    val rideVehicle: com.pokedaisy.app.companion.data.RideVehicle get() = com.pokedaisy.app.companion.data.RideVehicle.BIKE
    fun setRideVehicle(v: com.pokedaisy.app.companion.data.RideVehicle) {}
    /** RIVAL LOOK: the rival of the other gender ([rivalLookSupported]: Emerald, Ruby, Sapphire) dressed up with the player. */
    val rivalLookSupported: Boolean get() = false
    /** FOLLOWER: the party's lead walks behind the player ([followerSupported]: a GBA game it's drawn in; [followerReady]:
     * Pokémon sprites kept, from Heart and Soul). */
    val followerSupported: Boolean get() = false
    val followerReady: Boolean get() = false
    /** DAY AND NIGHT ([com.pokedaisy.app.companion.data.DayNight]): the overworld tinted by the clock. */
    val dayNightSupported: Boolean get() = false
    /** TEXT LOOK: the other games' text looks kept ([textLooks], "FIRERED" / "EMERALD"), the one on ([textLook], null =
     * the game's own). */
    val textLooks: List<String> get() = emptyList()
    val textLook: String? get() = null
    fun setTextLook(style: String?) {}




    val rewindBadge: String? get() = null
    /** Whether the running game's core can rewind (not a DS game: its states are megabytes each). */
    val rewindSupported: Boolean get() = true

    /** Controls and skin import supported by this host. Stored choices for other hosts are kept. */
    val touchButtons: Set<TouchButton> get() = TouchButton.entries.toSet()
    val touchXyBadge: String? get() = null
    val touchSkinsSupported: Boolean get() = true
    val touchBadge: String? get() = NewFeatures.badge(NewFeatures.TOUCH_CONTROLS)

    /** A host has newly enabled ROM-detection recovery through SETTINGS > PLAYS AS. */
    val gamePickBadge: String? get() = null

    /** TOUCH CONTROLS > SKINS: the imported Delta skins (picked here, imported on the top screen). */
    val touchSkins: List<InstalledSkin> get() = emptyList()

    /** The game's screen as the touch controls see it, for TOUCH CONTROLS' preview; null = laid out like a phone's. */
    val touchStage: TouchStage? get() = null

    /** A host with its own one-window layout supplies the same layout for the rotated preview too. */
    val touchStageInOtherOrientation: TouchStage? get() = null

    /** The newest savestate's picture for the preview (null: a drawn stand-in). */
    fun touchPreviewShot(): androidx.compose.ui.graphics.ImageBitmap? = null

    /** EDIT LAYOUT over the running game (the companion steps aside while it's open). */
    fun editTouchLayout() {}

    /** The touch controls are on the game's screen now (SHOW, a controller): EXPAND's UNDER GAME needs them. */
    val touchControlsShown: Boolean get() = true

    /** Bumped when EDIT LAYOUT saves (Compose state): TOUCH CONTROLS' page reads it to show CUSTOM. */
    val touchRevision: Int get() = 0

    /** Waits for the keys to bind (the game's activity gets every key); null where nothing can be pressed (previews). */
    val keyCapture: KeyCapture? get() = null

    /** Platform introduction badge for GAME BUTTONS and its binding rows. */
    val gameButtonsBadge: String? get() = null

    /** Current GBA-button bindings, by display name (e.g. "BUTTON_A", "Z"). */
    fun gbaControlBindings(): Map<GbaControls.Btn, List<String>>

    /** Rebinds a GBA button to a single key, by display name (no "KEYCODE_" prefix). */
    fun setGbaControlBinding(btn: GbaControls.Btn, keyName: String)

    /** Leaves TURBO A / B and the GBA / Game Boy rows unbound ([GbaControls.Btn.canClear]). */
    fun clearGbaControlBinding(btn: GbaControls.Btn) {}

    /** GAME BUTTONS' RESET TO DEFAULTS ([GbaControls.resetBindings]). */
    fun resetGbaControls() {}

    /** HOTKEYS' RESET TO DEFAULTS ([com.pokedaisy.app.Hotkeys.resetBindings]). */
    fun resetHotkeys() {}

    /** GAME BUTTONS' rows for the game running besides every game's ([GbaControls.Group.DS] or [GbaControls.Group.GBA]);
     * null = both groups (previews). */
    val buttonGroup: GbaControls.Group? get() = null


    /** Available actions and platform introduction badge. Hidden actions cannot take keys from gameplay. */
    val hotkeyActions: List<Hotkeys.Action> get() = Hotkeys.Action.entries
    val hotkeysBadge: String? get() = null

    /** Current hotkey bindings, by display name. */
    fun hotkeyBindings(): Map<Hotkeys.Action, List<String>>

    /** Rebinds a hotkey to one chord, by display names (pressed together) — replaces what it had; null unbinds it.
     * The chord comes off [takeFrom] (other hotkeys that had exactly it, moved by the player's MOVE). */
    fun setHotkeyBinding(action: Hotkeys.Action, keyNames: List<String>?, takeFrom: List<Hotkeys.Action> = emptyList())

    /** Hard-restarts the running game: stops the core, discards any pending
     * suspend-resume state, and reboots the same ROM re-reading its save file
     * fresh from disk — like RetroArch's "Restart". This is the escape hatch
     * for "the save didn't load" (e.g. the save file changed on disk after the
     * core already had it open), since a normal in-game reset never re-reads
     * the save. Caller (the UI) is expected to confirm before calling this. */
    fun restartGame()

    /** Ends the session and returns to the ROM list — same as backing out of
     * the game normally, so progress is suspended/saved the usual way via the
     * activity lifecycle. Caller (the UI) is expected to confirm first. */
    fun closeGame()

    /** Whether the companion can step off its own screen while the game runs (two screens; swapped, the game moves to the second). */
    val canCloseCompanion: Boolean get() = false

    /** Closes the companion's screen (the device's own launcher shows there) - BACK on the game brings it back. */
    fun closeCompanion() {}

    /**
     * The companion was closed while the screens were swapped: the game has the second screen and this companion opens
     * over it, as on one screen. Its SETTINGS then offers OPEN COMPANION ([openCompanion]) instead of CLOSE COMPANION.
     */
    val companionClosed: Boolean get() = false

    /** Puts the companion back on its own screen (the main one, the game back on the second). */
    fun openCompanion() {}

    /** SETTINGS > DISCORD: opens PokeDaisy's Discord server ([Discord.URL]) in the browser or the Discord app. */
    fun openDiscord() {}

    /** The running game is one its makers asked PokéDaisy not to read ([com.pokedaisy.app.companion.data.BlockedGames]):
     * SETTINGS shows the emulator's rows only. */
    val gameBlocked: Boolean get() = false

    /** A host that runs only part of the app (the iPad app, so far): SETTINGS' HOME shows only these rows (English
     * labels), each group's title kept over the ones left. Null = every row. */
    val shownRows: Set<String>? get() = null

    /** Opens a web link ([url]) in the browser - a blocked game's button ([com.pokedaisy.app.companion.data.BlockedAction]). */
    fun openUrl(url: String) {}




    /** Saves THEME (a ThemeSpec.id, see [Prefs.appTheme]; ui.theme.pickTheme); the screen puts the theme on itself. */
    fun setAppTheme(id: Int) {}





    /** ITEM POPUPS - see [com.pokedaisy.app.Prefs.itemPopups]. */
    val itemPopups: Boolean get() = true
    fun setItemPopups(on: Boolean) {}

    /** LAST BALL - see [com.pokedaisy.app.Prefs.lastBallButton]. */
    val lastBallButton: Boolean get() = true
    fun setLastBallButton(on: Boolean) {}




    /** Hosts with resume controls on the companion (Android keeps its control in library settings). */
    val resumeGamesEditable: Boolean get() = false
    val resumeBadge: String? get() = null

    /** Stores a SETTINGS > TWEAKS switch ([com.pokedaisy.app.companion.ui.CompanionTweaks]) by its key. */
    fun setTweak(key: String, on: Boolean) {}



    /** The status bar over the companion's tabs instead of over the game - see [Prefs.statusBarOnCompanion]. */
    val statusBarOnCompanion: Boolean get() = false
    fun setStatusBarOnCompanion(on: Boolean) {}

    /** SETTINGS > HIDE BATTLE MENU: two screens, the game's own battle menus off its screen - see
     * [com.pokedaisy.app.Prefs.hideBattleMenu]. Null = no row (one screen, or a game it doesn't work in). */
    val hideBattleMenu: Boolean? get() = null
    fun setHideBattleMenu(on: Boolean) {}

    /** SETTINGS > BATTLE BUTTONS: one screen, FIGHT, POKéMON and RUN beside a battle - see [Prefs.battleButtons]. */
    val battleButtons: Boolean get() = true
    fun setBattleButtons(on: Boolean) {}



    /** SETTINGS > QUICK MENU: the actions it shows - see [Prefs.quickMenuActions]. */
    val quickMenuSupportedActions: Set<QuickAction> get() = QuickAction.entries.toSet()
    val quickMenuBadge: String? get() = null


    /** FPS / CPU over the game's corner - see [Prefs.showPerformance]. */
    val showPerformance: Boolean get() = false
    fun setShowPerformance(on: Boolean) {}

    /** RetroAchievements popups - see [Prefs.raPopups]. The top screen's Settings > RetroAchievements has the same
     * switches; off also mutes the unlock fanfare. */
    val raPopups: Boolean get() = true
    fun setRaPopups(on: Boolean) {}
    /** RetroAchievements PROGRESS popups only - see [Prefs.raProgressPopups]; [raPopups] off hides these too. */
    val raProgressPopups: Boolean get() = true
    fun setRaProgressPopups(on: Boolean) {}


    /** Whether WIDE knows the running game (WideScreen.gameFor); it shows as ORIGINAL otherwise. */
    val wideSupported: Boolean get() = false

    /** EXPAND's LOOK AROUND - see [com.pokedaisy.app.Prefs.lookAround]. */
    val lookAround: Boolean get() = true
    fun setLookAround(on: Boolean) {}

    /** EXPAND's ZOOM steps, as how much more of the map each shows (1x first, [com.pokedaisy.app.WideCamera.factor]),
     * picked from SETTINGS > ZOOM - a device with no right stick has no R3 to step them; empty with no game view. */
    val zoomSteps: List<Float> get() = emptyList()
    /** The step picked on this game ([com.pokedaisy.app.Prefs.zoomLevelFor]). */
    val zoomLevel: Int get() = 0
    fun setZoomLevel(level: Int) {}

    /** TOUCH CONTROLS > VIBRATION / STRENGTH changed: one buzz at [level] (com.pokedaisy.app.TouchHaptics). */
    fun feelVibration(level: Int) {}

    /** EXPAND's AUTO ZOOM IN - see [com.pokedaisy.app.Prefs.expandZoomOnText]. */
    val expandZoomOnText: Boolean get() = true
    fun setExpandZoomOnText(on: Boolean) {}

    /** EXPAND's one-time offer in a game it knows, while EXPAND is off ([com.pokedaisy.app.Prefs.expandGameOfferSeen]):
     * true while it's up (Compose state). Shown once ever - the setting is global. */
    val expandOffer: Boolean get() = false
    fun answerExpandOffer(turnOn: Boolean) {}


    /** The tip card shows on this companion (it's on a screen of its own, not over the game: Compose state). */
    val showsTips: Boolean get() = false

    /** The keys a tip names (the player's own bindings), as key and what it does. */
    fun tipKeys(tip: TipId): List<Pair<String, String>> = emptyList()

    /** A tip's TRY IT. */
    fun tryTip(tip: TipId) {}

    /** SCREEN > OVERLAY's choices, as (key, label) - see [com.pokedaisy.app.Prefs.overlay]; empty = no row (no game).
     * Imported in the top screen's Settings (a file picker); picked here, live. */
    val overlayChoices: List<Pair<String, String>> get() = emptyList()
    val overlay: String get() = ""
    fun setOverlay(key: String) {}




    /** DISPLAY OPTIONS > LIGHT COMPANION ([LightCompanion], whose mode is Compose state): the activity also stores it
     * and re-applies what it changes (ON COMPANION's shaders). */
    fun setLightCompanion(mode: LightCompanionMode) { LightCompanion.mode = mode }




    /** Whether this device can run SMOOTHING (OpenGL ES 3); without it the row is greyed out. */
    val smoothingAvailable: Boolean get() = true

    /** Whether there's a second screen - SINGLE SCREEN only shows then. */
    val hasSecondScreen: Boolean get() = false

    /** A second screen played on one anyway - see [com.pokedaisy.app.Prefs.singleScreen]. */
    val singleScreen: Boolean get() = false
    fun setSingleScreen(on: Boolean) {}

    /** The companion is on the second screen (there is one, and SINGLE SCREEN is off): SWAP SCREENS shows, the
     * one-screen rows (BATTLE BUTTONS, COMPANION VIEW) don't. */
    val usesSecondScreen: Boolean get() = hasSecondScreen && !singleScreen

    /**
     * A phone held upright: the companion right under the game (true; the touch controls get the
     * screen's bottom) or along the screen's bottom - see [Prefs.portraitCompanionUnderGame].
     * Null when the companion isn't under the game (a second screen, or held sideways): no row.
     */
    val portraitUnderGame: Boolean? get() = null
    fun setPortraitUnderGame(on: Boolean) {}

    /** The same place for TOUCH CONTROLS' page, held either way on a one-screen device (the preview shows upright
     * too); null with a second screen. */
    val uprightUnderGame: Boolean? get() = null

    /**
     * One screen held sideways: the companion as a window in the middle over the game (true) or the side panel -
     * see [com.pokedaisy.app.Prefs.companionWindow]. Null when the companion isn't on the game's screen sideways: no row.
     */
    val companionViewBadge: String? get() = NewFeatures.badge(NewFeatures.COMPANION_WINDOW)
    val companionWindow: Boolean? get() = null
    fun setCompanionWindow(on: Boolean) {}

    /** SETTINGS > FOLD LAYOUT ([com.pokedaisy.app.companion.FoldMode]); null = not a foldable (no row). */
    val foldMode: FoldMode? get() = null
    fun setFoldMode(mode: FoldMode) {}

    /** SETTINGS > TABLETOP CONTROLS ([com.pokedaisy.app.Prefs.tabletopControlsBottom]); null = not a foldable (no row). */
    val tabletopControlsBottom: Boolean? get() = null
    fun setTabletopControlsBottom(on: Boolean) {}

    /** SETTINGS > COMPANION BUTTON: the edge tab that opens the companion ([com.pokedaisy.app.Prefs.companionTab]). Null
     * where [companionWindow] is (no row). */
    val companionTab: Boolean? get() = null
    fun setCompanionTab(on: Boolean) {}

    /** The game on the second screen and the companion on the main one - see [Prefs.swapScreens]. */
    val swapScreens: Boolean get() = false
    fun setSwapScreens(on: Boolean) {}

    /** The running game's cheats (added in the top screen's Settings > CHEATS), for SETTINGS > CHEATS. */
    val cheats: List<com.pokedaisy.app.cheats.Cheat> get() = emptyList()


    /** Turns the game's cheat [index] (in [cheats]) on or off; the game picks it up on the next frame. */
    fun setCheatEnabled(index: Int, on: Boolean) {}
    val cheatsBadge: String? get() = null
    val cheatsEditable: Boolean get() = false
    fun addCheat(cheat: com.pokedaisy.app.cheats.Cheat) {}
    fun removeCheat(index: Int) {}
    fun importCheats(onResult: (com.pokedaisy.app.cheats.CheatStore.Import?) -> Unit) {}

    /** Display name of the currently-detected game (e.g. "Pokémon Heart and
     * Soul") — see [com.pokedaisy.app.companion.data.GameKind.displayName]. */
    val gameName: String

    /** File name of the ROM currently loaded (e.g. "heart_and_soul.gba"). */
    val romFileName: String



    /** CO-OP shows only while its feature flag is on (read once by the activity, never per recomposition). */
    val coopEnabled: Boolean get() = false
    /** SETTINGS re-reads the link once a second while it shows (LINK's retry countdown); previews turn it off so they can go idle. */
    val coopLiveRefresh: Boolean get() = true
    /** CO-OP (see [com.pokedaisy.app.companion.coop.CoopSession]): the link's state, the pairings, the relay and its RELAY ONLY switch. */
    val coopState: com.pokedaisy.app.companion.coop.CoopState get() = com.pokedaisy.app.companion.coop.CoopState.Off()
    val coopPairs: List<com.pokedaisy.app.companion.coop.CoopPair> get() = emptyList()
    val coopRelayValue: String get() = ""
    val coopRelayOnly: Boolean get() = false
    fun setCoopRelayOnly(on: Boolean) {}
    /** NEARBY RANGE in steps (2 / 4 / 8 / 16 / 32): how close a partner's battle must be to join; the activity reads Prefs. */
    var coopNearbyRange: Int
        get() = 8
        set(_) {}
    fun coopUnpair(save: com.pokedaisy.app.companion.coop.CoopSaveId) {}
    /** PLAY TOGETHER ONLY: the running game's lock state and actions (null = the row is hidden). */
    val coopLock: com.pokedaisy.app.companion.coop.lock.CoopLockUi? get() = null
    val coopLockActions: com.pokedaisy.app.companion.coop.lock.CoopLockActions? get() = null
    /** Show the partner's PLAY TOGETHER ONLY proposal on the companion itself (two screens: the game screen can't be touched). */
    val coopProposalHere: Boolean get() = false
}

/** Every tab the player can show or hide, in tab-bar order. BATTLE (while a
 * battle runs) and SETTINGS (the way to the hidden ones) are always there.
 * CARD (the TRAINER CARD) and ACHIEVEMENTS (RetroAchievements) start off the
 * bar, under the MORE tab. */
val COMPANION_TABS = listOf("PARTY", "DEX", "MAP", "ITEMS", "BOXES", "GUIDE", "NUZLOCKE", "CARD", "STATES", "ACHIEVEMENTS")
val DEFAULT_COMPANION_TABS = listOf("PARTY", "DEX", "MAP", "ITEMS", "GUIDE")

/** Tabs next to SETTINGS: six chips in all is as many as fit. During a
 * battle BATTLE takes the last chosen tab's place. */
const val MAX_BAR_TABS = 5

/** Where fast-forward applies, in the order SETTINGS lists them. [label] stays English (the
 * selectors match by it); the rows translate it where they draw it. */
enum class FfMode(val label: String) {
    /** Menu screens (party, bag, summary, PC, Pokédex, trainer card...) and the
     * region map run at 1x; the field, dialogue and battles stay fast - but a
     * battle waiting for the player's command (its menus, bag, party) runs at 1x ([smartSlows]). */
    SMART(tk("SMART")),

    /** Fast everywhere. */
    NORMAL(tk("NORMAL")),
}


/** SCENE SPEEDS' choices, SAME (0: GAME SPEED's) first. */
val FF_SPEEDS = listOf(0f, 1.5f, 2f, 3f, 4f, 5f, 6f, 8f, 10f)

val SCENE_SPEEDS = listOf(0f, 1f, 1.5f, 2f, 3f, 4f)

/** SETTINGS > ASPECT: how the game sits on its screen, in the order the pickers list them.
 * [label] stays English; the rows translate it. */
enum class GameAspect(val label: String) {
    /** The GBA's 3:2, bars at the sides. */
    ORIGINAL(tk("ORIGINAL")),
    /** The game stretched to fill the screen. */
    STRETCH(tk("STRETCH")),
    /** EXPAND (WIDE in the code and prefs): the overworld drawn past the GBA's edges - beside the game on a wide
     * screen, above and below it on a phone upright - rebuilt from the game's own memory (WideScreen, pk_wide.c);
     * ZOOM and the look-around on top (WideCamera). The games it doesn't know yet show as ORIGINAL. */
    WIDE(tk("EXPAND")),
}

/** SETTINGS > SHADERS > FILTER: the screen effect drawn over the game (EmulatorView, ScreenShaders),
 * in the order the selector lists them. [label] stays English; the rows translate it. */
enum class ScreenFilter(val label: String) {
    NONE(tk("NONE")),
    /** The GBA's pixel grid. */
    LCD(tk("LCD")),
    /** A soft grid on paper: an unlit, reflective LCD (simpletex_lcd's style). */
    LCD_PAPER(tk("LCD PAPER")),
    SCANLINES(tk("SCANLINES")),
    /** Soft scanline beams and an RGB mask. */
    CRT(tk("CRT")),
}

/** The filter after this one, wrapping: SHADERS > FILTER steps through them on each tap (the user's
 * call, so each look shows on the game at once - the one row that cycles past two values). */
fun ScreenFilter.next(): ScreenFilter = ScreenFilter.entries[(ordinal + 1) % ScreenFilter.entries.size]

/** Whether this filter draws a pixel grid that SHADERS > GRID tunes. */
val ScreenFilter.hasGrid: Boolean get() = this == ScreenFilter.LCD || this == ScreenFilter.LCD_PAPER

/** SHADERS > GRID: how strongly LCD / LCD PAPER draw their grid (ScreenShaders.gridFor), in the
 * order a tap steps through them - it cycles like FILTER, so each shows on the game at once. */
enum class GridStrength(val label: String) {
    SOFT(tk("SOFT")),
    MEDIUM(tk("MEDIUM")),
    STRONG(tk("STRONG")),
    ;

    fun next(): GridStrength = entries[(ordinal + 1) % entries.size]
}

/** SHADERS > SMOOTHING: an edge-smoothing upscaler run on the game before FILTER (ScreenShaders.smoothingPasses),
 * in the order a tap steps through them - it cycles like FILTER. Never on the companion (it's UI, not pixel art).
 * [label]s are the shaders' own names. */
enum class Smoothing(val label: String) {
    OFF(tk("OFF")),
    /** xBRZ freescale: sharp, the cleanest edges. */
    XBRZ(tk("xBRZ")),
    /** xBR level 2: rounder and softer. */
    XBR(tk("xBR")),
    /** ScaleFX: the most even outlines, 3x then scaled (needs float framebuffers). */
    SCALEFX(tk("SCALEFX")),
    ;

    fun next(): Smoothing = entries[(ordinal + 1) % entries.size]
}

/**
 * What fast-forward sounds like, in the order SETTINGS lists them. [label] is
 * what the rows show.
 */
enum class FfMusicMode(val label: String) {
    /** The song at its normal tempo: a clean clip rendered from the ROM
     * (FfMusicRenderer), looped while the game races; silent until it's ready (SPED-UP only for a song
     * that can't get a clip). */
    STEADY(tk("STEADY")),

    /** The game's own audio, sped up with it (pitch and tempo). */
    SPED_UP(tk("SPED-UP")),

    /** Silent. */
    OFF(tk("OFF")),
}

/** SETTINGS > FLIGHT AREA: where FREE FLIGHT and the scenic FLY may go (companion.data.FlightArea). [label] stays English. */
enum class FlightAreaMode(val id: Int, val label: String) {
    /** Only over maps the player has seen: the save's towns, the routes between them, maps with a beaten trainer, every map stood on. */
    SEEN(0, tk("VISITED")),

    /** Anywhere the game's world goes; RetroAchievements pause from the first take-off. */
    ANYWHERE(1, tk("ANYWHERE")),
    ;

    companion object {
        fun of(id: Int): FlightAreaMode = entries.firstOrNull { it.id == id } ?: SEEN
    }
}

/** SETTINGS > FLY STYLE: how the companion's FLY (MAP, QUICK MENU) gets there. [label] stays English; the row translates it. */
enum class FlyStyle(val id: Int, val label: String) {
    /** The game's own cut-in, flight and landing. */
    CLASSIC(0, tk("CLASSIC")),

    /** Up on the bird and over the field to the town (companion.data.ScenicFly), the view zoomed out on EXPAND. */
    SCENIC(1, tk("SCENIC")),
    ;

    companion object {
        fun of(id: Int): FlyStyle = entries.firstOrNull { it.id == id } ?: CLASSIC
    }
}

/** SETTINGS > BATTLE STYLE: the battle controls' look. [label] stays English; the rows translate it. */
enum class BattleStyle(val id: Int, val label: String) {
    /** Platinum-like coloured buttons (BattleControlsScreen). */
    GEN4(0, tk("GEN 4")),

    /** Black-like: a Poké Ball split red / white, dark octagon plates. */
    GEN5(1, tk("GEN 5")),
    ;

    companion object {
        fun of(id: Int): BattleStyle = entries.firstOrNull { it.id == id } ?: GEN4
    }
}

/** An EXPAND zoom step as the HUD and SETTINGS > ZOOM show it: how much more of the map it shows ("1x", "1.4x"). */
fun zoomLabel(factor: Float): String = "${factor.toDouble().fixed(1)}x".replace(".0x", "x")

/** One-button capture used by setup and application/game settings on either host. */
fun CompanionSettings.gameButtonRequest(button: GbaControls.Btn, changed: () -> Unit = {}) =
    KeyCapture.Request(button.title, chord = false,
        onDone = { keys -> setGbaControlBinding(button, KeyCapture.keyName(keys.first())); changed() },
        onClear = if (button.canClear) ({ clearGbaControlBinding(button); changed() }) else null)
