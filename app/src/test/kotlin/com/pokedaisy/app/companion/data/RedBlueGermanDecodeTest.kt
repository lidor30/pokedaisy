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
 * German Red and Blue (Rote / Blaue Edition) on a save written from the `red` fixture's game (it loads in both: the
 * red_de* / blue_de* fixtures' READMEs): English Red's structs 5 bytes further on, German Yellow's names.
 */
class RedBlueGermanDecodeTest {
    private fun use(game: GameKind, cfg: Gen1Config) {
        activeGame = game
        romLanguage = cfg.language
        romGameCode = cfg.gameCode
    }

    @After fun tearDown() {
        romLanguage = 'E'
        romGameCode = ""
    }

    private fun load(fixture: String) = FixtureMemoryReader.load(fixture)

    @Test
    fun `party, money, bag and place - in German, in Red and in Blue`() {
        for ((game, cfg, fixture) in listOf(Triple(GameKind.RED, GEN1_RED_DE, "red_de"), Triple(GameKind.BLUE, GEN1_BLUE_DE, "blue_de"))) {
            use(game, cfg)
            val t = readGen1Telemetry(load(fixture), cfg)
            assertFalse(fixture, t.inBattle)
            assertEquals(fixture, listOf("PIKACHU", "SANDAMER", "RELAXO", "LAPRAS", "TAUBOSS", "RATTIKARL"), t.party.map { speciesName(it.species) })
            assertEquals(fixture, listOf("Joseph", "Liam", "Fred", "Henry", "Seth", "Richard"), t.party.map { it.nickname })
            assertEquals(fixture, listOf(182, 274, 450, 382, 300, 236), t.party.map { it.hp })
            assertEquals(fixture, listOf("DONNERBLITZ", "DONNER", "SLAM", "LICHTSCHILD"), t.party[0].moves.map { lookupMove(it).name })
            assertEquals(fixture, 999999L, t.money)
            assertEquals(fixture, 17, t.items.size)
            assertEquals(fixture, "BOOTSTICKET", itemName(t.items[1].itemId))
            assertEquals(fixture, 0x59, t.regionMapSectionId)
            assertEquals(fixture, "ORANIA CITY", lookupLocation(t.regionMapSectionId).mapSecName)
            assertEquals(fixture, 10 to 106, t.pokedex!!.caught.size to t.pokedex!!.seen.size)
            assertEquals(fixture, "", itemDescription(20))
        }
    }

    @Test
    fun `the hideout keeps Red's name`() {
        use(GameKind.RED, GEN1_RED_DE)
        assertEquals("ROCKET HQ", lookupLocation(199).mapSecName)
        assertEquals("ALABASTIA", lookupLocation(0).mapSecName)
    }

    @Test
    fun `battle - both battlers and the menus`() {
        for ((game, cfg, prefix, foeHp) in listOf(
            Quad(GameKind.RED, GEN1_RED_DE, "red_de", 31), Quad(GameKind.BLUE, GEN1_BLUE_DE, "blue_de", 33),
        )) {
            use(game, cfg)
            val battle = load("${prefix}_battle")
            val t = readGen1Telemetry(battle, cfg)
            assertTrue(prefix, t.inBattle)
            assertEquals(prefix, 25 to 100, t.battleMons[BATTLE_POS_PLAYER_LEFT].let { it.species to it.level })
            val foe = t.battleMons[BATTLE_POS_OPPONENT_LEFT]
            assertEquals(prefix, "GLUMANDA", speciesName(foe.species))
            assertEquals(prefix, 12 to foeHp, foe.level to foe.hp)
            assertTrue(prefix, t.enemyParty.isEmpty())
            // The move list, its cursor on the first move (DONNERBLITZ).
            assertEquals(prefix, BATTLE_INPUT_MOVE_SELECT to 0, readGen1BattleInput(battle, cfg))
            assertEquals(prefix, BATTLE_INPUT_NONE to -1, readGen1BattleInput(load(prefix), cfg))
        }
        // The battle menu, cursor on FLUCHT: column 1, row 1, as in English.
        assertEquals(BATTLE_INPUT_ACTION_SELECT to 3, readGen1BattleInput(load("red_de_battle_menu"), GEN1_RED_DE))
    }

