@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.pokedaisy.app.ios

import com.pokedaisy.app.ClosedSaveEdits
import com.pokedaisy.app.SaveEditFiles
import com.pokedaisy.app.SaveTradeJournal
import com.pokedaisy.app.companion.data.StateFiles
import com.pokedaisy.app.companion.data.writeBytes
import com.pokedaisy.app.game.RomPicker
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath
import okio.SYSTEM
import platform.posix.O_WRONLY
import platform.posix.close
import platform.posix.fsync
import platform.posix.open

/** Save edits publish while the game is closed. */
internal fun commitIosSaveEdit(
    save: Path, states: StateFiles, expected: ByteArray, edited: ByteArray,
): SaveEditFiles.Result = transactions().write(SaveEditFiles.Edit(save, expected, edited)) { states }

internal fun iosSaveTradeRecoveryError() = ClosedSaveEdits.recoveryError()

internal fun recoverIosSaveTrades(): Boolean = transactions().recover()

internal fun commitIosSaveTrade(a: SaveTradeJournal.Side, b: SaveTradeJournal.Side): SaveEditFiles.Result =
    transactions().trade(a, b)

private fun transactions() = ClosedSaveEdits(
    files = SaveEditFiles(
        backup = { StateFiles.backupBattery(it) },
        writeSynced = ::writeSynced,
        publish = FileSystem.SYSTEM::atomicMove,
    ),
    journal = SaveTradeJournal(appFilesDir() / "save-trade.pending", ::writeSynced),
    accept = { side ->
        val saveDir = RomPicker.savesDir.toPath(normalize = true)
        val key = side.stateKey
        side.edit.path.parent == saveDir && side.edit.path.name.endsWith(".sav") &&
            key.length == 64 && key.all { it in '0'..'9' || it in 'a'..'f' }
    },
    states = ::iosStateFiles,
)

private fun writeSynced(path: Path, data: ByteArray) {
    path.writeBytes(data)
    val fd = open(path.toString(), O_WRONLY)
    check(fd >= 0)
    try { check(fsync(fd) == 0) } finally { close(fd) }
}
