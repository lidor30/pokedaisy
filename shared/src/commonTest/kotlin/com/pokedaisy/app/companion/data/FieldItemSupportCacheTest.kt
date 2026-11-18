package com.pokedaisy.app.companion.data

import kotlin.test.*

class FieldItemSupportCacheTest {
    private fun live() = SnapshotView(connected = true, game = GameKind.EMERALD,
        party = listOf(MonView(1, "Test", 5, 10, 10, "", emptyList(), null)))
    @Test fun failedReadsRetryAndVerifiedTablesAreReusedUntilTheGameChanges() {
        val bytes = ByteArray(32)
        var reads = 0
        var booted = false
        val cache = FieldItemSupportCache(object : MemoryReader {
            override fun readCoreMemory(addr: Long, size: Int): ByteArray {
                reads++
                check(booted)
                return bytes
            }
        })
        val fns = FieldItemFns(0x08000100, 0, 0, 0, 0, 0, 0, listOf(crc32(bytes)))
        assertNull(cache.get(live(), "TEST", 0, fns))
        booted = true
        val support = assertNotNull(cache.get(live(), "TEST", 0, fns))
        assertEquals(2, reads)
        assertSame(support, cache.get(live(), "TEST", 0, fns))
        assertEquals(2, reads)
        assertNotNull(cache.get(live(), "TEST", 1, fns))
        assertEquals(3, reads)
    }
    @Test fun aRecognizedMapWithoutAPartyCannotExposeActionsEvenAfterTheRomWasVerified() {
        var reads = 0
        val bytes = ByteArray(32)
        val cache = FieldItemSupportCache(object : MemoryReader {
            override fun readCoreMemory(addr: Long, size: Int): ByteArray { reads++; return bytes }
        })
        val fns = FieldItemFns(0x08000100, 0, 0, 0, 0, 0, 0, listOf(crc32(bytes)))
        val game = live()
        val title = game.copy(party = emptyList(), location = game.location.copy(mapSecName = "Test"))
        assertTrue(title.hasData) // this was too weak for a field-action gate
        assertNull(cache.get(title, "TEST", 0, fns))
        assertEquals(0, reads)
        val support = assertNotNull(cache.get(game, "TEST", 0, fns))
        assertNull(cache.get(title, "TEST", 0, fns))
        assertNull(cache.get(game.copy(connected = false), "TEST", 0, fns))
        assertSame(support, cache.get(game, "TEST", 0, fns))
        assertEquals(1, reads)
    }

}
