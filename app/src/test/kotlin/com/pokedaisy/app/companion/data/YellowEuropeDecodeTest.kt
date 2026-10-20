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
 * The German, French, Italian and Spanish Yellows on the English save (it loads in each: the yellow_de ..
 * yellow_es fixtures' READMEs): English's structs 5 bytes further on, the carts' own names.
 */
class YellowEuropeDecodeTest {
    private class Lang(
        val key: String, val cfg: Gen1Config, val rom: String,
        val party: List<String>, val vermilion: String, val shipTicket: String, val moves: List<String>, val run: String,
    )

    private val langs = listOf(
        Lang("de", GEN1_YELLOW_DE, "Pokemon - Gelbe Edition - Special Pikachu Edition (Germany) (GBC,SGB Enhanced).gb",
            listOf("PIKACHU", "SANDAMER", "RELAXO", "LAPRAS", "TAUBOSS", "RATTIKARL"), "ORANIA CITY", "BOOTSTICKET",
            listOf("DONNERBLITZ", "DONNER", "SLAM", "LICHTSCHILD"), "FLUCHT"),
        Lang("fr", GEN1_YELLOW_FR, "Pokemon - Version Jaune - Edition Speciale Pikachu (France) (GBC,SGB Enhanced).gb",
            listOf("PIKACHU", "SABLAIREAU", "RONFLEX", "LOKHLASS", "ROUCARNAGE", "RATTATAC"), "CARMIN SUR MER", "PASSE BATEAU",
            listOf("TONNERRE", "FATAL-FOUDRE", "SOUPLESSE", "MUR LUMIERE"), "FUITE"),
        Lang("it", GEN1_YELLOW_IT, "Pokemon - Versione Gialla - Speciale Edizione Pikachu (Italy) (GBC,SGB Enhanced).gb",
            listOf("PIKACHU", "SANDSLASH", "SNORLAX", "LAPRAS", "PIDGEOT", "RATICATE"), "ARANCIOPOLI", "BIGL. NAVE",
            listOf("FULMINE", "TUONO", "SCHIANTO", "SCHERMO-LUCE"), "FUGA"),
        Lang("es", GEN1_YELLOW_ES, "Pokemon - Edicion Amarilla - Edicion Especial Pikachu (Spain) (GBC,SGB Enhanced).gb",
            listOf("PIKACHU", "SANDSLASH", "SNORLAX", "LAPRAS", "PIDGEOT", "RATICATE"), "CIUDAD CARMÍN", "TICKET BARCO",
            listOf("RAYO", "TRUENO", "PORTAZO", "PANTALLA LUZ"), "ESC"),
    )

    private fun use(l: Lang) {
        activeGame = GameKind.YELLOW
        romLanguage = l.cfg.language
        romGameCode = l.cfg.gameCode
    }

    @After fun tearDown() {
        romLanguage = 'E'
        romGameCode = ""
    }

    @Test
    fun `party, money, bag and place - in each cart's own names`() = langs.forEach { l ->
        use(l)
        val t = readGen1Telemetry(FixtureMemoryReader.load("yellow_${l.key}"), l.cfg)
        assertFalse(l.key, t.inBattle)
        assertEquals(l.key, l.party, t.party.map { speciesName(it.species) })
        // The English save's own nicknames, through each cart's character set.
        assertEquals(l.key, listOf("Joseph", "Liam", "Fred", "Henry", "Seth", "Richard"), t.party.map { it.nickname })
        val pikachu = t.party[0]
        assertEquals(l.key, 25 to 100, pikachu.species to pikachu.level)
        assertEquals(l.key, 182 to 182, pikachu.hp to pikachu.maxHp)
        assertEquals(l.key, l.moves, pikachu.moves.map { lookupMove(it).name })
        assertEquals(l.key, 999999L, t.money)
        assertEquals(l.key, 17, t.items.size)
        assertEquals(l.key, l.shipTicket, itemName(t.items[1].itemId))
        assertEquals(l.key, 89, t.regionMapSectionId)
        assertEquals(l.key, l.vermilion, lookupLocation(t.regionMapSectionId).mapSecName)
        // The Pokédex flags moved with the rest: the save's 10 owned.
        assertEquals(l.key, 10, t.pokedex!!.caught.size)
    }

    @Test
    fun `battle - both battlers and the menus`() = langs.forEach { l ->
        use(l)
        val battle = FixtureMemoryReader.load("yellow_${l.key}_battle")
        val t = readGen1Telemetry(battle, l.cfg)
        assertTrue(l.key, t.inBattle)
        assertEquals(l.key, 25 to 100, t.battleMons[BATTLE_POS_PLAYER_LEFT].let { it.species to it.level })
        val foe = t.battleMons[BATTLE_POS_OPPONENT_LEFT]
        assertEquals(l.key, 4 to 12, foe.species to foe.level)
        assertEquals(l.key, BATTLE_INPUT_MOVE_SELECT to 0, readGen1BattleInput(battle, l.cfg))
        // The battle menu, cursor on its run option (l.run): column 1, row 1, as in English.
        assertEquals(l.key, BATTLE_INPUT_ACTION_SELECT to 3, readGen1BattleInput(FixtureMemoryReader.load("yellow_${l.key}_battle_menu"), l.cfg))
        assertEquals(l.key, BATTLE_INPUT_NONE to -1, readGen1BattleInput(FixtureMemoryReader.load("yellow_${l.key}"), l.cfg))
    }

    @Test
    fun `no English item text under their names`() = langs.forEach { l ->
        use(l)
        assertEquals("", itemDescription(20))
    }

    private fun romOf(l: Lang): ByteArray? = File(RETAIL_GB_DIR + l.rom).takeIf { it.isFile }?.readBytes()
    private val english: ByteArray? get() = File(RETAIL_GB_DIR + YELLOW_ROM).takeIf { it.isFile }?.readBytes()

    @Test
    fun `dex pages - metric, their own text, English's pictures`() {
        val en = english
        assumeTrue("Yellow ROMs not on this machine", en != null)
        for (l in langs) {
            val rom = romOf(l) ?: continue
            Gen1Dex.rom = rom
            val t = l.cfg.pokedex!!.gen1!!
            val bulbasaur = Gen1Dex.entry(t, 1)!!
            // 0.7 m, 6.9 kg, as the cart stores them (English rounds 2'04", 15.2 lbs.).
            assertEquals(l.key, 7 to 69, bulbasaur.heightDm to bulbasaur.weightHg)
            assertNull(l.key, bulbasaur.heightIn)
            assertTrue(l.key, bulbasaur.description.length > 20)
            val pictures = (1..151).map { Gen1Dex.frontPixels(t, it)!! }
            Gen1Dex.rom = en
            for (dex in 1..151) assertArrayEquals("${l.key} $dex", Gen1Dex.frontPixels(GEN1_DEX_YELLOW, dex), pictures[dex - 1])
        }
        Gen1Dex.rom = null
    }

    @Test
    fun `art - English icons, town map and player, a font with their letters`() {
        val en = english
        assumeTrue("Yellow ROMs not on this machine", en != null)
        val enIcons = Gen1Art.icons(en!!, Gen1Art.YELLOW)
        val enMap = Gen1Art.townMap(en, Gen1Art.YELLOW)
        val enPlayer = Gen1Art.player(en, Gen1Art.YELLOW)
        for ((l, art, letters) in listOf(
            Triple(langs[0], Gen1Art.YELLOW_DE, "ÄÖÜäöüß"), Triple(langs[1], Gen1Art.YELLOW_FR, "àèéùçâêîôû"),
            Triple(langs[2], Gen1Art.YELLOW_IT, "àèéìòù"), Triple(langs[3], Gen1Art.YELLOW_ES, "ÁÉÍÑÓÚáéíñóú¿¡"),
        )) {
            val rom = romOf(l) ?: continue
            val icons = Gen1Art.icons(rom, art)
            for (dex in 1..151) assertArrayEquals("${l.key} icon $dex", enIcons[dex]!!.argb, icons[dex]!!.argb)
            assertArrayEquals(l.key, enMap.argb, Gen1Art.townMap(rom, art).argb)
            assertArrayEquals(l.key, enPlayer.argb, Gen1Art.player(rom, art).argb)
            val fontClass = Class.forName("java.awt.Font")
            val font = fontClass.getMethod("createFont", Int::class.java, java.io.InputStream::class.java)
                .invoke(null, 0, Gen1Art.font(rom, art).inputStream())
            val canDisplay = fontClass.getMethod("canDisplay", Char::class.java)
            for (c in "PIKACHU 182/182 $letters") assertTrue("${l.key} glyph $c", canDisplay.invoke(font, c) as Boolean)
        }
    }

    private companion object {
        val RETAIL_GB_DIR = File(RETAIL_ROM_DIR).parentFile!!.path + "/gbc/"
        const val YELLOW_ROM = "Pokemon - Yellow Version - Special Pikachu Edition (USA, Europe) (CGB+SGB Enhanced).gb"
    }
}
