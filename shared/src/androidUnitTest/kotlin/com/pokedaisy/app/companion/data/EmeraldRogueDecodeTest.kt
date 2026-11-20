package com.pokedaisy.app.companion.data

import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/** NATIVE_EMERALD_ROGUE on a headless capture of the user's real save (see the fixture's README). */
class EmeraldRogueDecodeTest {
    private val t = decodeNative("emerald_rogue", NATIVE_EMERALD_ROGUE)

    @Test
    fun `party populated with the real live mon`() {
        // 1 Rockruff (National Dex #744, Rogue's own species ids), level 10, full HP.
        assertEquals(1, t.party.size)
        val mon = t.party[0]
        assertEquals(744, mon.species)
        assertEquals(10, mon.level)
        assertEquals(31, mon.hp)
        assertEquals(31, mon.maxHp)
    }

    @Test
    fun `bag matches the game's own bag screen`() {
        // Headless screenshots: MEDICINE Potion x6, and 2/30 slots used.
        assertEquals(listOf(Item(39, 6, POCKET_ITEMS), Item(1, 10, POCKET_POKE_BALLS)), t.items)
        assertPocketsInRange(t)
    }

    @Test
    fun `location is the hub, named as the save names it`() {
        assertEquals(0, t.regionMapSectionId) // MAPSEC_POKEMON_HUB
        assertEquals(2 to 5, t.mapGroup to t.mapNum)
        assertEquals("The Pokémon HuA", t.mapSecName) // SaveBlock2.pokemonHubName, as stored
    }

    @Test
    fun `map shape follows gMapHeader into the ROM`() {
        val rom = RomFileReader.load(File(File(RomFileReader.FIRERED_REV1_PATH).parentFile, "Pokemon Emerald Rogue (v2.2.1-EX).gba").path)
        assumeTrue("no Rogue ROM on this machine", rom != null)
        val ram = FixtureMemoryReader.load("emerald_rogue")
        val both = object : MemoryReader {
            override fun readCoreMemory(addr: Long, size: Int) =
                if (addr >= 0x08000000L) rom!!.readCoreMemory(addr, size) else ram.readCoreMemory(addr, size)
        }
        assertEquals(MapShape(20, 16, 3), readMapShape(both, NATIVE_EMERALD_ROGUE.mapHeader)) // MAP_TYPE_ROUTE
    }

    @Test
    fun `money is read, not guessed`() {
        assertEquals(0L, t.money)
    }
}
