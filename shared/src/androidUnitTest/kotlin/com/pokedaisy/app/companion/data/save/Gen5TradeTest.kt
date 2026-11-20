package com.pokedaisy.app.companion.data.save

import com.pokedaisy.app.companion.data.NdsArt
import com.pokedaisy.app.companion.data.RETAIL_ROM_DIR
import java.io.File
import okio.Path.Companion.toOkioPath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Trades between two Black / White saves: the games' rules, the Pokédex told, Gen 5's trade evolutions. */
class Gen5TradeTest {
    private fun black(game: Gen5GameData = SyntheticGen5.demoGame()) =
        Gen5SaveEditor((Gen5SaveFile.parse(SyntheticGen5.save()) as Gen5SaveFile.Parsed.Ok).save, game)

    /** The demo game with Karrablast (588) and Shelmet (616) evolving when traded for each other, Gen 5's method 7. */
    private fun evoGame(): Gen5GameData {
        val g = SyntheticGen5.demoGame()
        val evos = List(650) { s ->
            when (s) {
                588 -> listOf(Triple(7, 616, 589))
                616 -> listOf(Triple(7, 588, 617))
                533 -> listOf(Triple(5, 0, 534))   // TIMBURR's line, a plain trade evolution here
                else -> emptyList()
            }
        }
        return Gen5GameData(
            List(650) { com.pokedaisy.app.companion.data.speciesNamesBlack[it]?.uppercase() ?: "" },
            List(560) { com.pokedaisy.app.companion.data.moveDataBlack[it]?.name ?: "" },
            List(639) { com.pokedaisy.app.companion.data.itemNamesBlack[it] ?: "" },
            emptyList(),
            List(650) { s -> if (s == 0) null else ByteArray(0x3C).also { r -> for (i in 0 until 6) r[i] = 60; r[0x12] = 127 } },
            2, Gen5Layout.BW, evolutions = evos, movePp = IntArray(560) { if (it == 0) 0 else 20 },
        ).also { assertEquals(g.layout, it.layout) }
    }

    private fun mon(species: Int, pid: Long): ByteArray {
        val n = ByteArray(128)
        SyntheticSave.le16(n, 0, species)
        SyntheticSave.le32(n, 4, 0x1234_5678L)
        SyntheticSave.le32(n, 8, com.pokedaisy.app.companion.data.gen3ExperienceTables[0][20].toLong())
        "HILBERT".forEachIndexed { k, c -> SyntheticSave.le16(n, 96 + k * 2, c.code) }
        SyntheticSave.le16(n, 96 + 14, 0xFFFF)
        for (k in 0 until 11) SyntheticSave.le16(n, 64 + k * 2, 0xFFFF)
        SyntheticSave.le16(n, 32, 33)
        return Gen4BoxMon.build(pid, n)
    }

    @Test
    fun aPcPokemonGoesOverAndIsRegistered() {
        val a = TradeSide("BLACK", black(), "IRBO")
        val b = TradeSide("WHITE", black(), "IRAO")
        assertEquals(Gen5Trade, a.rules)
        val from = MonRef.Pc(BoxSlot(0, 0))
        val sp = a.editor.monAt(from)!!.species
        val to = MonRef.Pc(BoxSlot(1, 0))
        a.rules.send(a, from, b, to)
        assertTrue(a.editor.monAt(from) == null)
        assertEquals(sp, b.editor.monAt(to)!!.species)
        assertTrue((b.editor as Gen5SaveEditor).caughtInDex(sp))
        // Both files still read as the games' saves.
        listOf(a, b).forEach { s ->
            val e = s.editor as Gen5SaveEditor
            assertNull(Gen5SaveData(e.game, (Gen5SaveFile.parse(e.build()) as Gen5SaveFile.Parsed.Ok).save.data).mismatch())
        }
    }

