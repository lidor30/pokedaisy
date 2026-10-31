package com.pokedaisy.app.companion.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * REUSABLE TMS' sites, found in each ROM by their code ([ReusableTms.find]): the TM-number compare, the `bhi` the
 * patch turns into a `b` and the call to the game's RemoveBagItem - which must be the RemoveBagItem FieldItems has for
 * that build (found its own way: port_field_items.py, the decomps). Played headless with mgba_dump's `rompatch`
 * (docs/ROM_FEATURES.md, note 15). Skips a ROM that isn't here.
 */
class ReusableTmsTest {
    private val cases = listOf(
        "Pokemon - FireRed Version (USA, Europe) (Rev 1).gba" to 3,
        "Pokemon - FireRed Version (USA).gba" to 3,
        "Pokemon - LeafGreen Version (USA).gba" to 3,
        "Pokemon - LeafGreen Version (USA, Europe) (Rev 1).gba" to 3,
        "Pokemon - Emerald Version (USA, Europe).gba" to 1,
        "Pokemon - Ruby Version (USA, Europe) (Rev 1).gba" to 1,
        "Pokemon - Sapphire Version (USA, Europe) (Rev 2).gba" to 1,
        // Hacks that kept that code.
        "Pokemon Emerald Kaizo (v2.0).gba" to 1,
        "Pokemon Emerald Legacy (v1.1.4).gba" to 1,
        "Pokemon Orange Islands (Beta 5.7).gba" to 3,
        "Pokemon FireRed Rocket Edition.gba" to 3,
        "Pokemon Ultra Violet (v1.22).gba" to 3,
        "Pokemon Celia's Stupid Romhack (v1.1.4).gba" to 3,
        // One of FireRed's three left: none, it would still use TMs up elsewhere.
        "Pokemon Ash Gray (v4.5.3).gba" to 0,
        "Pokemon Ghost Grey (v1.0.4).gba" to 0,
        // Their own TM code: Radical Red / Unbound / SoulGold's TMs never run out ([ReusableTms.BUILT_IN]).
        "Pokemon Radical Red (v4.1).gba" to 0,
        "Pokemon Unbound (v2.1.1.1).gba" to 0,
        "Pokemon SoulGold (v1.1.4).gba" to 0,
        "Pokemon SoulGold (v1.2) (Alt 2).gba" to 0,
        "Pokemon SoulGold (v1.2.1).gba" to 0,
        "Pokemon Glazed (v9.2.0).gba" to 0,
        "Pokemon Lazarus (v2.0).gba" to 0,
    )

    @Test
    fun findsEachGamesSites() {
        for ((name, want) in cases) {
            val rom = File(RETAIL_ROM_DIR + name)
            if (!rom.isFile) continue
            val sites = ReusableTms.find(rom)
            assertEquals(name, want, sites.size)
            sites.forEach { assertEquals(name, 0x03, it.expect[ReusableTms.BRANCH.toInt()].toInt()) }
        }
    }

    @Test
    fun sitesCallTheBuildsRemoveBagItem() {
        val rom = File(RETAIL_ROM_DIR + "Pokemon Emerald Legacy (v1.1.4).gba")
        if (rom.isFile) assertEquals("%08X".format(FIELD_ITEMS_EMERALD_LEGACY.removeBagItem), "%08X".format(ReusableTms.find(rom).single().calls))
    }

    /** Every retail cart in the user's "Pokemon Multi Language" folder (-DmultiRomDir=...): as many sites as its engine
     * has, each calling the RemoveBagItem of that build's field-item table. */
    @Test
    fun everyRetailBuild() {
        val dir = File(System.getProperty("multiRomDir") ?: (System.getProperty("user.home") + "/Downloads/Game ROMs & Emulation/Pokemon Multi Language"))
        assumeTrue(dir.isDirectory)
        var checked = 0
        for (f in dir.walk().filter { it.isFile && it.extension == "gba" && listOf("Pirate", "Debug").none { x -> x in it.name } }) {
            val h = f.inputStream().use { ByteArray(0xC0).also { b -> it.read(b) } }
            val code = String(h, 0xAC, 4)
            val sites = ReusableTms.find(f)
            assertEquals(f.name, ReusableTms.expectedSites(code), sites.size)
            val remove = fieldItemsFor(code, h[0xBC].toInt())?.removeBagItem ?: continue
            sites.forEach { assertEquals(f.name, "%08X".format(remove), "%08X".format(it.calls)) }
            checked++
        }
        assertTrue("checked $checked", checked >= 30)
    }

    @Test
    fun alwaysSkipLandsWhereTheBhiDid() {
        // bhi +3 (D803) and b +3 (E003): both to the instruction after the call.
        assertTrue(ReusableTms.ALWAYS_SKIP.contentEquals(byteArrayOf(0x03, 0xE0.toByte())))
        assertEquals(3, ReusableTms.expectedSites("BPRF"))
        assertEquals(1, ReusableTms.expectedSites("AXPJ"))
        assertEquals(0, ReusableTms.expectedSites("CGB0"))
    }
}
