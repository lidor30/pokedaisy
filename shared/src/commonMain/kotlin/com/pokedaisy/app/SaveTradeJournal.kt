package com.pokedaisy.app

import com.pokedaisy.app.companion.data.StateFiles
import com.pokedaisy.app.companion.i18n.tr
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlin.random.Random
import okio.Buffer
import okio.BufferedSource
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath
import okio.SYSTEM
import okio.buffer
import okio.use

/**
 * Persists both sides of a closed-game trade before publishing either battery file.
 * The host serializes trade, recovery and opening a game. Recovery keeps a fully written
 * trade, or restores the sides this trade actually wrote when it stopped partway.
 */
class SaveTradeJournal(
    private val file: Path,
    private val sync: (Path, ByteArray) -> Unit,
    private val fs: FileSystem = FileSystem.SYSTEM,
) {
    class Side(val edit: SaveEditFiles.Edit, val stateKey: String)

    fun write(a: Side, b: Side) {
        require(a.edit.path != b.edit.path)
        check(!fs.exists(file))
        val data = Buffer().writeUtf8(MAGIC)
        for (side in listOf(a, b)) {
            data.writeField(side.edit.path.toString().encodeToByteArray(), MAX_PATH_BYTES)
            data.writeField(side.stateKey.encodeToByteArray(), MAX_PATH_BYTES)
            data.writeField(side.edit.expected, GameSaves.MAX_BYTES)
            data.writeField(side.edit.updated, GameSaves.MAX_BYTES)
        }
        fs.createDirectories(file.parent!!)
        replace(file, data.readByteArray())
    }

    /** Leave the journal intact if it cannot be validated or a recovery operation fails. */
    fun recover(accept: (Side) -> Boolean, finishCommitted: (Side) -> Unit): Boolean = runCatching {
        if (!fs.exists(file)) return@runCatching true
        val sides = fs.source(file).buffer().use { source ->
            check(source.readUtf8(MAGIC.length.toLong()) == MAGIC)
            List(2) { source.readSide() }.also { check(source.exhausted()) }
        }
        check(sides[0].edit.path != sides[1].edit.path && sides.all(accept))
        // A detached SD card can make a published side temporarily disappear. Retry when both
        // saves are visible before deciding whether to retain or roll back the trade.
        check(sides.all { fs.metadataOrNull(it.edit.path)?.isRegularFile == true })
        if (sides.all { matches(it.edit.path, it.edit.updated) }) {
            sides.forEach(finishCommitted)
        } else {
            for (side in sides) {
                val edit = side.edit
                if (!edit.expected.contentEquals(edit.updated) && matches(edit.path, edit.updated)) {
                    replace(edit.path, edit.expected, from = edit.updated)
                }
            }
        }
        fs.delete(file)
        true
    }.getOrDefault(false)

    internal fun unchanged(side: Side): Boolean = runCatching {
        matches(side.edit.path, side.edit.expected)
    }.getOrDefault(false)

    private fun replace(target: Path, bytes: ByteArray, from: ByteArray? = null) {
        val nonce = Random.nextLong().toULong().toString(16)
        val staging = target.parent!! / "${target.name}.trade-$nonce.tmp"
        try {
            sync(staging, bytes)
            check(matches(staging, bytes))
            check(from == null || matches(target, from))
            fs.atomicMove(staging, target)
            check(matches(target, bytes))
        } finally {
            runCatching { fs.delete(staging) }
        }
    }

    private fun matches(path: Path, bytes: ByteArray): Boolean {
        val metadata = fs.metadataOrNull(path) ?: return false
        return metadata.isRegularFile && metadata.size == bytes.size.toLong() &&
            fs.read(path) { readByteArray(bytes.size.toLong()).contentEquals(bytes) && exhausted() }
    }

    private fun Buffer.writeField(value: ByteArray, limit: Int) {
        require(value.size in 1..limit)
        writeInt(value.size)
        write(value)
    }

    private fun BufferedSource.readField(limit: Int): ByteArray {
        val length = readInt()
        check(length in 1..limit)
        return readByteArray(length.toLong())
    }

    private fun BufferedSource.readSide(): Side {
        val filePath = readField(MAX_PATH_BYTES).decodeToString().toPath(normalize = true)
        val stateKey = readField(MAX_PATH_BYTES).decodeToString()
        val expected = readField(GameSaves.MAX_BYTES)
        val updated = readField(GameSaves.MAX_BYTES)
        return Side(SaveEditFiles.Edit(filePath, expected, updated), stateKey)
    }

    private companion object {
        const val MAGIC = "PKTRADE1"
        const val MAX_PATH_BYTES = 4096
    }
}


/** Serializes closed-game edits and recovery for a host. The caller still owns the core's lifecycle. */
class ClosedSaveEdits(
    private val files: SaveEditFiles,
    private val journal: SaveTradeJournal,
    private val accept: (SaveTradeJournal.Side) -> Boolean,
    private val states: (String) -> StateFiles,
) {
    fun recover(): Boolean = synchronized(lock) { recoverLocked() }

    fun write(edit: SaveEditFiles.Edit, state: () -> StateFiles): SaveEditFiles.Result = synchronized(lock) {
        if (!recoverLocked()) return@synchronized recoveryFailed()
        files.write(edit, state)
    }

    fun trade(a: SaveTradeJournal.Side, b: SaveTradeJournal.Side): SaveEditFiles.Result = synchronized(lock) {
        if (a.edit.path == b.edit.path) {
            return@synchronized SaveEditFiles.Result.Failed(tr("Both games use the same save file"))
        }
        if (!recoverLocked()) return@synchronized recoveryFailed()
        if (!journal.unchanged(a) || !journal.unchanged(b)) {
            return@synchronized SaveEditFiles.Result.Failed(tr("The save changed since it was opened - open it again"))
        }
        if (!runCatching {
                check(accept(a) && accept(b))
                journal.write(a, b)
            }.isSuccess) {
            return@synchronized SaveEditFiles.Result.Failed(tr("Can't write the save"))
        }
        val result = files.writeBoth(a.edit, b.edit)
        if (recoverLocked()) result else recoveryFailed()
    }

    private fun recoverLocked(): Boolean =
        journal.recover(accept) { files.finishFresh(states(it.stateKey)) }

    private fun recoveryFailed() = SaveEditFiles.Result.Failed(recoveryError())

    companion object {
        private val lock = SynchronizedObject()

        fun recoveryError() = tr("Couldn't finish recovering the interrupted save trade. Open the app again to retry.")
    }
}
