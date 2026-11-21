package com.pokedaisy.app.companion.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Crystal's PC moves (Gen2BoxSwap.kt): their code in both carts (skipped without them), and the calls on a stand-in game
 * that does what SendGetMonIntoFromBox / RemoveMonFromPartyOrBox do once the loop comes by again - the order, the
 * arguments, the checks and every refusal before a call.
 */
class Gen2BoxSwapTest {
    private val dir = File(File(RETAIL_ROM_DIR).parentFile, "gbc")

    @Test
    fun `the calls' code is in the English and the German cart, each only its own`() {
        val en = File(dir, "Pokemon - Crystal Version (USA, Europe) (Rev 1).gbc")
        val de = File(dir, "Pokemon - Kristall-Edition (Germany).gbc")
        assumeTrue("no Crystal carts", en.isFile && de.isFile)
        fun Gen2BoxCalls.on(f: File) = f.readBytes().let { rom -> codeMatches { off, len -> rom.copyOfRange(off.toInt(), (off + len).toInt()) } }
        assertTrue(GEN2_BOX_CALLS_CRYSTAL.on(en))
        assertTrue(GEN2_BOX_CALLS_CRYSTAL_DE.on(de))
        assertFalse(GEN2_BOX_CALLS_CRYSTAL.on(de))
        assertFalse(GEN2_BOX_CALLS_CRYSTAL_DE.on(en))
        assertTrue(GbField.Crystal.boxCalls === GEN2_BOX_CALLS_CRYSTAL && GbField.CrystalDe.boxCalls === GEN2_BOX_CALLS_CRYSTAL_DE)
    }

    private val cfg = GEN2_CRYSTAL
    private val calls = GEN2_BOX_CALLS_CRYSTAL
    private val box = cartRam(1, 0xAD10)

    /** WRAM bank 1 and the box: a party and a box of Pokémon told apart by species and OT ID. */
    private class Game(val cfg: Gen2Config, val calls: Gen2BoxCalls) : GameCalls {
        val wram = ByteArray(0x1000)
        val sram = ByteArray(0x450)
        val sramTop = ByteArray(0x10)   // A000-A00F: sCheckValue1, sPlayerData's ID
        val saved = mutableListOf<Int>()   // bank 5 calls, in order
        var pending: Int? = null
        val ran = mutableListOf<Pair<Int, Int>>()
        var onField = true

        private fun at(addr: Long): Pair<ByteArray, Int> = when {
            addr >= cartRam(1, 0xA000) && (addr and 0xFFFF) < 0xA010 -> sramTop to (addr and 0xFFFF).toInt() - 0xA000
            addr >= cartRam(1, 0xA000) -> sram to (addr and 0xFFFF).toInt() - 0xAD10
            else -> wram to (addr and 0xFFFF).toInt() - 0xD000
        }
        operator fun get(addr: Long) = at(addr).let { (m, i) -> m[i].toInt() and 0xFF }
        operator fun set(addr: Long, v: Int) = at(addr).let { (m, i) -> m[i] = v.toByte() }
        override fun call(fn: Long, a0: Int, a1: Int, a2: Int, a3: Int) = -1L
        override fun read(addr: Long, len: Int) = ByteArray(len) { get(addr + it).toByte() }
        override fun write(addr: Long, bytes: ByteArray): Boolean { bytes.forEachIndexed { i, b -> set(addr + i, b.toInt()) }; return true }
        override fun rom(off: Long, len: Int): ByteArray? = null

        override fun gbCallAt(at: Int, fn: Int, a: Int, b: Int, hl: Int): Boolean {
            if (!onField) return false
            pending?.let { run(it) }
            assertEquals(calls.at, at)
            pending = null
            if (fn == 0x0008 && a == 5) saved += hl
            else if (fn == 0x0008) pending = hl.also { assertEquals(3, a) }
            return true
        }

        val partyCount get() = this[cfg.partyCount]
        val boxCount get() = this[cartRam(1, 0xAD10)]
        fun partyMon(i: Int) = read(cfg.partyMons + i * 0x30, 0x30)
        fun boxMon(i: Int) = read(cartRam(1, 0xAD10) + 0x16 + i * 32, 32)

