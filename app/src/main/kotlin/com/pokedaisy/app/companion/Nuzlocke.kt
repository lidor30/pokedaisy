package com.pokedaisy.app.companion

import kotlinx.atomicfu.locks.synchronized
import kotlinx.atomicfu.locks.SynchronizedObject
import androidx.compose.runtime.mutableStateOf
import com.pokedaisy.app.companion.data.GameKind
import com.pokedaisy.app.companion.data.NuzlockeEvent
import com.pokedaisy.app.companion.data.NuzlockeOutcome
import com.pokedaisy.app.companion.data.NuzlockeRules
import com.pokedaisy.app.companion.data.NuzlockeRun
import com.pokedaisy.app.companion.data.NuzlockeSample
import com.pokedaisy.app.companion.data.NuzlockeTracker
import com.pokedaisy.app.companion.data.SnapshotView
import com.pokedaisy.app.companion.data.isDsGame
import com.pokedaisy.app.companion.data.isGameBoyGame
import com.pokedaisy.app.companion.i18n.tr

/**
 * SETTINGS > NUZLOCKE and the NUZLOCKE tab, for the running ROM (another game's areas and Pokémon mean nothing here):
 * whether it's on and its run ([com.pokedaisy.app.companion.data.NuzlockeTracker]). Compose state, so the tab and the
 * party's marks follow each sample; the activity [load]s each ROM's from Prefs (keyed by its CRC32) and every change is
 * stored through `save`. Off keeps the run, to pick up where it was when it's turned on again; NEW RUN clears it.
 * Previews start off and store nothing ([preview] shows one).
 */
object Nuzlocke {
    private val onState = mutableStateOf(false)
    private val runState = mutableStateOf(NuzlockeRun())
    private val tracker = NuzlockeTracker()
    private var saveOn: (Boolean) -> Unit = {}
    private var saveRun: (String) -> Unit = {}

    private val lock = SynchronizedObject()

    val on: Boolean get() = onState.value
    val run: NuzlockeRun get() = runState.value

    /**
     * A game the tracker follows: the GBA games (the Game Boy and DS readers don't give it a battle's type yet), but not
     * Emerald Rogue - a roguelike walks the same areas again every run, so one catch per area can't hold.
     */
    fun supports(game: GameKind?): Boolean =
        game != null && !game.isGameBoyGame && !game.isDsGame && game != GameKind.EMERALD_ROGUE

    /** A party Pokémon out of the run (fainted once), while NUZLOCKE is on. */
    fun isDead(personality: Long): Boolean = on && runState.value.isDead(personality)

    /** [on] / [stored] are this ROM's; the two savers write changes back. */
    fun load(on: Boolean, stored: String?, saveOn: (Boolean) -> Unit, saveRun: (String) -> Unit) = synchronized(lock) {
        this.saveOn = saveOn
        this.saveRun = saveRun
        val r = NuzlockeRun.decode(stored)
        tracker.reset(r)
        runState.value = r
        onState.value = on
    }

    fun setOn(v: Boolean) = synchronized(lock) {
        onState.value = v
        saveOn(v)
    }

    /**
     * A fresh sample (any thread, the activity's afterSample): what it changed goes into the run, and the notices for the
     * game screen come back, worded (none with WARNINGS off, or NUZLOCKE off).
     */
    fun onSample(s: SnapshotView): List<String> = synchronized(lock) {
        if (!onState.value || !s.connected || s.unsupported || !supports(s.game)) return@synchronized emptyList()
        val before = tracker.run
        val events = tracker.feed(NuzlockeSample.of(s))
        if (tracker.run != before) store(tracker.run)
        if (tracker.run.rules.warnings) events.map(::notice) else emptyList()
    }

    /** The tab's changes: an encounter set by hand (null clears it), a Pokémon marked dead or alive, the rules. */
    fun setOutcome(area: Int, outcome: NuzlockeOutcome?) = synchronized(lock) { change { r ->
        r.copy(encounters = if (outcome == null) r.encounters.filter { it.area != area } else r.encounters.map { if (it.area == area) it.copy(outcome = outcome) else it })
    } }

    fun setDead(personality: Long, dead: Boolean, where: String = "") = synchronized(lock) { change { r ->
        r.copy(mons = r.mons.map { if (it.personality == personality) it.copy(dead = dead, diedIn = if (dead) where else "", diedLevel = if (dead) it.level else 0) else it })
    } }

    fun setRules(rules: NuzlockeRules) = synchronized(lock) { change { it.copy(rules = rules) } }

    /** NEW RUN: every encounter and Pokémon forgotten, the rules kept. The party seen next starts it. */
    fun newRun() = synchronized(lock) { change { NuzlockeRun(rules = it.rules) } }

    private fun change(f: (NuzlockeRun) -> NuzlockeRun) {
        val r = f(tracker.run)
        tracker.set(r)
        store(r)
    }

    private fun store(r: NuzlockeRun) {
        runState.value = r
        saveRun(r.encode())
    }

    /** Screenshot tests and ui-preview: [r] shown, on, stored nowhere. */
    fun preview(r: NuzlockeRun) = load(true, r.encode(), {}, {})

    private fun notice(e: NuzlockeEvent): String = when (e) {
        is NuzlockeEvent.FirstEncounter -> tr("First encounter in {0}: {1}", e.areaName, e.foe)
        is NuzlockeEvent.AreaUsed -> tr("{0} is used up: no catching here", e.areaName)
        is NuzlockeEvent.Dupe -> tr("{0} is caught already: this one doesn't count", e.foe)
        is NuzlockeEvent.Caught -> tr("{0} joins the run", e.name)
        is NuzlockeEvent.Died -> tr("{0} fainted: it's out of the run", e.name)
        is NuzlockeEvent.DeadInParty -> tr("{0} is out of the run: leave it in the PC", e.name)
    }
}
