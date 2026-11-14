package com.pokedaisy.app.companion.data

import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okio.FileSystem
import okio.SYSTEM
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** Small runtime checks of the platform implementations, also linked and run on the iOS simulator in CI. */
class SharedPlatformSmokeTest {
    @Test
    fun filesAndPngsRoundTrip() {
        val dir = FileSystem.SYSTEM_TEMPORARY_DIRECTORY / "pokedaisy-smoke-${Random.nextLong().toULong()}"
        try {
            val file = dir / "art" / "sprite.png"
            val image = RomArt.Image(3, 2, intArrayOf(0, -1, 0xFF123456.toInt(), 0x80765432.toInt(), 1, 0))
            val encoded = image.png()
            file.writeAtomically(encoded)
            assertContentEquals(encoded, file.readBytes())
            val decoded = assertNotNull(RomArt.Image.decodePng(file.readBytes()))
            assertEquals(image.width, decoded.width)
            assertEquals(image.height, decoded.height)
            assertContentEquals(image.argb, decoded.argb)
            withRomFile(file) { handle ->
                assertContentEquals(encoded.copyOfRange(8, 16), handle.readFully(8, 8))
            }
            // Replacing an existing file must work on Native too, with no stale suffix left behind.
            file.writeAtomically(byteArrayOf(1, 2))
            assertContentEquals(byteArrayOf(1, 2), file.readBytes())
            assertEquals(listOf("sprite.png"), file.parent!!.listFiles().map { it.name })
            assertEquals("a9993e364706816aba3e25717850c26c9cd0d89d", sha1("abc".encodeToByteArray()).hex())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun backgroundWorkersShareALock() = runBlocking {
        val lock = SynchronizedObject()
        var count = 0
        val done = Channel<Unit>(8)
        val workers = List(8) {
            background("shared-smoke-worker") {
                repeat(1000) { synchronized(lock) { count++ } }
                done.trySend(Unit).getOrThrow()
            }
        }
        try {
            withTimeout(10_000) { repeat(workers.size) { done.receive() } }
            workers.forEach { it.join() }
            assertEquals(8000, synchronized(lock) { count })
        } finally {
            workers.forEach { it.cancel() }
            done.close()
        }
    }

    @Test
    fun serialQueueKeepsWriteOrder() = runBlocking {
        val queue = SerialQueue("shared-smoke-queue")
        val values = Channel<Int>(32)
        repeat(32) { value -> queue.post { values.trySend(value).getOrThrow() } }
        try {
            withTimeout(10_000) {
                repeat(32) { expected -> assertEquals(expected, values.receive()) }
            }
        } finally {
            values.close()
        }
    }

    @Test
    fun memoryReadsUseTheInstalledCore() {
        val previous = EmulatorCore.current
        val ram = ByteArray(0x40000) { it.toByte() }
        val rom = "test cart".encodeToByteArray()
        val fake = object : EmulatorCore {
            override fun readBytes(addr: Long, len: Int): ByteArray? = when {
                addr >= 0x02000000L && addr + len <= 0x02040000L ->
                    ram.copyOfRange((addr - 0x02000000L).toInt(), (addr - 0x02000000L).toInt() + len)
                addr >= 0x03000000L && addr + len <= 0x03008000L -> ByteArray(len)
                addr >= 0x08000000L && addr + len <= 0x08000000L + rom.size ->
                    rom.copyOfRange((addr - 0x08000000L).toInt(), (addr - 0x08000000L).toInt() + len)
                else -> null
            }
            override fun readInto(addr: Long, dst: ByteArray): Boolean {
                val bytes = readBytes(addr, dst.size) ?: return false
                bytes.copyInto(dst)
                return true
            }
            override fun romCode() = "BPRE"
            override fun romSize() = rom.size.toLong()
            override fun platform() = EmulatorCore.PLATFORM_GBA
            override fun romRead(off: Long, len: Int): ByteArray? = null
        }
        try {
            EmulatorCore.current = fake
            assertContentEquals(byteArrayOf(16, 17, 18, 19), InProcessReader.readCoreMemory(0x02000010L, 4))
            val buffers = RamSnapshot.Buffers(nds = false)
            val snapshot = assertNotNull(RamSnapshot.capture(buffers))
            ram[16] = 99
            assertContentEquals(byteArrayOf(16, 17, 18, 19), snapshot.readCoreMemory(0x02000010L, 4))
            assertContentEquals(rom, snapshot.readCoreMemory(0x08000000L, rom.size))
            EmulatorCore.current = EmulatorCore.None
            assertNull(RamSnapshot.capture(buffers))
            assertFailsWith<TelemetryDecodeException> { InProcessReader.readCoreMemory(0x02000010L, 4) }
        } finally {
            EmulatorCore.current = previous
        }
    }
}
