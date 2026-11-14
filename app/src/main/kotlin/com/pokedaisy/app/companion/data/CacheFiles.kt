package com.pokedaisy.app.companion.data

import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath
import okio.SYSTEM

/*
 * The files the data code keeps under filesDir (ROM art, caches, small stores), through okio (Kotlin
 * Multiplatform) instead of java.io.File, which the iPad build doesn't have. Named as java.io.File's, so a
 * converted call site reads the same: `File(dir, "x")` is `dir / "x"`.
 */

/** Whether a regular file is there. */
val Path.isFile: Boolean get() = FileSystem.SYSTEM.metadataOrNull(this)?.isRegularFile == true

/** Whether a directory is there. */
val Path.isDirectory: Boolean get() = FileSystem.SYSTEM.metadataOrNull(this)?.isDirectory == true

/** The modification time in ms, 0 when unknown, as File.lastModified(). */
val Path.lastModified: Long get() = FileSystem.SYSTEM.metadataOrNull(this)?.lastModifiedAtMillis ?: 0L

/** The size in bytes, 0 when it isn't there, as File.length(). */
val Path.length: Long get() = FileSystem.SYSTEM.metadataOrNull(this)?.size ?: 0L

fun Path.readBytes(): ByteArray = FileSystem.SYSTEM.read(this) { readByteArray() }

fun Path.readText(): String = FileSystem.SYSTEM.read(this) { readUtf8() }

/** The lines without their ends, as File.readLines() (no empty last line after a final newline). */
fun Path.readLines(): List<String> = FileSystem.SYSTEM.read(this) { generateSequence { readUtf8Line() }.toList() }

/** Creates the directory and its parents; quiet when it's there. */
fun Path.mkdirs() = FileSystem.SYSTEM.createDirectories(this)

/** Writes [bytes] here, creating the parent directories. */
fun Path.writeBytes(bytes: ByteArray) {
    parent?.mkdirs()
    FileSystem.SYSTEM.write(this) { write(bytes) }
}

fun Path.writeText(text: String) {
    parent?.mkdirs()
    FileSystem.SYSTEM.write(this) { writeUtf8(text) }
}

fun Path.writeTextAtomically(text: String) = writeAtomically(text.encodeToByteArray())

/** Writes [bytes] to a `.tmp` beside it, then moves that into place: a reader never sees half a file. */
fun Path.writeAtomically(bytes: ByteArray) {
    val tmp = resolveSibling("$name.tmp")
    tmp.writeBytes(bytes)
    tmp.moveTo(this)
}

/** Moves this file over [to] in one step, as File.renameTo did (but throwing where that returned false). */
fun Path.moveTo(to: Path) = FileSystem.SYSTEM.atomicMove(this, to)

/** Copies this file over [to], creating its parent directories. */
fun Path.copyTo(to: Path) {
    to.parent?.mkdirs()
    FileSystem.SYSTEM.copy(this, to)
}

/** The directory's entries, empty when it isn't one (File.listFiles(), never null). */
fun Path.listFiles(): List<Path> = FileSystem.SYSTEM.listOrNull(this).orEmpty()

/** Deletes a file or an empty directory; quiet when it's gone. */
fun Path.delete() = FileSystem.SYSTEM.delete(this, mustExist = false)

/** Deletes it and everything under it; quiet when it's gone. */
fun Path.deleteRecursively() = FileSystem.SYSTEM.deleteRecursively(this, mustExist = false)

private fun Path.resolveSibling(name: String): Path = parent?.div(name) ?: name.toPath()
