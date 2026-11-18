package com.pokedaisy.app.companion.data

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import okio.Path.Companion.toOkioPath

/**
 * Heartfelt Gold (HeartfeltGold): HeartGold's reader over its grown save, on the player's own save (the heartfelt_gold
 * fixtures' READMEs), and its tables read from the cart (skips without the ROM: `-DromDir=`'s ../nds/).
 */
class HeartfeltGoldTest {
    private val rom = File(File(RETAIL_ROM_DIR).parentFile, "nds/Pokemon Heartfelt Gold (v1.0.15).nds")
    private val zoomed = File(File(RETAIL_ROM_DIR).parentFile, "nds/Pokemon Heartfelt Gold (v1.0.15) (Zoomed In).nds")

    private fun read(fixture: String): Telemetry {
        activeGame = GameKind.HEARTGOLD
        heartfeltGold = true
        romLanguage = 'E'
        romGameCode = HeartfeltGold.CODE
        return readGen4Telemetry(FixtureMemoryReader.load(fixture), GEN4_HEARTFELT_GOLD)
    }

    private fun sha1(f: File): String = java.security.MessageDigest.getInstance("SHA-1").let { md ->
        f.inputStream().use { i -> val b = ByteArray(1 shl 20); while (true) { val n = i.read(b); if (n < 0) break; md.update(b, 0, n) } }
        md.digest().joinToString("") { "%02x".format(it) }
    }

    @After
    fun reset() {
        heartfeltGold = false
        HeartfeltGold.data = null
        NdsArt.names = null
        romGameCode = ""
    }

    @Test
    fun `save - the party, its grown bag, money and the place`() {
        val t = read("heartfelt_gold")
        assertFalse(t.inBattle)
        assertEquals(listOf(255 to 7), t.party.map { it.species to it.level })
        assertEquals(23 to 23, t.party[0].hp to t.party[0].maxHp)
        assertEquals(listOf(10, 45, 52, 98), t.party[0].moves.toList())
        assertEquals(3000L, t.money)
        assertEquals(68, t.mapNum)
        assertEquals(4 to 7, t.x to t.y)
        fun pocket(p: Int) = t.items.filter { it.pocket == p }.map { it.itemId to it.quantity }
        // Items 245 slots, Key Items 92, Balls 26: its items past HeartGold's ids sit where its bag screen shows them.
        assertEquals(listOf(216 to 1, 231 to 1, 664 to 1), pocket(POCKET_ITEMS))
        assertEquals(listOf(1587 to 1, 468 to 1, 696 to 1), pocket(POCKET_KEY_ITEMS))
        assertEquals(listOf(17 to 5), pocket(POCKET_MEDICINE))
        assertEquals(listOf(4 to 5), pocket(POCKET_POKE_BALLS))
    }

    @Test
    fun `save - its Pokedex, seen bits at 0x400 and species ids past ARCEUS`() {
        val dex = read("heartfelt_gold").pokedex!!
        // The game's own Pokédex screen: NATIONAL POKéDEX, SEEN 012, OBTAINED 001 (the fixture's README).
        assertEquals(setOf(10, 13, 161, 172, 255, 258, 278, 287, 406, 519, 731, 829), dex.seen)
        assertEquals(setOf(255), dex.caught)
        assertTrue(dex.national)
        assertEquals(POKEDEX_HEARTFELT, dex.tables)
        assertEquals(569, PokedexSource.speciesFor(dex.tables, 519))
        assertEquals(519, PokedexSource.nationalOf(dex.tables, 569))
        assertEquals(0, PokedexSource.nationalOf(dex.tables, 505))          // a form's id: no Dex number
        assertEquals(1075, HeartfeltGold.speciesOf(1025))
    }