        // The game's two routines, on wCurPartyMon and wPokemonWithdrawDepositParameter.
        private fun run(fn: Int) {
            val slot = this[calls.curPartyMon]
            val param = this[calls.withdrawDeposit]
            ran += fn to param
            if (fn == calls.send && param == 0) {          // box -> party's end
                val n = partyCount
                this[cfg.partyCount] = n + 1
                this[cfg.partyCount + 1 + n] = this[calls.curPartySpecies]
                this[cfg.partyCount + 2 + n] = 0xFF
                write(cfg.partyMons + n * 0x30, boxMon(slot) + ByteArray(16))
            } else if (fn == calls.send && param == 1) {   // party -> box's end
                val n = boxCount
                this[cartRam(1, 0xAD10)] = n + 1
                this[cartRam(1, 0xAD11) + n] = this[calls.curPartySpecies]
                this[cartRam(1, 0xAD12) + n] = 0xFF
                write(cartRam(1, 0xAD10) + 0x16 + n * 32, partyMon(slot).copyOf(32))
            } else if (fn == calls.remove && param == 1) { // out of the box
                val n = boxCount
                for (i in slot until n - 1) { this[cartRam(1, 0xAD11) + i] = this[cartRam(1, 0xAD12) + i]; write(cartRam(1, 0xAD10) + 0x16 + i * 32, boxMon(i + 1)) }
                this[cartRam(1, 0xAD10) + n] = 0xFF
                this[cartRam(1, 0xAD10)] = n - 1
            } else if (fn == calls.remove && param == 0) { // out of the party
                val n = partyCount
                for (i in slot until n - 1) { this[cfg.partyCount + 1 + i] = this[cfg.partyCount + 2 + i]; write(cfg.partyMons + i * 0x30, partyMon(i + 1)) }
                this[cfg.partyCount + n] = 0xFF
                this[cfg.partyCount] = n - 1
            }
        }

        fun mon(species: Int, ot: Int, hp: Int = 20, item: Int = 0) = ByteArray(0x30).also {
            it[0] = species.toByte(); it[1] = item.toByte(); it[6] = (ot shr 8).toByte(); it[7] = ot.toByte()
            it[0x22] = (hp shr 8).toByte(); it[0x23] = hp.toByte()
        }

        fun party(vararg mons: ByteArray) {
            this[cfg.partyCount] = mons.size
            mons.forEachIndexed { i, m -> this[cfg.partyCount + 1 + i] = m[0].toInt() and 0xFF; write(cfg.partyMons + i * 0x30, m) }
            this[cfg.partyCount + 1 + mons.size] = 0xFF
        }

        fun box(vararg mons: ByteArray) {
            this[cartRam(1, 0xAD10)] = mons.size
            mons.forEachIndexed { i, m -> this[cartRam(1, 0xAD11) + i] = m[0].toInt() and 0xFF; write(cartRam(1, 0xAD10) + 0x16 + i * 32, m.copyOf(32)) }
            this[cartRam(1, 0xAD11) + mons.size] = 0xFF
        }
    }

    private fun Game.partySpecies() = (0 until partyCount).map { partyMon(it)[0].toInt() and 0xFF }
    private fun Game.boxSpecies() = (0 until boxCount).map { boxMon(it)[0].toInt() and 0xFF }

    @Test
    fun `WITHDRAW - copied into the party, then taken out of the box`() {
        val g = Game(cfg, calls)
        g.party(g.mon(152, 1))
        g.box(g.mon(16, 2), g.mon(19, 3))
        val key = gen2MonKey(g.boxMon(1), 0)
        assertEquals(BoxSwapResult.SWAPPED, calls.withdraw(g, cfg, 1, key))
        assertEquals(listOf(calls.send to 0, calls.remove to 1), g.ran)
        assertEquals(listOf(152, 19), g.partySpecies())
        assertEquals(listOf(16), g.boxSpecies())
    }

    @Test
    fun `DEPOSIT - copied into the box, then taken out of the party`() {
        val g = Game(cfg, calls)
        g.party(g.mon(152, 1), g.mon(25, 4))
        g.box(g.mon(16, 2))
        assertEquals(BoxSwapResult.SWAPPED, calls.deposit(g, cfg, 0, gen2MonKey(g.partyMon(0), 0)))
        assertEquals(listOf(calls.send to 1, calls.remove to 0), g.ran)
        assertEquals(listOf(25), g.partySpecies())
        assertEquals(listOf(16, 152), g.boxSpecies())
    }

    @Test
    fun `SWAP - a full party's Pokémon into the box, then the box's out`() {
        val g = Game(cfg, calls)
        g.party(*Array(6) { g.mon(10 + it, it) })
        g.box(g.mon(16, 20), g.mon(19, 21))
        val r = calls.swap(g, cfg, 0, gen2MonKey(g.boxMon(0), 0), 2, gen2MonKey(g.partyMon(2), 0))
        assertEquals(BoxSwapResult.SWAPPED, r)
        assertEquals(listOf(10, 11, 13, 14, 15, 16), g.partySpecies())
        assertEquals(listOf(19, 12), g.boxSpecies())
    }

