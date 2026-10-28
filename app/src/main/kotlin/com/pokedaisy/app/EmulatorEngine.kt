package com.pokedaisy.app

import com.pokedaisy.app.companion.data.SnapshotCache
import com.pokedaisy.app.companion.i18n.tr
import android.graphics.Bitmap
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log
import com.pokedaisy.app.achievements.RetroAchievements
import com.pokedaisy.app.companion.FfMode
import com.pokedaisy.app.companion.FfMusicMode
import com.pokedaisy.app.companion.smartSlows
import com.pokedaisy.app.companion.data.FfMenuWatch
import com.pokedaisy.app.companion.data.FfMusicKey
import com.pokedaisy.app.companion.data.InProcessReader
import com.pokedaisy.app.companion.data.RegionMapWatch
import com.pokedaisy.app.companion.data.ShopWatch
import java.io.File
import java.nio.ByteBuffer
import kotlin.math.roundToInt

/**
 * Owns the single emulator thread: run a frame, push its audio, service any
 * pending savestate request, repeat.
 *
 * At 1x the frames follow the refresh of the screen the game is on, like
 * RetroArch's vsync: one game frame per refresh (60 Hz), or per two (120 Hz),
 * ticked by the game's GL thread ([onVsync]). The game then runs at the display's
 * rate (60 Hz is 0.46% faster than the GBA's 59.73) and every refresh shows a new
 * frame - paced by the audio device or a timer instead, the two clocks drifted
 * apart and a frame was shown twice or skipped every few seconds, and the audio
 * buffer handed frames out in bursts. Its audio is resampled to match (MgbaCore.pkSetAudioRate),
 * nudged by how full the device's buffer is (dynamic rate control), so it never
 * blocks the loop. The buffer also keeps the pacing honest (GitHub #80): a refresh
 * the loop missed costs a frame of audio, so running low runs the next frame at once
 * ([vsyncPace]) and running high waits for a fresh refresh - never silence written in
 * (that was the stutter). Underruns that keep coming send the run back to the timer
 * ([AudioTroubleWatch]). With no usable vsync (the view not drawing, an odd refresh
 * rate) or at any other speed, it falls back to a timer and blocking audio writes.
 *
 * Savestate/loadstate/suspend are always performed on this thread, between
 * frames, so the core is never touched mid-frame.
 */
