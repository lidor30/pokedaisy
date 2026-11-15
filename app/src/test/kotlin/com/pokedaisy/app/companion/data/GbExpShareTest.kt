package com.pokedaisy.app.companion.data

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * EXP SHARE on the Game Boy ([ExpShare.gbFind]): on each retail cart (the player's own, in `gb/` or `gbc/` beside the GBA ROM
 * folder; each skipped where it isn't here) the division and flags pret's symbols name - pokeyellow / pokered's
 * DivideExpDataByNumMonsGainingExp and wPartyGainExpFlags, pokegold / pokecrystal's .EvenlyDivideExpAmongParticipants and
 * wBattleParticipantsNotFainted - with the battle byte the cart's config reads; and nothing on a changed cart.
 */
class GbExpShareTest {
    /** Cart, its division's file offset (bank * 0x4000 + address - 0x4000), its flags, its battle byte. */
    private class Want(val file: String, val divide: Long, val flags: Long, val battle: Long)

    private val carts = listOf(
        Want("Pokemon - Red Version (USA, Europe) (SGB Enhanced).gb", 0x15L * 0x4000 + 0x146C, 0xD058, GEN1_RED.isInBattle),
        Want("Pokemon - Blue Version (USA, Europe) (SGB Enhanced).gb", 0x15L * 0x4000 + 0x146C, 0xD058, GEN1_BLUE.isInBattle),
        Want("Pokemon - Yellow Version - Special Pikachu Edition (USA, Europe) (CGB+SGB Enhanced).gb", 0x15L * 0x4000 + 0x147B, 0xD057, GEN1_YELLOW.isInBattle),
        Want("Pokemon - Gold Version (USA, Europe) (SGB Enhanced) (GB Compatible).gbc", 0x0FL * 0x4000 + 0x2EFD, 0xCB42, GEN2_GOLD.battleMode),
        Want("Pokemon - Silver Version (USA, Europe) (SGB Enhanced) (GB Compatible).gbc", 0x0FL * 0x4000 + 0x2EFD, 0xCB42, GEN2_SILVER.battleMode),
        Want("Pokemon - Crystal Version (USA, Europe) (Rev 1).gbc", 0x0FL * 0x4000 + 0x30D4, 0xC664, GEN2_CRYSTAL.battleMode),
    )

    private fun sha1(b: ByteArray) =
        java.security.MessageDigest.getInstance("SHA-1").digest(b).joinToString("") { "%02x".format(it) }

    private fun cart(name: String): File? {
        val root = File(RETAIL_ROM_DIR).parentFile ?: return null
        return listOf("gb", "gbc").map { File(root, "$it/$name") }.firstOrNull { it.isFile }
    }

    @Test
    fun `each retail cart's division and flags are pret's`() {
        var ran = 0
        for (w in carts) {
            val rom = cart(w.file)?.readBytes() ?: continue
            ran++
            val f = ExpShare.gbFind(rom, sha1(rom))
            assertNotNull(w.file, f)
            f!!
            assertTrue(w.file, f.gb)
            assertEquals(w.file, w.flags, f.sentPokes)
            assertEquals(w.file, w.battle, f.battle)
            val patch = f.full.single()
            assertEquals(w.file, w.divide, patch.addr)
            assertArrayEquals(w.file, byteArrayOf(0xFA.toByte(), w.flags.toByte(), (w.flags shr 8).toByte()), patch.expect)
            assertArrayEquals(w.file, byteArrayOf(0xC9.toByte(), 0, 0), patch.value)
            // A changed cart (another hash) gets nothing: its RAM and code aren't known.
            val changed = rom.copyOf().also { it[0x150] = (it[0x150] + 1).toByte() }
            assertNull(w.file, ExpShare.gbFind(changed, sha1(changed)))
        }
        assumeTrue("no Game Boy carts here", ran > 0)
    }

    @Test
    fun `SPLIT's mask leaves fainted Pokemon and eggs out on the Game Boy`() {
        fun mon(hp: Int, egg: Boolean = false) = MonView(25, "", 10, hp, 30, "", emptyList(), null, isEgg = egg)
        val party = listOf(mon(20), mon(0), mon(5), mon(5, egg = true))
        assertEquals(0b0101, ExpShare.maskOf(party, gb = true))
        assertEquals(0b0111, ExpShare.maskOf(party))
    }

    @Test
    fun `the flags are written only in a battle, and only when they differ`() {
        val f = ExpShare.Found(0xD058, emptyList(), gb = true, battle = 0xD057)
        val ram = HashMap<Long, Byte>()
        val writes = ArrayList<Long>()
        val read = { a: Long, n: Int -> ByteArray(n) { ram[a + it] ?: 0 } }
        val write = { a: Long, b: ByteArray -> writes += a; b.forEachIndexed { i, v -> ram[a + i] = v }; true }
        ExpShare.mark(f, 0b111, read, write)
        assertTrue("written outside a battle", writes.isEmpty())
        ram[0xD057] = 1
        ExpShare.mark(f, 0b111, read, write)
        assertEquals(listOf(0xD058L), writes)
        assertEquals(0b111.toByte(), ram[0xD058])
        ExpShare.mark(f, 0b111, read, write)
        assertEquals("written again though unchanged", 1, writes.size)
        ram[0xD057] = 0xFF.toByte()   // lost: the battle's over
        ram[0xD058] = 0
        ExpShare.mark(f, 0b111, read, write)
        assertEquals(1, writes.size)
    }
}
