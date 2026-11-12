package com.pokedaisy.app

import java.io.File
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

    private val dir = File(File(filesDir, "states"), romCrc).apply { mkdirs() }

    fun stateFile(slot: Int): File = File(dir, "ss$slot")
    fun thumbFile(slot: Int): File = File(dir, "ss$slot.png")
    fun bakFile(slot: Int): File = File(dir, "ss$slot.bak")        // one-level undo of a save
    fun bakThumbFile(slot: Int): File = File(dir, "ss$slot.bak.png")
    val resumeFile: File get() = File(dir, "resume")
    val undoLoadFile: File get() = File(dir, "undo_load")          // pre-load snapshot
    /** Set when the library loads another save file into this game ([GameSaves.load]):
     * the next start boots from that save instead of resuming, since a resume (or
     * manual) state still holds the old save's progress and would write it back. */
    val freshBootFile: File get() = File(dir, "boot_fresh")

    fun exists(slot: Int): Boolean = stateFile(slot).let { it.isFile && it.length() > 0 }

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
        val resume = resumeFile.takeIf { it.isFile && it.length() > 0 }
        val newestSlot = (SLOT_MIN..SLOT_MAX)
            .map { stateFile(it) }
            .filter { it.isFile && it.length() > 0 }
            .maxByOrNull { it.lastModified() }
        return listOfNotNull(resume, newestSlot).maxByOrNull { it.lastModified() }
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

        /** Where a DS game's resume state ([resumeFile]) waits for DS CORE's [core] (MgbaCore.NDS_CORE_*) while the other
         * core runs the game: states don't cross cores, so the engine sets it aside here and the next start on that core
         * resumes it (PokeDaisyActivity.startGame), if the save file still matches it as any resume must. */
        fun parkedResume(resume: File, core: Int): File =
            File(resume.parentFile, if (core == MgbaCore.NDS_CORE_DSPERATE) "resume.dsperate" else "resume.melonds")

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