class EmulatorEngine(
    private val input: GbaInput,
    private val states: SaveStates,
    private val ffMusicCache: FfMusicCache,
) {
    private var thread: Thread? = null
    @Volatile var running = false
        private set
    @Volatile private var loadError: String? = null
    @Volatile private var startNotice: String? = null

    @Volatile private var pendingSave = -1
    @Volatile private var pendingLoad = -1
    @Volatile private var pendingUndoSave = false
    @Volatile private var pendingUndoLoad = false
    @Volatile private var pendingSuspend: File? = null

    @Volatile private var ffHeld = false
    @Volatile private var ffToggled = false
    @Volatile private var slowmoHeld = false
    @Volatile private var rewindHeld = false
    @Volatile private var speedIdx = 0          // index into SPEED_STEPS

    /** Cap for hold/toggle fast-forward. 0 = unlimited. (Phase 4: make configurable.) */
    @Volatile var ffMaxSpeed = 2f

    /** REWIND (Settings): states kept for the rewind hotkey, 0 = off. Picked up on the emu thread. */
    @Volatile var rewindEntries = 0

    /** WIDE's native tables for this game ([WideScreen.gameFor]), or [WideScreen.OFF]; applied between frames. */
    @Volatile var wideGame = WideScreen.OFF
    /** A Game Boy game pk_wide_gb.c knows (its [WideScreen.gameFor]), EXPAND on or not: with EXPAND off it's configured
     * anyway with nothing drawn, for its scene (the FIELD HUD's field read: a Game Boy game has no FfMenuWatch). */
    @Volatile var gbSceneGame = WideScreen.OFF

    /** The same tables for the field reads behind SCENE SPEEDS' TEXT, AUTO-RUN and RUN INDOORS, EXPAND on or off
     * ([WideScreen.gameFor], [WideScreen.OFF] = a game they don't know); applied between frames. */
    @Volatile var fieldGame = WideScreen.OFF

    /** SETTINGS > SCENE SPEEDS (GitHub #43 / #85): the speed in a battle, while a text box is up on the field, and on
     * a menu screen, while fast-forward is off; 0 = GAME SPEED's, as on the field. FF and slow-mo still win. */
    @Volatile var battleSpeed = 0f
    @Volatile var textSpeed = 0f
    @Volatile var menuSpeed = 0f
    /** ... and riding a bike / surfing on the field (batch 2 of #43 / #85): the whole game at that speed meanwhile. */
    @Volatile var bikeSpeed = 0f
    @Volatile var surfSpeed = 0f

    /** The field read knows the player's bike / surf state on this game ([MgbaCore.FIELD_AVATAR]): BIKE / SURF SPEED. */
    @Volatile var fieldMovesKnown = false
        private set

    /** The field read has the player's controls locked (a script, a text box, the START menu); false where the game's
     * field isn't read. X's jump to the companion waits for it to clear. */
    val fieldLocked: Boolean
        get() = fieldState and (MgbaCore.FIELD_KNOWN or MgbaCore.FIELD_LOCKED) == MgbaCore.FIELD_KNOWN or MgbaCore.FIELD_LOCKED

    /** The player is on a bike, as the field read has it ([MgbaCore.FIELD_AVATAR] games only): QUICK MENU's GET OFF. */
    @Volatile var riding = false
        private set

    /** AUTO-RUN: B held for the player while walking on the field (holding B walks); never on a bike or in a menu. */
    @Volatile var autoRun = false

    /** RUN INDOORS: the current map lets the player run (gMapHeader.allowRunning; FireRed / Emerald engines). */
    @Volatile var runIndoors = false

    /** WILD ENCOUNTERS: the game's own switch ([WildEncounters.find]; 0 = not found) and the percent of wild
     * encounters left (100 = the game's own rate, 0 = none). */
    @Volatile var wildFlag = 0L
    @Volatile var wildEncounters = 100

    /** REUSABLE TMS: the ROM's spots that use a TM up ([com.pokedaisy.app.companion.data.ReusableTms]; empty = none
     * known) and whether they're patched; both go in on the next frame boundary ([setReusableTms]). */
    @Volatile private var tmSites: List<com.pokedaisy.app.companion.data.ReusableTms.Site> = emptyList()
    @Volatile private var reusableTms = false
    @Volatile private var tmPatchDirty = false

    fun setReusableTms(sites: List<com.pokedaisy.app.companion.data.ReusableTms.Site>, on: Boolean) {
        tmSites = sites
        reusableTms = on
        tmPatchDirty = true
    }

    /** PLAYER LOOK ([com.pokedaisy.app.companion.data.PlayerLook]): the patch to wear - its blocks into the ROM, then its
     * palettes swapped where the game has them loaded - on the next frame boundary ([setPlayerLook]); its palettes again
     * after every state load (a state made in another look). Null = nothing to do. */
    @Volatile private var lookPatch: com.pokedaisy.app.companion.data.PlayerLook.Patch? = null
    @Volatile private var lookDirty = false

    fun setPlayerLook(patch: com.pokedaisy.app.companion.data.PlayerLook.Patch?) {
        // The same look again (the party read changing, as it does as a battle starts) is nothing to do: putting it in
        // again set the player's sprite up again after the battle, mid-fade.
        val now = lookPatch
        if (patch === now || (patch == null && now == null) || (patch != null && patch.sameAs(now))) return
        lookPatch = patch
        lookDirty = true
    }

    /** A Game Boy game's PC / FLY / WILD ENCOUNTERS ([com.pokedaisy.app.companion.data.GbField], its code checked), or
     * null. WILD ENCOUNTERS OFF holds the game's own encounter state every frame ([gbWildHeld]). */
    @Volatile var gbField: com.pokedaisy.app.companion.data.GbField? = null
    /** Whether [gbField]'s wild encounters were held last frame (null = not known yet: put back once on the field). */
    private var gbWildHeld: Boolean? = null

    /** ZOOM and the look-around over WIDE: asked for the window to draw before each frame ([EmulatorView.camera]). */
    @Volatile var wideCamera: WideCamera? = null

    /** Called (emu thread) once WIDE has switched itself off for this game: a fault while drawing, caught
     * ([MgbaCore.pkWideFault]), or the camera failing - the game goes on with its plain frame. */
    var onWideOff: ((String) -> Unit)? = null

    /** What fast-forward sounds like (Settings: STEADY / SPED-UP / OFF). */
    @Volatile var ffMusicMode = FfMusicMode.STEADY

    /** The game's own audio is paused this frame (FF with a STEADY clip or none yet, slow-mo, rewind, a held frame). */
    @Volatile var gameAudioPaused = false
    /** SMART: 1x while a menu screen or the region map is up ([FfMenuWatch], [RegionMapWatch]) - not a shop's ([ShopWatch]). */
    @Volatile var ffMode = FfMode.SMART

    @Volatile var currentSlot = 0
        private set

    val lastError: String? get() = loadError
    /** Something the player should know about how the game started (not an error). */
    val lastNotice: String? get() = startNotice

    /** Fired from the emu thread; wrap UI work in the callback yourself. */
    var onCoreReady: ((width: Int, height: Int) -> Unit)? = null

    /**
     * Fired from the emu thread right before the core is freed (its frame buffer with
     * it): drop every reference to that buffer here, synchronously - the GL thread
     * reading it after this is a use-after-free that kills the process.
     */
    var onCoreStopping: (() -> Unit)? = null

    /** Fired on the emu thread after every frame, while the frame buffer holds that whole frame. */
    var onFrame: (() -> Unit)? = null

    /** False for a Game Boy / Color ROM: the GBA-only watchers (region map, menus, m4a songs) stay off. */
    @Volatile var gba = true

    /** A DS game (melonDS): its field read is its own (nds/pk_nds_field.cpp: pkFieldState's bits plus
     * [MgbaCore.FIELD_SCREEN] / [MgbaCore.FIELD_MENU]), standing in for FfMenuWatch and WIDE's tables. */
    @Volatile var nds = false

    /** The field screen is up, positively: FfMenuWatch's learned callback on a GBA game, the field map process on a DS. */
    private val fieldScreen get() = if (nds) fieldState and MgbaCore.FIELD_SCREEN != 0 else gba && FfMenuWatch.onField

    /** A Game Boy game's music tables: its STEADY FF-music key comes from Gen1Music instead of the m4a engine. */
    @Volatile var gen1Music: com.pokedaisy.app.companion.data.Gen1MusicTables? = null
    var onStateResult: ((action: Hotkeys.Action, slot: Int, ok: Boolean) -> Unit)? = null
    var onSlotChanged: ((slot: Int) -> Unit)? = null
    var onSpeedChanged: ((label: String) -> Unit)? = null

    /** Fired (emu thread) only when the FF music that should be playing
     * actually changes — a cached clip (its loop, intro and when the song
     * started), or null to stop (nothing cached yet for this state, or FF
     * isn't active). Wrap MediaPlayer work in the callback yourself (see FfMusicPlayer). */
    var onFfMusicChanged: ((clip: FfMusicPlayer.Clip?) -> Unit)? = null

    /** Fired (emu thread) in STEADY when the game starts a song with no clip
     * yet ([FfMusicKey]) - hand it to [FfMusicRenderer.request]. */
    var onFfMusicWanted: ((key: String) -> Unit)? = null

    /** STEADY: whether this song's clip is cached or on its way (SongRenderer.willRender). While it's on its
     * way FF is silent - the song isn't played sped up first; null = no renderer, SPED-UP stands in. */
    var ffMusicWillRender: ((key: String) -> Boolean)? = null

    /** Invoked on the emu thread ~1×/sec (between frames) — do core memory reads here. */
    var onSample: (() -> Unit)? = null

    /** A savestate was just written to this file (emu thread, between frames): save what goes beside it. */
    var onStateSaved: ((File) -> Unit)? = null

    /** The start's resume state loaded (true) or was skipped / failed (false), before the first frame.
     * Only fired for a start that was given one. */
    var onResumeResult: ((Boolean) -> Unit)? = null

    /** Bottom-screen touch battle control (see PLAN.md Phase 5):
     * drives the real battle menu via synthetic button presses through the
     * same touch-overlay bitmask the on-screen D-pad uses. */
    private val battleInput = BattleInputController(input, writer = { a, b -> MgbaCore.pkWriteBytes(a, b) })

    /** True while a queued battle-menu button sequence hasn't fully drained —
     * the touch battle UI should disable further taps while this is true. */
    val battleInputBusy: Boolean get() = battleInput.isBusy

    /** Keep showing the last frame: an automated switch is in the game's party menu (see [BattleInputController.holdFrame]). */
    val holdFrame: Boolean get() = battleInput.holdFrame

    /** actionIndex: 0=FIGHT, 1=BAG, 2=POKEMON, 3=RUN. Call from the UI thread. */
    fun battleSelectAction(actionIndex: Int) = battleInput.selectAction(actionIndex)

    /** moveIndex 0-3. Call from the UI thread; opens FIGHT first if needed. */
    fun battleSelectMove(moveIndex: Int) = battleInput.selectMove(moveIndex)

    /** Backs out of move-select to action-select (a single B press). */
    fun battleBack() = battleInput.back()

    fun battleCancelInput() = battleInput.cancel()

    /** Where [battleSwitchTo] finds the party menu; null = not supported for this game. */
    var battleSwitchAddrs: BattleInputController.SwitchAddrs?
        get() = battleInput.switchAddrs
        set(v) { battleInput.switchAddrs = v }

    /** Where the battle menus keep their cursors (picks go straight there); null = the blind walk. */
    var battleCursorAddrs: BattleInputController.CursorAddrs?
        get() = battleInput.cursorAddrs
        set(v) { battleInput.cursorAddrs = v }

    /** Switch to the party mon with this personality. Call from the UI thread. */
    fun battleSwitchTo(personality: Long) = battleInput.switchTo(personality)

    /** Invoked on the emu thread at a faster, fixed cadence than [onSample]
     * (not gated by its ~1 Hz throttle) — wire this to a fast, 2-byte
     * telemetry peek (TelemetrySampler.sampleBattleInputFast) and feed the
     * result back via [setBattleMenuState], so the touch battle UI reacts to
     * a menu transition within a fraction of a second, not up to a second
     * late. */
    var onBattleInputSample: (() -> Unit)? = null

    /** Feed the latest (battleActiveBattler, battleInputState) from the fast
     * poll above. */
    fun setBattleMenuState(activeBattler: Int, state: Int, cursor: Int = -1) = battleInput.onState(activeBattler, state, cursor)

    /** A Gen 1 game's battle menus (steered from their read cursor - see [BattleInputController.gen1]). */
    var battleGen1Menus: Boolean
        get() = battleInput.gen1
        set(v) { battleInput.gen1 = v }

    fun setFastForwardHeld(on: Boolean) {
        if (ffHeld == on) return
        ffHeld = on
        announceSpeed()
    }

    fun toggleFastForward() {
        ffToggled = !ffToggled
        onFastForwardToggledChanged?.invoke(ffToggled)
        announceSpeed()
    }

    /** Explicit set (vs. [toggleFastForward]'s flip) — for a UI switch that needs
     * to show/drive an exact on/off state, like the companion Settings tab. */
    fun setFastForwardToggled(on: Boolean) {
        if (ffToggled == on) return
        ffToggled = on
        onFastForwardToggledChanged?.invoke(ffToggled)
        announceSpeed()
    }

    /** Restore a persisted on/off state without flashing the HUD or re-firing
     * [onFastForwardToggledChanged] (same pattern as [restoreSpeedIndex]). */
    fun restoreFastForwardToggled(on: Boolean) { ffToggled = on }

    val fastForwardToggled: Boolean get() = ffToggled

    /** Fired (any thread) whenever the on/off state actually changes, from
     * either [toggleFastForward] (hotkey) or [setFastForwardToggled] (the
     * companion Settings switch) — not from [restoreFastForwardToggled]. Wire
     * this to persist the choice (see Prefs.ffToggled). */
    var onFastForwardToggledChanged: ((Boolean) -> Unit)? = null

    /** The rewind hotkey held: the game runs backwards (muted) through what REWIND kept. */
    /** Frames left of SOFT RESET's A + B + SELECT + START. */
    @Volatile private var softResetFrames = 0

    /** SOFT RESET: the games' own reset combo, held for a few frames (Gen 3 and Gen 1 check it every frame and go back to
     * the title screen without saving, wherever they allow it). */
    fun softReset() { softResetFrames = SOFT_RESET_FRAMES }

    fun setRewindHeld(on: Boolean) {
        rewindHeld = on
    }

    fun setSlowmoHeld(on: Boolean) {
        if (slowmoHeld == on) return
        slowmoHeld = on
        announceSpeed()
    }

    /** SPEED CYCLE: 1x, 1.5x ... 4x and round; from a slow step (GAME SPEED's 0.5x / 0.25x), back to 1x. */
    fun cycleSpeed() {
        speedIdx = if (speedIdx >= CYCLE_STEPS) 0 else (speedIdx + 1) % CYCLE_STEPS
        announceSpeed()
    }

    /** Current speed-cycle position, for persisting a per-ROM default. */
    val speedIndex: Int get() = speedIdx

    /** Restore a persisted speed index without flashing the HUD. */
    fun restoreSpeedIndex(i: Int) { speedIdx = i.coerceIn(0, SPEED_STEPS.size - 1) }

    /** The game speeds the companion's GAME SPEED offers ([speedIndex] picks one), slowest first. */
    val gameSpeeds: List<Float> get() = SPEED_STEPS.sorted()

    /** The speed step running now (1x unless SPEED CYCLE / GAME SPEED picked another). */
    val gameSpeed: Float get() = SPEED_STEPS[speedIdx]

    /** GAME SPEED: run at [speed] (one of [gameSpeeds]) until another is picked; the HUD says so. */
    fun setGameSpeed(speed: Float) {
        val i = SPEED_STEPS.indexOfFirst { it == speed }.takeIf { it >= 0 } ?: return
        speedIdx = i
        announceSpeed()
    }

    /** Emu thread: the game's region map / a menu screen is up ([RegionMapWatch], [FfMenuWatch]). */
    private var mapOpen = false
    private var menuOpen = false
    private var battleWaiting = false   // a DS battle waits for the player's command (SMART's 1x)

    /**
     * FIELD HUD: the player is on the plain overworld - a GBA game on its field screen (FfMenuWatch: learned from the
     * player's first steps, then remembered per ROM), a Game Boy game's screen matched to its map (pk_wide_gb.c's
     * SCENE_FIELD, [gbSceneGame]), no battle or region map up,
     * and at EXPAND (whose scene is known every frame, [MgbaCore.pkWideScene]) no text box, map preview or battle
     * transition either; a fade between maps changes nothing. Comes on [FIELD_SETTLE_FRAMES] after the field is
     * back (a conversation's lines don't make the HUD blink), goes at once. Set on the emu thread.
     */
    val onField = kotlinx.coroutines.flow.MutableStateFlow(false)
    private var fieldFrames = 0

    /**
     * DAY AND NIGHT ([com.pokedaisy.app.companion.data.DayNight]): the tint the activity wants on the field now (red,
     * green, blue, 256 = as is; [DayNight.NONE] off, indoors, or a game that isn't a GBA one). The engine eases toward it
     * on the field's screen (text boxes and the START menu too: their palettes aren't tinted) and back out of it for
     * battles and menu screens, a few frames either way, and hands each step to the core ([MgbaCore.pkSetTint]).
     */
    /** FOLLOWER ([com.pokedaisy.app.companion.data.MonArt]): the party lead's sprite to walk behind the player (null =
     * none: the row off, no sprite kept). Shown on the field's own screen, not surfing; set by the activity. */
    @Volatile var follower: com.pokedaisy.app.companion.data.MonArt.Mon? = null
    private var followerSent: com.pokedaisy.app.companion.data.MonArt.Mon? = null
    private var followerShown = false

    /** PLAYER LOOK changed (or a state loaded): the player's sprite is set up again on the field ([MgbaCore.pkLookRefresh]). */
    @Volatile private var lookRefreshPending = false
    private var lookRefreshTries = 0

    @Volatile var dayNightTint: IntArray = com.pokedaisy.app.companion.data.DayNight.NONE
    private val tintNow = floatArrayOf(256f, 256f, 256f)
    private val tintSent = intArrayOf(256, 256, 256)
    /** The last frame's EXPAND scene ([WideCamera.SCENE_FIELD] ...), -1 without EXPAND. Emu thread. */
    private var wideScene = -1

    /** Emu thread: what's on screen for SCENE SPEEDS, and the field reads' last bits ([MgbaCore.pkFieldState]). */
    private var scene = Scene.FIELD
    @Volatile var fieldState = 0
        private set
    private var fieldApplied = -1
    private var wildApplied = -1L

    /** Debug builds: the scene / speed and AUTO-RUN's state last logged ([logField]). */
    private var loggedScene: Scene? = null
    private var loggedSpeed = Float.NaN
    private var loggedAutoRun: String? = null

    /**
     * Debug builds only: a line when the scene or the speed changes ("scene BATTLE at 2.0x", "(FF)" while fast-forward
     * is on) and when AUTO-RUN becomes ready or blocked, and why - so SCENE SPEEDS, TEXT SPEED and AUTO-RUN's bike /
     * script / battle checks can be read off `adb logcat -s pokedaisy` after a test on a device.
     */
    /** FOLLOWER, once a frame (emu thread): its sprite to the core when it changed, and shown or not. */
    private fun stepFollower(field: Boolean) {
        val mon = follower
        if (mon !== followerSent) {
            MgbaCore.pkFollowerSet(mon?.frames, mon?.owPal)
            followerSent = mon
        }
        val show = field && mon != null
        // Also every second while shown: a core started since (another game, a restart) is hooked when told.
        if (show != followerShown || (show && ++followerFrames % 60 == 0)) {
            MgbaCore.pkFollowerShow(show)
            followerShown = show
        }
    }
    private var followerFrames = 0

    /** DAY AND NIGHT, once a frame (emu thread): [tintNow] a step toward [dayNightTint] on the field, toward none off it. */
    private fun stepTint(field: Boolean) {
        val want = if (field) dayNightTint else com.pokedaisy.app.companion.data.DayNight.NONE
        var changed = false
        for (c in 0..2) {
            val d = want[c] - tintNow[c]
            tintNow[c] = if (kotlin.math.abs(d) < TINT_STEP) want[c].toFloat() else tintNow[c] + kotlin.math.sign(d) * TINT_STEP
            val v = tintNow[c].toInt()
            if (v != tintSent[c]) { tintSent[c] = v; changed = true }
        }
        // While tinted, sent again every second too: a core started since (another game, a restart) has its own
        // renderer, which the native side hooks when it's told.
        if (changed || (++tintFrames % 60 == 0 && tintSent.any { it != 256 })) MgbaCore.pkSetTint(tintSent[0], tintSent[1], tintSent[2])
    }
    private var tintFrames = 0

    private fun logField(speed: Float) {
        if (!BuildConfig.DEBUG) return
        if (scene != loggedScene || speed != loggedSpeed) {
            loggedScene = scene
            loggedSpeed = speed
            Log.i("pokedaisy", "scene $scene at ${speedTag(speed)}${if (ffHeld || ffToggled) " (FF)" else ""}")
        }
        if (!autoRun) return
        val s = fieldState
        val why = when {
            s and MgbaCore.FIELD_KNOWN == 0 -> "not a game it reads"
            s and MgbaCore.FIELD_BATTLE != 0 -> "battle"
            s and MgbaCore.FIELD_BIKE != 0 -> "bike"
            s and MgbaCore.FIELD_LOCKED != 0 -> "script / text / START menu"
            !FfMenuWatch.onField -> "not the field (a menu screen, or the field not learned yet)"
            else -> null
        }
        val state = why?.let { "blocked: $it" } ?: "ready"
        if (state != loggedAutoRun) {
            loggedAutoRun = state
            Log.i("pokedaisy", "auto-run $state")
        }
    }

    private enum class Scene { FIELD, TEXT, BATTLE, MENU, BIKE, SURF }

    /** The speed for what's on screen with FF off: SCENE SPEEDS' pick for it, else GAME SPEED's step. */
    private fun sceneSpeed(): Float {
        val s = when (scene) {
            Scene.BATTLE -> battleSpeed
            Scene.TEXT -> textSpeed
            Scene.MENU -> menuSpeed
            Scene.BIKE -> bikeSpeed
            Scene.SURF -> surfSpeed
            Scene.FIELD -> 0f
        }
        return if (s > 0f) s else SPEED_STEPS[speedIdx]
    }

    /** The speed the player asked for (FF, slow-mo, the speed steps); Float.POSITIVE_INFINITY = "run uncapped". */
    private fun requestedSpeed(): Float = when {
        ffHeld || ffToggled -> if (ffMaxSpeed > 0f) ffMaxSpeed else Float.POSITIVE_INFINITY
        slowmoHeld -> 0.5f
        else -> sceneSpeed()
    }

    /** What the emulator actually runs at: [requestedSpeed], unless SMART is holding it at 1x for now. */
    private fun effectiveSpeed(): Float {
        // Rewinding steps back at the display's pace (2 game frames a frame), whatever FF says.
        if (rewindHeld && rewindEntries > 0) return 1f
        // An automated switch drives the game's party menu behind the held frame: nothing to watch
        // there, so it runs flat out (muted) and the battle comes back in a fraction of a second.
        if (battleInput.holdFrame) return Float.POSITIVE_INFINITY
        val s = requestedSpeed()
        // SMART: menus and the region map's cursor can't be steered sped up -
        // 1x while one is up, and FF (still on) picks up again when it closes.
        // A battle runs fast but where it waits for the player (its menus, bag and party).
        return if (ffMode == FfMode.SMART && s > 1f && smartSlows(battleInput.gameState, menuOpen, mapOpen, battleWaiting)) 1f else s
    }

    private fun speedTag(s: Float): String = if (s.isInfinite()) "ff" else "${s}x"

    private fun announceSpeed() {
        val label = when {
            ffHeld || ffToggled -> tr("Fast-forward")
            slowmoHeld -> tr("Slow-motion ½×")
            speedIdx == 0 -> tr("Normal speed")
            else -> SPEED_STEPS[speedIdx].let { tr("Speed {0}×", if (it % 1f == 0f) it.toInt() else it) }
        }
        onSpeedChanged?.invoke(label)
    }

    fun start(rom: File, save: File, resume: File?): Unit = synchronized(coreLock) {
        if (running) return
        // A loop that outlived stop()'s join (stuck in an audio write, a slow disk)
        // still owns the native core: a second loop's pkInit would free it under it.
        // There's one core for the whole process, so this goes for another engine's loop
        // too (a second game activity while the last one's loop still ran: ARMRunLoop /
        // md5_process SIGSEGVs on Crashlytics, its frames and RA's hash on a freed ROM).
        coreLoop?.let { old ->
            runCatching { old.join(5000) }
            if (old.isAlive) {
                Log.e("pokedaisy", "emu thread still running, not starting another")
                loadError = "The previous game is still closing, try again"
                return
            }
        }
        thread = null
        pendingSuspend = null
        running = true
        loadError = null
        startNotice = null
        val r = resume
        thread = Thread({ loop(rom, save, r) }, "pokedaisy-emu").also { coreLoop = it; it.start() }
    }

    /** Hard stop, no state written. */
    fun stop() {
        running = false
        nudgeSleep()
        thread?.join(3000)
        if (thread?.isAlive != true) thread = null
    }

    /** Save to [target] on the next frame boundary, then stop the loop. */
    fun stopWithSuspend(target: File) {
        if (!running) return
        pendingSuspend = target
        nudgeSleep()
        thread?.join(3000)
        if (thread?.isAlive != true) thread = null
        running = false
    }

    // --- Sleep: the screen went off. The loop holds between frames with the core up, so waking goes on from the very
    // frame it stopped on: no core restart and no state load, which players saw as the game restarting on every wake
    // (and where a freed frame buffer was read, a crash). The suspend state is still written first, as on a close, in
    // case Android ends the app while the device sleeps.

    @Volatile private var pendingSleep: File? = null
    /** The loop is holding for [sleep] until [wake]. */
    @Volatile var sleeping = false
        private set
    private val sleepLock = Object()

    /** UI thread: on the next frame boundary finishes a save in progress, writes [target], then holds. Waits until it
     * does (bounded); false if the loop isn't running or didn't get there - stop it the usual way then. */
    fun sleep(target: File): Boolean {
        if (!running) return false
        pendingSleep = target
        val end = System.nanoTime() + SLEEP_WAIT_NANOS
        synchronized(sleepLock) {
            while (!sleeping && running && System.nanoTime() < end) sleepLock.wait(50)
        }
        if (!sleeping) pendingSleep = null
        return sleeping
    }

    /** Lets a sleeping loop go on (from the frame it held on). */
    fun wake() = synchronized(sleepLock) {
        sleeping = false
        sleepLock.notifyAll()
    }

    /** A stop wakes a sleeping loop, so it ends (or writes its suspend state first) at once. */
    private fun nudgeSleep() = synchronized(sleepLock) { sleepLock.notifyAll() }

    /** The game's cheats as mGBA .cheats text (CheatFiles.coreText: the enabled ones,
     * "" with the master switch off), loaded before the first frame and again on the
     * next frame boundary whenever it changes. */
    @Volatile private var cheatText = ""
    @Volatile private var cheatsDirty = false

    fun setCheats(text: String) {
        cheatText = text
        cheatsDirty = true
    }

    /** A bag item's work on the game (FieldItems.kt), run on the next frame boundary: [run] gets the game's
     * functions and whether it's busy (a battle, a menu screen, the region map). */
    private class FieldItemJob(val inBattle: Boolean, var delayFrames: Int, val run: (com.pokedaisy.app.companion.data.GameCalls, Boolean) -> Unit) {
        /** Frames waited for a clean frame end ([MgbaCore.pkCallSafe]). */
        var waitedFrames = 0
    }
    private val fieldItemJobs = java.util.concurrent.ConcurrentLinkedQueue<FieldItemJob>()

    /** Plain jobs for the emu thread, run between frames (the companion's sample results: PokeDaisyActivity.startSample). */
    private val emuJobs = java.util.concurrent.ConcurrentLinkedQueue<() -> Unit>()

    /** Runs [job] on the emu thread before the next frame; dropped if the game closes first. */
    fun postToEmu(job: () -> Unit) {
        emuJobs.add(job)
    }

    /** Runs [run] on the emu thread between frames with the game's functions ([com.pokedaisy.app.companion.data.GameCalls])
     * and whether the game is busy - USE / GIVE on the ITEMS tab. Every request runs, in order. */
    fun requestFieldItemJob(inBattle: Boolean, delayFrames: Int = 0, run: (com.pokedaisy.app.companion.data.GameCalls, Boolean) -> Unit) {
        fieldItemJobs.add(FieldItemJob(inBattle, delayFrames, run))
    }

    private val gameCalls = object : com.pokedaisy.app.companion.data.GameCalls {
        override fun call(fn: Long, a0: Int, a1: Int, a2: Int, a3: Int) = MgbaCore.pkCall(fn, a0, a1, a2, a3)
        override fun read(addr: Long, len: Int) = InProcessReader.readCoreMemory(addr, len)
        override fun write(addr: Long, bytes: ByteArray) = MgbaCore.pkWriteBytes(addr, bytes)
        override fun rom(off: Long, len: Int) = MgbaCore.pkRomRead(off, len)
        override fun gbCallAt(at: Int, fn: Int, a: Int, b: Int, hl: Int) = MgbaCore.pkGbCallAt(at, fn, a, b, hl)
    }

    /** A Game Boy game's field, as its scene says (pk_wide_gb.c's screen match: no text box, menu or battle); a game
     * with no scene (no EXPAND row: the European Yellows, Red / Blue) leaves it to [gbField]'s own RAM checks. */
    private val gbOnField get() = wideScene == WideCamera.SCENE_FIELD || wideScene == -1

    fun requestSaveState(slot: Int) { pendingSave = slot.coerceIn(SaveStates.SLOT_MIN, SaveStates.SLOT_MAX) }
    fun requestLoadState(slot: Int) { pendingLoad = slot.coerceIn(SaveStates.SLOT_MIN, SaveStates.SLOT_MAX) }
    fun requestUndoSave() { pendingUndoSave = true }
    fun requestUndoLoad() { pendingUndoLoad = true }

    fun cycleSlot(delta: Int) {
        val n = (currentSlot + delta).mod(SaveStates.SLOT_MAX - SaveStates.SLOT_MIN + 1)
        currentSlot = n
        onSlotChanged?.invoke(n)
    }

    /** Refresh ticks from the game's GL thread, at most two frames' worth banked ([onVsync]). */
    private val vsyncTicks = java.util.concurrent.Semaphore(0)
    @Volatile private var lastVsyncNanos = 0L
    /** The game screen's refresh period, averaged over recent ticks; 0 = not known yet. */
    @Volatile private var vsyncPeriodNanos = 0.0

    /** Game frames run since the core started - SETTINGS > FPS / CPU counts them per second. */
    @Volatile var framesRun = 0L
        private set

    // FPS / CPU's performance panel (PerformanceOverlay), all cumulative / latest - the reader takes differences.
    /** Time the emu thread spent on frames (the core, audio and the watchers - not waiting), in total. */
    @Volatile var workNanos = 0L
        private set
    /** The longest frame's work since [takeMaxWorkNanos] last ran. */
    private val maxWork = java.util.concurrent.atomic.AtomicLong(0)
    fun takeMaxWorkNanos(): Long = maxWork.getAndSet(0)
    /** The game screen's refresh as measured (Hz, 0 = unknown) and refreshes per frame (0 = timer pacing). */
    val refreshHz: Float get() = vsyncPeriodNanos.let { if (it > 0.0) (1e9 / it).toFloat() else 0f }
    val refreshesPerFrame: Int get() = pacedPerFrame
    /** The speed the core runs at now (1 = real time, Infinity = uncapped FF). */
    @Volatile var currentSpeed = 1f
        private set
    /** The audio device ran dry this many times since the game started (each one a crackle). */
    @Volatile var audioUnderruns = 0
        private set

    /** The GL thread's late draws so far (EmulatorView.lateFrames), for the fps log line; null = unknown. */
    @Volatile var lateFramesSource: (() -> Long)? = null

    /** Vsync pacing's refreshes per game frame (0 = timer pacing), and refreshes since the last frame. */
    @Volatile private var pacedPerFrame = 0
    @Volatile private var ticksSinceFrame = 0
    /** This game's frames miss the GL thread's wait too often: drawn a refresh later, never waited for (as a DS
     * game's). Latched until the core stops - flipping back and forth would skip / repeat a frame each time. */
    @Volatile private var slowFrames = false

    /** GL thread: the game's screen just refreshed (EmulatorView.onVsync). Returns how long the draw may
     * wait for the frame this refresh starts (~60% of a refresh), or 0 when none is due. */
    fun onVsync(): Long {
        val now = System.nanoTime()
        val d = now - lastVsyncNanos
        lastVsyncNanos = now
        // A gap (the view paused, a dropped refresh) isn't a period.
        if (d in 4_000_000L..34_000_000L) {
            val p = vsyncPeriodNanos
            vsyncPeriodNanos = if (p == 0.0) d.toDouble() else p + (d - p) * 0.05
        }
        // At 1x two frames' worth of refreshes banked: one was too few - refreshes arriving unevenly (two close
        // together, then a gap) were dropped, each a game frame never run and its audio missing (GitHub #80).
        // More let the game race after a hiccup (4 were: a visible rush); the audio fill decides now (vsyncPace).
        // Fast-forward keeps one: its groups run on the tick.
        val n = pacedPerFrame
        if (vsyncTicks.availablePermits() < (if (n == 0) 1 else 2 * n)) vsyncTicks.release()
        if (n == 0) return 0L
        // A DS frame takes 4-20 ms on the RP6 / Thor: waited for within ~60% of a refresh, every one past ~10 ms
        // missed it and the last picture showed twice (GitHub #215: 45 of 120 frames late in HeartGold). It's drawn
        // a refresh later instead, never waited for: a frame of latency, and a whole refresh to finish in.
        // So does a game whose frames keep missing the wait (GitHub #63: Radical Red's frames take 5-9 ms on the
        // RP6 with 12-14 ms ones among them; 1-9 of 120 were late, each a stutter and a 57-58 on FPS / CPU).
        if (nds || slowFrames) { ticksSinceFrame++; return 0L }
        return if (++ticksSinceFrame >= n) (vsyncPeriodNanos * 0.6).toLong() else 0L
    }

    /** Refreshes per game frame while the display paces 1x, or 0 for timer pacing. */
    private fun vsyncsPerFrame(): Int {
        val p = vsyncPeriodNanos
        if (p == 0.0 || System.nanoTime() - lastVsyncNanos > 100_000_000L) return 0
        val hz = 1e9 / p
        val n = (hz / GBA_FPS).roundToInt()
        // Within 1.5% of the GBA's own rate (60 / 120 Hz: +0.46%); a 90 Hz screen would judder either way.
        return if (n >= 1 && kotlin.math.abs(hz / n / GBA_FPS - 1.0) < 0.015) n else 0
    }

    private var videoBuf: ByteBuffer? = null
    private var raFrameFailed = false
    private var vw = 0
    private var vh = 0

    private fun loop(rom: File, save: File, resume: File?) {
        emuJobs.clear()   // the last game's
        SaveBackups.snapshot(save)
        if (!MgbaCore.pkInit(rom.absolutePath, save.absolutePath)) {
            loadError = "mGBA could not load ${rom.name}"
            running = false
            if (resume != null) onResumeResult?.invoke(false)
            return
        }
        vw = MgbaCore.pkVideoWidth()
        vh = MgbaCore.pkVideoHeight()
        videoBuf = MgbaCore.pkVideoBuffer()
        onCoreReady?.invoke(vw, vh)

        // A state carries the save as it was when it was made, and loading it writes
        // that copy over the save file. So resume only if the file still holds that
        // save: if it was changed since (another emulator, a save copied in, the saves
        // folder moved), the file wins and the game boots from it.
        var resumed: File? = null
        if (resume != null && resume.isFile && resume.length() > 0) {
            if (MgbaCore.pkStateMatchesSave(resume.absolutePath) == 0) {
                Log.w("pokedaisy", "resume skipped: ${save.name} changed since the state was made")
                resume.renameTo(File(resume.parentFile, "resume.replaced"))
                SnapshotCache.fileFor(resume).delete()
                startNotice = tr("Your save changed since you last played here, so the game started from the save file")
            } else {
                val ok = MgbaCore.pkLoadState(resume.absolutePath)
                Log.i("pokedaisy", "resume state load: $ok")
                resume.delete()
                SnapshotCache.fileFor(resume).delete()
                if (ok) resumed = resume
            }
            onResumeResult?.invoke(resumed != null)
        }
        // The game's set loads after the resume (it needs the server), so the
        // resumed progress is handed over to be put back once it has.
        runCatching { RetroAchievements.onCoreStarted(rom, resumedFrom = resumed) }
            .onFailure { Log.e("pokedaisy", "achievements start failed", it) }
        // After the resume (a state never carries cheats) and before the first frame.
        // The save was backed up above, before any cheat could touch it.
        applyCheats()
        applyTmPatch()
        applyLook()

        val sampleRate = MgbaCore.pkSampleRate()
        // Out of audio resources (or a device mid-reroute) mustn't take the process down:
        // the game runs without sound instead.
        val track: AudioTrack? = runCatching { buildAudioTrack(sampleRate).also { it.play() } }
            .onFailure { Log.e("pokedaisy", "audio track failed", it) }
            .getOrNull()

        val scratch = ShortArray(4096)
        val frameNanos = 1_000_000_000L / 60L
        var next = System.nanoTime()
        var fpsFrames = 0
        var fpsSince = System.nanoTime()
        var fpsWork = 0L
        var fpsMaxWork = 0L
        // The companion's samples inside that work (onSample: the whole read + decode, on this thread): count, total, worst.
        var fpsSamples = 0
        // Audio over the same window (vsync pacing): frames the core made, frames the track took, how far it played.
        var fpsAudioMade = 0L
        var fpsAudioTaken = 0L
        var fpsPlayedAt = -1L
        var fpsSampleWork = 0L
        var fpsMaxSample = 0L
        var fpsLate = -1L
        var slowWindows = 0
        slowFrames = false
        var prevSpeed = 1f
        var paused = false
        var lastSample = 0L
        var lastBattleInputSample = 0L
        var lastFfMusicFile: File? = null
        var requestedKey: String? = null
        // The song playing and when it started (elapsedRealtime), so a clip picks up
        // where the song is: from the top for a song that just started, further on
        // when FF comes on mid-song. A key blinking off for a moment isn't a new song.
        var songKey: String? = null
        var songStartedAt = 0L
        // SPED-UP: the core's audio averaged down to real time (a box
        // filter, so it's the game's own sound pitched and tempo'd up).
        val spedUpOut = ShortArray(scratch.size)
        // A DS game's STEADY (nds/pk_nds_music.h): a copy of the game, its ARM9 stopped, playing on at 1x by itself -
        // started when FF comes on and again for each new song (a few frames on, once the sound driver has it); its
        // audio goes out instead of the game's. Off for the run if a copy ever fails to start (SPED-UP stands in).
        var dsMusicOn = false
        var dsMusicOk = true
        var dsMusicBgm = -1
        var dsMusicWait = -1   // game frames since a copy became due (-1: none due)
        val dsMusicOut = ShortArray(4096)
        var dsMusicFrames = 0   // audio frames the copy wrote since the last fps log line
        var dsMusicPos = 0      // the copy's last frame of audio not written yet: dsMusicOut[pos, pos + left)
        var dsMusicLeft = 0
        var dsMusicPeak = 0     // the loudest sample the copy wrote since the last fps line (the log's peak=)
        var gamePeak = 0        // the loudest of the game's own since then (gamePeak=)
        var decimPhase = 0f
        var decimL = 0
        var decimR = 0
        var decimN = 0
        var measuredSpeed = 4f // uncapped FF's real rate, from the fps log below
        // Vsync pacing (see the class comment): the device buffer's size, and what's been
        // written to it since the last resync - minus its play head, what's still queued.
        val bufferFrames = track?.bufferSizeInFrames ?: 0
        var framesWritten = 0L
        var underruns = track?.underrunCount ?: 0
        var vsyncMode = false
        // Audio queued after this frame's write (vsync pacing only): picks how the next frame is paced. Null on a
        // frame that wrote none (the game's audio paused for a STEADY clip, say): it paces on the tick alone - a
        // stale "low" once ran SMART's 1x menus at up to 3x while the clip played.
        var queuedAfter: Int? = null
        var lastQueued = -1
        var catchUpRun = 0
        // Underruns while vsync paces, counted once it has settled; too many and this run goes back to the timer.
        val trouble = AudioTroubleWatch()
        var vsyncFallback = false
        var vsyncSettledAt = 0L
        // For the fps log line: audio a full buffer cut off, frames run at once / held back.
        var fpsDropped = 0
        var fpsCatchUps = 0
        var fpsHolds = 0
        // Fast-forward at a whole number of game frames per refresh (2x / 3x / 4x on 60 Hz): the group of frames
        // for this refresh, run back to back on its tick, the last one drawn. Timer-paced frames with EXPAND's
        // window drawn every 12 ms gave the 60 Hz screen steps of 3, 3, 5 game frames: a judder RetroArch hasn't.
        var ffPerTick = 0
        var ffGroup = 0
        // The group's size this device keeps up with (FfGroupFit: a DS walking HeartGold's 3D at 4x didn't), and when it began.
        val ffFit = FfGroupFit()
        var ffGroupStart = 0L
        var rewindApplied = -1   // pkInit dropped any buffer: set it again for this core
        var wideApplied = WideScreen.OFF   // pkInit starts with WIDE off
        var wideFaultReported = false
        var lastWideDraw = 0L
        var frame = 0L
        try {
            while (running) {
                val frameStart = System.nanoTime()
                battleInput.tick()
                val rewindWanted = rewindEntries
                if (rewindWanted != rewindApplied) {
                    MgbaCore.pkSetRewind(rewindWanted)
                    rewindApplied = rewindWanted
                }
                val probeOnly = wideGame == WideScreen.OFF && gbSceneGame != WideScreen.OFF
                val wideWanted = if (probeOnly) gbSceneGame else wideGame
                if (wideWanted != wideApplied) {
                    MgbaCore.pkWideConfigure(wideWanted)
                    wideApplied = wideWanted
                    wideCamera?.reset()
                }
                val camera = wideCamera
                if (probeOnly) {
                    MgbaCore.pkWideSetView(0, 0, 0, 0)   // nothing drawn: the native side only works out the scene
                    wideScene = MgbaCore.pkWideScene()
                } else if (wideApplied != WideScreen.OFF && camera != null) {
                    // Never worth the game: a camera that throws leaves the plain frame on screen for this run.
                    try {
                        // Fast-forward runs several frames per screen refresh: only one of them is ever seen, so only
                        // that one has its window drawn (the rest just keep the RAM after them) - it was most of the CPU.
                        val now = System.nanoTime()
                        val draw = effectiveSpeed() <= 1f || (if (ffPerTick > 0) ffGroup <= 1 else now - lastWideDraw >= WIDE_DRAW_NANOS)
                        if (draw) lastWideDraw = now
                        MgbaCore.pkWideSetDraw(draw)
                        camera.walking = input.mask and DIRECTIONS != 0
                        wideScene = MgbaCore.pkWideScene()
                        val win = camera.frame(wideScene)
                        if (win != null) MgbaCore.pkWideSetView(win.x, win.y, win.w, win.h)
                        else MgbaCore.pkWideSetView(0, 0, 0, 0)
                    } catch (t: Throwable) {
                        Log.e("pokedaisy", "WIDE camera failed; zoom off for this run", t)
                        wideCamera = null
                        MgbaCore.pkWideSetView(-WideCamera.MARGIN_FALLBACK, 0, camera.frameW + 2 * WideCamera.MARGIN_FALLBACK, camera.frameH)
                        runCatching { onWideOff?.invoke("camera") }
                    }
                }
                val rewinding = rewindHeld && rewindWanted > 0
                MgbaCore.pkSetRewinding(rewinding)
                // TURBO A / B: pressed 2 frames, released 2, in game frames - so FF mashes faster too.
                val reset = if (softResetFrames > 0) { softResetFrames--; SOFT_RESET_KEYS } else 0
                var keys = input.mask or reset or (if ((frame++ / 2) % 2 == 0L) input.turboMask else 0)
                // AUTO-RUN: B flipped while walking on the player's own field (holding it walks) - never in a script, a
                // battle, a menu screen (B leaves it) or on a bike (the Acro Bike's wheelie is B). Nor with A held: the
                // expansion's DexNav creeps on A (Seaglass & co.), but checks B's run first, so a held B scared the
                // Pokémon off. A while walking does nothing in the other games.
                if (autoRun && (gba || nds) && keys and DIRECTIONS != 0 && keys and MgbaCore.Key.A == 0 &&
                    fieldState and AUTO_RUN_BLOCKERS == MgbaCore.FIELD_KNOWN && fieldScreen
                ) keys = keys xor MgbaCore.Key.B
                MgbaCore.pkSetKeys(keys)
                if (nds) {
                    // Fast-forward on the refresh shows the last frame of each group: the others skip their drawing, and
                    // EXPAND's slices (drawn after a frame, for the next) only come before a shown one - drawing them for
                    // every frame was two thirds of a DS frame's cost, and at 3x the group outran the refresh (stutter).
                    val ff = ffPerTick > 0
                    MgbaCore.pkNdsSetDraw(!ff || ffGroup <= 1, !ff || (if (ffGroup > 1) ffGroup <= 2 else ffFit.size <= 1))
                }
                MgbaCore.pkRunFrame()
                onFrame?.invoke()
                framesRun++
                ticksSinceFrame = 0
                // Achievements never take the game down with them (a pending JNI
                // exception from a callback would otherwise end this loop).
                try {
                    RetroAchievements.onFrame()
                } catch (t: Exception) {
                    if (!raFrameFailed) Log.e("pokedaisy", "achievements frame failed", t)
                    raFrameFailed = true
                }

                servicePending()
                if (sleeping) {
                    runCatching { track?.pause(); track?.flush() }
                    synchronized(sleepLock) {
                        while (sleeping && running && pendingSuspend == null) sleepLock.wait(SLEEP_POLL_MS)
                        sleeping = false
                    }
                    Log.i("pokedaisy", "sleep: woke")
                    if (!paused) runCatching { track?.play() }
                    // As after the audio pausing below: flush() put the play head back at 0. And the frames due while
                    // it slept are not owed.
                    framesWritten = 0L
                    vsyncSettledAt = System.nanoTime() + VSYNC_SETTLE_NANOS
                    vsyncTicks.drainPermits()
                    next = System.nanoTime()
                }
                if (wideApplied != WideScreen.OFF && !wideFaultReported && MgbaCore.pkWideFault() != 0) {
                    wideFaultReported = true
                    Log.e("pokedaisy", "WIDE switched itself off: signal ${MgbaCore.pkWideFault()} while drawing")
                    runCatching { onWideOff?.invoke("fault") }
                }

                val nowMs = System.currentTimeMillis()
                // The companion's full sample: once a second, four times in a battle (HP and PP follow the
                // game within ~1/4 s; a battle state change gets one at once anyway, see onBattleInputSample).
                val sampleEvery = if (battleInput.gameState != com.pokedaisy.app.companion.data.BATTLE_INPUT_NONE) 250L else 1000L
                if (nowMs - lastSample >= sampleEvery) {
                    lastSample = nowMs
                    val sampleStart = System.nanoTime()
                    runCatching { onSample?.invoke() }
                    val took = System.nanoTime() - sampleStart
                    fpsSamples++
                    fpsSampleWork += took
                    if (took > fpsMaxSample) fpsMaxSample = took
                }
                // Every frame while a switch runs flat out behind the held frame: at that speed a
                // 66 ms gap is dozens of game frames, and the turbo would run on into the battle.
                if (battleInput.holdFrame || nowMs - lastBattleInputSample >= 66L) {
                    lastBattleInputSample = nowMs
                    runCatching { onBattleInputSample?.invoke() }
                }

                // GBA memory watchers; a Game Boy game has none of these screens / engines to read.
                mapOpen = gba && RegionMapWatch.isOpen(InProcessReader)
                // A shop's screens count as the field: its clerk talks inside them (GitHub #41).
                menuOpen = gba && FfMenuWatch.tick(InProcessReader) && !ShopWatch.isOpen(InProcessReader) ||
                    nds && fieldState and MgbaCore.FIELD_MENU != 0   // a DS menu screen: the field's child process
                battleWaiting = nds && fieldState and MgbaCore.FIELD_BATTLE_INPUT != 0
                if (wideApplied == WideScreen.OFF || (wideCamera == null && !probeOnly)) wideScene = -1
                // A fade between two maps keeps whatever the HUD was doing.
                if (wideScene != WideCamera.SCENE_FADE) {
                    // The field's own screen, positively (FfMenuWatch): "no menu found" isn't enough - a game whose field
                    // screen isn't learned yet (a new game) never reports a menu, so its naming screen passed as field.
                    // A Game Boy game: its scene (pk_wide_gb.c's screen match, with EXPAND or without) must say FIELD.
                    val screen = if (gba || nds) fieldScreen && !mapOpen else wideScene == WideCamera.SCENE_FIELD
                    val field = screen && !battleInput.holdFrame &&
                        battleInput.gameState == com.pokedaisy.app.companion.data.BATTLE_INPUT_NONE &&
                        (wideScene == -1 || wideScene == WideCamera.SCENE_FIELD)
                    fieldFrames = if (field) fieldFrames + 1 else 0
                    val show = fieldFrames >= FIELD_SETTLE_FRAMES
                    if (onField.value != show) onField.value = show
                }
                // SCENE SPEEDS / AUTO-RUN / RUN INDOORS / WILD ENCOUNTERS: the field as WIDE's tables read it (or a
                // pending hack's), every frame on a game they know - a few bytes, and SETTINGS learns from it whether
                // BIKE / SURF SPEED can work here.
                if (fieldGame != fieldApplied || wildFlag != wildApplied) {
                    MgbaCore.pkFieldConfigure(if (gba) fieldGame else WideScreen.OFF, wildFlag.toInt(), wildEncounters < 100)
                    fieldApplied = fieldGame
                    wildApplied = wildFlag
                    fieldMovesKnown = false
                }
                // WILD ENCOUNTERS on a Game Boy game: held every frame while OFF, put back once on the field when ON.
                gbField?.let { gf ->
                    val hold = wildEncounters == 0
                    runCatching {
                        if (hold) { gf.holdWild(gameCalls); gbWildHeld = true }
                        else if (gbWildHeld != false && gbOnField && gf.releaseWild(gameCalls)) gbWildHeld = false
                    }.onFailure { Log.e("pokedaisy", "Game Boy wild encounters failed", it) }
                } ?: run { gbWildHeld = null }
                // A DS game holds its own switch in its field read (pk_nds_field.cpp: the map's rates at 0).
                val wildKeep = if (wildFlag != 0L || nds) wildEncounters else 100
                fieldState = when {
                    nds -> MgbaCore.pkFieldState(false, wildKeep)
                    gba && fieldGame != WideScreen.OFF -> MgbaCore.pkFieldState(runIndoors, wildKeep)
                    else -> 0
                }
                if (fieldState and MgbaCore.FIELD_AVATAR != 0) fieldMovesKnown = true
                val bike = MgbaCore.FIELD_AVATAR or MgbaCore.FIELD_BIKE
                riding = (fieldState and bike) == bike
                val battleState = battleInput.gameState
                scene = when {
                    // A battle's own bag and party screens count as menus, as SMART has them.
                    battleState == com.pokedaisy.app.companion.data.BATTLE_INPUT_PARTY_OPEN ||
                        battleState == com.pokedaisy.app.companion.data.BATTLE_INPUT_BAG_OPEN || menuOpen || mapOpen ||
                        fieldState and MgbaCore.FIELD_MENU != 0 -> Scene.MENU
                    battleState != com.pokedaisy.app.companion.data.BATTLE_INPUT_NONE || FfMenuWatch.battling ||
                        fieldState and MgbaCore.FIELD_BATTLE != 0 -> Scene.BATTLE
                    fieldState and MgbaCore.FIELD_TEXT != 0 -> Scene.TEXT
                    // Riding / surfing on the field (a cutscene that holds the controls stays FIELD).
                    fieldState and (MgbaCore.FIELD_LOCKED or MgbaCore.FIELD_AVATAR) == MgbaCore.FIELD_AVATAR &&
                        fieldState and MgbaCore.FIELD_SURF != 0 -> Scene.SURF
                    fieldState and (MgbaCore.FIELD_LOCKED or MgbaCore.FIELD_AVATAR) == MgbaCore.FIELD_AVATAR &&
                        fieldState and MgbaCore.FIELD_BIKE != 0 -> Scene.BIKE
                    else -> Scene.FIELD
                }
                // PLAYER LOOK: the player's sprite set up again once the field's own screen runs (never over a battle's
                // sprites); the native side checks it's still the player's.
                // Not done (the screen mid-fade, the palette buffers not agreeing yet): again on a later field frame, for
                // up to ~10 s of them.
                if (lookRefreshPending && gba && fieldScreen && !mapOpen && !FfMenuWatch.battling) {
                    if (MgbaCore.pkLookRefresh(lookPatch?.playerPal) || ++lookRefreshTries >= LOOK_REFRESH_TRIES) {
                        if (lookRefreshTries >= LOOK_REFRESH_TRIES) Log.w("pokedaisy", "player look: the sprite's palette never settled, left as it is")
                        lookRefreshPending = false
                        lookRefreshTries = 0
                    }
                }
                if (gba) stepFollower(fieldScreen && !mapOpen && !FfMenuWatch.battling &&
                    battleInput.gameState == com.pokedaisy.app.companion.data.BATTLE_INPUT_NONE && fieldState and MgbaCore.FIELD_SURF == 0)
                if (gba) stepTint(fieldScreen && !mapOpen && !FfMenuWatch.battling &&
                    battleInput.gameState == com.pokedaisy.app.companion.data.BATTLE_INPUT_NONE)
                logField(effectiveSpeed())
                val speed = effectiveSpeed()
                // FF the player still has on - SMART only holding it at 1x for a menu.
                val ffWanted = requestedSpeed() > 1f
                val mode = ffMusicMode
                // The song the game is playing right now (FfMusicKey): read
                // every frame, so a clip starts and stops exactly with it.
                val ffMusicKey = if (gba) FfMusicKey.current(InProcessReader)
                    else gen1Music?.let { com.pokedaisy.app.companion.data.Gen1Music.current(InProcessReader, it) }
                if (ffMusicKey != null && ffMusicKey != songKey) {
                    songKey = ffMusicKey
                    songStartedAt = android.os.SystemClock.elapsedRealtime()
                }
                // STEADY: have each new song rendered on its own, at any
                // speed, so its clip is usually ready before FF needs it.
                if (mode != FfMusicMode.STEADY) requestedKey = null
                else if (ffMusicKey != null && ffMusicKey != requestedKey) {
                    requestedKey = ffMusicKey
                    ffMusicCache.notePlayed(ffMusicKey)   // the songs played longest ago go first when the cache is full
                    if (!ffMusicCache.complete(ffMusicKey)) runCatching { onFfMusicWanted?.invoke(ffMusicKey) }
                }
                // The clip follows the FF the player asked for, not SMART's 1x stretches: it
                // plays straight through a menu instead of handing over to the game's music
                // (at another spot in the song) and starting over when FF picks up again.
                val clip = if (ffWanted && mode == FfMusicMode.STEADY && ffMusicKey != null) {
                    ffMusicCache.fileIfCached(ffMusicKey)
                } else null
                if (clip != lastFfMusicFile) {
                    lastFfMusicFile = clip
                    onFfMusicChanged?.invoke(
                        clip?.let { FfMusicPlayer.Clip(it, ffMusicCache.introIfCached(ffMusicKey!!), songStartedAt) },
                    )
                }
                // STEADY with the song's clip still rendering: FF stays silent until it's in (it used to
                // play the song SPED-UP meanwhile, then switch) - unless no clip will ever come.
                val awaitingClip = mode == FfMusicMode.STEADY && clip == null && ffMusicKey != null &&
                    ffMusicWillRender?.invoke(ffMusicKey) == true
                // A DS game: no clips, the copy (above). Like a clip it follows the FF the player asked for, so it
                // plays straight through SMART's 1x menus.
                val dsBgm = if (nds && dsMusicOk && mode == FfMusicMode.STEADY && ffWanted) MgbaCore.pkNdsBgm() else -1
                if (dsBgm >= 0) {
                    // A copy is due when FF comes on and for each new song: taken once the sound driver has the song
                    // (a few frames on) and no volume fade runs (a song coming back after a battle fades in - a copy
                    // taken then stayed nearly silent), or after DS_MUSIC_MAX_WAIT_FRAMES whatever.
                    if (!dsMusicOn && dsMusicWait < 0) dsMusicWait = DS_MUSIC_SETTLE_FRAMES
                    else if (dsMusicOn && dsBgm != dsMusicBgm) {
                        // 0: the game fading its song out before the next (its fades run on the ARM9, stopped in the
                        // copy).
                        if (dsBgm == 0) MgbaCore.pkNdsMusicFade(DS_MUSIC_FADE_FRAMES) else dsMusicWait = 0
                    }
                    dsMusicBgm = dsBgm
                    if (dsMusicWait >= 0 && ++dsMusicWait > DS_MUSIC_SETTLE_FRAMES &&
                        (!MgbaCore.pkNdsBgmFading() || dsMusicWait > DS_MUSIC_MAX_WAIT_FRAMES)
                    ) {
                        dsMusicWait = -1
                        dsMusicLeft = 0
                        dsMusicOn = MgbaCore.pkNdsMusicStart()
                        if (dsMusicOn) Log.i("pokedaisy", "FF music: DS copy playing song %d".format(dsBgm))
                        else {
                            dsMusicOk = false
                            Log.w("pokedaisy", "FF music: the DS music copy didn't start; SPED-UP for this run")
                        }
                    }
                } else {
                    if (dsMusicOn) MgbaCore.pkNdsMusicStop()
                    dsMusicOn = false
                    dsMusicLeft = 0
                    dsMusicBgm = -1
                    dsMusicWait = -1
                }
                // Heard at 1x (unless a clip is playing through SMART's 1x); during FF
                // only as SPED-UP (which STEADY falls back to only for a song or ROM it can't
                // render). Slow-mo stays muted.
                val spedUp = speed > 1f && clip == null && mode != FfMusicMode.OFF && !awaitingClip && !dsMusicOn
                val wantPaused = rewinding || battleInput.holdFrame ||
                    !((speed == 1f && clip == null) || spedUp || dsMusicOn)
                // Refreshes per frame when the display paces 1x; 0 = the timer and blocking audio.
                val perFrame = if (speed == 1f && track != null && bufferFrames > 0 && !vsyncFallback) vsyncsPerFrame() else 0
                pacedPerFrame = perFrame
                val ffPer = ffFramesPerRefresh(speed, vsyncsPerFrame())
                if (ffPer != ffPerTick) {
                    ffPerTick = ffPer
                    ffFit.reset(ffPer)
                    ffGroup = 1   // this frame ends a group: drawn, then the next refresh's tick
                    if (ffPer > 0) vsyncTicks.drainPermits()
                }
                if ((perFrame > 0) != vsyncMode) {
                    vsyncMode = perFrame > 0
                    if (vsyncMode) {
                        vsyncTicks.drainPermits()   // start on the next refresh, not a banked one
                        vsyncSettledAt = System.nanoTime() + VSYNC_SETTLE_NANOS
                        catchUpRun = 0
                        // Blocking writes left the buffer about full.
                        framesWritten = (track!!.playbackHeadPosition.toLong() and 0xFFFFFFFFL) + bufferFrames
                    } else {
                        MgbaCore.pkSetAudioRate(sampleRate.toDouble())
                    }
                }
                gameAudioPaused = wantPaused
                if (wantPaused != paused) {
                    paused = wantPaused
                    if (paused) runCatching { track?.pause(); track?.flush() }
                    else runCatching { track?.play() }
                    // flush() empties the buffer and puts the play head back at 0.
                    framesWritten = 0L
                    vsyncSettledAt = System.nanoTime() + VSYNC_SETTLE_NANOS
                }

                queuedAfter = null
                val n = MgbaCore.pkReadAudio(scratch)
                fpsAudioMade += n / 2
                if (BuildConfig.DEBUG && nds) for (i in 0 until n) gamePeak = maxOf(gamePeak, kotlin.math.abs(scratch[i].toInt()))
                // In FF, the game's sounds for the companion's automated battle presses stay silent but the last.
                if (n > 0 && speed > 1f && battleInput.muteGame) java.util.Arrays.fill(scratch, 0, n, 0.toShort())
                if (dsMusicOn && !paused && track != null) {
                    // The copy's audio instead of the game's: as many of its frames as keep the buffer full, so it
                    // plays at 1x whatever the game's speed (it makes 1/60 s a frame). Full, not half: a track that
                    // hasn't started playing yet starts only once its buffer is (its start threshold) - kept half full,
                    // one never made a sound.
                    val played = track.playbackHeadPosition
                    var queued = framesWritten.toInt() - played
                    if (queued < 0) queued = 0
                    framesWritten = played.toLong() + queued
                    // A copy frame runs only once the last one's audio is all written (what didn't fit waits in
                    // dsMusicOut): run per game frame instead, the copy ran at the game's speed and its music skipped.
                    var runs = 0
                    while (queued < bufferFrames) {
                        if (dsMusicLeft == 0) {
                            if (runs++ >= DS_MUSIC_MAX_FRAMES) break
                            dsMusicLeft = MgbaCore.pkNdsMusicRead(dsMusicOut).coerceAtLeast(0)
                            dsMusicPos = 0
                            if (dsMusicLeft == 0) break
                        }
                        val w = track.write(dsMusicOut, dsMusicPos, dsMusicLeft, AudioTrack.WRITE_NON_BLOCKING)
                        if (w <= 0) break
                        if (BuildConfig.DEBUG) for (i in dsMusicPos until dsMusicPos + w) dsMusicPeak = maxOf(dsMusicPeak, kotlin.math.abs(dsMusicOut[i].toInt()))
                        dsMusicPos += w
                        dsMusicLeft -= w
                        framesWritten += w / 2
                        queued += w / 2
                        dsMusicFrames += w / 2
                    }
                } else if (n > 0 && !paused && track != null) {
                    if (vsyncMode) {
                        // The play head wraps as an unsigned int; the difference doesn't care.
                        val played = track.playbackHeadPosition
                        var queued = framesWritten.toInt() - played
                        val nowUnderruns = track.underrunCount
                        if (queued < 0 || nowUnderruns != underruns) queued = 0   // ran dry: the guess was off
                        if (nowUnderruns != underruns && System.nanoTime() >= vsyncSettledAt &&
                            trouble.record(System.nanoTime())
                        ) {
                            // The device keeps running dry with this screen's pacing: the timer and blocking
                            // writes for the rest of this run, as before vsync pacing (1.1.x).
                            vsyncFallback = true
                            Log.w("pokedaisy", "audio: underruns keep coming under vsync pacing; timer pacing for this run")
                        }
                        underruns = nowUnderruns
                        // No silence written in: it was the stutter (30-40 ms of it each time the buffer ran low). A
                        // low buffer runs the next frame at once instead (vsyncPace below).
                        framesWritten = played.toLong() + queued
                        // Samples per emulated second so that a frame's worth lasts one frame of the
                        // display's, nudged towards a half-full buffer (dynamic rate control).
                        val fill = queued.toDouble() / bufferFrames
                        val frameHz = 1e9 / (vsyncPeriodNanos * perFrame)
                        val drc = 1.0 + DRC_MAX * (1.0 - 2.0 * fill).coerceIn(-1.0, 1.0)
                        // The core's own frame rate: a DS makes 59.83 frames a second, not the GBA's 59.73 (0.17% that DRC
                        // otherwise spent a third of its range on, GitHub #215).
                        MgbaCore.pkSetAudioRate(sampleRate * (if (nds) NDS_FPS else GBA_FPS) / frameHz * drc)
                        val w = track.write(scratch, 0, n, AudioTrack.WRITE_NON_BLOCKING)
                        if (w > 0) fpsAudioTaken += w / 2
                        if (w > 0) framesWritten += w / 2
                        if (w in 0 until n) fpsDropped += (n - w) / 2   // a full buffer cut it short
                        queuedAfter = queued + maxOf(w, 0) / 2
                        lastQueued = queuedAfter
                    } else if (speed == 1f) {
                        track.write(scratch, 0, n)   // blocking → real-time pacing at 1x
                    } else {
                        // The speed the game really runs at: a refresh-paced group the device can't fit runs fewer frames.
                        val factor = if (speed.isInfinite()) measuredSpeed else if (ffPerTick > 0) ffFit.size * speed / ffPerTick else speed
                        var m = 0
                        for (i in 0 until n / 2) {
                            decimL += scratch[i * 2]; decimR += scratch[i * 2 + 1]; decimN++
                            decimPhase += 1f
                            if (decimPhase >= factor) {
                                decimPhase -= factor
                                spedUpOut[m++] = (decimL / decimN).toShort()
                                spedUpOut[m++] = (decimR / decimN).toShort()
                                decimL = 0; decimR = 0; decimN = 0
                            }
                        }
                        // Never block: FF mustn't be paced by the audio device.
                        if (m > 0) track.write(spedUpOut, 0, m, AudioTrack.WRITE_NON_BLOCKING)
                    }
                }

                val work = System.nanoTime() - frameStart
                workNanos += work
                if (work > maxWork.get()) maxWork.set(work)
                currentSpeed = speed
                if (fpsFrames and 31 == 0) track?.let { t -> runCatching { audioUnderruns = t.underrunCount } }
                fpsWork += work
                if (work > fpsMaxWork) fpsMaxWork = work
                if (++fpsFrames >= 120) {
                    val now = System.nanoTime()
                    val fps = fpsFrames * 1e9 / (now - fpsSince)
                    // Pacing, for a logcat read: the screen's refresh and refreshes per frame (0 = the timer), the GL
                    // thread's late draws since the last line, the frames' work (avg / worst ms) and audio underruns.
                    val late = lateFramesSource?.invoke() ?: -1L
                    // Then vsync pacing's audio guard: frames run at once (catchup) / held to a fresh refresh (hold),
                    // audio frames a full buffer cut off (dropped), how full the buffer is, and whether it gave up.
                    Log.i("pokedaisy", ("fps=%.1f (%s) refresh=%.2fHz paced=%s ffPerTick=%s late=%d work=%.1f/%.1fms sample=%d:%.1f/%.1fms underruns=%d " +
                        "catchup=%d hold=%d dropped=%d queued=%d/%d audio=%d/%d/%d%s").format(
                        fps, speedTag(speed), refreshHz, if (slowFrames) "$pacedPerFrame (slow: not waited for)" else "$pacedPerFrame", if (ffPerTick > 0 && ffFit.size != ffPerTick) "${ffFit.size}/$ffPerTick" else "$ffPerTick", if (late < 0 || fpsLate < 0) -1 else late - fpsLate,
                        fpsWork / fpsFrames / 1e6, fpsMaxWork / 1e6,
                        fpsSamples, if (fpsSamples > 0) fpsSampleWork / fpsSamples / 1e6 else 0.0, fpsMaxSample / 1e6, audioUnderruns,
                        fpsCatchUps, fpsHolds, fpsDropped, lastQueued, bufferFrames,
                        fpsAudioMade, fpsAudioTaken,
                        (track?.playbackHeadPosition?.toLong()?.and(0xFFFFFFFFL) ?: 0L).let { p -> if (fpsPlayedAt < 0) -1 else p - fpsPlayedAt },
                        if (vsyncFallback) " fallback" else "") +
                        if (nds) " dsMusic=%s/%d written=%d peak=%d gamePeak=%d track=%d".format(dsMusicOn, dsMusicBgm, dsMusicFrames, dsMusicPeak, gamePeak, track?.playState ?: -1) else "")
                    dsMusicPeak = 0
                    gamePeak = 0
                    dsMusicFrames = 0
                    fpsCatchUps = 0
                    fpsHolds = 0
                    fpsDropped = 0
                    // [slowFrames]: 3+ late draws in two windows of 1x frames (a single one can be the device's hiccup).
                    if (!slowFrames && pacedPerFrame > 0 && speed == 1f && fpsLate >= 0 && late - fpsLate >= SLOW_LATE_FRAMES &&
                        ++slowWindows >= SLOW_WINDOWS
                    ) {
                        slowFrames = true
                        Log.i("pokedaisy", "pacing: frames keep missing the refresh; drawn a refresh later from now on")
                    }
                    fpsLate = late
                    fpsWork = 0L
                    fpsMaxWork = 0L
                    fpsSamples = 0
                    fpsAudioMade = 0L
                    fpsAudioTaken = 0L
                    fpsPlayedAt = track?.playbackHeadPosition?.toLong()?.and(0xFFFFFFFFL) ?: -1L
                    fpsSampleWork = 0L
                    fpsMaxSample = 0L
                    if (speed.isInfinite()) measuredSpeed = (fps / 60.0).toFloat().coerceAtLeast(1f)
                    fpsFrames = 0
                    fpsSince = now
                }

                if (vsyncMode) {
                    // Wait for the game screen's next refresh(es); if they stop coming (the view
                    // went away) the wait times out and the timer takes over from the next frame.
                    // The audio left in the buffer can override that (GitHub #80): low = a refresh was missed,
                    // run the next frame now (at most two in a row: a device that never fills can't make the
                    // game race); high = ahead, drop the banked refreshes and wait for a fresh one.
                    val pace = queuedAfter?.let { vsyncPace(it, bufferFrames) } ?: VsyncPace.ON_TICK
                    val wait = (vsyncPeriodNanos * perFrame * 3).toLong()
                    if (pace == VsyncPace.CATCH_UP && catchUpRun < MAX_CATCH_UP) {
                        catchUpRun++
                        fpsCatchUps++
                        vsyncTicks.tryAcquire(perFrame)   // take the refreshes it stands for, if they came
                    } else {
                        catchUpRun = 0
                        if (pace == VsyncPace.HOLD) {
                            fpsHolds++
                            vsyncTicks.drainPermits()
                        }
                        runCatching { vsyncTicks.tryAcquire(perFrame, wait, java.util.concurrent.TimeUnit.NANOSECONDS) }
                    }
                    next = System.nanoTime()
                    prevSpeed = speed
                    continue
                }

                if (ffPerTick > 0) {
                    // Fast-forward on the refresh: the group's frames run as soon as the tick comes, then the
                    // next tick (or the timer takes over if the refreshes stop).
                    if (--ffGroup <= 0) {
                        // A group that outran its refresh left that refresh nothing new (GitHub #216: HeartGold at 4x
                        // swung between ~2x and 4x, its SPED-UP music short): fewer frames a group until they fit.
                        val groupDone = System.nanoTime()
                        if (ffGroupStart > 0L) ffFit.groupDone(groupDone - ffGroupStart, vsyncPeriodNanos.toLong())
                        val wait = (vsyncPeriodNanos * 3).toLong()
                        runCatching { vsyncTicks.tryAcquire(1, wait, java.util.concurrent.TimeUnit.NANOSECONDS) }
                        ffGroup = ffFit.size
                        ffGroupStart = System.nanoTime()
                    }
                    next = System.nanoTime()
                    prevSpeed = speed
                    continue
                }

                if (speed.isInfinite()) {
                    next = System.nanoTime()                 // uncapped: no wait
                    if (fpsFrames and 63 == 0) Thread.yield()
                    continue
                }

                val interval = (frameNanos / speed).toLong()
                if (speed != prevSpeed) next = System.nanoTime() + interval else next += interval
                prevSpeed = speed
                val sleep = next - System.nanoTime()
                if (sleep > 1_000_000L) {
                    try {
                        Thread.sleep(sleep / 1_000_000L)
                    } catch (_: InterruptedException) {
                    }
                } else if (sleep < -interval * 4) {
                    next = System.nanoTime()
                }
            }
        } catch (t: Throwable) {
            Log.e("pokedaisy", "emu loop crashed", t)
            loadError = t.message ?: t.toString()
        } finally {
            // A loop that died mid-game mustn't look alive, or leave a suspend for the next start.
            running = false
            pacedPerFrame = 0   // no frames are coming: the GL thread mustn't wait for one
            pendingSuspend = null
            runCatching { track?.stop() }
            runCatching { track?.release() }
            // The STEADY clip is only told when it changes, and the next loop starts with none: silence it here, or a
            // RESTART GAME (or another ROM) kept the old song playing over the new one.
            runCatching { onFfMusicChanged?.invoke(null) }
            if (nds) runCatching { MgbaCore.pkNdsMusicStop() }
            runCatching { RetroAchievements.onCoreStopping() }
            // The GL thread lets go of the frame buffer before pkDeinit frees it.
            runCatching { onCoreStopping?.invoke() }.onFailure { Log.e("pokedaisy", "core stopping hook failed", it) }
            videoBuf = null
            runCatching { finishSave() }
            MgbaCore.pkDeinit()
            runCatching { RetroAchievements.onCoreStopped() }
        }
    }

    /** A save the game is in the middle of writing gets to finish before the core stops or a suspend state is taken
     * (MgbaCore.pkFinishSave): a game left mid-save and then swiped away from recents came back with its newer save
     * slot half written. 600 frames is ten seconds of game time; they run unpaced, a fraction of that. */
    private fun finishSave() {
        val n = MgbaCore.pkFinishSave(600)
        if (n > 0) Log.i("pokedaisy", "let the game finish its save: $n frames")
    }

    /** Loads [cheatText] into the core (emu thread). Achievements pause while any is in. */
    private fun applyCheats() {
        cheatsDirty = false
        val text = if (gba) cheatText else ""
        val n = MgbaCore.pkCheatsApply(text)
        if (n < 0) Log.w("pokedaisy", "cheats didn't load")
        else if (text.isNotEmpty()) Log.i("pokedaisy", "cheats loaded: $n")
        runCatching { RetroAchievements.setCheatsActive(n > 0) }
    }

    /** REUSABLE TMS onto the ROM (emu thread): the sites patched, or the file's bytes back. */
    private fun applyTmPatch() {
        tmPatchDirty = false
        MgbaCore.pkRomUnpatch()
        val sites = tmSites
        if (!gba || !reusableTms || sites.isEmpty()) return
        val all = sites.all {
            MgbaCore.pkRomPatch(it.at, it.expect, it.branch, com.pokedaisy.app.companion.data.ReusableTms.ALWAYS_SKIP)
        }
        // All or none: a ROM that only matched in part would still use TMs up on the other paths.
        if (!all) MgbaCore.pkRomUnpatch()
        Log.i("pokedaisy", "reusable TMs: ${if (all) "${sites.size} site(s) patched" else "code didn't match"}")
    }

    /** PLAYER LOOK onto the ROM (emu thread): the last look's blocks out, this one's in (all or none), then the palettes. */
    private fun applyLook() {
        lookDirty = false
        MgbaCore.pkRomUnblock()
        val patch = lookPatch
        // The player's sprite still holds the last look's record (a Pokémon's 32x32 shape, a frame table in the tail):
        // set up from the ROM as it is now, whatever comes next.
        if (gba && patch == null) { lookRefreshPending = true; lookRefreshTries = 0 }
        if (!gba || patch == null) return
        if (patch.blocks.isNotEmpty()) {
            val data = java.io.ByteArrayOutputStream()
            patch.blocks.forEach { data.write(it.data) }
            val n = MgbaCore.pkRomBlocks(
                IntArray(patch.blocks.size) { 0x08000000 + patch.blocks[it].off },
                IntArray(patch.blocks.size) { patch.blocks[it].data.size },
                IntArray(patch.blocks.size) { patch.blocks[it].crc },
                data.toByteArray(),
            )
            Log.i("pokedaisy", "player look: ${if (n < 0) "the ROM didn't match, left as it is" else "$n block(s) in"}")
            if (n < 0) return
        }
        settleLook()
    }

    /** The worn look's palettes over any other hero's the game has loaded (emu thread). */
    private fun settleLook() {
        if (gba) { lookRefreshPending = true; lookRefreshTries = 0 }
        val patch = lookPatch ?: return
        if (!gba) return
        var n = 0
        for (p in patch.palettes) {
            val from = java.io.ByteArrayOutputStream().also { o -> p.from.forEach { o.write(it) } }.toByteArray()
            n += MgbaCore.pkRepalette(from, p.to)
        }
        if (n > 0) Log.i("pokedaisy", "player look: $n palette(s) swapped")
    }

    private fun servicePending() {
        repeat(emuJobs.size) { emuJobs.poll()?.let { job -> runCatching(job).onFailure { Log.e("pokedaisy", "emu job failed", it) } } }
        if (cheatsDirty) applyCheats()
        if (tmPatchDirty) applyTmPatch()
        if (lookDirty) applyLook()
        // The jobs queued by now (one a job queues runs on a later frame); a delayed one waits its frames.
        repeat(fieldItemJobs.size) {
            val job = fieldItemJobs.poll() ?: return@repeat
            if (job.delayFrames > 0) {
                job.delayFrames--
                fieldItemJobs.add(job)
                return@repeat
            }
            // The game's code part-way through this frame (its interrupt handler, a lag frame): its functions are
            // called on a later frame that ends cleanly, never in the middle of its own (MgbaCore.pkCallSafe).
            if (gba && job.waitedFrames < CALL_WAIT_FRAMES && !MgbaCore.pkCallSafe()) {
                job.waitedFrames++
                fieldItemJobs.add(job)
                return@repeat
            }
            // Not with a menu screen or the region map up either: the game itself only uses items from its bag.
            // A Game Boy game: its scene must be the plain field.
            val busy = job.inBattle || if (gba) menuOpen || mapOpen else !gbOnField
            runCatching { job.run(gameCalls, busy) }
                .onFailure { Log.e("pokedaisy", "field item job failed", it) }
        }
        pendingSuspend?.let { target ->
            pendingSuspend = null
            finishSave()
            val ok = saveState(target)
            Log.i("pokedaisy", "suspend state save: $ok -> ${target.name}")
            running = false
            return
        }
        pendingSleep?.let { target ->
            pendingSleep = null
            finishSave()
            val ok = saveState(target)
            Log.i("pokedaisy", "sleep: state save $ok -> ${target.name}, holding")
            synchronized(sleepLock) {
                sleeping = true
                sleepLock.notifyAll()
            }
            return
        }
        val save = pendingSave
        if (save >= 0) {
            pendingSave = -1
            // Keep one level of undo: stash the slot's previous contents first.
            runCatching {
                val cur = states.stateFile(save)
                if (cur.isFile) cur.copyTo(states.bakFile(save), overwrite = true)
                RetroAchievements.progressFile(cur).let { if (it.isFile) it.copyTo(RetroAchievements.progressFile(states.bakFile(save)), overwrite = true) }
                SnapshotCache.copy(cur, states.bakFile(save))
                states.thumbFile(save).let { if (it.isFile) it.copyTo(states.bakThumbFile(save), overwrite = true) }
            }
            val ok = saveState(states.stateFile(save))
            if (ok) writeThumb(states.thumbFile(save))
            onStateResult?.invoke(Hotkeys.Action.SAVE_STATE, save, ok)
        }
        val load = pendingLoad
        if (load >= 0) {
            pendingLoad = -1
            if (states.exists(load)) {
                // Snapshot the live state so the load can be undone.
                runCatching { saveState(states.undoLoadFile) }
                val ok = loadState(states.stateFile(load))
                onStateResult?.invoke(Hotkeys.Action.LOAD_STATE, load, ok)
            } else {
                onStateResult?.invoke(Hotkeys.Action.LOAD_STATE, load, false)
            }
        }
        if (pendingUndoSave) {
            pendingUndoSave = false
            val slot = currentSlot
            val ok = runCatching {
                val bak = states.bakFile(slot)
                if (!bak.isFile) return@runCatching false
                bak.copyTo(states.stateFile(slot), overwrite = true)
                RetroAchievements.progressFile(states.stateFile(slot)).let { cur ->
                    val bakProgress = RetroAchievements.progressFile(bak)
                    if (bakProgress.isFile) bakProgress.copyTo(cur, overwrite = true) else cur.delete()
                }
                SnapshotCache.copy(bak, states.stateFile(slot))
                states.bakThumbFile(slot).let { if (it.isFile) it.copyTo(states.thumbFile(slot), overwrite = true) }
                true
            }.getOrDefault(false)
            onStateResult?.invoke(Hotkeys.Action.UNDO_SAVE, slot, ok)
        }
        if (pendingUndoLoad) {
            pendingUndoLoad = false
            val ok = states.undoLoadFile.let { it.isFile && it.length() > 0 } &&
                loadState(states.undoLoadFile)
            onStateResult?.invoke(Hotkeys.Action.UNDO_LOAD, currentSlot, ok)
        }
    }

    /** A savestate plus its achievement progress (RetroAchievements.progressFile) and
     * companion data ([onStateSaved]) beside it. */
    private fun saveState(target: File): Boolean {
        val ok = MgbaCore.pkSaveState(target.absolutePath)
        if (ok) RetroAchievements.afterSaveState(target)
        if (ok) runCatching { onStateSaved?.invoke(target) }.onFailure { Log.w("pokedaisy", "state side data failed", it) }
        return ok
    }

    /** Loads [source] and puts achievement progress back to where that state had it. */
    private fun loadState(source: File): Boolean {
        val ok = MgbaCore.pkLoadState(source.absolutePath)
        if (ok) RetroAchievements.afterLoadState(source)
        // A state made in another PLAYER LOOK holds that look's palettes.
        if (ok) settleLook()
        return ok
    }

    private fun writeThumb(dest: File) {
        val buf = videoBuf ?: return
        try {
            // Build the pixels by hand from the raw RGBX bytes (the same byte
            // order EmulatorView uploads as GL_RGBA) instead of
            // copyPixelsFromBuffer(): mGBA leaves the 4th byte as junk, not an
            // opaque alpha, and an ARGB_8888 Bitmap is *premultiplied*, so
            // copying the raw bytes in and reading them back via getPixels()
            // un-premultiplied every pixel by that junk alpha - zero-alpha
            // pixels came back black and the rest scaled - leaving thumbnails
            // that looked like faint, patchy "shades" of the screen. Forcing
            // alpha to 0xFF afterwards (the previous fix) was too late: the
            // colors were already destroyed.
            // Absolute gets: they leave the buffer's position alone (the GL
            // thread uploads from this same buffer).
            val px = IntArray(vw * vh)
            for (i in px.indices) {
                val r = buf.get(i * 4).toInt() and 0xFF
                val g = buf.get(i * 4 + 1).toInt() and 0xFF
                val b = buf.get(i * 4 + 2).toInt() and 0xFF
                px[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            }
            val full = Bitmap.createBitmap(px, vw, vh, Bitmap.Config.ARGB_8888)
            val tw = 160
            val th = (tw * vh / vw)
            val thumb = Bitmap.createScaledBitmap(full, tw, th, true)
            dest.outputStream().use { thumb.compress(Bitmap.CompressFormat.PNG, 90, it) }
            full.recycle()
            thumb.recycle()
        } catch (t: Throwable) {
            Log.w("pokedaisy", "thumb write failed", t)
        }
    }

    private fun buildAudioTrack(sampleRate: Int): AudioTrack {
        val minBuf = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_STEREO,
            AudioFormat.ENCODING_PCM_16BIT,
        ).coerceAtLeast(4096)
        return AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_GAME)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build(),
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(sampleRate)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                    .build(),
            )
            // Room for the vsync pacing's rate control to keep it half full: at least ~85 ms.
            .setBufferSizeInBytes(maxOf(minBuf * 2, 4096 * 4))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
            .build()
            .also { t ->
                // A low-latency track can start with less of its buffer in use than was asked for: vsync pacing
                // keeps it about half full, so give it all of it (and say what it got, for a logcat read: #80).
                val asked = t.bufferSizeInFrames
                runCatching { t.setBufferSizeInFrames(t.bufferCapacityInFrames) }
                Log.i("pokedaisy", "audio: %d Hz, buffer %d frames (was %d, capacity %d)".format(
                    sampleRate, t.bufferSizeInFrames, asked, t.bufferCapacityInFrames))
            }
    }

    private companion object {
        /** DAY AND NIGHT's ease: per frame, of 256 (a full night's dimming in about half a second). */
        const val TINT_STEP = 4f

        /** PLAYER LOOK's refresh: field frames it's tried again while the palette can't go in yet. */
        const val LOOK_REFRESH_TRIES = 600

        /** The last loop started on the native core, by any engine (there's one core, `g` in pokedaisy_jni.c). */
        @Volatile private var coreLoop: Thread? = null
        private val coreLock = Any()

        /** Frames a field-item job waits for a clean frame end (MgbaCore.pkCallSafe) - after that its calls just fail
         * (pk_call refuses them), so the job reports its own failure rather than calling into the game mid-code. */
        const val CALL_WAIT_FRAMES = 120

        /** FIELD HUD: frames of plain field before [onField] comes on (~1/3 s at 1x). */
        const val FIELD_SETTLE_FRAMES = 20

        private const val SOFT_RESET_FRAMES = 10
        private const val SOFT_RESET_KEYS = MgbaCore.Key.A or MgbaCore.Key.B or MgbaCore.Key.SELECT or MgbaCore.Key.START

        /** The GBA's (and Game Boy's) frame rate: 16777216 Hz / 280896 cycles per frame. */
        const val GBA_FPS = 16777216.0 / 280896.0
        /** A DS's: its 33.51 MHz bus over 263 lines of 355 dots, 6 cycles each. */
        const val NDS_FPS = 33513982.0 / 560190.0
        /** Late draws in a 120-frame window, in this many windows, that make a game's frames [slowFrames]. */
        private const val SLOW_LATE_FRAMES = 3
        private const val SLOW_WINDOWS = 2
        /** Dynamic rate control's most the audio is stretched or squeezed (RetroArch's default). */
        const val DRC_MAX = 0.005
        /** Vsync pacing: frames run at once in a row at most, when the audio buffer runs low ([vsyncPace]). */
        const val MAX_CATCH_UP = 2
        /** Underruns this soon after vsync pacing (re)starts or the audio resumes don't count ([AudioTroubleWatch]). */
        const val VSYNC_SETTLE_NANOS = 2_000_000_000L
        /** [sleep]'s longest wait for the loop to hold (a save it has to finish first runs up to 600 frames). */
        const val SLEEP_WAIT_NANOS = 3_000_000_000L
        /** A held loop looks again this often, should a stop's nudge be missed. */
        const val SLEEP_POLL_MS = 250L
        /** Speed steps, by the index saved per ROM. Index 0 (1×) is the normal, audio-on state; SPEED CYCLE
         * goes through the first [CYCLE_STEPS], GAME SPEED adds the slow ones (muted, like SLOWMO HOLD) -
         * appended, so the indexes saved before keep their meaning. */
        val SPEED_STEPS = floatArrayOf(1f, 1.5f, 2f, 3f, 4f, 0.5f, 0.25f)
        const val CYCLE_STEPS = 5
        /** At fast-forward, a WIDE window is drawn at most this often (~80 a second: every refresh of a 60 Hz screen). */
        const val WIDE_DRAW_NANOS = 12_000_000L
        /** A DS game's STEADY copy: game frames after a new song id before copying the game again (the ARM9 hands the
         * sound driver its start command at the end of its frame), and at most this long while a volume fade runs (5 s
         * of game time); copy frames for a song fading out (~0.5 s); and at most this many copy frames run per game
         * frame (it only catches up after a hitch). */
        const val DS_MUSIC_SETTLE_FRAMES = 4
        const val DS_MUSIC_MAX_WAIT_FRAMES = 300
        const val DS_MUSIC_FADE_FRAMES = 30
        const val DS_MUSIC_MAX_FRAMES = 4
        /** pkFieldState's bits that keep AUTO-RUN off: a script holding the field, a battle, a bike (known must be set). */
        const val AUTO_RUN_BLOCKERS = MgbaCore.FIELD_KNOWN or MgbaCore.FIELD_LOCKED or MgbaCore.FIELD_BATTLE or MgbaCore.FIELD_BIKE
        /** The D-pad's bits: one held = the player walking (the look-around glides back). */
        const val DIRECTIONS = MgbaCore.Key.UP or MgbaCore.Key.DOWN or MgbaCore.Key.LEFT or MgbaCore.Key.RIGHT
    }
}

