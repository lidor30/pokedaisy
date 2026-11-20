package com.pokedaisy.app

import android.util.Log
import java.io.File
import okio.Path.Companion.toOkioPath

internal fun androidFfMusicCache(filesDir: File, crc: String, gameBoy: Boolean = false): FfMusicCache =
    FfMusicCache(filesDir.toOkioPath(), crc, gameBoy,
        freeBytes = { filesDir.usableSpace },
        log = { Log.i("pokedaisy", it) })

internal fun FfMusicCache.prepareOnAndroid(waiting: () -> Boolean) =
    prepare(waiting,
        keepLegacyTime = { wav, flac -> flac.toFile().setLastModified(wav.toFile().lastModified()) },
        rest = Thread::sleep)