    @Test
    fun karrablastAndShelmetEvolveTradedForEachOther() {
        val g = evoGame()
        val a = TradeSide("BLACK", black(g), "IRBO")
        val b = TradeSide("WHITE", black(g), "IRAO")
        (a.editor as Gen5SaveEditor).putIncoming(MonRef.Pc(BoxSlot(2, 0)), mon(588, 0x5000))
        (b.editor as Gen5SaveEditor).putIncoming(MonRef.Pc(BoxSlot(2, 0)), mon(616, 0x6000))
        val at = MonRef.Pc(BoxSlot(2, 0))
        // Sent alone, neither evolves; for each other, both do.
        assertNull(a.rules.evolution(a.editor.monAt(at)!!, b))
        assertNotNull(a.rules.evolution(a.editor.monAt(at)!!, b, b.editor.monAt(at)))
        a.rules.swap(a, at, b, at)
        assertEquals(617, a.editor.monAt(at)!!.species)   // ACCELGOR
        assertEquals(589, b.editor.monAt(at)!!.species)   // ESCAVALIER
        assertEquals("ESCAVALIER", b.editor.monAt(at)!!.nickname)
        // A plain trade evolution goes off on a one-way trade, and an Everstone stops it.
        (a.editor as Gen5SaveEditor).putIncoming(MonRef.Pc(BoxSlot(2, 1)), mon(533, 0x7000))
        assertEquals(534, a.rules.evolution(a.editor.monAt(MonRef.Pc(BoxSlot(2, 1)))!!, b)!!.into)
        assertNull(g.tradeEvolution(533, 229))
    }

    @Test
    fun aPartysLastPokemonStays() {
        val a = TradeSide("BLACK", black(), "IRBO")
        val b = TradeSide("WHITE", black(), "IRAO")
        a.rules.send(a, MonRef.Party(0), b, MonRef.Pc(BoxSlot(3, 0)))
        assertNotNull(a.rules.partyBlocker(a, MonRef.Party(0)))
    }

    @Test
    fun realBlackAndBlack2Trade() {
        val nds = File(File(RETAIL_ROM_DIR).parentFile, "nds")
        val bRom = File(nds, "Pokemon - Black Version (USA, Europe) (NDSi Enhanced).nds")
        val b2Rom = File(nds, "Pokemon - Black Version 2 (USA, Europe) (NDSi Enhanced) [b].nds")
        val bSav = File(System.getProperty("user.home"), "Documents/RetroArch/saves/mGBA/Pokemon - Black Version (USA, Europe) (NDSi Enhanced).sav")
        val b2Sav = File(nds, "Pokemon Black 2 Version.sav")
        assumeTrue(bRom.isFile && b2Rom.isFile && bSav.isFile && b2Sav.isFile)
        val bE = Gen5SaveEditor((Gen5SaveFile.parse(bSav.readBytes()) as Gen5SaveFile.Parsed.Ok).save, NdsArt.readGen5SaveTables(bRom.toOkioPath())!!)
        val b2E = Gen5SaveEditor((Gen5SaveFile.parse(b2Sav.readBytes()) as Gen5SaveFile.Parsed.Ok).save, NdsArt.readGen5SaveTables(b2Rom.toOkioPath())!!)
        val a = TradeSide("BLACK", bE, "IRBO")
        val b = TradeSide("BLACK 2", b2E, "IREO")
        val from = (0 until 24 * 30).map { MonRef.Pc(BoxSlot(it / 30, it % 30)) }
            .first { val m = bE.monAt(it); m != null && !m.isEgg && m.heldItem == 0 && a.rules.evolution(m, b) == null }
        val to = b2E.freePlaces(1).first().let { MonRef.Pc(it) }
        val sent = bE.monAt(from)!!
        a.rules.send(a, from, b, to)
        val back = Gen5SaveEditor((Gen5SaveFile.parse(b2E.build()) as Gen5SaveFile.Parsed.Ok).save, b2E.game)
        val got = back.monAt(to)!!
        assertEquals(sent.personality, got.personality)
        assertEquals(sent.species, got.species)
        assertEquals(sent.otName, got.otName)
        assertTrue(back.caughtInDex(sent.species))
        assertNull(back.data.mismatch())
        System.getenv("GEN5_TRADE_OUT")?.let { File(it).writeBytes(b2E.build()) }
    }
}
