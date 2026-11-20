package com.pokedaisy.app.companion.data

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import okio.Path.Companion.toOkioPath

/**
 * Sacred Gold / Storm Silver v1.05 ([SacredGold]): HeartGold's reader on the player's HeartGold save booted in Sacred Gold (the
 * sacred_gold fixture's README), and their tables read from the carts (skips without the ROMs: `-DromDir=`'s ../nds/).
 */
class SacredGoldTest {
    private val nds = File(File(RETAIL_ROM_DIR).parentFile, "nds")
    private val sacredGoldRom = File(nds, "Pokemon Sacred Gold (v1.05) (Complete).nds")
    private val sacredGoldClassic = File(nds, "Pokemon Sacred Gold (v1.05) (Classic).nds")
    private val stormSilver = File(nds, "Pokemon Storm Silver (v1.05) (Complete).nds")
    private val heartGold = File(nds, "Pokemon - HeartGold Version (USA).nds")

    @After
    fun reset() {
        sacredGold = false
        SacredGold.data = null
        NdsArt.names = null
        romGameCode = ""
    }

    @Test
    fun `the saves - HeartGold's and SoulSilver's RAM`() {
        // (the player's HeartGold / SoulSilver saves: their parties, money, map header and place, as in the retail fixtures)
        for ((fixture, kind, cfg, code, want) in listOf(
            listOf("sacred_gold", GameKind.HEARTGOLD, GEN4_HEARTGOLD_US, SacredGold.CODE_SACRED_GOLD,
                listOf(listOf(154, 181, 197, 164, 195, 20), 593506L, 76, 352 to 369)),
            listOf("storm_silver", GameKind.SOULSILVER, GEN4_SOULSILVER_US, SacredGold.CODE_STORM_SILVER,
                listOf(listOf(157, 196, 18, 62, 162, 20), 575648L, 56, 1209 to 440)),
        )) {
            activeGame = kind as GameKind
            sacredGold = true
            romLanguage = 'E'
            romGameCode = code as String
            val t = readGen4Telemetry(FixtureMemoryReader.load(fixture as String), cfg as Gen4Config)
            assertEquals(fixture, want, listOf(t.party.map { it.species }, t.money, t.mapNum, t.x to t.y))
            assertTrue(fixture, t.party.all { it.level == 100 })
            assertFalse(fixture, t.inBattle)
            assertTrue(fixture, t.party.isNotEmpty())
        }
    }

    @Test
    fun `the carts - told apart by their layout, their own tables`() {
        if (!sacredGoldRom.isFile) return
        for (f in listOf(sacredGoldRom, sacredGoldClassic, stormSilver)) assertTrue(f.name, SacredGold.matches(f.toOkioPath()))
        if (heartGold.isFile) assertFalse(SacredGold.matches(heartGold.toOkioPath()))
        val sg = NdsArt.readSacredGold(sacredGoldRom.toOkioPath())!!
        val classic = NdsArt.readSacredGold(sacredGoldClassic.toOkioPath())!!
        assertTrue(sg.moveData.size > 400)
        assertTrue(sg.wildRates.size / 3 > 100)
        // Complete changes species; Classic keeps HeartGold's.
        assertNotEquals(sg.speciesTypes, classic.speciesTypes)
        val ss = NdsArt.readSacredGold(stormSilver.toOkioPath())!!
        assertTrue(ss.wildRates.size / 3 > 100)
        // The reader on the retail cart gives what pokeheartgold's own data says (HEARTGOLD_WORLD): the map headers and sets
        // are where it looks.
        if (heartGold.isFile) {
            val retail = NdsArt.readSacredGold(heartGold.toOkioPath())!!
            fun triples(a: IntArray) = a.toList().chunked(3).toSet()
            assertEquals(triples(HEARTGOLD_WORLD.wildRates()), triples(retail.wildRates))
            assertEquals(speciesTypesPlatinum[49], retail.speciesTypes[49])
        }
    }
}
