package com.pokedaisy.app.companion.data.save

import com.pokedaisy.app.companion.data.NdsArt
import com.pokedaisy.app.companion.data.RETAIL_ROM_DIR
import java.io.File
import java.time.LocalDate
import okio.Path.Companion.toOkioPath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * PAL PARK: GBA Pokémon brought into a DS save the way pret/pokeplatinum's migration and Pal Park do it, on made-up
 * saves and, when they're on this machine, the player's own Emerald and HeartGold saves.
 */
class PalParkTest {
    private val today = LocalDate.of(2026, 11, 16)

    private fun ds(national: Boolean = true) =
        Gen4SaveEditor((Gen4SaveFile.parse(SyntheticGen4.save(national)) as Gen4SaveFile.Parsed.Ok).save, SyntheticGen4.demoGame())

    private fun refused(block: () -> Unit) {
        try { block() } catch (e: SaveEditRefused) { return }
        fail("allowed")
    }

    @Test
    fun aMigratedPokemonIsTheGamesConversion() {
        val gba = Gen3SaveEditor(SyntheticSave.demoFireRed())
        val mon = gba.data.mon(BoxSlot(0, 0))   // BULBASAUR
        val game = SyntheticGen4.demoGame()
        val r = Gen4BoxMon.read(PalPark.convert(mon, 4, game, today), 0, game)
        assertFalse(r.isBadEgg)
        assertEquals(1, r.species)
        assertEquals(mon.personality, r.personality)
        assertEquals(mon.otId, r.otId)
        assertEquals(mon.exp, r.exp)
        assertEquals(70, r.friendship)
        assertEquals(mon.ivs.toList(), r.ivs.toList())
        assertEquals(mon.evs.toList(), r.evs.toList())
        assertEquals(mon.moves.toList(), r.moves.toList())
        assertEquals(listOf(20, 20, 20, 0), r.pp.toList())   // the demo game's every move: 20 PP
        assertEquals("BULBASAUR", r.nickname)
        assertFalse(r.nicknamed)   // its own name, the same language
        assertEquals("RED", r.otName)
        assertEquals(4, r.originGame)   // FireRed
        // Its gender by the DS game's ratio (the demo game's: half and half).
        assertEquals(if ((mon.personality and 0xFF) < 127) Gen3Gender.FEMALE else Gen3Gender.MALE, r.gender)
        assertEquals(mon.isShiny, r.isShiny)
        assertEquals(r.level, r.metLevel)   // met at Pal Park at its current level
        assertEquals(game.abilities(1)[mon.abilityNum], r.ability)
    }

    @Test
    fun sixGoAndBothSavesStayReadable() {
        val gba = Gen3SaveEditor(SyntheticSave.demoFireRed())
        val e = ds()
        val picks = (0 until 6).map { BoxSlot(0, it) }
        val species = picks.map { Gen3Species.nationalDex(gba.data.mon(it).species)!! }
        val before = (0 until 18).sumOf { b -> e.data.boxMons(b).count { !it.isEmpty } }
        val to = PalPark.migrate(gba, "BPRE", "FIRERED", picks, e, "HEARTGOLD", today)
        assertEquals(6, to.size)
        assertEquals(species, to.map { e.data.mon(it).species })
        // The first free slots from the box the PC is on: box 1 holds BULBASAUR and CHARMANDER.
        assertEquals(BoxSlot(0, 2), to[0])
        assertEquals(before + 6, (0 until 18).sumOf { b -> e.data.boxMons(b).count { !it.isEmpty } })
        picks.forEach { assertTrue(gba.data.mon(it).isEmpty) }
        species.forEach { assertTrue(e.caughtInDex(it)) }
        val g3 = (Gen3SaveFile.parse(gba.build(), gba.file.format) as Gen3SaveFile.Parsed.Ok).save
        assertNull(Gen3SaveData(g3).mismatch())
        val g4 = (Gen4SaveFile.parse(e.build()) as Gen4SaveFile.Parsed.Ok).save
        val back = Gen4SaveData(SyntheticGen4.demoGame(), g4.general, g4.pc)
        assertNull(back.mismatch())
        to.forEach { assertFalse(back.mon(it).isBadEgg) }
    }

