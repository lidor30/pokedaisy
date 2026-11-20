package com.pokedaisy.app.companion.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** NATIVE_QUETZAL on the field (a headless capture of the user's save, rhh_splash.txt). */
class QuetzalDecodeTest {
    @Test
    fun `party, bag and location decode from the real save`() {
        activeGame = GameKind.QUETZAL
        val t = decodeNative("quetzal", NATIVE_QUETZAL)
        assertEquals(1, t.party.size)
        val mon = t.party[0]
        assertEquals("Charmander", speciesNamesQuetzal[mon.species])
        assertEquals("Charmander", mon.nickname) // vanilla's 10-byte nickname at +0x08
        assertEquals(5, mon.level)
        assertEquals(10 to 19, mon.hp to mon.maxHp) // HP is 10 bits at +0x23
        assertEquals(0L, mon.status)
        assertEquals(140L, mon.exp)
        assertEquals(listOf("Tackle", "Growl", "Ember"), mon.moves.filter { it != 0 }.map { lookupMove(it).name })
        assertEquals(listOf(56, 64, 40), mon.pp.take(3)) // 3 PP Ups on everything
        assertFalse(mon.isEgg)
        // IVs reproduce all six stored stats (19 / 11 / 10 / 13 / 11 / 11).
        val stats = mon.stats!!
        assertEquals(listOf(19, 31, 22, 31, 10, 31), stats.ivs)
        assertEquals(listOf(19, 11, 10, 13, 11, 11), stats.stats)

        assertEquals(
            mapOf("Poké Ball" to 6, "Premier Ball" to 1, "Potion" to 1, "Burn Heal" to 1, "Paralyze Heal" to 1,
                "Repel" to 1, "Escape Rope" to 1, "Poké Doll" to 1),
            t.items.filter { it.pocket != POCKET_KEY_ITEMS }.associate { itemNamesQuetzal[it.itemId] to it.quantity },
        )
        assertEquals(setOf("Town Map", "Mega Ring", "Z-Power Ring", "Exp. Share"),
            t.items.filter { it.pocket == POCKET_KEY_ITEMS }.map { itemNamesQuetzal[it.itemId] }.toSet())
        assertEquals(POCKET_POKE_BALLS, t.items.first { it.itemId == 1 }.pocket)
        assertPocketsInRange(t)

        assertEquals(0x65, t.regionMapSectionId)
        assertEquals("Route 1", mapSecDataQuetzal.getValue(t.regionMapSectionId).name)
        assertTrue(t.x > 0 && t.y > 0)
        assertFalse(t.inBattle)
    }

    /** A second save, further on: six Pokémon (Gen 9's TINKATINK among them), standing in Johto. */
    @Test
    fun `a Johto save decodes - party, bag, map, money`() {
        activeGame = GameKind.QUETZAL
        val t = decodeNative("quetzal_johto", NATIVE_QUETZAL)
        assertEquals(
            listOf("Charmander" to 11, "Sneasel" to 12, "Cranidos" to 10, "Spiritomb" to 10, "Rufflet" to 8, "Tinkatink" to 12),
            t.party.map { speciesNamesQuetzal[it.species] to it.level },
        )
        assertEquals(listOf("Torch Song", "Growl", "Ember", "Smokescreen"), t.party[0].moves.map { lookupMove(it).name })
        assertTrue(t.party.none { it.isEgg })
        assertEquals(255, t.items.first { itemNamesQuetzal[it.itemId] == "Poké Ball" }.quantity)
        assertEquals(setOf("Town Map", "Mega Ring", "Z-Power Ring", "Exp. Share", "Dynamax Band", "Tera Orb"),
            t.items.filter { it.pocket == POCKET_KEY_ITEMS }.map { itemNamesQuetzal[it.itemId] }.toSet())
        assertPocketsInRange(t)
        // Map group 35: Johto's own section table (0x100 + 2).
        assertEquals(35 to 102, t.mapGroup to t.mapNum)
        assertEquals(0x102, t.regionMapSectionId)
        assertEquals("Violet City", mapSecDataQuetzal.getValue(t.regionMapSectionId).name)
        assertEquals(48937L, t.money)
        assertFalse(t.inBattle)
    }

    /** In Johto's map groups (34-35) the section id is Johto's own table's: 0x100 + id. */
    @Test
    fun `Johto maps name their sections from Johto's table`() {
        val base = FixtureMemoryReader.load("quetzal")
        val sb1 = u32le(base.readCoreMemory(NATIVE_QUETZAL.saveBlock1Ptr, 4), 0)
        val reader = object : MemoryReader {
            override fun readCoreMemory(addr: Long, size: Int): ByteArray {
                val b = base.readCoreMemory(addr, size)
                (sb1 + 0x474 - addr).takeIf { it in 0 until size }?.let { b[it.toInt()] = 34 }        // mapGroup
                (NATIVE_QUETZAL.mapHeader + 0x14 - addr).takeIf { it in 0 until size }?.let { b[it.toInt()] = 2 } // mapsec
                return b
            }
        }
        resetNativeBagCache()
        val t = readNativeTelemetry(reader, NATIVE_QUETZAL)
        assertEquals(0x102, t.regionMapSectionId)
        assertEquals("Violet City", mapSecDataQuetzal.getValue(t.regionMapSectionId).name)
    }

    @Test
    fun `an egg is the IV word's bit 30`() {
        activeGame = GameKind.QUETZAL
        val raw = FixtureMemoryReader.load("quetzal").readCoreMemory(NATIVE_QUETZAL.playerParty, 0x68)
        raw[0x53] = (raw[0x53].toInt() or 0x40).toByte()
        val base = FixtureMemoryReader.load("quetzal")
        val party = NATIVE_QUETZAL.playerParty
        val reader = object : MemoryReader {
            override fun readCoreMemory(addr: Long, size: Int): ByteArray {
                val b = base.readCoreMemory(addr, size)
                for (i in b.indices) (addr + i - party).takeIf { it in raw.indices.map(Int::toLong) }?.let { b[i] = raw[it.toInt()] }
                return b
            }
        }
        resetNativeBagCache()
        val mon = readNativeTelemetry(reader, NATIVE_QUETZAL).party[0]
        assertTrue(mon.isEgg)
        assertEquals(1529, mon.species)
    }
}
