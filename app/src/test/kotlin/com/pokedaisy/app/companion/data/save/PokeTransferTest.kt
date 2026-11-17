package com.pokedaisy.app.companion.data.save

import com.pokedaisy.app.companion.data.NdsArt
import com.pokedaisy.app.companion.data.RETAIL_ROM_DIR
import java.io.File
import kotlinx.datetime.LocalDate
import okio.Path.Companion.toOkioPath
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * POKé TRANSFER on the player's own HeartGold and Black saves (skipped where they aren't on this machine): six Pokémon
 * converted the way the Poké Transfer Lab does it, both files written and read back.
 */
class PokeTransferTest {
    private val today = LocalDate(2026, 11, 16)
    private val nds = File(File(RETAIL_ROM_DIR).parentFile, "nds")
    private val hgRom = File(nds, "Pokemon - HeartGold Version (USA).nds")
    private val blackRom = File(nds, "Pokemon - Black Version (USA, Europe) (NDSi Enhanced).nds")
    // POKE_TRANSFER_HG_IN=<file>: another HeartGold save to send from (the player's has fewer than six the Lab takes).
    private val hgSav = System.getenv("POKE_TRANSFER_HG_IN")?.let(::File)?.takeIf { it.isFile }
        ?: nds.walkTopDown().firstOrNull { it.name == "Pokemon - HeartGold Version (USA).sav" }
    private val blackSav = File(System.getProperty("user.home"), "Documents/RetroArch/saves/mGBA/Pokemon - Black Version (USA, Europe) (NDSi Enhanced).sav")

    private fun sides(): Pair<Gen4SaveEditor, Gen5SaveEditor> {
        assumeTrue(hgRom.isFile && blackRom.isFile && hgSav != null && blackSav.isFile)
        val g4 = NdsArt.readSaveTables(hgRom.toOkioPath())!!
        val g5 = NdsArt.readGen5SaveTables(blackRom.toOkioPath())!!
        val hg = Gen4SaveEditor((Gen4SaveFile.parse(hgSav!!.readBytes()) as Gen4SaveFile.Parsed.Ok).save, g4)
        val black = Gen5SaveEditor((Gen5SaveFile.parse(blackSav.readBytes()) as Gen5SaveFile.Parsed.Ok).save, g5)
        return hg to black
    }

    private fun demo(national: Boolean = true): Pair<Gen4SaveEditor, Gen5SaveEditor> {
        val hg = Gen4SaveEditor((Gen4SaveFile.parse(SyntheticGen4.save(national = true)) as Gen4SaveFile.Parsed.Ok).save, SyntheticGen4.demoGame())
        // Six more in box 1, after its two.
        listOf(152, 155, 158, 25, 133, 143).forEachIndexed { i, sp ->
            hg.putIncoming(MonRef.Pc(BoxSlot(0, 2 + i)), SyntheticGen4.mon(0x3000L + i * 131, sp, 2000))
        }
        val black = Gen5SaveEditor((Gen5SaveFile.parse(SyntheticGen5.save(national)) as Gen5SaveFile.Parsed.Ok).save, SyntheticGen5.demoGame())
        return hg to black
    }

    @Test
    fun madeUpSavesTransferAndReadBack() {
        val (hg, black) = demo()
        val picks = (0 until 30).map { BoxSlot(0, it) }.filter { val m = hg.data.mon(it); !m.isEmpty && PokeTransfer.blocker(m, hg.game) == null }
        assertTrue(picks.size >= 6)
        val before = picks.take(6).map { hg.data.mon(it) }
        val to = PokeTransfer.transfer(hg, "HG", picks.take(6), black, "BLACK", today)
        // The first free places of box 1 (it holds two: the other two are in the party), in order.
        assertEquals((2 until 8).map { BoxSlot(0, it) }, to)
        val back = Gen5SaveData(black.game, (Gen5SaveFile.parse(black.build()) as Gen5SaveFile.Parsed.Ok).save.data)
        for ((at, was) in to.zip(before)) {
            val now = back.mon(at)
            assertEquals(was.species, now.species)
            assertEquals(was.otName, now.otName)
            assertEquals(was.nickname, now.nickname)
            assertEquals(PokeTransfer.MET_LOCATION, now.metLocation)
            assertEquals(was.personality % 25, now.nature.toLong())
            assertEquals(70, now.friendship)
            assertEquals(was.moves.map { if (it == 0) 0 else 20 }, now.pp.toList())   // the demo game's every move: 20 PP
        }
    }

    @Test
    fun theLabNeedsTheNationalDexAndOneLanguage() {
        val (hg, black) = demo(national = false)
        assertNotNull(PokeTransfer.linkBlocker(hg, "HG", black, "BLACK"))
        val (hg2, black2) = demo()
        val french = Gen5SaveEditor(black2.file, SyntheticGen5.demoGame(language = 3))
        assertNotNull(PokeTransfer.linkBlocker(hg2, "HG", french, "NOIRE"))
        assertEquals(null, PokeTransfer.linkBlocker(hg2, "HG", black2, "BLACK"))
    }

