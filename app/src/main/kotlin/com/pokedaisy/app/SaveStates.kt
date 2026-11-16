package com.pokedaisy.app

import com.pokedaisy.app.companion.DsCore
import java.io.File
import okio.Path.Companion.toOkioPath
import com.pokedaisy.app.companion.data.StateFiles
import java.util.zip.CRC32

/**
 * Per-game savestate storage: `files/states/<rom-crc32>/ss<N>` + `ss<N>.png`
 * thumbnail, for slots [SLOT_MIN]..[SLOT_MAX]. A dedicated `resume` slot backs
 * suspend-on-background.
 *
 * The `.ss` blobs are mGBA's own extended (mpack) state format, so they also
 * load in desktop mGBA via "Load state from file".
 */
class SaveStates(filesDir: File, romCrc: String) {

    internal val files = StateFiles(File(File(filesDir, "states"), romCrc).toOkioPath(), extraSidecars = listOf(".ra"))

    fun stateFile(slot: Int): File = File(files.stateFile(slot).toString())
    fun thumbFile(slot: Int): File = File(files.thumbFile(slot).toString())
    fun bakFile(slot: Int): File = File(files.bakFile(slot).toString())        // one-level undo of a save
    fun bakThumbFile(slot: Int): File = File(files.bakThumbFile(slot).toString())
    val resumeFile: File get() = File(files.resumeFile.toString())
    val undoLoadFile: File get() = File(files.undoLoadFile.toString())          // pre-load snapshot
    /** Set when the library loads another save file into this game ([GameSaves.load]):
     * the next start boots from that save instead of resuming, since a resume (or
     * manual) state still holds the old save's progress and would write it back. */
    val freshBootFile: File get() = File(files.freshBootFile.toString())

    fun exists(slot: Int): Boolean = files.exists(slot)

    fun slotInfo(slot: Int): SlotInfo {
        val f = stateFile(slot)
        return SlotInfo(
            slot = slot,
            present = f.isFile && f.length() > 0,
            savedAt = if (f.isFile) f.lastModified() else 0L,
            thumb = thumbFile(slot).takeIf { it.isFile },
        )
    }

    fun allSlots(): List<SlotInfo> = (SLOT_MIN..SLOT_MAX).map { slotInfo(it) }

    /**
     * The file to auto-load on a fresh boot: whichever of [resumeFile] (written
     * on the last clean close/background) or a manual save slot was written
     * most recently. The auto-suspend snapshot is normally the newest by
     * definition (it captures the live game state, which already reflects any
     * manual saves made before closing) — this fallback only matters if the
     * last session ended without a clean close (crash, force-kill), leaving
     * [resumeFile] stale or missing while a manual slot is actually newer.
     * Returns null if there's nothing to resume from at all.
     */
    fun latestResumeSource(): File? {
        return files.latestResumeSource()?.let { File(it.toString()) }
    }

    data class SlotInfo(
        val slot: Int,
        val present: Boolean,
        val savedAt: Long,
        val thumb: File?,
    )

    companion object {
        const val SLOT_MIN = 0
        const val SLOT_MAX = 9

        /** Where a DS game's resume state ([resumeFile]) waits for DS CORE's [core] (DsCore.CORE_*) while the other
         * core runs the game: states don't cross cores, so the engine sets it aside here and the next start on that core
         * resumes it (PokeDaisyActivity.startGame), if the save file still matches it as any resume must. */
        fun parkedResume(resume: File, core: Int): File =
            File(resume.parentFile, if (core == DsCore.CORE_DSPERATE) "resume.dsperate" else "resume.melonds")

        /** CRC32 of the ROM file (an archive's ROM, see [RomArchive]), as 8 lowercase hex digits. */
        fun crc32(rom: File): String {
            val crc = CRC32()
            RomArchive.open(rom).buffered(1 shl 16).use { s ->
                val buf = ByteArray(1 shl 16)
                while (true) {
                    val n = s.read(buf)
                    if (n < 0) break
                    crc.update(buf, 0, n)
                }
            }
            return "%08x".format(crc.value)
        }
    }
}
