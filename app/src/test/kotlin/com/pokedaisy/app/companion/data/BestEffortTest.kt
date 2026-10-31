package com.pokedaisy.app.companion.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * BEST EFFORT over the real ROMs + saves: each game, treated as an unknown ROM of its base game,
 * is SURE of its own config (a rebuilt / re-hashed build of a supported version loses its "not
 * supported" without asking). With its own game's configs taken away - a hack the app doesn't
 * know - nothing is sure, so the player picks: another game that merely shares the engine's RAM
 * must not be used quietly (the report: a Galar hack read as Emerald, wrong names, sprites and map).
 */
class BestEffortTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private class Game(val rom: String, val fixture: String, val code: String, val id: String?)

    private val games = listOf(
        Game("Pokemon - FireRed Version (USA, Europe) (Rev 1).gba", "firered_vanilla", "BPRE", "FIRERED_REV1"),
        Game("Pokemon - LeafGreen Version (USA, Europe) (Rev 1).gba", "leafgreen_rev1", "BPGE", "LEAFGREEN_REV1"),
        Game("Pokemon - Emerald Version (USA, Europe).gba", "emerald_vanilla", "BPEE", "EMERALD"),
        Game("../Pokemon Multi Language/Pokemon - Edicion Esmeralda (Spain)/Pokemon - Edicion Esmeralda (Spain).gba", "emerald_es", "BPES", "EMERALD_ES"),
        Game("Pokemon - Ruby Version (USA, Europe) (Rev 1).gba", "ruby_rev1", "AXVE", "RUBY"),
        Game("Pokemon Unbound (v2.1.1.1).gba", "unbound", "BPRE", "UNBOUND"),
        Game("Pokemon Unbound (v2.1.1.1) (French).gba", "unbound_fr", "BPRE", "UNBOUND_FR"),
        Game("Pokemon Orange Islands.gba", "orange_islands", "BPRE", "ORANGE_ISLANDS"),
        Game("Pokemon FireRed Rocket Edition.gba", "rocket_edition", "BPRE", "ROCKET_EDITION"),
        Game("Pokemon Ash Gray (v4.5.3).gba", "ash_gray", "BPRE", "ASH_GRAY"),
        Game("Pokemon Ghost Grey (v1.0.4).gba", "ghost_grey", "BPRE", "GHOST_GREY"),
        // Recharged Yellow's ROM carries other languages' name tables too (French species after its own): no config's
        // names cover it, so never sure - but its own reads the party name for name and comes first (below).
        Game("Pokemon Recharged Yellow (v1.8.0).gba", "recharged_yellow_v180", "BPRE", null),
        Game("Pokemon Recharged Yellow (v1.9.0).gba", "recharged_yellow_v190", "BPRE", null),
        Game("Pokemon Recharged Yellow (v1.9.4).gba", "recharged_yellow_v194", "BPRE", null),
        Game("Pokemon Recharged Yellow (v1.9.7).gba", "recharged_yellow", "BPRE", null),
        Game("Pokemon Gaia (v3.2).gba", "gaia", "BPRE", "GAIA_V3_2"),
        Game("Pokemon Radical Red (v4.1).gba", "radical_red", "BPRE", "RADICAL_RED_V4_1"),
        Game("Pokemon Odyssey (v4.1.1).gba", "odyssey", "BPRE", "ODYSSEY"),
        Game("Pokemon Amethyst (v1.3.0).gba", "amethyst", "BPRE", "AMETHYST"),
        Game("Pokemon Amethyst (v1.4.1).gba", "amethyst_v141", "BPRE", "AMETHYST_V1_4_1"),
        // The app's Celia tables stop at 385 species and its ROM has more: never sure, but first.
        Game("Pokemon Celia's Stupid Romhack (v1.1.4).gba", "celia", "BPRE", null),
        Game("Pokemon SoulGold (v1.1.4).gba", "soulgold", "BPEE", "SOULGOLD"),
        Game("Pokemon SoulGold (v1.2).gba", "soulgold_v12", "BPEE", "SOULGOLD_V1_2"),
        Game("Pokemon SoulGold (v1.2) (Alt 1).gba", "soulgold_v12b", "BPEE", "SOULGOLD_V1_2B"),
        Game("Pokemon SoulGold (v1.2.1).gba", "soulgold_v121", "BPEE", "SOULGOLD_V1_2_1"),
        Game("Pokemon Heart and Soul (v2.0.6).gba", "heart_and_soul", "BPEE", "HEART_AND_SOUL"),
        Game("Pokemon Lazarus (v2.0).gba", "lazarus", "BPEE", "LAZARUS"),
        Game("Pokemon Emerald Seaglass (v3.0).gba", "emerald_seaglass", "BPEE", "EMERALD_SEAGLASS"),
        Game("Pokemon Too Many Types 2 (v1.5.2).gba", "tmt2", "BPEE", "TMT2"),
        Game("Pokemon Emerald Rogue (v2.2.1-EX).gba", "emerald_rogue", "BPEE", "EMERALD_ROGUE"),
        Game("Pokemon Glazed (v9.2.0).gba", "glazed", "BPEE", "GLAZED"),
        Game("Pokemon Emerald Imperium (v1.3.1).gba", "imperium", "BPEE", "IMPERIUM"),
        Game("Pokemon R.O.W.E. (v2.1.9.1 Experimental).gba", "rowe", "BPEE", "ROWE"),
        Game("Pokemon Quetzal (Alpha 9.0) (English).gba", "quetzal", "BPEE", "QUETZAL"),
        // FireRed 1.0 under its own game code: only its own config is tried.
        Game("Pokemon Ultra Violet (v1.22).gba", "ultra_violet", "PUVV", "ULTRA_VIOLET"),
        Game("Pokemon Sword and Shield Ultimate Plus (v1.2.1.2).gba", "sword_shield_up", "BPRE", "SWORD_SHIELD"),
        Game("Pokemon GS Chronicles (v2.7.6).gba", "gs_chronicles", "BPRE", "GS_CHRONICLES"),
        Game("Super Mariomon (v1.5.1 Anniversary).gba", "mariomon_v1_5_1", "BPEE", "MARIOMON_V1_5_1"),
        Game("Pokemon Elite Redux (2.65.4.1b).gba", "elite_redux", "BPEE", "ELITE_REDUX_V2_65_4_1B"),
        Game("Pokemon Team Rocket Dragonsden Version.gba", "team_rocket", "BPEE", "TEAM_ROCKET"),
        Game("Pokemon Wishes of Tomorrow (v1.2.6).gba", "wishes", "BPEE", "WISHES"),
        Game("Pokemon Dreamstone Mysteries.gba", "dreamstone", "BPEE", "DREAMSTONE"),
        Game("Super Mariomon (v1.3).gba", "mariomon_v1_3", "BPEE", "MARIOMON_V1_3"),
    )

    /**
     * With its own game's configs gone, these read as an engine sibling - and every name they'd
     * show is confirmed at its id: Orange Islands and Odyssey are in-place edits of FireRed rev 0
     * (how Orange Islands read before it had a config); FireRed Rocket Edition and Ash Gray, two more, read through
     * Orange Islands' tables (rev 0's, its moves found where they moved). Unbound reads as well through Amethyst's
     * CFRU tables as through Sword and Shield Ultimate Plus' (the same species ids), so neither
     * is used quietly: the player picks. Sword and Shield Ultimate Plus reads through Amethyst's
     * (the other CFRU configs' moves don't hold), with its ROM-side parts switched off. GS Chronicles
     * reads through Unbound's (DPE's species ids; its DEX, GUIDE and item text switched off).
     */
    private val siblings = mapOf(
        "orange_islands" to GameKind.FIRERED, "odyssey" to GameKind.FIRERED, "rocket_edition" to GameKind.ORANGE_ISLANDS,
        "ash_gray" to GameKind.ORANGE_ISLANDS, "sword_shield_up" to GameKind.AMETHYST,
        "gs_chronicles" to GameKind.UNBOUND,
        // Orange Islands is FireRed rev 0 edited in place (97% of its names); Odyssey used to tie with it on
        // FireRed's names until its own were read (GitHub #206).
        "firered_vanilla" to GameKind.ORANGE_ISLANDS, "leafgreen_rev1" to GameKind.ORANGE_ISLANDS,
    )

    private class Loaded(val reader: MemoryReader, val size: Long, val text: BestEffort.RomText)

    /** One at a time: 26 ROMs of up to 32 MB don't all fit in the test JVM. */
    private fun load(g: Game): Loaded? {
        val f = File(RETAIL_ROM_DIR + g.rom).takeIf { it.isFile } ?: return null
        val bytes = f.readBytes()
        val r = RomFileReader(bytes).withRam(FixtureMemoryReader.load(g.fixture))
        return Loaded(r, bytes.size.toLong(), BestEffort.romText(r, g.code, bytes.size.toLong()))
    }

    private fun each(check: (Game, Loaded) -> Unit) {
        var ran = 0
        for (g in games) {
            val l = load(g) ?: continue
            check(g, l)
            ran++
        }
        assumeTrue("no ROMs on this machine", ran > 0)
    }

    @Test fun eachGameIsSureOfItsOwnConfig() = each { g, l ->
        val v = BestEffort.evaluate(l.reader, g.code, l.size, l.text)
        if (g.id == null) {
            assertNull("${g.fixture}: ${v.fits.first()}", v.sure)
        } else {
            assertEquals("${g.fixture}: ${v.fits.take(3)}", g.id, v.sure?.candidate?.id)
            assertEquals(g.fixture, g.id, v.fits.first().candidate.id)
        }
    }

    /** Each Recharged Yellow release: its own config first in the picker, with nothing off. */
    @Test fun rechargedYellowRanksItsOwnConfigFirst() = each { g, l ->
        if (!g.fixture.startsWith("recharged_yellow")) return@each
        val first = BestEffort.evaluate(l.reader, g.code, l.size, l.text).fits.first()
        val want = "RECHARGED_YELLOW_V" + mapOf(
            "recharged_yellow_v180" to "1_8_0", "recharged_yellow_v190" to "1_9_0",
            "recharged_yellow_v194" to "1_9_4", "recharged_yellow" to "1_9_7",
        ).getValue(g.fixture)
        assertEquals("${g.fixture}: $first", want, first.candidate.id)
        assertTrue(first.off.isEmpty() && first.shownHolds)
    }

    @Test fun anUnknownGameIsNeverReadAsAnotherQuietly() = each { g, l ->
        val own = BestEffort.evaluate(l.reader, g.code, l.size, l.text).fits.first().candidate.kind
        val others = BestEffort.candidatesFor(g.code).filter { it.kind != own }
        val v = BestEffort.evaluate(l.reader, g.code, l.size, l.text, others)
        assertEquals("${g.fixture} without $own: ${v.fits.take(3)}", siblings[g.fixture], v.sure?.candidate?.kind)
    }

    /** A rebuild (the SoulGold v1.2 builds): the exact config gone, a sibling build of the same game is sure. */
    @Test fun aRebuildReadsAsItsSiblingBuild() {
        val g = games.first { it.fixture == "soulgold_v12b" }
        val l = load(g)
        assumeTrue(l != null)
        val v = BestEffort.evaluate(l!!.reader, g.code, l.size, l.text, BestEffort.candidatesFor(g.code).filter { it.id != g.id })
        assertEquals(v.fits.take(3).toString(), GameKind.SOULGOLD, v.sure?.candidate?.kind)
        // Its moved tables are found where they are: names checked id by id, not by address.
        assertTrue(v.sure!!.text.speciesHold)
    }

    private fun romOnly(rom: String): Pair<MemoryReader, Long>? {
        val f = File(RETAIL_ROM_DIR + rom).takeIf { it.isFile } ?: return null
        val r = RomFileReader(f.readBytes())
        return object : MemoryReader {
            override fun readCoreMemory(address: Long, length: Int): ByteArray =
                if (address >= 0x08000000L) r.readCoreMemory(address, length) else ByteArray(length)
        } to f.length()
    }

    /** A new game: no party yet, so nothing is sure - but a ROM whose names are one game's gets it as a guess. */
    @Test fun aNewGameGetsAGuessFromItsNames() {
        val (r, size) = romOnly("Pokemon SoulGold (v1.2) (Alt 1).gba") ?: return
        val v = BestEffort.evaluate(r, "BPEE", size, BestEffort.romText(r, "BPEE", size))
        assertNull(v.sure)
        assertEquals(BestEffort.Miss.NO_PARTY, v.miss)
        assertEquals(v.fits.take(3).toString(), GameKind.SOULGOLD, v.guess?.candidate?.kind)
    }

    /** ... and one whose names many games share (FireRed's Kanto) gets none: the picker asks. */
    @Test fun aSharedBaseGetsNoGuess() {
        val (r, size) = romOnly("Pokemon - FireRed Version (USA, Europe) (Rev 1).gba") ?: return
        val (m, miss) = BestEffort.match(r, "BPRE", size)
        assertNull(m)
        assertEquals(BestEffort.Miss.NO_PARTY, miss)
        assertNull(BestEffort.evaluate(r, "BPRE", size, BestEffort.romText(r, "BPRE", size)).guess)
    }

    @Test fun storeKeepsAMatch() = withStore {
        val c = BestEffort.candidate("SOULGOLD_V1_2")!!
        BestEffortStore.save("ABC", BestEffort.Match(c, full = false, off = setOf(BestEffort.Part.GUIDE), score = 1), BestEffortStore.Source.PICKED, verified = false)
        val e = BestEffortStore.load("abc")!!
        assertEquals("SOULGOLD_V1_2", e.id)
        assertFalse(e.full)
        assertEquals(setOf(BestEffort.Part.GUIDE), e.off)
        assertEquals(BestEffortStore.Source.PICKED, e.source)
        assertFalse(e.verified)
        val m = BestEffortStore.matchOf(e)!!
        assertNull(m.config.guideTables)
        BestEffortStore.forget("abc")
        assertNull(BestEffortStore.load("abc"))
    }

    @Test fun noneOfTheseIsKeptApart() = withStore {
        BestEffortStore.decline("DEF")
        assertTrue(BestEffortStore.declined("def"))
        assertNull(BestEffortStore.load("def"))
        assertTrue(BestEffortStore.all().isEmpty())
        BestEffortStore.forget("def")
        assertFalse(BestEffortStore.declined("def"))
    }

    /** A row from before the picker (4 columns) is an automatic match to look at again. */
    @Test fun anOldRowIsCheckedAgain() = withStore {
        File(tmp.root, "best-effort.tsv").writeText("abc\tEMERALD\tPARTIAL\tDEX,GUIDE\n")
        val e = BestEffortStore.load("abc")!!
        assertEquals(BestEffortStore.Source.AUTO, e.source)
        assertFalse(e.verified)
        assertEquals(setOf(BestEffort.Part.DEX, BestEffort.Part.GUIDE), e.off)
    }

    /** The pieces that hide when they don't hold: no sprites rather than wrong ones. */
    @Test fun iconsOffClearTheirTables() {
        val c = BestEffort.candidate("SOULGOLD_V1_2")!!
        val cfg = BestEffort.Match(c, false, setOf(BestEffort.Part.MON_ICONS, BestEffort.Part.ITEM_ICONS), 0).config
        assertEquals(0L, cfg.monIconTable)
        assertEquals(0L, cfg.itemIconTable)
        assertTrue(cfg.playerParty != 0L)
    }

    private fun withStore(block: () -> Unit) {
        BestEffortStore.dir = tmp.root
        try {
            block()
        } finally {
            BestEffortStore.dir = null
        }
    }
}
