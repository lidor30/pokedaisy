package com.pokedaisy.app.companion.data

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse

import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.zip.CRC32

/**
 * WILD PIN's contract ([WildPin]): the 48-byte block over a map's land slots, the pinned species' level range, the block
 * list it joins with PLAYER LOOK's, when it ends and RetroAchievements idling. The headless check (mgba_dump, the prove
 * stage) uses [WildPin.Pin.toText]: a `romblocks` line at the slots' ROM address ([GuideRomSource.pinSlots]).
 */
class WildPinTest {
    /** A made-up map: species 10 in slots 0, 2, 5 (Lv 2-3, 3-4, 5-5), species 20 elsewhere at Lv 4-6, one rare 30 at Lv 9. */
    private val slotBytes: ByteArray = run {
        val slots = listOf(
            Triple(2, 3, 10), Triple(4, 6, 20), Triple(3, 4, 10), Triple(4, 6, 20), Triple(4, 6, 20), Triple(5, 5, 10),
            Triple(4, 6, 20), Triple(4, 6, 20), Triple(4, 6, 20), Triple(4, 6, 20), Triple(9, 9, 30), Triple(4, 6, 20),
        )
        ByteArray(48).also { b ->
            slots.forEachIndexed { i, (min, max, sp) ->
                b[i * 4] = min.toByte(); b[i * 4 + 1] = max.toByte(); b[i * 4 + 2] = (sp and 0xFF).toByte(); b[i * 4 + 3] = (sp shr 8).toByte()
            }
        }
    }
    private val slots = WildPin.Slots(0x083C9CB8L, slotBytes)

    @Test
    fun blockIsTwelveSlotsOfTheSpeciesAtItsOwnLevels() {
        val pin = must(WildPin.pin(10, 3, 19, 0, slots))
        assertEquals(2, pin.minLevel)
        assertEquals(5, pin.maxLevel)
        val b = pin.block.data
        assertEquals(48, b.size)
        for (i in 0 until 12) {
            assertEquals(2, b[i * 4].toInt())
            assertEquals(5, b[i * 4 + 1].toInt())
            assertEquals(10, (b[i * 4 + 2].toInt() and 0xFF) or ((b[i * 4 + 3].toInt() and 0xFF) shl 8))
        }
        assertEquals(0x083C9CB8, pin.block.addr)
        // Guarded by the file's bytes: the native side puts it in only where the ROM still holds them.
        assertEquals(CRC32().apply { update(slotBytes) }.value.toInt(), pin.block.crc)
    }

    @Test
    fun aSpeciesIdAbove255KeepsBothBytes() {
        val b = WildPin.blockBytes(0x19A, 7, 9)
        assertArrayEquals(byteArrayOf(7, 9, 0x9A.toByte(), 0x01), b.copyOfRange(44, 48))
    }

    @Test
    fun rareSlotKeepsItsOneLevel() {
        val pin = must(WildPin.pin(30, 3, 19, 0, slots))
        assertEquals(9..9, pin.minLevel..pin.maxLevel)
    }

    @Test
    fun noPinForASpeciesTheMapDoesNotHave() {
        assertNull(WildPin.pin(99, 3, 19, 0, slots))
        assertNull(WildPin.pin(10, 3, 19, 0, WildPin.Slots(0x083C9CB8L, ByteArray(40))))
        assertNull(WildPin.pin(10, 3, 19, 0, WildPin.Slots(0x02000000L, slotBytes)))
    }

    @Test
    fun slotsThatCannotBeRealAreSkipped() {
        val b = slotBytes.copyOf()
        b[44] = 0; b[45] = 0 // slot 11 at Lv 0: never the game's
        b[46] = 40
        assertNull(WildPin.levelRange(b, 40))
        b[44] = 8; b[45] = 7 // max below min
        assertNull(WildPin.levelRange(b, 40))
    }

    @Test
    fun romblocksLine() {
        val pin = must(WildPin.pin(30, 3, 19, 0, slots))
        val text = pin.toText()
        assertTrue(text.startsWith("block 083C9CB8 %08X 09091E00".format(pin.block.crc)))
        assertEquals(6 + 9 + 9 + 96 + 1, text.length)
    }

