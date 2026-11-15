package com.pokedaisy.app.companion.data.save

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/** MISSING: what a save's Pokédex lacks, and which of the other saves could send one (the games' trade and Pal Park rules). */
class MissingDexTest {

    @Test
    fun anotherGamesPokemonFillTheGaps() {
        val (fr, em) = SyntheticSave.demoTrade()
        val target = TradeSide("FIRERED", Gen3SaveEditor(fr), "BPRE")
        val source = TradeSide("EMERALD", Gen3SaveEditor(em), "BPEE")
        val entries = MissingDex.find(target, listOf(source))
        // FireRed has its National Dex here, so every species it hasn't caught is listed, in National order.
        assertEquals(entries.map { it.number }, entries.map { it.number }.sorted())
        assertTrue(entries.isNotEmpty())
        // Emerald's TREECKO (252) can come; its MEW without an event's mark can't, and says why.
        val treecko = entries.first { it.species == 252 }
        assertTrue(treecko.best!!.reachable)
        assertFalse(treecko.best!!.palPark)
        val mew = entries.firstOrNull { it.species == 151 }
        if (mew != null) assertNotNull(mew.best!!.blocker)
        // A species nobody has: listed with no source.
        assertTrue(entries.any { it.sources.isEmpty() })
    }

    @Test
    fun aGbaPcReachesADsGameThroughPalPark() {
        val gba = TradeSide("FIRERED", Gen3SaveEditor(SyntheticSave.demoFireRed()), "BPRE")
        val ds = TradeSide("HEARTGOLD",
            Gen4SaveEditor((Gen4SaveFile.parse(SyntheticGen4.save(national = true)) as Gen4SaveFile.Parsed.Ok).save, SyntheticGen4.demoGame()), "IPKE")
        val entries = MissingDex.find(ds, listOf(gba))
        // SQUIRTLE is in FireRed's PC: Pal Park brings it.
        val squirtle = entries.first { it.species == 7 }
        assertTrue(squirtle.best!!.palPark)
        assertNull(squirtle.best!!.blocker)
        // CHARIZARD is only in FireRed's party: Pal Park won't take it from there.
        val charizard = entries.first { it.species == 6 }
        assertNotNull(charizard.best!!.blocker)
        // Nothing goes back from a DS game to a GBA one.
        assertTrue(MissingDex.find(gba, listOf(ds)).all { it.sources.isEmpty() })
    }

    @Test
    fun aRealRubyLivingDexFillsAnEmerald() {
        val dir = File(System.getProperty("user.home"), "Documents/RetroArch/saves/mGBA")
        val ruby = File(dir, "Pokemon - Ruby Version (USA, Europe) (Living Dex).sav")
        val em = File(dir, "Pokemon - Emerald Version (USA, Europe).srm")
        assumeTrue(ruby.isFile && em.isFile)
        fun open(f: File, format: Gen3SaveFormat) = (Gen3SaveFile.parse(f.readBytes(), format) as Gen3SaveFile.Parsed.Ok).save
        val target = TradeSide("EMERALD", Gen3SaveEditor(open(em, Gen3SaveFormat.EMERALD)), "BPEE")
        val source = TradeSide("RUBY", Gen3SaveEditor(open(ruby, Gen3SaveFormat.RUBY_SAPPHIRE)), "AXVE")
        val entries = MissingDex.find(target, listOf(source))
        println("Emerald misses ${entries.size}; Ruby could send ${entries.count { it.best?.reachable == true }}")
        entries.forEach { e -> assertFalse(MissingDex.caught(target.editor, e.species)) }
    }
}
