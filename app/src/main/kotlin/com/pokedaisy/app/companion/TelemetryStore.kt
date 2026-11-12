package com.pokedaisy.app.companion

import com.pokedaisy.app.companion.i18n.tk
import android.util.Log
import com.pokedaisy.app.companion.data.DecompIconSource
import com.pokedaisy.app.companion.data.InProcessReader
import com.pokedaisy.app.companion.data.MemoryReader
import com.pokedaisy.app.companion.data.SnapshotView
import com.pokedaisy.app.companion.data.TelemetrySampler
import com.pokedaisy.app.companion.data.activeGame
import com.pokedaisy.app.companion.data.hasData
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Holds the latest [SnapshotView] for the bottom-screen UI to observe.
 * [refresh] runs ~1×/sec (EmulatorEngine.onSample): on a sampler thread over the RAM the emu thread copied at a
 * frame's end ([com.pokedaisy.app.companion.data.RamSnapshot]), or on the emu thread itself (a Game Boy game, a
 * savestate's copy), one at a time.
 */
class TelemetryStore {
    // The sampler remembers the game it detected, so a new ROM gets a new one (see [reset]).
    @Volatile private var sampler = TelemetrySampler()

    private val _snapshot = MutableStateFlow(SnapshotView(connected = false, error = tk("starting…")))
    val snapshot: StateFlow<SnapshotView> = _snapshot

    /** WHICH GAME IS THIS? ([com.pokedaisy.app.companion.data.BestEffort]): the player's answers, taken on the next sample. */
    val gamePick = GamePickActions(
        pick = { sampler.pickGame(it) },
        none = { sampler.pickNone() },
        keep = { sampler.keepGame() },
        seen = { sampler.bestEffortSeen() },
    )

    /** A different ROM is about to run in this activity: forget the detected
     * game. Call while the emulator is stopped. */
    fun reset() {
        generation++
        sampler = TelemetrySampler()
        cached = null
        _snapshot.value = SnapshotView(connected = false, error = tk("starting…"))
    }

    // The launch's saved copy ([com.pokedaisy.app.companion.data.SnapshotCache]) while it's shown.
    @Volatile private var cached: SnapshotView? = null
    private var cachedSamples = 0

    /** Shows [v] (the copy saved beside the state this launch resumes) until the live data is in.
     * Call before the engine starts. */
    fun showCached(v: SnapshotView) {
        val game = v.game ?: return
        // The per-game look reads activeGame; another game's icon tables would read this ROM wrong.
        if (game != activeGame) DecompIconSource.tables = com.pokedaisy.app.companion.data.IconTables()
        activeGame = game
        // And its names' language: the item tags look names up as they draw (GitHub #227).
        com.pokedaisy.app.companion.data.romGameCode = v.textCode
        com.pokedaisy.app.companion.data.romLanguage = v.textLanguage
        cachedSamples = 0
        cached = v
        _snapshot.value = v
    }

    /** The resume state didn't load after all (the save changed since): its copy is stale. */
    fun dropCached() {
        if (cached == null) return
        cached = null
        _snapshot.value = SnapshotView(connected = false, error = tk("starting…"))
    }

    /** Bumped by [reset]: a sample of the last ROM that finishes after it isn't shown. */
    @Volatile private var generation = 0

    private var logCount = 0
    private var lastPublishNanos = 0L

    /**
     * LIGHT COMPANION ([LightCompanion]): whether sample [s] goes to the companion. Not when nothing it shows changed
     * (the frame counter moves every sample), and at most every [LightCompanion.MIN_UPDATE_MS] - but a battle starting
     * or ending, or its menu changing (the move list the player waits for), always at once.
     */
    private fun lightPublishes(s: SnapshotView): Boolean {
        val prev = _snapshot.value
        if (s.inBattle != prev.inBattle || s.battleInputState != prev.battleInputState || s.connected != prev.connected ||
            s.game != prev.game || s.unsupported != prev.unsupported) return true
        if (s.copy(frameCounter = prev.frameCounter) == prev) return false
        return System.nanoTime() - lastPublishNanos >= LightCompanion.MIN_UPDATE_MS * 1_000_000L
    }

    /** Call on the emulator thread. Returns the fresh sample so the caller
     * can cheaply reuse fields from it (e.g. [SnapshotView.inBattle] /
     * [SnapshotView.regionMapSectionId] for the FF music player) without a
     * second read. */
    /** Full samples and fast battle checks taken so far, for the FPS / CPU panel's rates. */
    @Volatile var samplesTaken = 0L
        private set
    @Volatile var battleChecksTaken = 0L
        private set

