package com.pokedaisy.app.companion.data

import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Origin HeartGold (OriginHeartGold): HeartGold's reader over its grown save, on a new game played headless (the
 * origin_heartgold fixture's README), and its tables read from the cart (skips without the ROM: `-DromDir=`'s ../nds/).
 */
class OriginHeartGoldTest {
    private val rom = File(File(RETAIL_ROM_DIR).parentFile, "nds/Pokemon Origin HeartGold (v4.0.3) (EN 1.0.0-rc8).nds")

    private fun read(): Telemetry {
        activeGame = GameKind.HEARTGOLD
        originHeartGold = true
        romLanguage = 'E'
        romGameCode = OriginHeartGold.CODE
        return readGen4Telemetry(FixtureMemoryReader.load("origin_heartgold"), GEN4_ORIGIN_HEARTGOLD)
    }

    @After
    fun reset() {
        originHeartGold = false
        OriginHeartGold.data = null
        NdsArt.names = null
        NdsArt.dir = null
        romGameCode = ""
    }

    @Test
    fun `party - species past 493 and a form's own id`() {
        val t = read()
        assertFalse(t.inBattle)
        assertEquals(listOf(155, 700, 25, 1028, 445, 887), t.party.map { it.species })
        assertEquals(listOf(12, 20, 9, 30, 40, 35), t.party.map { it.level })
        assertEquals(listOf(32 to 32, 73 to 73, 28 to 28, 92 to 92, 139 to 139, 117 to 117), t.party.map { it.hp to it.maxHp })
        assertEquals(234, t.party[1].heldItem)
        assertEquals(listOf(33, 43, 108, 52), t.party[0].moves.toList())
    }

    @Test
    fun `save - money, the place and the bag's pockets (151 TM slots)`() {
        val t = read()
        assertEquals(3000L, t.money)
        assertEquals(49, t.mapNum)
        assertEquals("Pallet Town", t.mapSecName)
        assertEquals(1033 to 364, t.x to t.y)
        assertEquals(0, t.repelSteps)
        fun pocket(p: Int) = t.items.filter { it.pocket == p }.map { it.itemId to it.quantity }
        assertEquals(listOf(744 to 1, 450 to 1), pocket(POCKET_KEY_ITEMS))
        assertEquals(listOf(328 to 1), pocket(POCKET_TM_HM))
        assertEquals(listOf(137 to 1), pocket(POCKET_MAIL))
        assertEquals(listOf(17 to 5, 780 to 1, 26 to 4), pocket(POCKET_MEDICINE))
        assertEquals(listOf(155 to 3), pocket(POCKET_BERRIES))
        assertEquals(listOf(4 to 10, 1 to 1), pocket(POCKET_POKE_BALLS))
        assertEquals(listOf(55 to 2), pocket(POCKET_BATTLE_ITEMS))
        // The Pokédex's wider flag arrays: the five given by GiveMon (a form's own id, 1028, sets no bit), past 493 too.
        val dex = t.pokedex!!
        assertEquals(setOf(25, 155, 445, 700, 887), dex.caught)
        assertEquals(setOf(25, 155, 445, 700, 887), dex.seen)
        assertEquals(1025, dex.tables.nationalCount)
    }

    private fun battle(fixture: String): Telemetry {
        activeGame = GameKind.HEARTGOLD
        originHeartGold = true
        return readGen4Telemetry(FixtureMemoryReader.load(fixture), GEN4_ORIGIN_HEARTGOLD)
    }

    @Test
    fun `battle - its own engine's objects, after a switch and Agility`() {
        val t = battle("origin_heartgold_battle")
        assertTrue(t.inBattle)
        assertFalse(t.isDoubleBattle)
        val me = t.battleMons[BATTLE_POS_PLAYER_LEFT]
        val foe = t.battleMons[BATTLE_POS_OPPONENT_LEFT]
        // DRAGAPULT (switched in: its object first now) with AGILITY's Speed +2; PIDGEY Lv50.
        assertEquals(887 to 35, me.species to me.level)
        assertEquals(80 to 117, me.hp to me.maxHp)                                    // after PIDGEY's hits
        assertEquals(16 to 7, me.type1 to me.type2)
        assertEquals(listOf(83, 68, 112, 79, 74), me.stats!!.toList())            // Atk, Def, Speed, Sp. Atk, Sp. Def
        assertEquals(listOf(6, 6, 8, 6, 6, 6, 6), me.statStages!!.toList())
        assertEquals(listOf(372, 506, 97, 458), me.moves.toList())
        assertEquals(16 to 50, foe.species to foe.level)
        assertEquals(0 to 2, foe.type1 to foe.type2)                                 // Normal / Flying
        assertEquals(77, foe.ability)                                                // Tangled Feet
        assertEquals(listOf(16), t.battleFoes.map { it.species })
        assertEquals(emptyList<Mon>(), t.enemyParty)                                 // a wild one: no FOE TEAM
        assertEquals(BATTLE_INPUT_NONE, t.battleInputState)                          // no battle buttons: its panels are its own
    }

    @Test
    fun `battle - a trainer's, its team`() {
        val t = battle("origin_heartgold_trainer")
        assertTrue(t.inBattle)
        assertEquals(155, t.battleMons[BATTLE_POS_PLAYER_LEFT].species)
        assertEquals(399, t.battleMons[BATTLE_POS_OPPONENT_LEFT].species)            // BIDOOF
        assertEquals(listOf(399), t.enemyParty.map { it.species })
        assertEquals(0, t.enemyActive)
    }

