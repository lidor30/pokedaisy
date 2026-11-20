package com.pokedaisy.app.companion.ui

import com.pokedaisy.app.Hotkeys
import com.pokedaisy.app.Prefs
import com.pokedaisy.app.companion.CompanionSettings
import com.pokedaisy.app.companion.FlyAction
import com.pokedaisy.app.companion.ItemUse
import com.pokedaisy.app.companion.QuickAction
import com.pokedaisy.app.companion.REPEL_KINDS
import com.pokedaisy.app.companion.data.CenterResult
import com.pokedaisy.app.companion.data.FlyResult
import com.pokedaisy.app.companion.data.FlyTargets
import com.pokedaisy.app.companion.data.ItemUseResult
import com.pokedaisy.app.companion.data.SnapshotView
import com.pokedaisy.app.companion.i18n.tr

/** The menu's existing game actions, shared by both hosts. Native-only actions stay with [extra].
 * Field operations retain ItemUse/FlyAction's queued core transactions and lifetime checks. */
class GameQuickActions(
    private val menu: QuickMenuState,
    private val prefs: Prefs,
    private val settings: CompanionSettings,
    private val snapshot: () -> SnapshotView,
    private val items: () -> ItemUse?,
    private val fly: () -> FlyAction?,
    private val hotkey: (Hotkeys.Event) -> Unit,
    private val notice: (String) -> Unit,
    private val active: () -> Boolean,
    private val extra: QuickActions,
    /** HEAL done: the party as it was before (the host shows the bars filling up); null = a notice says so. */
    private val healed: ((List<com.pokedaisy.app.companion.data.MonView>) -> Unit)? = null,
) : QuickActions {
    init {
        menu.onRun = ::run
        menu.onRepel = ::repel
        menu.onFly = ::flyTo
    }

    override fun offered(action: QuickAction): Boolean = when (action) {
        QuickAction.PC -> items()?.canPc() == true
        QuickAction.HEAL -> items()?.center()?.heal == true
        QuickAction.FLY -> fly()?.canFly() == true
        QuickAction.REPEL -> REPEL_KINDS.any { items()?.canUse(it.first) == true } || extra.offered(action)
        QuickAction.WILD -> settings.wildEncountersSupported
        QuickAction.SAVE_STATE, QuickAction.LOAD_STATE, QuickAction.FF -> true
        else -> extra.offered(action)
    }

    override fun run(action: QuickAction) {
        if (!active()) return
        when (action) {
            QuickAction.PC -> items()?.openPc { outcome ->
                if (active() && outcome.result != ItemUseResult.USED) notice(itemUseMessage(outcome))
            }
            QuickAction.HEAL -> {
                val before = snapshot().party
                items()?.centerHeal { result ->
                    if (!active()) return@centerHeal
                    val show = healed
                    if (result == CenterResult.OK && show != null) show(before)
                    else notice(if (result == CenterResult.OK) tr("Your POKéMON are fully healed.") else centerMessage(result, ""))
                }
            }
            QuickAction.FLY -> {
                val visit = menu.visit
                fly()?.targets { targets ->
                    if (active() && menu.open && menu.visit == visit) {
                        if (targets.result == FlyResult.OK) menu.fly = targets
                        else { menu.close(); notice(flyMessage(targets.result)) }
                    }
                }
            }
            QuickAction.REPEL -> repelKindsIn(snapshot()).firstOrNull()?.let(::repel)
            QuickAction.WILD -> {
                val now = prefs.wildEncounters
                val next = if (now != 0) { prefs.wildEncountersBeforeOff = now; 0 }
                    else prefs.wildEncountersBeforeOff.takeIf { it > 0 } ?: 100
                settings.setWildEncounters(next)
                notice(tr("WILD ENCOUNTERS") + ": " + tr(wildEncountersLabel(next)))
            }
            QuickAction.SAVE_STATE -> hotkey(Hotkeys.Event(Hotkeys.Action.SAVE_STATE, true))
            QuickAction.LOAD_STATE -> hotkey(Hotkeys.Event(Hotkeys.Action.LOAD_STATE, true))
            QuickAction.FF -> hotkey(Hotkeys.Event(Hotkeys.Action.FF_TOGGLE, true))
            else -> extra.run(action)
        }
    }

    override fun flyTo(mapsec: Int, targets: FlyTargets) {
        if (!active()) return
        fly()?.fly(mapsec, targets) { result ->
            if (active() && result != FlyResult.OK) notice(flyMessage(result))
        }
    }

    override fun repel(itemId: Int) {
        if (!active()) return
        items()?.use(itemId) { outcome -> if (active()) notice(itemUseMessage(outcome)) }
    }

    override fun wildEncounters() = prefs.wildEncounters
    override fun repelSteps(): Int = snapshot().let { s ->
        s.repelSteps ?: items()?.let { item ->
            item.repelVar?.let { s.progress?.variable(it) }?.let(item::repelSteps)
        } ?: 0
    }
    override fun riding() = extra.riding()
    override fun flying() = extra.flying()
    override fun flashDark() = extra.flashDark()
    override fun turbo() = extra.turbo()
}
