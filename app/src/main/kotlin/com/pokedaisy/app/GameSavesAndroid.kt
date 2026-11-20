package com.pokedaisy.app

import android.content.Context
import java.io.File
import okio.Path.Companion.toOkioPath

/** Android locations and its existing RZIP decoder; import/backup/rollback rules are common. */
fun GameSaves.backupName(current: File, now: Long = System.currentTimeMillis()): String = backupName(current.name, now)
fun GameSaves.load(context: Context, prefs: Prefs, rom: File, picked: ByteArray): GameSaves.Result {
    val bytes = when (val decoded = RzipSave.decode(picked)) {
        RzipSave.Result.NotRzip -> picked
        is RzipSave.Result.Unpacked -> decoded.bytes
        is RzipSave.Result.Unreadable -> return GameSaves.Result.Failed(RzipSave.unreadableMessage())
    }
    if (!SaveFileEdits.recover(context)) return GameSaves.Result.Failed(ClosedSaveEdits.recoveryError())
    val current = SavesLocation.saveFor(context, prefs, rom)
    return load(current.toOkioPath(), bytes,
        states = { SaveStates(context.getExternalFilesDir(null) ?: context.filesDir, SaveStates.crc32(rom)).files })
}
