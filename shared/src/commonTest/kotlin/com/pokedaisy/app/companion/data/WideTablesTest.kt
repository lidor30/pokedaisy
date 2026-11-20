package com.pokedaisy.app.companion.data

import kotlin.test.Test
import kotlin.test.assertEquals
import okio.Buffer
import okio.Path.Companion.toPath

class WideTablesTest {
    private var reads = 0
    private var opens = 0
    private var adds = 0
    private val emptyIdentity = RomIdentityReader({ _, _ -> reads++; null }) { opens++; Buffer() }
    private fun tables(wide: (Int) -> Boolean = { false }, field: (Int) -> Boolean = { false }) =
        WideTables(wide, field, { _, _, _ -> adds++; true })
    private fun resolve(t: WideTables, crc: String = "12345678", size: Long = 1024) =
        t.resolve(crc, emptyIdentity, size, { opens++; Buffer() }) { _, _ -> reads++; null }

    @Test fun knownWideTableDoesNotReadTheRom() {
        assertEquals("12345678", resolve(tables(wide = { it == 0x12345678 })))
        assertEquals(0, reads); assertEquals(0, opens); assertEquals(0, adds)
    }
    @Test fun fieldOnlyTableAlsoSkipsEngineProof() {
        assertEquals("12345678", resolve(tables(field = { it == 0x12345678 })))
        assertEquals(0, reads); assertEquals(0, opens); assertEquals(0, adds)
    }
    @Test fun malformedCrcDoesNotReadOrRegister() {
        assertEquals("unknown", resolve(tables(), "unknown"))
        assertEquals(0, reads); assertEquals(0, opens); assertEquals(0, adds)
    }
    @Test fun missingHeaderCannotRegisterAnEngine() {
        assertEquals("12345678", resolve(tables()))
        assertEquals(0, opens); assertEquals(0, adds)
    }
    @Test fun unavailableNativeLookupRetainsSafeUnknownFallback() {
        assertEquals("12345678", resolve(tables(wide = { error("unavailable") })))
        assertEquals(0, adds)
    }
    @Test fun knownFileNeedsNoReadableFile() {
        var queries = 0
        assertEquals("12345678", tables(wide = { queries++; true }).resolve("/missing.gba".toPath(), "12345678"))
        assertEquals(1, queries)
        assertEquals(0, adds)
    }
    @Test fun unreadableFileRetainsOriginalCrc() {
        assertEquals("12345678", tables().resolve("/missing.gba".toPath(), "12345678"))
        assertEquals(0, adds)
    }
    @Test fun headerAloneIsNeverProof() {
        val header = ByteArray(0xC0)
        "BPEE".forEachIndexed { i, c -> header[0xAC+i] = c.code.toByte() }
        val read: (Long, Int) -> ByteArray? = { at, count -> if (at >= 0 && at+count <= header.size) header.copyOfRange(at.toInt(), at.toInt()+count) else null }
        assertEquals("12345678", tables().resolve("12345678", RomIdentityReader(read) { Buffer().write(header) }, header.size.toLong(), { Buffer().write(header) }, read))
        assertEquals(0, adds)
    }
}