    @Test
    fun pinJoinsPlayerLooksBlocksInOneList() {
        val look = listOf(WildPin.RomBlock(0x08300000, 1, ByteArray(32)), WildPin.RomBlock(0x08310000, 2, ByteArray(8)))
        val pin = must(WildPin.pin(10, 3, 19, 0, slots))
        val all = WildPin.withPin(look, pin)
        assertEquals(listOf(0x08300000, 0x08310000, 0x083C9CB8), all.map { it.addr })
        // After it ends (pin cleared): only the look's go back in, and with no look nothing at all.
        assertEquals(look, WildPin.withPin(look, null))
        assertTrue(WildPin.withPin(emptyList(), null).isEmpty())
        assertEquals(listOf(0x083C9CB8), WildPin.withPin(emptyList(), pin).map { it.addr })
    }

    @Test
    fun endsOnTheFirstWildBattleOnly() {
        assertTrue(WildPin.endsNow(wasBattling = false, battling = true) { 0 }) // wild single
        assertTrue(WildPin.endsNow(false, true) { 0x01 }) // wild double
        assertFalse(WildPin.endsNow(false, true) { 0x08 }) // a trainer's: the pin waits
        assertFalse(WildPin.endsNow(false, true) { 0x09 })
        assertTrue(WildPin.endsNow(false, true) { null }) // unreadable: one encounter is the rule
        assertTrue(WildPin.endsNow(false, true) { error("read failed") })
        assertFalse(WildPin.endsNow(true, true) { 0 }) // already in it
        assertFalse(WildPin.endsNow(false, false) { 0 })
    }

    @Test
    fun pinBelongsToItsMap() {
        val pin = must(WildPin.pin(10, 3, 19, 0, slots))
        assertTrue(pin.onMap(3, 19))
        assertFalse(pin.onMap(3, 20))
        assertFalse(pin.onMap(4, 19))
    }

    @Test
    fun blockedReasons() {
        assertEquals(WildPin.NUZLOCKE_REASON, WildPin.blocked(nuzlocke = true, inBattle = false))
        assertEquals(WildPin.NUZLOCKE_REASON, WildPin.blocked(nuzlocke = true, inBattle = true))
        assertEquals(WildPin.BATTLE_REASON, WildPin.blocked(nuzlocke = false, inBattle = true))
        assertNull(WildPin.blocked(nuzlocke = false, inBattle = false))
    }

    @Test
    fun offeredOnRetailEnglishAndMonomyth() {
        assertTrue(WildPin.offered(GUIDE_TABLES_FIRERED_REV1))
        assertTrue(WildPin.offered(GUIDE_TABLES_LEAFGREEN_REV0))
        assertTrue(WildPin.offered(GUIDE_TABLES_LEAFGREEN_REV1))
        assertTrue(WildPin.offered(GUIDE_TABLES_EMERALD))
        assertTrue(WildPin.offered(GUIDE_TABLES_MONOMYTH))
        assertTrue(WildPin.offered(GUIDE_TABLES_MONOMYTH_V1_1))
        assertFalse(WildPin.offered(null))
        assertFalse(WildPin.offered(GUIDE_TABLES_EMERALD_KAIZO))
        assertFalse(WildPin.offered(GUIDE_TABLES_EMERALD.copy(probeName = "X")))
    }

    /** A made-up 10-slot fishing table: old rod 0-1 (species 40, Lv 5), good rod 2-4 (41 at 10-15, 42 at 12), super rod
     * 5-9 (41 again at 25-30, 43 elsewhere). */
    private val fishBytes: ByteArray = run {
        val slots = listOf(
            Triple(5, 5, 40), Triple(5, 5, 40), Triple(10, 15, 41), Triple(12, 12, 42), Triple(10, 15, 41),
            Triple(25, 30, 43), Triple(25, 30, 41), Triple(25, 30, 43), Triple(25, 30, 43), Triple(25, 30, 43),
        )
        ByteArray(40).also { b ->
            slots.forEachIndexed { i, (min, max, sp) -> b[i * 4] = min.toByte(); b[i * 4 + 1] = max.toByte(); b[i * 4 + 2] = sp.toByte() }
        }
    }