    @Test
    fun `refused before any call`() {
        val g = Game(cfg, calls)
        g.party(*Array(6) { g.mon(10 + it, it) })
        g.box(g.mon(16, 20))
        assertEquals(BoxSwapResult.PARTY_FULL, calls.withdraw(g, cfg, 0, gen2MonKey(g.boxMon(0), 0)))
        assertEquals(BoxSwapResult.CHANGED, calls.withdraw(g, cfg, 0, 12345L))
        assertEquals(BoxSwapResult.CHANGED, calls.withdraw(g, cfg, 1, gen2MonKey(g.boxMon(0), 0)))

        g.party(g.mon(152, 1, item = 0xB5), g.mon(25, 4))            // SURF_MAIL
        assertEquals(BoxSwapResult.HOLDS_MAIL, calls.deposit(g, cfg, 0, gen2MonKey(g.partyMon(0), 0)))

        g.party(g.mon(152, 1), g.mon(25, 4, hp = 0))                  // the other one has fainted
        assertEquals(BoxSwapResult.LAST_MON, calls.deposit(g, cfg, 0, gen2MonKey(g.partyMon(0), 0)))
        g.party(g.mon(152, 1))
        assertEquals(BoxSwapResult.LAST_MON, calls.deposit(g, cfg, 0, gen2MonKey(g.partyMon(0), 0)))
        g.party(g.mon(152, 1), g.mon(0xFD, 4).also { it[0] = 25 })    // the other one is an egg
        g[cfg.partyCount + 2] = 0xFD
        assertEquals(BoxSwapResult.LAST_MON, calls.deposit(g, cfg, 0, gen2MonKey(g.partyMon(0), 0)))

        g.party(g.mon(152, 1), g.mon(25, 4))
        g.box(*Array(20) { g.mon(16, 100 + it) })
        assertEquals(BoxSwapResult.BOX_FULL, calls.deposit(g, cfg, 0, gen2MonKey(g.partyMon(0), 0)))
        assertEquals(emptyList<Pair<Int, Int>>(), g.ran)
    }

    @Test
    fun `off the field the loop never comes by - NOT_NOW, nothing written`() {
        val g = Game(cfg, calls)
        g.party(g.mon(152, 1))
        g.box(g.mon(16, 2))
        g.onField = false
        assertEquals(BoxSwapResult.NOT_NOW, calls.withdraw(g, cfg, 0, gen2MonKey(g.boxMon(0), 0)))
        assertEquals(1, g.partyCount)
        assertEquals(1, g.boxCount)
    }

    @Test
    fun `CHANGE BOX - SaveBox, the new wCurBox, LoadBox, then the save, only on this player's save`() {
        val g = Game(cfg, calls)
        g[calls.curBox] = 2
        g[calls.saveCheck] = 99
        g[calls.playerId] = 0xEC; g[calls.playerId + 1] = 0x78
        g[calls.savedPlayerId] = 0xEC; g[calls.savedPlayerId + 1] = 0x77
        assertEquals(BoxChangeResult.OTHER_SAVE, calls.changeBox(g, 7))
        g[calls.saveCheck] = 0; g[calls.savedPlayerId + 1] = 0x78
        assertEquals(BoxChangeResult.OTHER_SAVE, calls.changeBox(g, 7))   // never saved
        g[calls.saveCheck] = 99
        assertEquals(BoxChangeResult.FAILED, calls.changeBox(g, 14))
        assertEquals(emptyList<Int>(), g.saved)
        assertEquals(BoxChangeResult.CHANGED, calls.changeBox(g, 7))
        assertEquals(listOf(calls.saveBox, calls.loadBox, calls.saveGame), g.saved)
        assertEquals(7, g[calls.curBox])
        g.onField = false
        assertEquals(BoxChangeResult.NOT_NOW, calls.changeBox(g, 3))
        assertEquals(7, g[calls.curBox])
    }

    @Test
    fun `the PC's moves wait where the bag's PC would - not while a script runs`() {
        val g = Game(cfg, calls)
        g[wramx(1, 0xD438)] = 1   // wScriptRunning
        assertEquals(BoxSwapResult.NOT_NOW, GbField.Crystal.boxMove(g) { _, _ -> BoxSwapResult.SWAPPED })
        g[wramx(1, 0xD438)] = 0
        assertEquals(BoxSwapResult.SWAPPED, GbField.Crystal.boxMove(g) { _, _ -> BoxSwapResult.SWAPPED })
        g[cfg.battleMode] = 1
        assertEquals(BoxSwapResult.NOT_NOW, GbField.Crystal.boxMove(g) { _, _ -> BoxSwapResult.SWAPPED })
    }
}
