package com.pokedaisy.app.companion.data

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * GEN1_BROWN (Pokémon Brown 6.1.2 / 6.0.1): Red's RAM and reader over its own save (the `brown*` fixtures' READMEs), its
 * own tables to Dex No. 252 (gen_gen1_tables.py brown / brown_601). What each check expects is what the game's own party,
 * bag and battle screens showed.
 */
class BrownDecodeTest {
    private fun read(fixture: String, v601: Boolean = false): Telemetry {
        activeGame = GameKind.BROWN
        brownV601 = v601
        return readGen1Telemetry(FixtureMemoryReader.load(fixture), GEN1_BROWN)
    }

    @After fun reset() {
        brownV601 = false
    }

    @Test
    fun `its save - the party, the bag and money in both builds`() {
        assertEquals(6, readGen1Telemetry(FixtureMemoryReader.load("brown"), GEN1_BROWN).party.size)
        for ((fixture, v601) in listOf("brown" to false, "brown_v601" to true)) {
            val t = read(fixture, v601)
            assertFalse(t.inBattle)
            assertEquals(
                listOf("FERALIGATR" to 68, "AERODACTYL" to 33, "OMANYTE" to 35, "KABUTO" to 35, "CHIKORITA" to 12, "CYNDAQUIL" to 11),
                t.party.map { speciesName(it.species) to it.level },
            )
            assertEquals(listOf(160, 142, 138, 140, 152, 155), t.party.map { it.species })
            assertEquals(224 to 224, t.party[0].hp to t.party[0].maxHp)
            assertEquals(listOf("Waterfall", "Crunch", "Mega Kick", "Hydro Pump"), t.party[0].moves.map { lookupMove(it).name })
            assertEquals(77019L, t.money)
            val bag = t.items.associate { itemName(it.itemId) to it.quantity }
            assertEquals(15, bag["Ultra Ball"])
            assertEquals(1, bag["HM01"])
            assertEquals(1, bag["Dubious Disc"])
            assertEquals(15, t.items.size)
            assertEquals(8 to (11 to 7), t.mapNum to (t.x to t.y))
        }
    }

    @Test
    fun `a wild battle - its own species and types, Red's battle menu`() {
        val r = FixtureMemoryReader.load("brown_battle")
        val t = read("brown_battle")
        assertTrue(t.inBattle)
        val me = t.battleMons[BATTLE_POS_PLAYER_LEFT]
        val foe = t.battleMons[BATTLE_POS_OPPONENT_LEFT]
        assertEquals("FERALIGATR" to 68, speciesName(me.species) to me.level)
        assertEquals("CHIKORITA" to 12, speciesName(foe.species) to foe.level)
        assertEquals("Grass" to "Grass", typeName(foe.type1) to typeName(foe.type2))
        assertEquals(BATTLE_INPUT_ACTION_SELECT to 0, readGen1BattleInput(r, GEN1_BROWN))
    }

    @Test
    fun `its tables - Rijon's places, its own types and the 6_0_1 differences`() {
        activeGame = GameKind.BROWN
        assertEquals("PHANCERO", speciesName(252))
        assertEquals("Wood", typeName(28))
        assertEquals("Fairy", typeName(31))
        assertEquals("Gravel Town", activeMapSecData[0]?.name)
        assertEquals("Old Amber", itemName(50))
        assertEquals("Giga Impact", lookupMove(226).name)
        brownV601 = true
        assertEquals("Brick", itemName(50))
        assertEquals("Route 59", activeMapSecData[73]?.name)
    }

    @Test
    fun `its POKéDEX flags - 211 owned, 227 seen, as the game's own POKéDEX says`() {
        val dex = read("brown").pokedex!!
        assertEquals(211 to 227, dex.caught.size to dex.seen.size)
        // Slot 231's bits are PHANCERO's (No.252): seen on this save, not caught.
        assertTrue(252 in dex.seen && 252 !in dex.caught)
        assertTrue(dex.seen.none { it in 231..251 })
        // Caught but for these (the save's own bits, read by hand from D490).
        assertEquals(listOf(122, 139, 141, 145, 146, 165, 169, 172, 195, 197, 204, 209, 215, 217, 220, 221, 226, 227, 228),
            (1..230).filter { it !in dex.caught })
    }

