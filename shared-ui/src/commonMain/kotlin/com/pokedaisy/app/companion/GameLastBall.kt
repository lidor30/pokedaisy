package com.pokedaisy.app.companion

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.pokedaisy.app.Prefs
import com.pokedaisy.app.companion.data.BattleBall
import com.pokedaisy.app.companion.data.ItemView
import com.pokedaisy.app.companion.data.NativeConfig
import com.pokedaisy.app.companion.data.SnapshotView
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import okio.Path

/** Discovers the loaded game's throw functions and remembers successful throws from its bag samples. */
class GameLastBall(
    private val prefs: Prefs,
    private val snapshot: () -> SnapshotView,
    private val removeBagItem: () -> Long,
    private val handOver: () -> Unit,
    private val submit: (BattleBall.Fns, Int, Long) -> Unit,
) {
    private val lock = SynchronizedObject()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var rom: Path? = null
    private var key = ""
    private var version = 0
    private var scanFor: NativeConfig? = null
    private var functions by mutableStateOf<BattleBall.Fns?>(null)
    private var last by mutableStateOf<Int?>(null)
    private var previousItems: List<ItemView>? = null
    private var enabled by mutableStateOf(prefs.lastBallButton)

    fun start(path: Path, romKey: String) = synchronized(lock) {
        version++
        rom = path
        key = romKey
        scanFor = null
        functions = null
        previousItems = null
        last = prefs.lastBallFor(romKey)
        enabled = prefs.lastBallButton
    }

    fun set(on: Boolean) = synchronized(lock) {
        prefs.lastBallButton = on
        enabled = on
    }

    fun observe(view: SnapshotView, config: NativeConfig?) = synchronized(lock) {
        val path = rom ?: return@synchronized
        if (!view.connected || view.blocked != null) return@synchronized
        if (config != null && config !== scanFor) {
            scanFor = config
            functions = null
            val current = ++version
            scope.launch {
                val found = BattleBall.find(path, config.completeWhenChoseItem, config.battlerControllerFuncs)
                synchronized(lock) { if (version == current) functions = found }
            }
        }
        if (view.inBattle) previousItems?.let { BattleBall.thrown(it, view.items) }?.let { item ->
            if (item != last) {
                prefs.setLastBallFor(key, item)
                last = item
            }
        }
        previousItems = view.items
    }

    private fun selected(view: SnapshotView): ItemView? =
        if (enabled && view.connected && view.blocked == null && BattleBall.canThrow(view))
            BattleBall.pick(view.items, last) else null

    val current: ItemView?
        get() = selected(snapshot())?.takeIf { functions != null && removeBagItem() != 0L }

    fun throwBall() {
        val fns = functions ?: return
        val item = selected(snapshot()) ?: return
        val remove = removeBagItem().takeIf { it != 0L } ?: return
        handOver()
        submit(fns, item.itemId, remove)
    }

    fun resetSample() = synchronized(lock) { previousItems = null }

    fun close() {
        synchronized(lock) {
            version++
            rom = null
            functions = null
            previousItems = null
        }
        scope.cancel()
    }
}
