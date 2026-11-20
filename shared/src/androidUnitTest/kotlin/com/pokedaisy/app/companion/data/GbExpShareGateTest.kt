package com.pokedaisy.app.companion.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.security.MessageDigest

/**
 * EXP SHARE's Game Boy gate ([ExpShare.mark]): the participants byte is written only while the game's battle byte says a
 * battle is on. Gen 1's battle byte is taken as the byte before wPartyGainExpFlags (pokered's WRAM); every Gen 1 cart here
 * with EXP SHARE has its config's wIsInBattle there. Gen 2's comes from its config at run time.
 */
class GbExpShareGateTest {
    private val dirs = listOf("gbc", "gb").map { File(File(RETAIL_ROM_DIR).parentFile, it) }

    private fun sha1(b: ByteArray) = MessageDigest.getInstance("SHA-1").digest(b).joinToString("") { "%02x".format(it) }

    @Test
    fun `Gen 1's battle byte is its config's wIsInBattle, Gen 2's is left to the config`() {
        var gen1 = 0
        var gen2 = 0
        for (f in dirs.flatMap { it.listFiles()?.toList().orEmpty() }.filter { it.extension == "gb" || it.extension == "gbc" }) {
            val bytes = f.readBytes()
            val exp = GbGamePatches.find(bytes)?.exp ?: continue
            val cfg = TelemetrySampler.gen1ConfigFor(sha1(bytes))
            if (cfg != null) {
                // D000+ is read in WRAM bank 1 there, the bus elsewhere: a Gen 1 game never switches banks, so they agree.
                assertEquals(f.name, cfg.isInBattle and 0xFFFF, exp.battle and 0xFFFF)
                gen1++
            } else if (exp.battle == 0L) gen2++
        }
        assumeTrue("no Game Boy carts with EXP SHARE on this machine", gen1 + gen2 > 0)
        println("EXP SHARE gate: $gen1 Gen 1 carts checked, $gen2 left to their config")
    }

    @Test
    fun `nothing is written outside a battle, or when the battle byte isn't known`() {
        val ram = HashMap<Long, Byte>()
        val read: (Long, Int) -> ByteArray = { a, n -> ByteArray(n) { ram[a + it] ?: 0 } }
        val write: (Long, ByteArray) -> Boolean = { a, b -> b.forEachIndexed { i, v -> ram[a + i] = v }; true }
        val gen1 = ExpShare.Found(0x1D058, emptyList(), width = 1, battle = 0x1D057)
        ram[0x1D058] = 0x55
        ExpShare.mark(gen1, 0x3F, read, write)
        assertEquals(0x55.toByte(), ram[0x1D058])                     // on the field: the byte is someone else's
        ram[0x1D057] = 1
        ExpShare.mark(gen1, 0x3F, read, write)
        assertEquals(0x3F.toByte(), ram[0x1D058])                     // a wild battle
        ram[0x1D057] = 0xFF.toByte()
        ram[0x1D058] = 0x01
        ExpShare.mark(gen1, 0x3F, read, write)
        assertEquals(0x01.toByte(), ram[0x1D058])                     // lost (0xFF): not a battle any more
        // Gen 2: the byte from the config, none = nothing.
        val gen2 = ExpShare.Found(0xC664, emptyList(), width = 1)
        ram[0xC664] = 0x11
        ExpShare.mark(gen2, 0x3F, read, write)
        assertEquals(0x11.toByte(), ram[0xC664])
        ram[0x1D22D] = 2
        ExpShare.mark(gen2, 0x3F, read, write, gbBattle = 0x1D22D)
        assertEquals(0x3F.toByte(), ram[0xC664])                      // a trainer battle
        // The GBA's two bytes: no gate.
        val gba = ExpShare.Found(0x0202420C, emptyList())
        ExpShare.mark(gba, 0x07, read, write)
        assertTrue(ram[0x0202420C] == 0x07.toByte() && ram[0x0202420D] == 0x07.toByte())
        assertFalse(gba.ds)
    }
}
