package com.pokedaisy.app.ios

import com.pokedaisy.app.GameSaves
import com.pokedaisy.app.companion.data.StateFiles
import okio.Path

/** Include both in-place import backups and the copies made before running or rewinding a game. */
internal fun iosSaveBackups(save: Path): List<Path> {
    val directory = save.parent ?: return emptyList()
    return GameSaves.restoreBackups(
        directory,
        listOf(save.name.substringBeforeLast('.')),
        StateFiles.batteryBackups(save),
        GameSaves.RestoreBackupOrder.MODIFIED_TIME,
    )
}