/**
 * Fast-forward on the refresh: game frames to run on each refresh of a screen that shows one game frame per
 * [refreshesPerFrame] refreshes at 1x (1 on 60 Hz, 2 on 120 Hz), or 0 when [speed] isn't a whole number of
 * frames a refresh there (the timer paces it). 2x is 2 a refresh on 60 Hz but 1 on 120 Hz - multiplying
 * instead ran 120 Hz screens at 4x the speed asked for (GitHub #64).
 */
internal fun ffFramesPerRefresh(speed: Float, refreshesPerFrame: Int): Int {
    if (speed <= 1f || !speed.isFinite() || refreshesPerFrame <= 0) return 0
    val n = speed / refreshesPerFrame
    val r = n.roundToInt()
    return if (r >= 1 && kotlin.math.abs(n - r) < 0.01f) r else 0
}

/**
 * Fast-forward on the refresh runs a group of frames per refresh; [size] is how many this device keeps up with, up to
 * the [reset] value asked for (GitHub #216). Decided on a running average of the groups' time ([EMA]), so one slow
 * group (a GC, a map load) changes nothing:
 * - over [OVER] of a refresh, a frame goes - but only while the smaller group still runs at least [KEEP] of what the
 *   late one manages (4 frames in 17.6 ms make ~3.8 a refresh, unevenly: 3 even ones are smoother; 2 in 17 ms still
 *   make ~2, and 1 would halve it);
 * - under [ROOM] of a refresh for [wait] groups, a frame is tried; a try that ends up over [OVER] within [PROBE_GROUPS]
 *   groups goes back, and the next one waits twice as long ([MAX_WAIT_GROUPS] at most).
 * No cost per frame is modelled: a DS's skipped frames cost a fraction of its drawn one, and a group of 1 has none.
 */
