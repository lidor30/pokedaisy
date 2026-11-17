package com.pokedaisy.app.companion.data

import okio.FileSystem
import okio.HashingSource
import okio.Path
import okio.blackholeSink
import okio.buffer
import okio.use

/** State-file layout shared by Android and iPad. State blobs publish atomically; pictures/data are optional. */
class StateFiles(val directory: Path, private val extraSidecars: List<String> = emptyList()) {
    init { directory.mkdirs() }

    fun stateFile(slot: Int): Path { require(slot in 0..9); return directory / "ss$slot" }
    fun thumbFile(slot: Int): Path = sidecar(stateFile(slot), ".png")
    fun bakFile(slot: Int): Path = sidecar(stateFile(slot), ".bak")
    fun bakThumbFile(slot: Int): Path = sidecar(bakFile(slot), ".png")
    val resumeFile get() = directory / "resume"
    val undoLoadFile get() = directory / "undo_load"
    /** BOSS RETRY: the state taken as a trainer battle began (never a slot; one per game). */
    val retryFile get() = directory / "battle_retry"
    val freshBootFile get() = directory / "boot_fresh"

    fun exists(slot: Int) = present(stateFile(slot))
    fun latestResumeSource(): Path? = (listOf(resumeFile) + (0..9).map(::stateFile))
        .filter(::present).maxByOrNull { it.lastModified }

    /** A failed/partial native write never replaces a usable slot or its undo copy. */
    fun saveSlot(slot: Int, writer: (Path) -> Boolean, thumbnail: ByteArray? = null, snapshot: ByteArray? = null): Boolean =
        writeState(stateFile(slot), writer, thumbnail, snapshot, previous = bakFile(slot))

    fun writeState(target: Path, writer: (Path) -> Boolean, thumbnail: ByteArray? = null,
                   snapshot: ByteArray? = null, previous: Path? = null): Boolean {
        val tmp = sidecar(target, ".tmp")
        return try {
            if (!writer(tmp) || !present(tmp)) return false
            if (previous != null && present(target)) copyBundle(target, previous)
            tmp.moveTo(target)
            optional(sidecar(target, ".png"), thumbnail)
            optional(SnapshotCache.fileFor(target), snapshot)
            for (suffix in extraSidecars) copyOptional(sidecar(tmp, suffix), sidecar(target, suffix))
            true
        } catch (_: Exception) { false } finally {
            runCatching { tmp.delete() }
            for (suffix in extraSidecars) runCatching { sidecar(tmp, suffix).delete() }
        }
    }

    fun undoSave(slot: Int): Boolean = runCatching {
        val backup = bakFile(slot)
        if (!present(backup)) return@runCatching false
        copyBundle(backup, stateFile(slot))
        true
    }.getOrDefault(false)

    fun deleteSlot(slot: Int) {
        for (state in listOf(stateFile(slot), bakFile(slot))) removeState(state)
    }

    fun removeState(state: Path) {
        state.delete(); sidecar(state, ".png").delete(); SnapshotCache.fileFor(state).delete()
        for (suffix in extraSidecars) sidecar(state, suffix).delete()
    }

    /** Copy/move a native state with its portable cache and host-owned progress files. */
    fun copyState(from: Path, to: Path) = copyBundle(from, to)
    fun moveState(from: Path, to: Path) { copyBundle(from, to); removeState(from) }

    private fun copyBundle(from: Path, to: Path) {
        val tmp = sidecar(to, ".tmp")
        try { from.copyTo(tmp); tmp.moveTo(to) } finally { runCatching { tmp.delete() } }
        for ((src, dst) in listOf(sidecar(from, ".png") to sidecar(to, ".png"),
            SnapshotCache.fileFor(from) to SnapshotCache.fileFor(to))) {
            copyOptional(src, dst)
        }
        for (suffix in extraSidecars) copyOptional(sidecar(from, suffix), sidecar(to, suffix))
    }

    private fun copyOptional(from: Path, to: Path) {
        runCatching { optional(to, from.takeIf { it.isFile }?.readBytes()) }
            .onFailure { runCatching { to.delete() } }
    }

    private fun optional(path: Path, bytes: ByteArray?) {
        // A stale thumbnail/cache must not describe the newly committed state if writing its replacement fails.
        runCatching { if (bytes == null) path.delete() else path.writeAtomically(bytes) }
            .onFailure { runCatching { path.delete() } }
    }

    companion object {
        private fun present(path: Path) = path.isFile && path.length > 0
        private fun sidecar(path: Path, suffix: String) = path.parent!! / "${path.name}$suffix"

        /** Content, rather than the iOS container path, identifies a ROM across app reinstalls and renames. */
        fun romKey(rom: Path): String = hash(rom)

        /** A distinct battery backup before opening a game or explicitly rewinding it. Failure aborts the load. */
        fun backupBattery(save: Path, keep: Int = 10) {
            require(keep > 0)
            if (!present(save)) return
            val dir = save.parent!! / "pokedaisy-backups"
            dir.mkdirs()
            val prefix = "${save.name}."
            val target = dir / "$prefix${hash(save)}.sav"
            val tmp = sidecar(target, ".tmp")
            try { save.copyTo(tmp); tmp.moveTo(target) } finally { runCatching { tmp.delete() } }
            dir.listFiles().filter { it.name.startsWith(prefix) && it.name.endsWith(".sav") }
                .sortedWith(compareByDescending<Path> { it.lastModified }.thenByDescending { it == target })
                .drop(keep).forEach { it.delete() }
        }

        private fun hash(file: Path): String = HashingSource.sha256(FileSystem.SYSTEM.source(file)).use { hash ->
            hash.buffer().use { it.readAll(blackholeSink()) }
            hash.hash.hex()
        }
    }
}