    @Test
    fun `the cart - detection, its tables and art`() {
        assumeTrue("no Origin HeartGold ROM at $rom", rom.isFile)
        assertTrue(OriginHeartGold.matches(rom))
        val us = File(File(RETAIL_ROM_DIR).parentFile, "nds/Pokemon - HeartGold Version (USA).nds")
        if (us.isFile) assertFalse(OriginHeartGold.matches(us))
        val names = NdsArt.readNames(rom)!!
        assertEquals('E', names.language)
        assertEquals(listOf("Bulbasaur", "Sylveon", "Dragapult", "Pecharunt"), listOf(1, 700, 887, 1025).map { names.species[it] })
        assertEquals("Rash Mint", names.items[780])
        assertEquals("Pallet Town", names.places[GEN4_ORIGIN_HEARTGOLD.mapPlaceIds.getValue(49)])
        val data = NdsArt.readOrigin(rom)!!
        assertEquals(SpeciesTypes(9, 9), data.speciesTypes[700])           // Sylveon: Fairy
        assertEquals(SpeciesTypes(16, 7), data.speciesTypes[887])          // Dragapult: Dragon / Ghost
        assertEquals(SpeciesTypes(9, 9), data.speciesTypes[35])            // Clefairy, Fairy here
        assertEquals(MoveInfo("Tackle", 0, 40), data.moveData[33])
        assertEquals(MoveInfo("Ember", 10, 40), data.moveData[52])
        assertEquals(920, data.moveData.keys.max())
        // Gen 6's chart: Fairy beats Dragon, Dragon can't touch it, Steel no longer resists Ghost.
        assertEquals(200, data.typeChart[9 * 100 + 16])
        assertEquals(0, data.typeChart[16 * 100 + 9])
        assertEquals(200, data.typeChart[8 * 100 + 9])
        assertNull(data.typeChart[7 * 100 + 8])
        assertEquals(1027, data.formSpecies(6, 1))
        assertEquals(1028, data.formSpecies(6, 2))
        assertEquals(6, data.formSpecies(6, 0))
        assertArrayEquals(intArrayOf(264, 337, 352), data.machines.copyOfRange(0, 3))   // TM01 Focus Punch ...
        assertEquals(19, data.machines[93])                                          // HM02 Fly
        assertEquals(473, data.machines[100])                                        // TM93 Psyshock
        assertEquals(815, data.machines[137])                                        // TM130
        assertTrue(data.wildRates.size > 100 * 3)
        // The companion's tables over its own, once read.
        OriginHeartGold.data = data
        NdsArt.names = names
        val t = read()
        assertEquals(listOf("Cyndaquil", "Sylveon", "Pikachu", "Charizard", "Garchomp", "Dragapult"), t.party.map { speciesName(it.species) })
        assertEquals("Fairy", typeName(activeSpeciesTypeData.getValue(700).type1))
        assertEquals(listOf("Tackle", "Leer", "Smokescreen", "Ember"), t.party[0].moves.map { lookupMove(it).name })
        assertEquals(Triple(264, false, 1), OriginHeartGold.machine(328))
        assertEquals(Triple(19, true, 2), OriginHeartGold.machine(421))
        assertEquals(Triple(815, false, 130), OriginHeartGold.machine(574))
        val dir = kotlin.io.path.createTempDirectory("nds-art").toFile()
        try {
            assertTrue(NdsArt.extractTo(rom, dir))
            for (id in listOf(1, 700, 887, 1025, 1028, 1440, NdsArt.ORIGIN_EGG)) assertTrue("$id", File(dir, "${NdsArt.ORIGIN_ICONS}/$id.png").isFile)
            for (id in listOf(1, 17, 780, 790)) assertTrue("$id", File(dir, "${NdsArt.ORIGIN_ITEMS}/$id.png").isFile)
            // The DEX: its pages and fronts for the National Dex's 1025.
            for (id in listOf(1, 700, 1025)) assertTrue("$id", File(dir, "${NdsArt.ORIGIN_FRONT}/$id.png").isFile)
            NdsArt.dir = dir
            val sylveon = Gen4Dex.entry(GEN4_DEX_ORIGIN, 700)!!
            assertEquals(Triple("Intertwine", 10, 235), Triple(sylveon.category, sylveon.heightDm, sylveon.weightHg))
            assertEquals(9 to 9, sylveon.type1 to sylveon.type2)
            assertEquals(listOf(95, 65, 70, 110, 130, 65), sylveon.baseStats)   // its own (the hack buffed it)
            assertEquals(listOf("Cute Charm"), sylveon.abilities)
            assertEquals("Pixilate", sylveon.hiddenAbility)
            val bulbasaur = Gen4Dex.entry(GEN4_DEX_ORIGIN, 1)!!
            assertEquals(listOf("Overgrow", "Chlorophyll"), bulbasaur.abilities)
            assertEquals("Thick Fat", bulbasaur.hiddenAbility)
            val evo = Gen4Dex.evolutions(GEN4_DEX_ORIGIN)!!
            assertEquals(listOf(EvoReq.LevelUp, EvoReq.KnowsMoveType(9)), evo.getValue(133).first { it.to == 700 }.reqs)
            assertEquals(listOf(EvoReq.Level(16)), evo.getValue(1).single().reqs)
            assertEquals(LevelMove(1, 33), Gen4Dex.levelUp(GEN4_DEX_ORIGIN, 1)!!.first())
            OriginHeartGold.data = data
            val tms = Gen4Dex.machines(GEN4_DEX_ORIGIN, 1)!!.map { it.label to it.move }
            assertTrue(tms.toString(), "TM06" to 92 in tms && "HM01" to 15 in tms && tms.any { it.first == "TM101" || it.first.length == 5 })
            assertEquals(138, Gen4Dex.machines(GEN4_DEX_ORIGIN, 151)!!.size)   // MEW: every machine
        } finally {
            dir.deleteRecursively()
        }
    }
}
