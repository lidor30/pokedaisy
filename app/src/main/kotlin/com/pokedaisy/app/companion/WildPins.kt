package com.pokedaisy.app.companion

import androidx.compose.runtime.mutableStateOf
import com.pokedaisy.app.companion.data.SnapshotView
import com.pokedaisy.app.companion.data.WildPin

/**
 * WILD PIN for the running game, as the companion and the HUD see it ([WildPin] is the contract): whether GAMEPLAY > WILD
 * PIN is on, the pin that's in (Compose state, so HERE, the HUD's WILD and its button follow it) and the activity's [Host]
 * that builds a pin from the ROM and hands it to the engine. The engine ends a pin by itself at its wild battle; the
 * activity ends it on another map. Previews set [preview] instead of a host.
 */
object WildPins {
    /** The activity: [pin] reads the map's slots for the method off the UI thread and puts the pin in; [unpin] takes it out. */
    class Host(val pin: (method: WildPin.Method, species: Int) -> Unit, val unpin: () -> Unit)

    private val onState = mutableStateOf(true)
    private val pinState = mutableStateOf<WildPin.Pin?>(null)
    private val previewState = mutableStateOf<Preview?>(null)
    private var saveOn: (Boolean) -> Unit = {}

    @Volatile var host: Host? = null

    /** GAMEPLAY > WILD PIN (ON by default): OFF hides every PIN. */
    val on: Boolean get() = onState.value
    val pinned: WildPin.Pin? get() = pinState.value

    fun load(on: Boolean, save: (Boolean) -> Unit) {
        onState.value = on
        saveOn = save
    }

    fun setOn(on: Boolean) {
        onState.value = on
        saveOn(on)
        if (!on && pinned != null) host?.unpin?.invoke()
    }

    /** The pin that's in now (the activity, any thread). */
    fun setPinned(pin: WildPin.Pin?) {
        pinState.value = pin
    }

    /** The PIN on a [method] row (GRASS / CAVE, SURFING, ROCK SMASH, a rod) of [snapshot]'s map for [species], or null for
     * none: no method (HEADBUTT ...), WILD PIN off, a game outside the slice ([WildPin.offered]) or no host. */
    fun control(snapshot: SnapshotView, method: WildPin.Method?, species: Int): PinControl? {
        if (method == null) return null
        previewState.value?.let { p ->
            return PinControl(method, species, pinned = species == p.pinned && method == p.method, blocked = WildPin.blocked(p.nuzlocke, inBattle = false))
        }
        if (!on || species <= 0 || host == null || snapshot.unsupported || !WildPin.offered(snapshot.guideTables)) return null
        val p = pinned
        return PinControl(
            method, species, pinned = p != null && p.species == species && p.method == method && p.onMap(snapshot.mapGroup, snapshot.mapNum),
            blocked = WildPin.blocked(Nuzlocke.on, snapshot.inBattle),
        )
    }

    /** The species pinned on [snapshot]'s map (the HUD's WILD button wears it), or 0. */
    fun pinnedHere(snapshot: SnapshotView): Int {
        previewState.value?.let { return it.pinned }
        val p = pinned ?: return 0
        return if (on && p.onMap(snapshot.mapGroup, snapshot.mapNum)) p.species else 0
    }

    /** A tap on PIN / UNPIN. */
    fun toggle(control: PinControl) {
        if (control.blocked != null) return
        val h = host ?: return
        if (control.pinned) h.unpin() else h.pin(control.method, control.species)
    }

    /** Paparazzi / ui-preview: every row offers PIN, [pinned] (0 = none) by [method] shows as pinned, [nuzlocke] greys them. */
    fun preview(pinned: Int = 0, nuzlocke: Boolean = false, method: WildPin.Method = WildPin.Method.LAND) {
        previewState.value = Preview(pinned, nuzlocke, method)
    }

    fun endPreview() {
        previewState.value = null
    }

    private data class Preview(val pinned: Int, val nuzlocke: Boolean, val method: WildPin.Method)
}

/** One row's PIN ([species] by [method]): [pinned] shows UNPIN; [blocked] (an English key, null = none) greys it and says why. */
data class PinControl(val method: WildPin.Method, val species: Int, val pinned: Boolean, val blocked: String?)