    @Test
    fun methodsHaveTheGamesSlotCounts() {
        assertEquals(listOf(12, 5, 5, 10), WildPin.Method.entries.map { it.slots })
        assertEquals(listOf(0, 1, 2, 3), WildPin.Method.entries.map { it.index })
        assertEquals(WildPin.Method.LAND, WildPin.Method.forSection("GRASS / CAVE"))
        assertEquals(WildPin.Method.WATER, WildPin.Method.forSection("SURFING"))
        assertEquals(WildPin.Method.ROCK_SMASH, WildPin.Method.forSection("ROCK SMASH"))
        for (rod in listOf("OLD ROD", "GOOD ROD", "SUPER ROD")) assertEquals(WildPin.Method.FISHING, WildPin.Method.forSection(rod))
        assertNull(WildPin.Method.forSection("HEADBUTT"))
        assertNull(WildPin.Method.forSection("AT NIGHT"))
    }

    @Test
    fun surfBlockIsFiveSlots() {
        val surf = ByteArray(20).also { b ->
            listOf(Triple(20, 30, 50), Triple(10, 20, 51), Triple(30, 35, 50), Triple(5, 35, 52), Triple(25, 30, 52)).forEachIndexed { i, (min, max, sp) ->
                b[i * 4] = min.toByte(); b[i * 4 + 1] = max.toByte(); b[i * 4 + 2] = sp.toByte()
            }
        }
        val pin = must(WildPin.pin(50, 1, 2, 0, WildPin.Slots(0x08550000L, surf, WildPin.Method.WATER)))
        assertEquals(WildPin.Method.WATER, pin.method)
        assertEquals(20..35, pin.minLevel..pin.maxLevel)
        assertEquals(20, pin.block.size)
        assertEquals(CRC32().apply { update(surf) }.value.toInt(), pin.block.crc)
        for (i in 0 until 5) assertArrayEquals(byteArrayOf(20, 35, 50, 0), pin.block.data.copyOfRange(i * 4, i * 4 + 4))
        // A land-sized table read as water (or the other way) is refused.
        assertNull(WildPin.pin(50, 1, 2, 0, WildPin.Slots(0x08550000L, surf, WildPin.Method.LAND)))
        assertNull(WildPin.pin(10, 3, 19, 0, WildPin.Slots(0x083C9CB8L, slotBytes, WildPin.Method.WATER)))
    }

    @Test
    fun rockSmashBlockIsFiveSlots() {
        val rock = ByteArray(20).also { b -> for (i in 0 until 5) { b[i * 4] = (10 + i).toByte(); b[i * 4 + 1] = (15 + i).toByte(); b[i * 4 + 2] = (60 + i % 2).toByte() } }
        val pin = must(WildPin.pin(61, 1, 2, 0, WildPin.Slots(0x08560000L, rock, WildPin.Method.ROCK_SMASH)))
        assertEquals(11..18, pin.minLevel..pin.maxLevel) // slots 1 and 3
        assertEquals(20, pin.block.size)
    }

    @Test
    fun fishingPinsAllTenSlotsSoAnyRodCatchesIt() {
        val fish = WildPin.Slots(0x08570000L, fishBytes, WildPin.Method.FISHING)
        // On two rods' slots: its levels span both (10-15 good, 25-30 super).
        val pin = must(WildPin.pin(41, 1, 2, 0, fish))
        assertEquals(10..30, pin.minLevel..pin.maxLevel)
        assertEquals(40, pin.block.size)
        for (i in 0 until 10) assertArrayEquals(byteArrayOf(10, 30, 41, 0), pin.block.data.copyOfRange(i * 4, i * 4 + 4))
        assertTrue(pin.toText().startsWith("block 08570000 %08X 0A1E2900".format(pin.block.crc)))
        // An old rod one: its own level, on every rod.
        assertEquals(5..5, must(WildPin.pin(40, 1, 2, 0, fish)).let { it.minLevel..it.maxLevel })
        assertNull(WildPin.pin(99, 1, 2, 0, fish))
    }

    @Test
    fun aPinIsForItsMethod() {
        val fish = must(WildPin.pin(41, 1, 2, 0, WildPin.Slots(0x08570000L, fishBytes, WildPin.Method.FISHING)))
        assertEquals(WildPin.Method.FISHING, fish.method)
        assertEquals(WildPin.Method.LAND, must(WildPin.pin(10, 3, 19, 0, slots)).method)
    }

    private fun <T> must(v: T?): T = requireNotNull(v)
}