    @Test
    fun `the POKéMON switch - its menus, the party by key and the one out`() {
        use(GameKind.RED, GEN1_RED_DE)
        assertEquals(GbPartyMenu(submenu = false, cursor = 2), readGen1PartyMenu(load("red_de_battle_party"), GEN1_RED_DE))
        assertEquals(GbPartyMenu(submenu = true, cursor = 1), readGen1PartyMenu(load("red_de_battle_switch_menu"), GEN1_RED_DE))
        assertEquals(GbPartyMenu(submenu = false, cursor = 1, forced = true), readGen1PartyMenu(load("red_de_battle_faint"), GEN1_RED_DE))
        for (f in listOf("red_de_battle_menu", "red_de_battle", "red_de")) assertNull(f, readGen1PartyMenu(load(f), GEN1_RED_DE))
        val s = Gen1PartySwitch(GEN1_RED_DE)
        val r = load("red_de_battle_party")
        assertEquals(readGen1Telemetry(r, GEN1_RED_DE).party.map { it.personality }, s.party(r).map { it.key })
        assertEquals(6, s.party(r).map { it.key }.toSet().size)
        assertEquals(0, s.outSlot(r))
        assertEquals(0, s.party(load("red_de_battle_faint"))[0].hp)
    }

    private data class Quad<A, B, C, D>(val a: A, val b: B, val c: C, val d: D)

    private fun romOf(name: String): ByteArray? = File(GB_DIR + name).takeIf { it.isFile }?.readBytes()
    /** English Red: the cart, or pret/pokered's build of it (byte for byte) under $DECOMPS. */
    private val english: ByteArray? get() = listOfNotNull(
        File(GB_DIR + "Pokemon - Red Version (USA, Europe) (SGB Enhanced).gb"),
        System.getenv("DECOMPS")?.let { File(it, "pokered/pokered.gbc") },
    ).firstOrNull { it.isFile }?.readBytes()

    @Test
    fun `dex pages - metric, their own text, Red's pictures`() {
        val en = english
        assumeTrue("Red ROMs not on this machine", en != null)
        // Else the loop below skips both carts and the test passes having checked nothing.
        assumeTrue("German Red / Blue not on this machine", listOf(RED_DE_ROM, BLUE_DE_ROM).any { romOf(it) != null })
        activeGame = GameKind.RED
        for ((name, cfg) in listOf(RED_DE_ROM to GEN1_RED_DE, BLUE_DE_ROM to GEN1_BLUE_DE)) {
            val rom = romOf(name) ?: continue
            Gen1Dex.rom = rom
            val t = cfg.pokedex!!.gen1!!
            val bulbasaur = Gen1Dex.entry(t, 1)!!
            assertEquals(name, 7 to 69, bulbasaur.heightDm to bulbasaur.weightHg)
            assertNull(name, bulbasaur.heightIn)
            assertTrue(name, bulbasaur.description.length > 20)
            assertTrue(name, Gen1Dex.entry(t, 151)!!.description.length > 20)
            val pictures = (1..151).map { Gen1Dex.frontPixels(t, it)!! }
            Gen1Dex.rom = en
            for (dex in 1..151) assertArrayEquals("$name $dex", Gen1Dex.frontPixels(POKEDEX_RED.gen1!!, dex), pictures[dex - 1])
        }
        Gen1Dex.rom = null
    }

    @Test
    fun `art - Red's icons, town map and player, a font with German letters`() {
        val en = english
        assumeTrue("Red ROMs not on this machine", en != null)
        // Else the loop below skips both carts and the test passes having checked nothing.
        assumeTrue("German Red / Blue not on this machine", listOf(RED_DE_ROM, BLUE_DE_ROM).any { romOf(it) != null })
        val enIcons = Gen1Art.icons(en!!, Gen1Art.RED)
        val enMap = Gen1Art.townMap(en, Gen1Art.RED)
        val enPlayer = Gen1Art.player(en, Gen1Art.RED)
        for ((name, art) in listOf(RED_DE_ROM to Gen1Art.RED_DE, BLUE_DE_ROM to Gen1Art.BLUE_DE)) {
            val rom = romOf(name) ?: continue
            val icons = Gen1Art.icons(rom, art)
            for (dex in 1..151) assertArrayEquals("$name icon $dex", enIcons[dex]!!.argb, icons[dex]!!.argb)
            assertArrayEquals(name, enMap.argb, Gen1Art.townMap(rom, art).argb)
            assertArrayEquals(name, enPlayer.argb, Gen1Art.player(rom, art).argb)
            val fontClass = Class.forName("java.awt.Font")
            val font = fontClass.getMethod("createFont", Int::class.java, java.io.InputStream::class.java)
                .invoke(null, 0, Gen1Art.font(rom, art).inputStream())
            val canDisplay = fontClass.getMethod("canDisplay", Char::class.java)
            for (c in "PIKACHU 182/182 ÄÖÜäöüß") assertTrue("$name glyph $c", canDisplay.invoke(font, c) as Boolean)
        }
    }

    private companion object {
        val GB_DIR = File(RETAIL_ROM_DIR).parentFile!!.path + "/gbc/"
        const val RED_DE_ROM = "Pokemon - Rote Edition (Germany) (SGB Enhanced).gb"
        const val BLUE_DE_ROM = "Pokemon - Blaue Edition (Germany) (SGB Enhanced).gb"
    }
}
