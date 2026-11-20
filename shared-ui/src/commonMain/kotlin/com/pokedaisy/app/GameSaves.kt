package com.pokedaisy.app

import com.pokedaisy.app.companion.data.StateFiles
import com.pokedaisy.app.companion.data.StateResume
import com.pokedaisy.app.companion.data.writeTextAtomically
import com.pokedaisy.app.companion.data.writeAtomically
import com.pokedaisy.app.companion.i18n.tr
import kotlin.time.Clock
import kotlin.time.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.number
import kotlinx.datetime.toLocalDateTime
import okio.*

/** Battery-save imports. Call off the UI thread, with the game's core stopped. */
object GameSaves {
    /** Bigger than any GBA save (128 KB flash, plus mGBA's RTC footer) or a Pokémon DS game's (512 KB flash): at 256 KB,
     * SaveBackups skipped every DS save. */
    const val MAX_BYTES = 1024 * 1024

    /** Close the native source after reading at most one byte beyond the import limit.
     * The extra byte lets the confirmation flow reject oversized input without retaining the whole file.
     */
    fun readPicked(source: Source): ByteArray = source.buffer().use {
        val limit = MAX_BYTES + 1L
        it.request(limit)
        it.buffer.readByteArray(minOf(it.buffer.size, limit))
    }

    /** What a save of [size] bytes is, by the GBA's save chips. */
    fun kind(size: Long): String? = when (size) {
        512L, 528L -> "EEPROM 4K"
        8192L, 8208L -> "EEPROM 64K"
        32768L, 32784L -> "SRAM 32K"
        65536L, 65552L -> "FLASH 64K"
        131072L, 131088L -> "FLASH 128K"
        else -> null
    }


    fun backupName(saveName: String, now: Long = Clock.System.now().toEpochMilliseconds(),
                   zone: TimeZone = TimeZone.currentSystemDefault()): String {
        val date = Instant.fromEpochMilliseconds(now).toLocalDateTime(zone)
        fun Int.digits(n: Int = 2) = toString().padStart(n, '0')
        val stamp = "${date.year.digits(4)}${date.month.number.digits()}${date.day.digits()}-${date.hour.digits()}${date.minute.digits()}${date.second.digits()}"
        return "${saveName.substringBeforeLast('.', saveName)}.backup-$stamp.${saveName.substringAfterLast('.', "")}"
    }

    fun backups(dir: Path, romBaseName: String): List<Path> {
        val fs = FileSystem.SYSTEM
        fun stamp(path: Path) = path.name.substringAfterLast(".backup-").take(15)
        fun index(path: Path) = path.name.substringAfterLast(".backup-").substringBeforeLast('.').drop(15)
            .removePrefix("-").toIntOrNull() ?: 1
        return fs.listOrNull(dir).orEmpty().filter {
            fs.metadataOrNull(it)?.isRegularFile == true && it.name.startsWith("$romBaseName.backup-")
        }.sortedWith(compareByDescending<Path>(::stamp).thenByDescending(::index).thenByDescending { it.name })
    }

    sealed class Result {
        class Loaded(val save: Path, val backup: Path?) : Result()
        class Failed(val reason: String) : Result()
    }

    /** Stage bytes first, commit fresh-boot intent before replacement, and restore the old save on commit failure.
     * A second import in the same second gets a distinct backup, retaining the normal save extension. */
    fun load(current: Path, picked: ByteArray, states: () -> StateFiles, beforeReplace: () -> Unit = {},
             now: Long = Clock.System.now().toEpochMilliseconds(), fs: FileSystem = FileSystem.SYSTEM): Result {
        if (picked.isEmpty() || picked.size > MAX_BYTES) return Result.Failed(tr("That isn't a game save"))
        if (picked.size >= 6 && picked.copyOfRange(0, 6).decodeToString() == "#RZIPv") {
            return Result.Failed(tr("That save is compressed: turn off RetroArch's save compression and save again"))
        }
        val dir = current.parent ?: return Result.Failed(tr("Can't write the save"))
        val tmp = dir / "${current.name}.tmp"
        var backup: Path? = null
        var stateFiles: StateFiles? = null
        var markerBefore: ByteArray? = null
        var marked = false
        return try {
            fs.createDirectories(dir)
            fs.write(tmp) { write(picked) }
            beforeReplace()
            val state = states().also { stateFiles = it }
            markerBefore = if (fs.exists(state.freshBootFile)) fs.read(state.freshBootFile) { readByteArray() } else null
            state.freshBootFile.writeTextAtomically("fresh")
            marked = true
            if (fs.exists(current)) {
                val name = backupName(current.name, now)
                var target = dir / name
                var index = 2
                while (fs.exists(target)) {
                    target = dir / "${name.substringBeforeLast('.')}-${index++}.${name.substringAfterLast('.')}"
                }
                fs.atomicMove(current, target)
                backup = target
            }
            fs.atomicMove(tmp, current)
            // A failed import must retain its old automatic state. Discard it only after the battery commits;
            // the already committed marker still blocks it if this optional cleanup fails.
            runCatching { StateResume.startFresh(state) }
            Result.Loaded(current, backup)
        } catch (_: Exception) {
            runCatching { fs.delete(tmp) }
            val restored = runCatching {
                val old = backup
                if (old == null) true
                else if (fs.exists(current)) false
                else { fs.atomicMove(old, current); true }
            }.getOrDefault(false)
            if (restored && marked) stateFiles?.let { state ->
                runCatching {
                    val previous = markerBefore
                    if (previous == null) fs.delete(state.freshBootFile) else state.freshBootFile.writeAtomically(previous)
                }
            }
            if (!restored && backup != null) return Result.Failed(
                tr("Couldn't import the save. The previous save is kept as {0}", backup.name))
            Result.Failed(tr("Can't write the save to {0}", dir.toString()))
        }
    }
}
