package com.pokedaisy.app.companion

import com.pokedaisy.app.Hotkeys
import com.pokedaisy.app.companion.data.FLY_MOVE
import com.pokedaisy.app.companion.data.FlyWithoutMoveGame
import com.pokedaisy.app.companion.data.SnapshotView
import com.pokedaisy.app.companion.data.hasData
import com.pokedaisy.app.companion.i18n.tr
import kotlinx.coroutines.delay

/** Host state at a tip clock sample. Only active play may produce moments or count toward a tip. */
data class GameTipFrame(
    val snapshot: SnapshotView,
    val settledOnField: Boolean,
    val blocked: Boolean,
    val dismissForOverlay: Boolean,
    val cardOnCompanion: Boolean = false,
    val moments: GameTipMoments? = null,
    val mapOpen: Boolean = false,
    val dsTriggers: Boolean = false,
) {
    val onField get() = settledOnField && snapshot.hasData && !snapshot.unsupported && !snapshot.inBattle
}

/** The live tip session owns timing, eligibility and TRY IT. Hosts supply controls and presentation state. */
class GameTipSession(
    private val settings: CompanionSettings,
    private val snapshot: () -> SnapshotView,
    private val items: () -> ItemUse?,
    private val fly: () -> FlyAction?,
    private val openQuickMenu: () -> Unit,
    private val quickAction: (QuickAction) -> Unit,
    private val notice: (String) -> Unit,
    private val frame: () -> GameTipFrame,
    private val running: () -> Boolean = { true },
    private val expanded: () -> Boolean,
    private val expandOfferSeen: () -> Boolean = { true },
    private val shortcut: (Hotkeys.Action) -> String?,
    private val quickMenuHasActions: () -> Boolean,
    private val hudAvailable: () -> Boolean,
    private val touchShortcut: (Hotkeys.Action) -> Boolean = { false },
    private val showLookAroundKey: Boolean = false,
    private val dsTriggers: () -> Boolean = { false },
    private val dsTouchFloats: () -> Boolean = { false },
    private val tryOnlyWhenCalm: Boolean = false,
    private val cycleZoom: () -> Unit,
    private val openSummary: () -> Unit,
    private val toggleDsTouch: () -> Unit = {},
    private val nextDsLayout: () -> Unit = {},
) {
    private val offer: (TipId) -> Boolean = ::available

    fun attach() {
        FeatureTips.available = offer
    }

    fun detach() {
        if (FeatureTips.available === offer) FeatureTips.available = { false }
    }

    suspend fun run(
        nowMillis: () -> Long,
        onFailure: (Throwable) -> Unit = { throw it },
        afterTick: () -> Unit = {},
    ) {
        var last = nowMillis()
        while (true) {
            delay(POLL_MILLIS)
            val now = nowMillis()
            runCatching { FeatureTips.scheduler?.let { tick(it, now - last) } }.onFailure(onFailure)
            afterTick()
            last = now
        }
    }

    internal fun tick(scheduler: TipScheduler, elapsed: Long) {
        val sample = frame()
        val onField = sample.onField
        if (!sample.cardOnCompanion && (!onField || sample.dismissForOverlay)) {
            scheduler.current?.let(scheduler::timedOut)
        }
        sample.moments?.observe(sample.snapshot, sample.mapOpen, scheduler)
        if (sample.dsTriggers) scheduler.moment(TipId.DS_TRIGGERS)
        scheduler.tick(elapsed.coerceAtMost(MAX_TICK_MILLIS), onField, onField && !sample.blocked)
    }

    private fun hasControl(action: Hotkeys.Action) = touchShortcut(action) || shortcut(action) != null

    fun available(tip: TipId): Boolean = running() && featureAvailable(tip)

    private fun featureAvailable(tip: TipId): Boolean = when (tip) {
        TipId.ZOOM -> expanded() && hasControl(Hotkeys.Action.ZOOM)
        TipId.EXPAND -> settings.wideSupported && !expanded() && expandOfferSeen()
        TipId.QUICK_MENU -> hasControl(Hotkeys.Action.QUICK_MENU) && quickMenuHasActions()
        TipId.HUD -> hudAvailable()
        TipId.DS_TRIGGERS -> dsTriggers() && shortcut(Hotkeys.Action.DS_LAYOUT) != null
        TipId.PC -> items()?.canPc() == true
        TipId.AUTO_RUN -> !settings.autoRun && settings.fieldReadable && !settings.autoRunBuiltIn
        TipId.WILD -> settings.wildEncountersSupported && settings.wildEncounters != 0
        TipId.REUSABLE_TMS -> settings.reusableTmsSupported && !settings.reusableTmsBuiltIn && !settings.reusableTms
        TipId.FLY -> fly()?.canFly() == true && (FlyWithoutMoveGame.active ||
            snapshot().party.any { !it.isEgg && it.moves.any { move -> move.id == FLY_MOVE } })
    }

    fun keys(tip: TipId): List<Pair<String, String>> = when (tip) {
        TipId.ZOOM -> listOfNotNull(
            shortcut(Hotkeys.Action.ZOOM)?.let { it to tr("ZOOM") },
            if (showLookAroundKey) tr("RIGHT STICK") to tr("LOOK AROUND") else null,
        )
        TipId.QUICK_MENU -> listOfNotNull(shortcut(Hotkeys.Action.QUICK_MENU)?.let { it to tr("QUICK MENU") })
        TipId.DS_TRIGGERS -> listOfNotNull(
            if (dsTouchFloats()) shortcut(Hotkeys.Action.TOUCH_SCREEN)?.let { it to tr("TOUCH SCREEN") } else null,
            shortcut(Hotkeys.Action.DS_LAYOUT)?.let { it to tr("NEXT LAYOUT") },
        )
        else -> emptyList()
    }

    fun tryIt(tip: TipId) {
        if (tryOnlyWhenCalm) {
            val sample = frame()
            if (!sample.onField || sample.blocked || !available(tip)) return
        }
        when (tip) {
            TipId.ZOOM -> cycleZoom()
            TipId.EXPAND -> settings.setGameAspect(GameAspect.WIDE)
            TipId.QUICK_MENU -> openQuickMenu()
            TipId.HUD -> openSummary()
            TipId.DS_TRIGGERS -> if (dsTouchFloats()) toggleDsTouch() else nextDsLayout()
            // The calm check already validates eligibility. A companion card may outlive its field action.
            else -> if (tryOnlyWhenCalm || featureAvailable(tip)) tryFieldAction(tip)
        }
    }

    private fun tryFieldAction(tip: TipId) {
        when (tip) {
            TipId.PC -> quickAction(QuickAction.PC)
            TipId.AUTO_RUN -> {
                settings.setAutoRun(true)
                notice(tr("AUTO-RUN") + ": " + tr("ON"))
            }
            TipId.WILD -> quickAction(QuickAction.WILD)
            TipId.REUSABLE_TMS -> {
                settings.setReusableTms(true)
                notice(tr("REUSABLE TMS") + ": " + tr("ON"))
            }
            TipId.FLY -> {
                openQuickMenu()
                quickAction(QuickAction.FLY)
            }
            else -> Unit
        }
    }

    private companion object {
        const val POLL_MILLIS = 500L
        const val MAX_TICK_MILLIS = 1_000L
    }
}

/** The first controller-only chord, excluding alternate keyboard bindings and mixed chords. */
fun controllerTipShortcut(
    chords: List<String>,
    label: (String) -> String = { KeyCapture.label(it) },
): String? = chords.asSequence().map { chord -> chord.split('+').map(String::trim) }
    .firstOrNull { keys -> keys.all { it.startsWith("BUTTON_") } }
    ?.joinToString(" + ", transform = label)