internal class FfGroupFit {
    var size = 0
        private set
    private var max = 0
    private var avg = 0.0
    private var roomy = 0
    private var wait = PROBE_GROUPS
    /** Groups since a frame was added, while it's on trial. */
    private var trial = -1

    fun reset(perTick: Int) {
        max = perTick
        size = perTick
        avg = 0.0
        roomy = 0
        wait = PROBE_GROUPS
        trial = -1
    }

    /** A group of [size] frames took [groupNanos] against a refresh of [periodNanos]. */
    fun groupDone(groupNanos: Long, periodNanos: Long) {
        if (size <= 0 || periodNanos <= 0) return
        avg = if (avg == 0.0) groupNanos.toDouble() else avg + (groupNanos - avg) * EMA
        val period = periodNanos.toDouble()
        if (avg > period * OVER) {
            roomy = 0
            // Frames a refresh the late groups make, run back to back, against the smaller group's.
            val making = size * period / avg
            if (size > 1 && (size - 1) >= making * KEEP) {
                if (trial >= 0) wait = minOf(wait * 2, MAX_WAIT_GROUPS)   // the frame just tried didn't fit
                trial = -1
                avg = avg * (size - 1) / size
                size--
            }
            return
        }
        if (trial >= 0 && ++trial >= PROBE_GROUPS) {
            trial = -1
            wait = PROBE_GROUPS
        }
        if (trial < 0 && size < max && avg < period * ROOM) {
            if (++roomy >= wait) {
                roomy = 0
                trial = 0
                size++
            }
        } else {
            roomy = 0
        }
    }

