package com.pokedaisy.app

import com.pokedaisy.app.companion.data.RETAIL_PORTS
import com.pokedaisy.app.companion.data.RETAIL_PORT_TITLES
import com.pokedaisy.app.companion.data.RomCategory
import com.pokedaisy.app.companion.data.TelemetrySampler
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

class GameTitlesTest {
    @Test
    fun `every hack the companion supports has a name`() {
        val missing = TelemetrySampler.SUPPORTED_HACK_SHA1S - GameTitles.BY_SHA1.keys
        assertTrue("no title for $missing", missing.isEmpty())
    }

    /** scripts/port_retail.py's ROMs are named by their hashes too (RETAIL_PORT_TITLES, generated with them). */
    @Test
    fun `every ported ROM has a name`() {
        assertEquals(RETAIL_PORTS.size, RETAIL_PORT_TITLES.size)
        assertTrue(GameTitles.BY_SHA1.keys.containsAll(RETAIL_PORT_TITLES.keys))
        assertEquals("Pokémon Feuerrote Edition", GameTitles.BY_SHA1["18a3758ceeef2c77b315144be2c3910d6f1f69fe"])
    }

    @Test
    fun `tidy drops dump tags and numbering`() {
        assertEquals("Pokemon - FireRed Version", GameTitles.tidy("Pokemon - FireRed Version (USA, Europe) (Rev 1)"))
        assertEquals("Pokemon Emerald Imperium", GameTitles.tidy("Pokemon Emerald Imperium (World) (v1.3.1)"))
        assertEquals("Pokemon Radical Red", GameTitles.tidy("1636 - Pokemon Radical Red"))
        assertEquals("Pokemon Fire Red", GameTitles.tidy("Pokemon_Fire_Red [!]"))
        assertEquals("FireRed Hack", GameTitles.tidy("FireRed Hack"))
        assertEquals("firered-hack", GameTitles.tidy("firered-hack"))
        // Nothing left: the name as it was.
        assertEquals("(Rev 1)", GameTitles.tidy("(Rev 1)"))
    }

    /** The library's GB / GBC views (GitHub #237, #271): Polished Crystal's, Gold's and Silver's headers say dual-mode
     * (0x80), but they're Color games; Yellow says the same and stays GB, Crystal says Color-only (0xC0), Red none.
     * Skips without the ROMs. */
    @Test
    fun `Polished Crystal and Gold are GBC games, Yellow a GB one`() {
        val dir = File(File(com.pokedaisy.app.companion.data.RETAIL_ROM_DIR).parentFile, "gbc")
        fun platform(name: String): RomCategory {
            val rom = File(dir, name)
            assumeTrue("no $rom", rom.isFile)
            return GameTitles.platformOf(rom) { RomIdentity.sha1(rom) }
        }
        assertEquals(RomCategory.GBC, platform("Pokemon Polished Crystal (v3.2.3).gbc"))
        assertEquals(RomCategory.GBC, platform("Pokemon Polished Crystal (v3.2.3) (Faithful).gbc"))
        assertEquals(RomCategory.GBC, platform("Pokemon - Crystal Version (USA, Europe) (Rev 1).gbc"))
        assertEquals(RomCategory.GB, platform("Pokemon - Yellow Version - Special Pikachu Edition (USA, Europe) (CGB+SGB Enhanced).gb"))
        assertEquals(RomCategory.GBC, platform("Pokemon - Gold Version (USA, Europe) (SGB Enhanced) (GB Compatible).gbc"))
        assertEquals(RomCategory.GBC, platform("Pokemon - Silver Version (USA, Europe) (SGB Enhanced) (GB Compatible).gbc"))
        assertEquals(RomCategory.GB, platform("Pokemon - Gelbe Edition - Special Pikachu Edition (Germany) (GBC,SGB Enhanced).gb"))
        assertEquals(RomCategory.GB, platform("Pokemon - Red Version (USA, Europe) (SGB Enhanced).gb"))
    }
}
