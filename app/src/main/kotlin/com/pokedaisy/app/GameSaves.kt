package com.pokedaisy.app

import android.content.Context
import com.pokedaisy.app.companion.i18n.tr
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Loading another save file into a game (the library menu's LOAD SAVE): the
 * current one is kept, renamed to a dated backup next to it, and the picked one
 * takes its place under the name the game looks for ([SavesLocation.resolve]).
 */
object GameSaves {

    /** Bigger than any GBA save (128 KB flash, plus mGBA's RTC footer) or a Pokémon DS game's (512 KB flash): at 256 KB,
     * SaveBackups skipped every DS save. */
    const val MAX_BYTES = 1024 * 1024

    /** What a save of [size] bytes is, by the GBA's save chips. */
    fun kind(size: Long): String? = when (size) {
        512L, 528L -> "EEPROM 4K"
        8192L, 8208L -> "EEPROM 64K"
        32768L, 32784L -> "SRAM 32K"
        65536L, 65552L -> "FLASH 64K"
        131072L, 131088L -> "FLASH 128K"
        else -> null
    }

    /** `<game>.backup-20261006-113012.sav`: same folder and extension, so other emulators still read it. */
    fun backupName(current: File, now: Long = System.currentTimeMillis()): String {
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date(now))
        return "${current.nameWithoutExtension}.backup-$stamp.${current.extension}"
    }

    /** [romBaseName]'s backups in [dir], newest first. */
    fun backups(dir: File, romBaseName: String): List<File> =
        dir.listFiles { f -> f.isFile && f.name.startsWith("$romBaseName.backup-") }
            ?.sortedByDescending { it.name }.orEmpty()

    sealed class Result {
        /** [backup]: where the previous save went; null if there was none. */
        class Loaded(val save: File, val backup: File?) : Result()
        class Failed(val reason: String) : Result()
    }

    /**
     * Makes [bytes] [rom]'s save. Blocking (CRC32 of the ROM, file I/O) - off the UI
     * thread, and only while the game isn't running (the library is in front, so its
     * engine is stopped). The game boots from the new save next time instead of
     * resuming ([SaveStates.freshBootFile]).
     */
    fun load(context: Context, prefs: Prefs, rom: File, picked: ByteArray): Result {
        // RetroArch's compressed .srm goes in unpacked (RzipSave): mGBA reads only a plain save.
        val bytes = when (val r = RzipSave.decode(picked)) {
            RzipSave.Result.NotRzip -> picked
            is RzipSave.Result.Unpacked -> r.bytes
            is RzipSave.Result.Unreadable -> return Result.Failed(RzipSave.unreadableMessage())
        }
        val current = SavesLocation.saveFor(context, prefs, rom)
        val dir = current.parentFile ?: SavesLocation.dir(context, prefs)
        var backup: File? = null
        if (current.exists()) {
            val b = File(dir, backupName(current))
            if (!current.renameTo(b)) return Result.Failed(tr("Can't rename the current save in {0} - no write access there", dir.absolutePath))
            backup = b
        }
        val tmp = File(dir, "${current.name}.tmp")
        val ok = runCatching {
            tmp.writeBytes(bytes)
            tmp.renameTo(current)
        }.getOrDefault(false)
        if (!ok) {
            tmp.delete()
            backup?.renameTo(current)   // put the old save back
            return Result.Failed(tr("Can't write the save to {0}", dir.absolutePath))
        }
        runCatching {
            SaveStates(context.getExternalFilesDir(null) ?: context.filesDir, SaveStates.crc32(rom)).freshBootFile.createNewFile()
        }
        return Result.Loaded(current, backup)
    }
}