    companion object {
        const val EMA = 0.1
        const val OVER = 0.95
        const val KEEP = 0.75
        const val ROOM = 0.85
        const val PROBE_GROUPS = 60
        const val MAX_WAIT_GROUPS = 1800
    }
}

/** How vsync pacing runs the next 1x frame, by the audio the device still holds ([vsyncPace]). */
internal enum class VsyncPace { CATCH_UP, ON_TICK, HOLD }

/**
 * Vsync pacing's audio guard (GitHub #80): with [queued] audio frames left of a [bufferFrames] buffer after this
 * frame's write, the next frame runs at once when it's under 3/8 (a refresh went missing: its frame never ran),
 * waits for a fresh refresh when it's over 7/10 (ahead: one more frame's write would overflow and be cut), else
 * waits for the next refresh as usual. Dynamic rate control keeps it near half full in between.
 */
internal fun vsyncPace(queued: Int, bufferFrames: Int): VsyncPace = when {
    bufferFrames <= 0 -> VsyncPace.ON_TICK
    queued < bufferFrames * 3 / 8 -> VsyncPace.CATCH_UP
    queued > bufferFrames * 7 / 10 -> VsyncPace.HOLD
    else -> VsyncPace.ON_TICK
}

/** [max] underruns within [windowNanos] under vsync pacing: [record] says it's time to go back to the timer. */
internal class AudioTroubleWatch(private val max: Int = 4, private val windowNanos: Long = 10_000_000_000L) {
    private val times = ArrayDeque<Long>()

    /** One underrun at [now] (System.nanoTime); true once [max] fell within [windowNanos]. */
    fun record(now: Long): Boolean {
        times.addLast(now)
        while (now - times.first() > windowNanos) times.removeFirst()
        return times.size >= max
    }
}
