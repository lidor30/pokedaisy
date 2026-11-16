package com.pokedaisy.app.companion.data

import okio.Path

/** Automatic resume is permitted only when the core establishes a battery-save match. Called between frames. */
class StateResume(
    private val files: StateFiles,
    private val write: (Path) -> Boolean,
    private val read: (Path) -> Boolean,
    private val matchesSave: (Path) -> Int,
    private val enabled: () -> Boolean = { true },
    private val snapshot: () -> ByteArray? = { null },
    private val loaded: (Path) -> Unit = {},
    private val rejected: ((Path, Int) -> Unit)? = null,
) {
    fun save(): Boolean = runCatching {
        if (!enabled()) { startFresh(); return@runCatching true }
        val ok = files.writeState(files.resumeFile, write, snapshot = snapshot())
        if (ok) files.freshBootFile.delete()
        ok
    }.getOrDefault(false)

    fun restore(source: Path? = null): Boolean = runCatching {
        if (!enabled() || files.freshBootFile.isFile) return@runCatching false
        val source = source ?: files.latestResumeSource() ?: return@runCatching false
        val verdict = matchesSave(source)
        if (verdict != 1 || !read(source)) {
            // Keep manual slots for explicit loads; an unsafe automatic snapshot is parked rather than retried.
            if (rejected != null) rejected.invoke(source, verdict)
            else if (source == files.resumeFile) files.moveState(source, files.directory / "resume.replaced")
            return@runCatching false
        }
        loaded(source)
        true
    }.getOrDefault(false)

    /** An imported save or RESTART GAME must boot from disk even when a manual slot has matching battery bytes.
     * Keep the marker until a new resume snapshot is committed, including across a crash on that first boot. */
    fun startFresh() = startFresh(files)

    companion object {
        /** Android consumes its own automatic snapshot, never a manual slot. Keep all sidecars when staging it. */
        fun stage(files: StateFiles): Path? {
            if (files.freshBootFile.isFile) return null
            val source = files.latestResumeSource() ?: return null
            if (source != files.resumeFile) files.copyState(source, files.resumeFile)
            return files.resumeFile
        }

        /** Also used before importing battery data while no core is running. */
        fun startFresh(files: StateFiles) {
            files.freshBootFile.writeTextAtomically("fresh")
            files.removeState(files.resumeFile)
        }
    }
}
