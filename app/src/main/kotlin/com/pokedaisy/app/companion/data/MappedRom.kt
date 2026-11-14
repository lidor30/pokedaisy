package com.pokedaisy.app.companion.data

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel

/** [block] over [file] mapped read-only (a 32 MB ROM without copying it onto the heap). */
internal inline fun <T> withMappedRom(file: File, block: (LittleEndianReader) -> T): T =
    RandomAccessFile(file, "r").use { f ->
        block(MappedLittleEndian(f.channel.map(FileChannel.MapMode.READ_ONLY, 0, f.length()).order(ByteOrder.LITTLE_ENDIAN)))
    }

internal class MappedLittleEndian(private val b: ByteBuffer) : LittleEndianReader {
    override fun limit(): Int = b.limit()
    override fun get(i: Int): Byte = b.get(i)
    override fun getShort(i: Int): Short = b.getShort(i)
    override fun getInt(i: Int): Int = b.getInt(i)
    override fun getLong(i: Int): Long = b.getLong(i)
}
