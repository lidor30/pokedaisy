package com.pokedaisy.app.companion

import com.pokedaisy.app.cheats.Cheat
import com.pokedaisy.app.cheats.CheatFiles
import com.pokedaisy.app.cheats.CheatStore
import com.pokedaisy.app.companion.i18n.tk
import kotlinx.atomicfu.atomic
import okio.Buffer
import okio.FileSystem
import okio.Path.Companion.toPath
import okio.Source
import okio.SYSTEM
import okio.use

/** Per-game cheat storage and enabled text, shared by the game hosts and Android's library editor.
 * The host submits text between frames and retains its native availability / co-op policy. */
class GameCheats(
    val supported: Boolean,
    private val openStore: () -> CheatStore?,
    private val enabled: () -> Boolean,
    private val submit: (String) -> Unit,
) {
    private val closed = atomic(false)
    private var store: CheatStore? = null
    private val current = atomic(emptyList<Cheat>())
    val list get() = current.value
    val editable get() = !closed.value && store != null

    fun start() {
        if (!supported || closed.value) return
        refresh()
        apply()
    }

    fun close() { closed.value = true }

    fun refresh() {
        if (closed.value) return
        if (supported && store == null) store = runCatching(openStore).getOrNull()
        current.value = store?.load().orEmpty()
    }
    fun apply() { if (supported && !closed.value) submit(if (enabled()) CheatFiles.coreText(list) else "") }
    fun save(cheats: List<Cheat>) {
        if (closed.value) return
        val target = store ?: return
        target.save(cheats)
        current.value = cheats.toList()
        apply()
    }
    fun setEnabled(index: Int, on: Boolean) {
        val before = list
        if (index in before.indices) save(before.mapIndexed { i, c -> if (i == index) c.copy(enabled = on) else c })
    }
    fun add(cheat: Cheat) = save(list + cheat)
    fun remove(index: Int) { if (index in list.indices) save(list.filterIndexed { i, _ -> i != index }) }

    fun import(path: String): CheatStore.Import? = runCatching {
        val file = path.toPath()
        FileSystem.SYSTEM.source(file).use { import(it, file.name.substringBeforeLast('.').ifBlank { tk("CHEAT") }) }
    }.getOrNull()

    /** Read at most the existing 1 MiB limit plus one byte, even when a document provider reports no size. */
    fun import(source: Source, fallbackName: String, ds: Boolean = false): CheatStore.Import? {
        if (closed.value) return null
        val target = store ?: return null
        val buffer = Buffer()
        while (buffer.size <= MAX_FILE_BYTES) {
            if (source.read(buffer, minOf(8192L, MAX_FILE_BYTES + 1L - buffer.size)) == -1L) {
                if (closed.value || buffer.size == 0L) return null
                val result = target.import(buffer.readUtf8().removePrefix("\uFEFF"), fallbackName, ds)
                refresh()
                apply()
                return result
            }
        }
        return null
    }

    companion object { const val MAX_FILE_BYTES = 1 shl 20 }
}
