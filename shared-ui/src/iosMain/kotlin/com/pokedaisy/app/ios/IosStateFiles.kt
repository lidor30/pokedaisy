package com.pokedaisy.app.ios

import com.pokedaisy.app.companion.data.StateFiles
import com.pokedaisy.app.game.RomPicker
import okio.Path
import okio.Path.Companion.toPath

internal fun iosStateFiles(rom: Path): StateFiles = iosStateFiles(StateFiles.romKey(rom))

internal fun iosStateFiles(key: String): StateFiles =
    StateFiles(RomPicker.statesDir.toPath() / key, listOf(StateFiles.ACHIEVEMENT_PROGRESS_SUFFIX))