    @Test
    fun aRealBlackSaveWritesBackAsItWas() {
        val (_, black) = sides()
        // Nothing changed: the file comes back byte for byte (its CRCs and the backup copy are the game's).
        assertArrayEquals(blackSav.readBytes().copyOf(Gen5Layout.FILE_SIZE), black.build().copyOf(Gen5Layout.FILE_SIZE))
        val d = black.data
        assertTrue(d.nationalDex)
        assertEquals("Hilbert", d.trainerName)
        // Every Pokémon in its PC reads (checksums) and has a name from the cart.
        val mons = (0 until d.boxCount).flatMap { d.boxMons(it) }.filter { !it.isEmpty }
        assertTrue(mons.isNotEmpty() && mons.none { it.isBadEgg })
        assertTrue(mons.all { d.game.speciesName(it.species) != null })
    }

    @Test
    fun sixFromHeartGoldArriveInBlackAsTheLabMakesThem() {
        val (hg, black) = sides()
        val picks = (0 until hg.data.boxCount).flatMap { b -> (0 until 30).map { BoxSlot(b, it) } }
            .filter { val m = hg.data.mon(it); !m.isEmpty && PokeTransfer.blocker(m, hg.game) == null }
        val inPc = (0 until hg.data.boxCount).sumOf { b -> hg.data.boxMons(b).count { !it.isEmpty } }
        println("HeartGold's PC: $inPc Pokémon, ${picks.size} the Lab would take")
        assumeTrue(picks.size >= 6)
        val six = picks.take(6)
        val before = six.map { hg.data.mon(it) }
        val to = PokeTransfer.transfer(hg, "HEARTGOLD", six, black, "BLACK", today)
        val built = black.build()
        System.getenv("POKE_TRANSFER_OUT")?.let { File(it).writeBytes(built) }
        System.getenv("POKE_TRANSFER_HG_OUT")?.let { File(it).writeBytes(hg.build()) }
        println("Arrived at " + to.joinToString { "box ${it.box + 1} slot ${it.slot + 1}" })
        // The file reads back: its checksums hold, the backup copy is the main one.
        val back = (Gen5SaveFile.parse(built) as Gen5SaveFile.Parsed.Ok).save
        val l = back.layout
        assertArrayEquals(built.copyOfRange(0, l.size), built.copyOfRange(l.size, 2 * l.size))
        for (n in 0 until Gen5Layout.BOXES) {
            val o = Gen5Layout.FIRST_BOX + n * Gen5Layout.BOX_STRIDE
            val c = Gen4SaveFile.crc16(built, o, Gen5Layout.BOX_LEN)
            assertEquals(c, u16(built, o + Gen5Layout.BOX_LEN + 2))
            assertEquals(c, u16(built, l.checksums + 2 * (n + 1)))
        }
        assertEquals(Gen4SaveFile.crc16(built, l.dex, l.dexLen), u16(built, l.checksums + 2 * l.dexIndex))
        val data = Gen5SaveData(black.game, back.data)
        for ((at, was) in to.zip(before)) {
            val now = data.mon(at)
            assertFalse(now.isBadEgg)
            assertEquals(was.species, now.species)
            assertEquals(was.personality, now.personality)
            assertEquals(was.exp, now.exp)
            assertEquals(was.ivs.toList(), now.ivs.toList())
            assertEquals(was.otName, now.otName)
            assertEquals(was.nickname, now.nickname)
            assertEquals(was.personality % 25, now.nature.toLong())
            assertEquals(PokeTransfer.MET_LOCATION, now.metLocation)
            assertEquals(was.level, now.metLevel)
            assertEquals(70, now.friendship)
            assertEquals(0, now.heldItem)
            assertTrue(black.caughtInDex(now.species))
        }
        // They left HeartGold, which still reads, their items in its bag.
        six.forEach { assertTrue(hg.data.mon(it).isEmpty) }
        val g4 = (Gen4SaveFile.parse(hg.build()) as Gen4SaveFile.Parsed.Ok).save
        assertNotNull(g4)
    }

    @Test
    fun theLabsRulesHold() {
        val (hg, black) = sides()
        // An HM mover, an egg: refused before anything changes.
        val all = (0 until hg.data.boxCount).flatMap { b -> (0 until 30).map { BoxSlot(b, it) } }.filter { !hg.data.mon(it).isEmpty }
        val hm = all.firstOrNull { PokeTransfer.blocker(hg.data.mon(it), hg.game) != null }
        val ok = all.filter { PokeTransfer.blocker(hg.data.mon(it), hg.game) == null }
        assumeTrue(hm != null && ok.size >= 5)
        try {
            PokeTransfer.transfer(hg, "HEARTGOLD", ok.take(5) + hm!!, black, "BLACK", today)
            throw AssertionError("transferred")
        } catch (_: SaveEditRefused) {}
        assertFalse(hg.changed)
        assertFalse(black.changed)
    }
}
