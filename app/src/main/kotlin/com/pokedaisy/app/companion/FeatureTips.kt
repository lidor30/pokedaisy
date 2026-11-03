package com.pokedaisy.app.companion

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * The features a TIP card introduces (SETTINGS > APP > TIPS, `Prefs.tips`, ON): one a player hasn't found, shown at a
 * moment it would help ([TipScheduler]). [generic] tips may show on any calm stretch of the field once the player has
 * played a while; the others only after their moment ([TipScheduler.moment]): holding B while walking, a few wild
 * battles in a row, a TM used up, the game's own Fly map closed. An [TipId.early] tip's moment calls it up without the
 * half hour's wait or the gap after another card ([TipScheduler.EARLY_SETTLE] into a session): the DS triggers, which a
 * player needs from their first DS game on (the user's call, 2026-11-03).
 */
enum class TipId(val key: String, val generic: Boolean, val early: Boolean = false) {
    ZOOM("zoom", true),
    EXPAND("expand", true),
    QUICK_MENU("quick_menu", true),
    HUD("hud", true),
    PC("pc", true),
    AUTO_RUN("auto_run", false),
    WILD("wild", false),
    REUSABLE_TMS("reusable_tms", false),
    FLY("fly", false),
    /** A DS game on one screen: L2 floats its touch screen in, R2 steps DS LAYOUT. [early]: shown in the first DS game. */
    DS_TRIGGERS("ds_triggers", false, early = true),
    ;

    companion object {
        fun of(key: String) = entries.firstOrNull { it.key == key }
    }
}

/** One tip's history: how often it has been shown, when last (field play time), and whether it's done with - tried,
 *  turned down, or the player found the feature by themselves. */
data class TipRecord(val shows: Int = 0, val lastShownAt: Long = -1, val done: Boolean = false)

/** Where [TipScheduler] keeps what it has to remember across launches ([com.pokedaisy.app.Prefs] in the app). */
interface TipStore {
    /** Field play time, every game together, in ms. */
    var fieldMillis: Long

    /** [fieldMillis] when the last card went away (-1 = none yet). */
    var lastClosedAt: Long

    var records: Map<TipId, TipRecord>
}

/**
 * When a tip shows. Time is field play time ([tick] counts only frames on the field), so menus, battles and the
 * Library don't count:
 * - none before [FIRST_TIP_AFTER] of play, and none in a session's first [SESSION_SETTLE] (not as the game comes up);
 * - [GAP] between one card going away and the next (the user's grace period: never one after the other);
 * - only once the field has been calm for [CALM_MS] (no battle, text box, menu, panel or companion window - [tick]'s
 *   `calm`), and never while another card is up;
 * - a moment's tip ([moment]) first, while it's fresh ([MOMENT_TTL]); then the generic ones, in [TipId] order;
 * - a tip is offered only where [available] says the game and device have it and it isn't on already;
 * - TRY IT, NO THANKS or the player finding the feature ([used]) ends a tip for good; one that ran out unanswered may
 *   come back once ([MAX_SHOWS]), [AGAIN_AFTER] later.
 */
class TipScheduler(private val store: TipStore, private val available: (TipId) -> Boolean) {
    /** The tip on screen, if any (Compose state: the card's host reads it). */
    var current by mutableStateOf<TipId?>(null)
        private set

    /** TIPS in SETTINGS: off, nothing shows (and a card up goes away). */
    var enabled = true
        set(v) {
            field = v
            if (!v) current?.let { close(it) }
        }

    private var records = store.records
    private var fieldMillis = store.fieldMillis
    private var savedAt = fieldMillis
    private var sessionMillis = 0L
    private var calmMillis = 0L
    private val moments = mutableMapOf<TipId, Long>()

    /** [dtMillis] of play went by; [onField]: the player was on the field (not in a battle, menu or text box: [calm] is
     *  stricter - also no HUD panel, QUICK MENU or companion window open). */
    fun tick(dtMillis: Long, onField: Boolean, calm: Boolean) {
        if (!onField || dtMillis <= 0) {
            calmMillis = 0
            return
        }
        fieldMillis += dtMillis
        sessionMillis += dtMillis
        calmMillis = if (calm) calmMillis + dtMillis else 0
        if (fieldMillis - savedAt >= SAVE_EVERY) save()
        if (current == null && enabled && calmMillis >= CALM_MS) next()?.let { show(it) }
    }

    /** The moment [tip] would help just happened (it shows at the next calm stretch, if it's still fresh then). */
    fun moment(tip: TipId) {
        if (!done(tip)) moments[tip] = fieldMillis
    }

    /** The player found the feature by themselves: its tip never shows (a card up for it goes away). */
    fun used(tip: TipId) {
        if (records[tip]?.done == true) return
        update(tip) { it.copy(done = true) }
        if (current == tip) close(tip)
    }

    /** TRY IT or NO THANKS: either way that tip is done with. */
    fun answered(tip: TipId) {
        update(tip) { it.copy(done = true) }
        close(tip)
    }

    /** The card ran out with no answer: maybe later. */
    fun timedOut(tip: TipId) = close(tip)

    /** The tip that would show now, ignoring [calm]-ness (tests). */
    fun next(): TipId? {
        moments.entries.removeAll { fieldMillis - it.value > MOMENT_TTL || done(it.key) }
        val ready = { t: TipId -> !done(t) && canShowAgain(t) && available(t) }
        // An early tip's moment: no half hour first, no gap after another card.
        if (sessionMillis >= EARLY_SETTLE) moments.entries.filter { it.key.early }.sortedBy { it.value }.firstOrNull { ready(it.key) }?.let { return it.key }
        if (fieldMillis < FIRST_TIP_AFTER || sessionMillis < SESSION_SETTLE) return null
        val last = store.lastClosedAt
        if (last >= 0 && fieldMillis - last < GAP) return null
        moments.entries.sortedBy { it.value }.firstOrNull { ready(it.key) }?.let { return it.key }
        return TipId.entries.firstOrNull { it.generic && ready(it) }
    }

    /** Field play time so far (ms). */
    val playedMillis get() = fieldMillis

    /** Writes what's not saved yet (the activity's onPause). */
    fun save() {
        store.fieldMillis = fieldMillis
        savedAt = fieldMillis
    }

    private fun show(tip: TipId) {
        moments.remove(tip)
        update(tip) { it.copy(shows = it.shows + 1, lastShownAt = fieldMillis) }
        current = tip
    }

    private fun close(tip: TipId) {
        if (current != tip) return
        current = null
        calmMillis = 0
        store.lastClosedAt = fieldMillis
        save()
    }

    private fun done(tip: TipId) = records[tip]?.done == true

    private fun canShowAgain(tip: TipId): Boolean {
        val r = records[tip] ?: return true
        return r.shows < MAX_SHOWS && (r.lastShownAt < 0 || fieldMillis - r.lastShownAt >= AGAIN_AFTER)
    }

    private fun update(tip: TipId, change: (TipRecord) -> TipRecord) {
        records = records + (tip to change(records[tip] ?: TipRecord()))
        store.records = records
    }

    companion object {
        const val MINUTE = 60_000L
        const val FIRST_TIP_AFTER = 30 * MINUTE
        const val GAP = 5 * MINUTE
        const val SESSION_SETTLE = MINUTE
        /** An [TipId.early] tip's wait into a session: the game up and the player walking, a few seconds. */
        const val EARLY_SETTLE = 5_000L
        const val CALM_MS = 3_000L
        const val MOMENT_TTL = 10 * MINUTE
        const val MAX_SHOWS = 2
        const val AGAIN_AFTER = 60 * MINUTE
        private const val SAVE_EVERY = 15_000L

        /** How long a card stays up unanswered. */
        const val SHOW_MS = 15_000L
    }
}

/** The app's one [TipScheduler] (both screens read [TipScheduler.current]); the game's activity sets [available] for the
 *  running game and ticks it. Null until [init] (previews, screenshot tests: no card). */
object FeatureTips {
    /** Whether a tip's feature is there to try in the running game (and not on already). */
    @Volatile
    var available: (TipId) -> Boolean = { false }

    @Volatile
    var scheduler: TipScheduler? = null
        private set

    fun init(prefs: com.pokedaisy.app.Prefs): TipScheduler =
        scheduler ?: TipScheduler(prefs.tipStore) { available(it) }.also {
            it.enabled = prefs.tips
            scheduler = it
        }

    /** The player found [tip]'s feature: no card for it, ever. */
    fun used(tip: TipId) = scheduler?.used(tip)

    fun moment(tip: TipId) = scheduler?.moment(tip)
}
