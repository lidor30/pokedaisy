package com.pokedaisy.app.companion.data

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * The other Emeralds (EmeraldLanguages.kt): English's save in each one
 * (emerald_es / _de / _fr / _it / _ja, headless) read at each one's RAM addresses
 * (English's but for Japanese),
 * the game's own names, and the ROM tables (skipped without the ROMs).
 */
class EmeraldLanguagesTest {
    private class Lang(
        val letter: Char, val code: String, val fixture: String, val cfg: NativeConfig, val rom: String,
        val torchic: String, val potion: String, val pound: String, val hardy: String, val littleroot: String,
        val torchicCategory: String, val height: String = "0,7 m", val weight: String = "6,9 kg",
    )

    private val langs = listOf(
        Lang('S', "BPES", "emerald_es", NATIVE_EMERALD_ES, "Pokemon - Edicion Esmeralda (Spain).gba",
            "TORCHIC", "POCIÓN", "DESTRUCTOR", "FUERTE", "VILLA RAÍZ", "POKéMON POLLUELO"),
        Lang('D', "BPED", "emerald_de", NATIVE_EMERALD_DE, "Pokemon - Smaragd-Edition (Germany).gba",
            "FLEMMLI", "TRANK", "PFUND", "ROBUST", "WURZELHEIM", "KÜKEN"),
        Lang('F', "BPEF", "emerald_fr", NATIVE_EMERALD_FR, "Pokemon - Version Emeraude (France).gba",
            "POUSSIFEU", "POTION", "ECRAS'FACE", "HARDI", "BOURG-EN-VOL", "POUSSIN"),
        Lang('I', "BPEI", "emerald_it", NATIVE_EMERALD_IT, "Pokemon - Versione Smeraldo (Italy).gba",
            "TORCHIC", "POZIONE", "BOTTA", "ARDITA", "ALBANOVA", "POKéMON PULCINO"),
        Lang('J', "BPEJ", "emerald_ja", NATIVE_EMERALD_JA, "Pocket Monsters - Emerald (Japan).gba",
            "アチャモ", "キズぐすり", "はたく", "がんばりや", "ミシロタウン", "ひよこポケモン", "0.7m", "6.9kg"),
    )
    private val european get() = langs.filter { it.letter != 'J' }

    private fun select(l: Lang?) {
        activeGame = GameKind.EMERALD
        romLanguage = l?.letter ?: 'E'
        romGameCode = l?.code.orEmpty()
    }

    @After fun english() = select(null)

    private fun romFile(l: Lang) = File(RomFileReader.EMERALD_PATH).parentFile?.let { File(it, l.rom) }

    @Test fun `each game code gets its config`() = langs.forEach { l ->
        assertSame(l.code, l.cfg, TelemetrySampler.otherRetailConfig(l.code, 0))
        assertEquals(l.letter, l.cfg.language)
        assertTrue(l.code in TelemetrySampler.OTHER_RETAIL_CODES)
    }

    /** English's save, read at each one's addresses: the same party, bag, money and place. */
    @Test fun `the english save reads the same`() {
        select(null)
        val en = decodeNative("emerald_vanilla", NATIVE_EMERALD_RETAIL)
        langs.forEach { l ->
            select(l)
            val t = decodeNative(l.fixture, l.cfg)
            assertEquals(l.code, en.party.map { it.species to it.level }, t.party.map { it.species to it.level })
            assertEquals(l.code, en.money, t.money)
            assertEquals(l.code, en.regionMapSectionId, t.regionMapSectionId)
            assertEquals(l.code, en.items.map { it.itemId to it.quantity }.toSet(), t.items.map { it.itemId to it.quantity }.toSet())
            t.items.forEach { assertFalse("${l.code} item ${it.itemId}", '#' in itemName(it.itemId)) }
            t.party.forEach { m -> m.moves.filter { it != 0 }.forEach { assertFalse("${l.code} move $it", '#' in lookupMove(it).name) } }
        }
    }

    @Test fun `names are the game's own`() = langs.forEach { l ->
        select(null)
        val pound = lookupMove(1)
        select(l)
        assertEquals(l.torchic, speciesName(280))
        assertEquals(l.potion, itemName(13))
        assertEquals(l.pound, lookupMove(1).name)
        assertEquals(pound.type to pound.power, lookupMove(1).type to lookupMove(1).power) // English's type and power
        assertEquals(l.hardy, natureName(0))
        assertEquals(l.littleroot, lookupLocation(0).mapSecName)
        assertEquals("LITTLEROOT TOWN", englishMapSecName(0, lookupLocation(0).mapSecName).uppercase())
    }

    @Test fun `english keeps its tables`() {
        select(null)
        assertEquals("TORCHIC", speciesName(280))
        assertEquals("Littleroot Town", lookupLocation(0).mapSecName)
        assertEquals(null, localText)
    }

    @Test fun `dex and guide tables are where the configs say`() = langs.forEach { l ->
        val f = romFile(l)
        assumeTrue("${l.rom} not on this machine", f?.isFile == true)
        val rom = RomFileReader.load(f!!.path)!!
        select(l)
        PokedexSource.reader = rom
        val dex = l.cfg.pokedex!!
        assertTrue(l.code, pokedexMatchesRom(rom, dex))
        assertFalse("English's probe word isn't in ${l.code}", pokedexMatchesRom(rom, POKEDEX_EMERALD.copy(entries = dex.entries)))
        val torchic = PokedexSource.entry(dex, 255)!! // TORCHIC is national 255
        assertEquals(l.torchicCategory, dexCategoryLine(dex, torchic.category))
        assertEquals(l.height, formatDexHeight(dex, 7))
        assertEquals(l.weight, formatDexWeight(dex, 69))
        val guide = l.cfg.guideTables!!
        assertTrue(l.code, guideTablesMatchRom(rom, guide))
        // ROXANNE's team (trainer 265) - GEODUDE 12, GEODUDE 12, NOSEPASS 15 (internal id 320) - as in English.
        assertEquals(listOf(74 to 12, 74 to 12, 320 to 15), GuideRomSource.party(guide, 265)!!.map { it.species to it.level })
        assertNotNull(GuideRomSource.encounters(guide, 0, 16))
    }

    @Test fun `party art from each rom`() = european.forEach { l ->
        val f = romFile(l)
        assumeTrue("${l.rom} not on this machine", f?.isFile == true)
        val images = RomArt.compose(RomArt.find(f!!.readBytes()))
        val dir = RomArt.emeraldPartyDir(l.rom.let { when (l.letter) { 'S' -> "es"; 'D' -> "de"; 'F' -> "fr"; else -> "it" } })
        assertTrue(l.code, "partyem/pokeball.png" in images)
        listOf("slot_normal.png", "status_icons.png", "font_small.png").forEach {
            assertTrue("${l.code} $dir/$it", "$dir/$it" in images)
        }
        assertTrue(l.code, "partybg/emerald.png" in images)
        assertFalse("English's status icons aren't in ${l.code}", "partyem/status_icons.png" in images)
    }

    /** A scripted wild battle (PIKACHU Lv 5) in each, at the action menu: battlers and the input state. */
    @Test fun `battle at the action menu`() = langs.forEach { l ->
        select(l)
        val ram = FixtureMemoryReader.load(l.fixture + "_battle")
        val t = readNativeTelemetry(ram, l.cfg)
        assertTrue(l.code, t.inBattle)
        assertEquals(l.code, 64 to 36, t.battleMons[BATTLE_POS_PLAYER_LEFT].let { it.species to it.level }) // KADABRA
        assertEquals(l.code, 25 to 5, t.battleMons[BATTLE_POS_OPPONENT_LEFT].let { it.species to it.level })
        assertEquals(l.code, 0 to BATTLE_INPUT_ACTION_SELECT, readNativeBattleInputFast(ram, l.cfg))
    }

    /** Japanese text through Gen3Text while a Japanese ROM runs: kana, full-width spaces; Western otherwise. */
    @Test fun `japanese text decodes as kana`() {
        val bytes = byteArrayOf(0x51, 0x5C, 0x0C, 0x00, 0xA2.toByte(), 0xFE.toByte(), 0x52, 0xFF.toByte())
        select(langs.single { it.letter == 'J' })
        assertEquals("アシし　1　イ", Gen3Text.decode(bytes))
        select(null)
        assertEquals("¿(Ï 1 ¡", Gen3Text.decode(bytes))
    }

    /** Japanese Emerald with a Japanese save (emerald_ja_own): what its own trainer card shows. */
    @Test fun `japanese save reads like its trainer card`() {
        select(langs.single { it.letter == 'J' })
        val t = decodeNative("emerald_ja_own", NATIVE_EMERALD_JA)
        assertEquals(284669L, t.money)
        assertTrue(t.party.isNotEmpty() && t.party.all { it.species in 1..411 && it.level in 1..100 && it.hp <= it.maxHp })
        t.party.forEach { assertFalse('#' in speciesName(it.species)) }
        assertTrue(t.items.isNotEmpty())
        t.items.forEach { assertFalse("item ${it.itemId}", '#' in itemName(it.itemId)) }
        val f = romFile(langs.single { it.letter == 'J' })
        assumeTrue(f?.isFile == true)
        val dex = readPokedexState(RomFileReader.load(f!!.path)!!.withRam(FixtureMemoryReader.load("emerald_ja_own")), NATIVE_EMERALD_JA, POKEDEX_EMERALD_JA)!!
        assertEquals(101, if (dex.national) dex.caught.size else dex.caught.count { it in HOENN_TO_NATIONAL })
    }
}
