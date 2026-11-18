package com.pokedaisy.app.companion.data

import kotlin.random.Random
import kotlin.test.*
import okio.*
import okio.ByteString.Companion.toByteString

class RomIdentityReaderTest {
    private fun reader(bytes: ByteArray) = RomIdentityReader(
        { at, size -> if (at < 0 || at > bytes.size) null else bytes.copyOfRange(at.toInt(), minOf(bytes.size, at.toInt() + size)) },
        { Buffer().write(bytes) })

    @Test fun headersAndConsoleIdentityUseTheSameBytesOnEveryHost() {
        val gba = ByteArray(0x160)
        "DIAGNOSTIC  ".encodeToByteArray().copyInto(gba, 0xA0)
        "BPEE".encodeToByteArray().copyInto(gba, 0xAC)
        gba[0xB2] = 0x96.toByte(); gba[0xBC] = 3
        val r = reader(gba)
        assertEquals("BPEE" to 3, r.header()); assertEquals("BPEE", r.gameCode())
        assertEquals(3, r.revision()); assertEquals("DIAGNOSTIC", r.headerTitle())
        assertTrue(r.looksLikeRom()); assertEquals(5, r.raConsole())
        val gb = ByteArray(0x160)
        byteArrayOf(0xCE.toByte(), 0xED.toByte(), 0x66, 0x66).copyInto(gb, 0x104)
        assertEquals(4, reader(gb).raConsole())
        gb[0x143] = 0xC0.toByte(); assertEquals(6, reader(gb).raConsole())
        assertNull(reader(gba.copyOf(0xB0)).header())
    }

    @Test fun wholeFileHashesStreamBeyondOneBufferAndRawPathReadsMatch() {
        val bytes = ByteArray(200_000) { (it * 37).toByte() }
        val r = reader(bytes)
        assertEquals(bytes.toByteString().sha1().hex(), r.sha1())
        assertEquals(bytes.toByteString().md5().hex(), r.md5())
        val file = FileSystem.SYSTEM_TEMPORARY_DIRECTORY / "pokedaisy-identity-${Random.nextLong().toULong()}.gba"
        try {
            FileSystem.SYSTEM.write(file) { write(bytes) }
            assertEquals(r.sha1(), RomIdentityReader.forPath(file).sha1())
            assertEquals(r.md5(), RomIdentityReader.forPath(file).md5())
        } finally { FileSystem.SYSTEM.delete(file) }
        assertNull(RomIdentityReader.forPath(file).sha1())
    }

    @Test fun dsAchievementHashIncludesCodeAndPaddedIconsAfterOptionalSuperCardHeader() {
        val bytes = ByteArray(0x250) { (it * 3).toByte() }
        fun u32(at: Int, value: Int) { repeat(4) { bytes[at + it] = (value ushr (it * 8)).toByte() } }
        "DGNE".encodeToByteArray().copyInto(bytes, 0x0C); bytes[0x1E] = 2
        bytes[0x15C] = 0x56; bytes[0x15D] = 0xCF.toByte()
        u32(0x20, 0x200); u32(0x2C, 16); u32(0x30, 0x220); u32(0x3C, 12); u32(0x68, 0x240)
        val expected = (bytes.copyOfRange(0, 0x160) + bytes.copyOfRange(0x200, 0x210) +
            bytes.copyOfRange(0x220, 0x22C) + bytes.copyOfRange(0x240, 0x250).copyOf(0xA00)).toByteString().md5().hex()
        val r = reader(bytes)
        assertEquals("DGNE" to 2, r.header(nds = true)); assertEquals(18, r.raConsole())
        assertEquals(expected, r.ndsRaHash()); assertEquals(expected, r.raHash())
        val prefix = ByteArray(512)
        prefix[0] = 0x2E; prefix[3] = 0xEA.toByte()
        prefix[0xB0] = 0x44; prefix[0xB1] = 0x46; prefix[0xB2] = 0x96.toByte()
        assertEquals(expected, reader(prefix + bytes).ndsRaHash())
        u32(0x2C, 17 shl 20)
        assertNull(reader(bytes).ndsRaHash())
    }
}