    @Test
    fun `the cart - its DEX pages, pictures and party icons`() {
        if (!rom.isFile) return
        val dir = kotlin.io.path.createTempDirectory("heartfelt-art").toFile()
        val before = NdsArt.dir
        try {
            NdsArt.names = NdsArt.readNames(rom.toOkioPath())
            assertTrue(NdsArt.extractTo(rom.toOkioPath(), dir.toOkioPath()))
            NdsArt.dir = dir.toOkioPath()
            // Party icons for every species, past the old table's 543 too, in the palettes hg-engine reads (/a/0/2/8).
            for (sp in listOf(13, 494, 700, 1075, 1534)) assertTrue("icon $sp", File(dir, "${NdsArt.HEARTFELT_ICONS}/$sp.png").isFile)
            val t = POKEDEX_HEARTFELT
            val chespin = PokedexSource.entry(t, 650)!!
            assertEquals(700, chespin.species)
            assertEquals("Spiny Nut", chespin.category)
            assertEquals(16 to 198, chespin.heightIn to chespin.weightLbs10)      // 1'04", 19.8 lbs.
            assertEquals(4 to 90, chespin.heightDm to chespin.weightHg)
            assertEquals(listOf("Overgrow"), chespin.abilities)
            val pecharunt = PokedexSource.entry(t, 1025)!!
            assertEquals(1075, pecharunt.species)
            assertEquals(listOf("Poison Puppeteer"), pecharunt.abilities)       // ability 310: a u16 in its records
            val geodude = PokedexSource.entry(t, 74)!!
            assertEquals(listOf("Rock Head", "Sturdy"), geodude.abilities)
            assertEquals(152, PokedexSource.regionalOrder(t)!!.first())          // Johto's order: CHIKORITA first
            assertTrue(File(dir, "${NdsArt.HEARTFELT_FRONT}/1075.png").isFile)
            // Evolutions and moves by species id: TORCHIC at 16, EEVEE's stones to SYLVEON (No.700, species 750).
            val evo = Gen4Dex.evolutions(GEN4_DEX_HEARTFELT)!!
            assertEquals(listOf(256), evo.getValue(255).map { it.to })
            assertTrue(evo.getValue(133).any { it.to == 750 })
            assertEquals(null, evo[509])                                          // a form's id: no row
            val dewott = evo.getValue(HeartfeltGold.speciesOf(502))              // SAMUROTT by day (its Hisui form left out)
            assertEquals(listOf(EvoReq.Level(36), EvoReq.Time(EvoTime.DAY)), dewott.single().reqs)
            assertEquals(LevelMove(1, 33), Gen4Dex.levelUp(GEN4_DEX_HEARTFELT, 1)!!.first())   // BULBASAUR: TACKLE
        } finally {
            NdsArt.dir = before
            PokedexSource.romChanged()
            dir.deleteRecursively()
        }
    }

    @Test
    fun `battle - HeartGold's battle system, a wild one`() {
        val t = read("heartfelt_gold_battle")
        assertTrue(t.inBattle)
        assertEquals(false, t.isTrainerBattle)
        assertFalse(t.isDoubleBattle)
        val me = t.battleMons[BATTLE_POS_PLAYER_LEFT]
        val foe = t.battleMons[BATTLE_POS_OPPONENT_LEFT]
        assertEquals(255 to 7, me.species to me.level)
        assertEquals(23 to 23, me.hp to me.maxHp)
        assertEquals(16 to 5, foe.species to foe.level)
        assertEquals(0 to 2, foe.type1 to foe.type2)   // NORMAL / FLYING
        assertEquals(listOf(16 to 5), t.battleFoes.map { it.species to it.level })
        // "What will Torchic do?", HeartGold's command panel drawn: the battle buttons' taps go in (FIGHT, then a move's
        // card at HeartGold's places used SCRATCH headless: NdsBattleTaps.GEN4).
        assertEquals(BATTLE_INPUT_ACTION_SELECT, t.battleInputState)
    }

    @Test
    fun `the cart - detection, names and its tables`() {
        if (!rom.isFile) return
        assertTrue(HeartfeltGold.matches(rom.toOkioPath()))
        assertEquals(HeartfeltGold.V1_0_15_SHA1, sha1(rom))
        if (zoomed.isFile) {
            assertTrue(HeartfeltGold.matches(zoomed.toOkioPath()))
            assertEquals(HeartfeltGold.V1_0_15_ZOOMED_IN_SHA1, sha1(zoomed))
        }
        // Retail HeartGold's cart isn't it.
        val retail = File(rom.parentFile, "Pokemon - HeartGold Version (USA).nds")
        if (retail.isFile) assertFalse(HeartfeltGold.matches(retail.toOkioPath()))
        val names = NdsArt.readNames(rom.toOkioPath())!!
        assertEquals("Torchic", names.species[255])
        assertEquals("Chespin", names.species[700])
        assertEquals("Supercell Slam", names.moves[919])
        assertEquals("Blazikenite", names.items[664])
        val data = NdsArt.readHeartfelt(rom.toOkioPath())!!
        assertEquals(SpeciesTypes(9, 9), data.speciesTypes[35])        // CLEFAIRY: Fairy
        assertEquals(SpeciesTypes(10, 10), data.speciesTypes[255])      // TORCHIC: Fire
        assertEquals("Pound" to 40, data.moveData.getValue(1).let { it.name to it.power })
        assertEquals(200, data.typeChart[9 * 100 + 16])                 // Fairy beats Dragon
        assertEquals(0, data.typeChart[16 * 100 + 9])                   // Dragon can't touch it
        assertEquals(2, data.moveCategories[45])                        // GROWL: status
    }
}
