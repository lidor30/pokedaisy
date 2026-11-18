package com.pokedaisy.app.companion.data.save

import com.pokedaisy.app.companion.data.NdsArt
import com.pokedaisy.app.companion.data.RETAIL_ROM_DIR
import java.io.File
import okio.Path.Companion.toOkioPath
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * The SAVE EDITOR on the player's own Black and Black 2 saves (skipped where they aren't on this machine): the party,
 * PC, bag, money and Pokédex read as the games show them, every edit written back with valid checksums and read again.
 * POKE_TRANSFER_EDIT_OUT=<file> writes the edited Black save for booting in nds_dump.
 */
class Gen5SaveTest {
    private val nds = File(File(RETAIL_ROM_DIR).parentFile, "nds")
    private val blackRom = File(nds, "Pokemon - Black Version (USA, Europe) (NDSi Enhanced).nds")
    private val black2Rom = File(nds, "Pokemon - Black Version 2 (USA, Europe) (NDSi Enhanced) [b].nds")
    private val blackSav = File(System.getProperty("user.home"), "Documents/RetroArch/saves/mGBA/Pokemon - Black Version (USA, Europe) (NDSi Enhanced).sav")
    private val black2Sav = File(nds, "Pokemon Black 2 Version.sav")

    private fun open(rom: File, sav: File): Pair<ByteArray, Gen5SaveEditor> {
        assumeTrue(rom.isFile && sav.isFile)
        val bytes = sav.readBytes()
        val game = NdsArt.readGen5SaveTables(rom.toOkioPath())!!
        val file = (Gen5SaveFile.parse(bytes) as Gen5SaveFile.Parsed.Ok).save
        assertEquals(game.layout, file.layout)
        return bytes to Gen5SaveEditor(file, game)
    }

    /** Every block an edit can touch holds its CRC in both places, in both copies, and the table's own CRC holds. */
    private fun assertChecksums(bytes: ByteArray, l: Gen5Layout) {
        for ((off, len, index) in l.editedBlocks) {
            val c = Gen4SaveFile.crc16(bytes, off, len)
            assertEquals("block $index", c, u16(bytes, off + len + 2))
            assertEquals("table $index", c, u16(bytes, l.checksums + 2 * index))
        }
        assertEquals(Gen4SaveFile.crc16(bytes, l.checksums, l.checksumsLen), u16(bytes, l.checksumsCrc))
        assertArrayEquals(bytes.copyOfRange(0, l.size), bytes.copyOfRange(l.size, 2 * l.size))
    }

    @Test
    fun blackReadsAsTheGameShowsIt() {
        val (bytes, ed) = open(blackRom, blackSav)
        assertArrayEquals(bytes, ed.build())
        assertNull(ed.data.mismatch())
        assertEquals(5, ed.data.partyCount)
        assertEquals(listOf(381, 245, 639, 521, 612), ed.data.partyMons.map { it.mon.species })
        // The party's stored levels agree with their EXP, and their stats with the game's formula.
        for (p in ed.data.partyMons) {
            assertEquals(p.mon.level, p.level)
            assertArrayEquals(ed.game.stats(p.mon, p.level), p.stats)
        }
        assertEquals(684_260L, ed.money)
        assertEquals("93:20", ed.playTime)
        assertEquals(PocketKind.ITEMS, ed.pockets.first().kind)
        assertEquals(75, ed.pocket(PocketKind.ITEMS)!!.stacks.size)
        assertEquals(9, ed.pocket(PocketKind.KEY)!!.stacks.size)
        assertEquals(ItemStack(328, 1), ed.pocket(PocketKind.TMHM)!!.stacks.first())
        assertEquals(468, ed.tmMove(328))   // TM01 HONE CLAWS
        assertTrue(ed.dex.national)
        assertTrue(ed.dex.caught >= 5)
        assertTrue(ed.data.partyMons.all { ed.caughtInDex(it.mon.species) })
        // Learnsets from the cart: a party Pokémon can relearn what it knows.
        val first = ed.data.partyMons[0].mon
        assertTrue(first.moves.filter { it != 0 }.any { it in ed.learnable(MonRef.Party(0)) })
    }