    @Test
    fun `its dex pages and pictures from the cart`() {
        val rom = File(File(RETAIL_ROM_DIR).parentFile, "gbc/Pokemon Brown (v6.1.2).gb")
        assumeTrue("Brown ROM not on this machine", rom.isFile)
        activeGame = GameKind.BROWN
        Gen1Dex.rom = rom.readBytes()
        val t = POKEDEX_BROWN.gen1!!
        try {
            // BULBASAUR's page, as the game draws it: SEED, 2'04", 15.0 lb, Red's text.
            val bulbasaur = Gen1Dex.entry(t, 1)!!
            assertEquals("SEED", bulbasaur.category)
            assertEquals(2 * 12 + 4 to 150, bulbasaur.heightIn to bulbasaur.weightLbs10)
            assertTrue(bulbasaur.description, bulbasaur.description.startsWith("A strange seed was planted on its back at birth."))
            // Its picture in the colours the game shows on a Super Game Boy (palette 0x69: mGBA drew 4AE77B and F74A29 - the
            // same RGB555 colours, scaled by 255 / 31 here), white clear.
            assertEquals(setOf(0, 0xFF4AE67B.toInt(), 0xFFF64A29.toInt(), 0xFF000000.toInt()),
                Gen1Dex.frontPixels(t, 1)!!.toSet())
            // PHANCERO: slot 231's GLITCHBIRD page, its own base data.
            val phancero = Gen1Dex.entry(t, 252)!!
            assertEquals("GLITCHBIRD", phancero.category)
            assertNull(Gen1Dex.entry(t, 240))
            val out = File("build/gen1-art-brown-dex").apply { mkdirs() }
            for (s in (1..230) + 252) {
                assertNotNull("entry $s", Gen1Dex.entry(t, s))
                val pic = Gen1Dex.frontPixels(t, s)
                assertNotNull("pic $s", pic)
                assertTrue("pic $s", pic!!.count { it != 0 } > 100)
                if (s in listOf(1, 25, 152, 200, 230, 252)) File(out, "$s.png").writeBytes(RomArt.Image(56, 56, pic).png())
            }
        } finally {
            Gen1Dex.rom = null
        }
    }

    @Test
    fun `its art in its own Super Game Boy colours`() {
        val rom = File(File(RETAIL_ROM_DIR).parentFile, "gbc/Pokemon Brown (v6.1.2).gb")
        assumeTrue("Brown ROM not on this machine", rom.isFile)
        val bytes = rom.readBytes()
        val out = File("build/gen1-art-brown").apply { mkdirs() }
        // The town map as its Fly menu shows it: cream, sky blue, green, black (sampled headless).
        val map = Gen1Art.townMap(bytes, Gen1Art.BROWN)
        File(out, "townmap.png").writeBytes(map.png())
        assertEquals(setOf(0xFFFFF7DE.toInt(), 0xFF84DEF7.toInt(), 0xFF8CBD52.toInt(), 0xFF000000.toInt()), map.argb.toSet())
        // The party menu's icons: two browns and black (FERALIGATR, the save's lead, on the game's screen).
        val icons = Gen1Art.icons(bytes, Gen1Art.BROWN)
        assertEquals(252, icons.size)
        File(out, "icon160.png").writeBytes(icons.getValue(160).png())
        assertEquals(setOf(0, 0xFFD68463.toInt(), 0xFF7B3939.toInt(), 0xFF000000.toInt()), icons.getValue(160).argb.toSet())
        File(out, "player.png").writeBytes(Gen1Art.player(bytes, Gen1Art.BROWN).png())
    }
}