    @Synchronized
    fun refresh(reader: MemoryReader = InProcessReader): SnapshotView {
        val gen = generation
        samplesTaken++
        val s = sampler.sample(reader)
        if (gen != generation) return s   // a new ROM started while this one was read: it's the last game's
        val c = cached
        if (c != null) {
            // Live replaces the saved copy once it has data (or won't get any); until then the copy stays.
            if (s.hasData || s.unsupported || ++cachedSamples > CACHED_MAX_SAMPLES) {
                cached = null
                // Icons the copy couldn't read yet (the core wasn't up) can load now.
                DecompIconSource.iconsChanged()
            } else {
                // The sampler sets FireRed while it's still detecting: keep the copy's look.
                activeGame = c.game ?: activeGame
                return s
            }
        }
        if (LightCompanion.on && !lightPublishes(s)) return s
        _snapshot.value = s
        lastPublishNanos = System.nanoTime()
        if (logCount++ % 5 == 0) {
            // pockets=<pocket id>:<count>,... - lets scripts/smoke_test.sh (and
            // manual debugging) verify item CATEGORIZATION specifically, not
            // just that items exist. A regression that tags everything as one
            // pocket (see the 2026-09-21 native-RAM bug) still shows items>0
            // here but only ever prints a single pocket id.
            val pockets = s.items.groupingBy { it.pocket }.eachCount().toSortedMap()
                .entries.joinToString(",") { "${it.key}:${it.value}" }
            Log.i(
                "pokedaisy",
                "telemetry game=$activeGame connected=${s.connected} err=${s.error} " +
                    "party=${s.party.size} inBattle=${s.inBattle} loc=${s.location.mapSecName} (${s.x},${s.y}) " +
                    "items=${s.items.size} pockets=$pockets iconTables=${com.pokedaisy.app.companion.data.DecompIconSource.tables.present}",
            )
        }
        return s
    }

    /** What a savestate made now should carry beside it: the live data, else the saved copy still showing. */
    fun forState(): SnapshotView? = _snapshot.value.takeIf { it.hasData }

    /** Fast, non-1Hz-throttled peek at (battleActiveBattler, battleInputState)
     * — see [TelemetrySampler.sampleBattleInputFast]. Call on the emu thread. */
    fun refreshBattleInputFast(): Pair<Int, Int>? {
        battleChecksTaken++
        return sampler.sampleBattleInputFast(InProcessReader)
    }

    /** The battle menu's cursor from that poll, for a Gen 1 game (else -1). */
    fun battleMenuCursor(): Int = sampler.battleMenuCursor

    /** The detected game's own gMain + inBattle offset, when it has a native config. */
    fun knownGMain(): Pair<Long, Long>? = sampler.knownGMain

    /** The detected game's gPartyMenu + gPlayerParty, when its config has them. */
    fun knownPartyMenu(): Pair<Long, Long>? = sampler.knownPartyMenu
    /** The detected game's layout for overworld party reordering, including verified English Unbound. */
    fun knownPartyReorderConfig(): com.pokedaisy.app.companion.data.NativeConfig? = sampler.knownPartyReorderConfig
    fun knownPartyMenuStyle(): Pair<Long, Int>? = sampler.knownPartyMenuStyle
    fun retailEnginePartyMenu(): Pair<Long, Long>? = sampler.retailEnginePartyMenu

    /** The detected game's own USE / GIVE / TAKE tables, when its config has them (FieldItems.kt). */
    fun knownFieldItems(): com.pokedaisy.app.companion.data.FieldItemFns? = sampler.knownFieldItems

    /** SHINY ODDS' foe party and player ID, on retail FireRed / Emerald only (ShinyOdds.kt). */
    fun knownShinyTarget(): com.pokedaisy.app.companion.data.ShinyOdds.Target? = sampler.knownShinyTarget

    /** A Game Boy game's battle party menu, for the POKéMON switch, or null. */
    fun gbPartySwitch(): com.pokedaisy.app.companion.data.GbPartySwitch? = sampler.gbPartySwitch

    /** The battle menus' cursors, where the ROM's RAM is known to be its config's. */
    fun knownCursorAddrs(): Pair<Long, Long>? = sampler.knownCursorAddrs
    fun retailRom(): Boolean = sampler.retailRom

    private companion object {
        /** ~1 sample a second: a game whose data never comes in drops the copy after this long. */
        const val CACHED_MAX_SAMPLES = 20
    }
}