    @Test
    fun blackEditsWriteBackWithValidChecksums() {
        val (_, ed) = open(blackRom, blackSav)
        val l = ed.file.layout
        ed.setMoney(1_234_567)
        val party = MonRef.Party(1)
        val lv = ed.data.partyMons[1].level
        if (lv < 100) ed.setLevel(party, lv + 1)
        ed.setIvs(party, IntArray(6) { 31 })
        ed.setEvs(party, intArrayOf(252, 252, 4, 0, 0, 0))
        val before = ed.monAt(party)!!
        ed.reroll(party, nature = (before.nature + 1) % 25)
        // A Pokémon from the PC into the party, and the party one back.
        val pcAt = (0 until Gen5Layout.BOXES * Gen5Layout.BOX_SLOTS).map { BoxSlot(it / 30, it % 30) }
            .first { val m = ed.data.mon(it); !m.isEmpty && !m.isEgg && !m.isBadEgg }
        val pcMon = ed.data.mon(pcAt)
        ed.move(MonRef.Pc(pcAt), MonRef.Party(5))
        assertEquals(6, ed.data.partyCount)
        assertEquals(pcMon.personality, ed.data.partyMons[5].mon.personality)
        // A new item into MEDICINE and held.
        val potion = ed.addable(PocketKind.MEDICINE).first { it !in ed.pocket(PocketKind.MEDICINE)!!.stacks.map { s -> s.item } }
        ed.addItem(PocketKind.MEDICINE, potion, 3)
        val heldBefore = ed.monAt(MonRef.Party(5))!!.heldItem
        ed.setHeldItem(MonRef.Party(5), potion)
        ed.registerOwned()
        val built = ed.build()
        assertChecksums(built, l)
        System.getenv("POKE_TRANSFER_EDIT_OUT")?.let { File(it).writeBytes(built) }

        val back = Gen5SaveEditor((Gen5SaveFile.parse(built) as Gen5SaveFile.Parsed.Ok).save, ed.game)
        assertNull(back.data.mismatch())
        assertEquals(1_234_567L, back.money)
        val m = back.monAt(party)!!
        assertEquals((before.nature + 1) % 25, m.nature)
        assertEquals(before.personality, m.personality)
        assertTrue(m.ivs.all { it == 31 })
        assertEquals(508, m.evs.sum())
        if (lv < 100) assertEquals(lv + 1, back.data.partyMons[1].level)
        assertArrayEquals(back.game.stats(m, back.data.partyMons[1].level), back.data.partyMons[1].stats)
        assertEquals(potion, back.monAt(MonRef.Party(5))!!.heldItem)
        assertEquals(2, back.pocket(PocketKind.MEDICINE)!!.stacks.first { it.item == potion }.quantity)
        if (heldBefore != 0) assertTrue(back.pockets.flatMap { it.stacks }.any { it.item == heldBefore })
        assertTrue(back.data.mon(pcAt).isEmpty)
    }

    @Test
    fun blackRenamesRerollsAndSorts() {
        val (_, ed) = open(blackRom, blackSav)
        ed.renameBox(0, "SAVED")
        assertEquals("SAVED", ed.data.boxName(0))
        val own = ed.data.partyMons.withIndex().first { ed.isOwn(it.value.mon) }.index
        ed.setNickname(MonRef.Party(own), "Daisy")
        assertEquals("Daisy", ed.monAt(MonRef.Party(own))!!.nickname)
        val was = ed.monAt(MonRef.Party(own))!!
        ed.reroll(MonRef.Party(own), shiny = true)
        val now = ed.monAt(MonRef.Party(own))!!
        assertTrue(now.isShiny)
        assertEquals(was.gender, now.gender)
        assertEquals(was.nature, now.nature)
        assertEquals(was.ability, now.ability)
        ed.sortBox(0, BoxSort.DEX) { it.species.toString() }
        val species = ed.data.boxMons(0).filter { !it.isEmpty && !it.isEgg }.map { it.species }
        assertEquals(species.sorted(), species)
        assertChecksums(ed.build(), ed.file.layout)
    }

    @Test
    fun black2ReadsAndRebuilds() {
        val (bytes, ed) = open(black2Rom, black2Sav)
        assertEquals(Gen5Layout.B2W2, ed.file.layout)
        assertArrayEquals(bytes, ed.build())
        assertNull(ed.data.mismatch())
        assertEquals(7, ed.pocket(PocketKind.ITEMS)!!.stacks.size)
        assertEquals(301, ed.game.regional.count { it >= 0 })
        assertFalse(ed.pockets.isEmpty())
        ed.setMoney(5000)
        ed.markAllDex(caught = false)
        val built = ed.build()
        assertChecksums(built, ed.file.layout)
        val back = Gen5SaveEditor((Gen5SaveFile.parse(built) as Gen5SaveFile.Parsed.Ok).save, ed.game)
        assertEquals(5000L, back.money)
        assertEquals(301, back.dex.regionalSeen)
    }
}
