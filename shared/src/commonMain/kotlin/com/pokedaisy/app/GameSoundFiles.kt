package com.pokedaisy.app

import com.pokedaisy.app.companion.data.delete
import com.pokedaisy.app.companion.data.isFile
import com.pokedaisy.app.companion.data.lastModified
import com.pokedaisy.app.companion.data.length
import com.pokedaisy.app.companion.data.listFiles
import com.pokedaisy.app.companion.data.mkdirs
import com.pokedaisy.app.companion.data.moveTo
import okio.FileSystem
import okio.Path
import okio.SYSTEM

/** A sound rendered from this ROM. Carts with unfamiliar sound engines can use a clip already made on this device. */
class GameSoundFiles(filesDir: Path, romCrc: String, name: String) {
    private val dir = (filesDir / "sfx").apply { mkdirs() }
    private val suffix = "-$name.wav"
    private val own = dir / "$romCrc$suffix"

    val hasOwn: Boolean get() = own.isFile && own.length > 0
    fun available(): Path? = own.takeIf { hasOwn }
        ?: dir.listFiles().filter { it.name.endsWith(suffix) && it.isFile && it.length > 0 }.maxByOrNull { it.lastModified }

    /** Strip silence from both ends. Returns false if the worker only captured silence. */
    fun write(pcm: ShortArray, len: Int, sampleRate: Int): Boolean {
        fun loud(frame: Int): Boolean =
            maxOf(kotlin.math.abs(pcm[frame * 2].toInt()), kotlin.math.abs(pcm[frame * 2 + 1].toInt())) > 8
        val frames = len / 2
        val first = (0 until frames).firstOrNull(::loud) ?: return false
        val last = (frames - 1 downTo first).first(::loud)
        writeWav(own, pcm, (last - first + 1) * 2, sampleRate, from = first * 2)
        return true
    }
}

/** Writes the interleaved 16-bit stereo WAV used for menu sounds and older game recordings. */
fun writeWav(file: Path, pcm: ShortArray, len: Int, sampleRate: Int, from: Int = 0) {
    val dir = checkNotNull(file.parent)
    val tmp = dir / "${file.name}.tmp"
    val dataBytes = len * 2
    try {
        FileSystem.SYSTEM.write(tmp) {
            writeUtf8("RIFF")
            writeIntLe(36 + dataBytes)
            writeUtf8("WAVEfmt ")
            writeIntLe(16)
            writeShortLe(1)
            writeShortLe(2)
            writeIntLe(sampleRate)
            writeIntLe(sampleRate * 4)
            writeShortLe(4)
            writeShortLe(16)
            writeUtf8("data")
            writeIntLe(dataBytes)
            for (i in from until from + len) writeShortLe(pcm[i].toInt())
        }
        tmp.moveTo(file)
    } finally {
        tmp.delete()
    }
}