    @Test
    fun theGamesRulesHold() {
        val gba = Gen3SaveEditor(SyntheticSave.demoFireRed())
        val six = (0 until 6).map { BoxSlot(0, it) }
        // No National Pokédex yet.
        refused { PalPark.migrate(gba, "BPRE", "FIRERED", six, ds(national = false), "HEARTGOLD", today) }
        // A GBA game in another language.
        refused { PalPark.migrate(gba, "BPRF", "ROUGE FEU", six, ds(), "HEARTGOLD", today) }
        // Five isn't six; the egg in slot 23 can't go.
        refused { PalPark.migrate(gba, "BPRE", "FIRERED", six.take(5), ds(), "HEARTGOLD", today) }
        refused { PalPark.migrate(gba, "BPRE", "FIRERED", six.take(5) + BoxSlot(0, 23), ds(), "HEARTGOLD", today) }
        assertNotNull(PalPark.blocker(gba.data.mon(BoxSlot(0, 23))))
        // Nothing changed after a refusal.
        assertFalse(gba.changed)
    }

    @Test
    fun anHmOrAKeyItemStays() {
        val f = Gen3SaveFormat.FIRERED_LEAFGREEN
        fun mon(moves: IntArray, item: Int = 0) = Gen3BoxMon.read(SyntheticSave.mon(personality = 0x1234, otId = 1, species = 1, exp = 135, item = item, moves = moves), 0)
        assertNotNull(PalPark.blocker(mon(intArrayOf(33, 57, 0, 0))))         // SURF
        assertNotNull(PalPark.blocker(mon(intArrayOf(33, 0, 0, 0), 259)))     // MACH BIKE
        assertNotNull(PalPark.blocker(mon(intArrayOf(33, 0, 0, 0), 121)))     // ORANGE MAIL
        assertNull(PalPark.blocker(mon(intArrayOf(33, 0, 0, 0), 13)))         // POTION
        assertEquals(17, palParkItems[13])
        assertTrue(f.kanto)
    }

    @Test
    fun namesConvertByLanguage() {
        val n = PalPark.convertName(byteArrayOf(0xBB.toByte(), 0x00, 0xC3.toByte(), 0xFF.toByte()), 10, 2)
        assertEquals("A I", n.text)
        assertEquals(listOf(0x12B, PalParkChars.SPACE, 0x133), n.codeList)
    }

    // ---- The player's own saves ----

    private val nds = File(File(RETAIL_ROM_DIR).parentFile, "nds")
    private val gbaSaves = File(System.getProperty("user.home"), "Documents/RetroArch/saves/mGBA")

    @Test
    fun aRealEmeraldIntoARealHeartGold() {
        val rom = File(nds, "Pokemon - HeartGold Version (USA).nds")
        val hgSav = nds.walkTopDown().firstOrNull { it.name == "Pokemon - HeartGold Version (USA).sav" }
        val emSav = File(gbaSaves, "Pokemon - Emerald Version (USA, Europe).srm")
        assumeTrue(rom.isFile && hgSav != null && emSav.isFile)
        val game = NdsArt.readSaveTables(rom.toOkioPath())!!
        val hgFile = (Gen4SaveFile.parse(hgSav!!.readBytes()) as Gen4SaveFile.Parsed.Ok).save
        val em = Gen3SaveEditor((Gen3SaveFile.parse(emSav.readBytes(), Gen3SaveFormat.EMERALD) as Gen3SaveFile.Parsed.Ok).save)
        val hg = Gen4SaveEditor(hgFile, game)
        val picks = (0 until em.data.boxCount).flatMap { b -> (0 until 30).map { BoxSlot(b, it) } }
            .filter { val m = em.data.mon(it); !m.isEmpty && PalPark.blocker(m) == null }
        assumeTrue(picks.size >= 6)
        // Every one the games would take converts to a record HeartGold reads back as itself.
        for (at in picks) {
            val m = em.data.mon(at)
            val r = Gen4BoxMon.read(PalPark.convert(m, 3, game, today), 0, game)
            assertFalse(r.isBadEgg)
            assertEquals(Gen3Species.nationalDex(m.species), r.species)
            assertEquals(m.level, r.level)
            assertEquals(m.gender, r.gender)
            assertEquals(m.nature, r.nature)
            assertEquals(m.isShiny, r.isShiny)
            if (m.nickname != null) assertEquals(m.nickname, r.nickname)
            assertEquals(m.otName, r.otName)
        }
        println("HeartGold has its National Pokedex: ${hg.nationalDex}; ${picks.size} could go")
        if (!hg.nationalDex) return
        val to = PalPark.migrate(em, "BPEE", "EMERALD", picks.take(6), hg, "HEARTGOLD", today)
        val out = (Gen4SaveFile.parse(hg.build()) as Gen4SaveFile.Parsed.Ok).save
        assertNull(Gen4SaveData(game, out.general, out.pc).mismatch())
        to.forEach { assertFalse(Gen4SaveData(game, out.general, out.pc).mon(it).isBadEgg) }
        assertNull(Gen3SaveData((Gen3SaveFile.parse(em.build(), Gen3SaveFormat.EMERALD) as Gen3SaveFile.Parsed.Ok).save).mismatch())
    }
}
