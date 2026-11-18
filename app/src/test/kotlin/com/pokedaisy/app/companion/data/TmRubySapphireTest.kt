package com.pokedaisy.app.companion.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/** TMs from the bag on Ruby / Sapphire ([tmFnsFor]'s TM_RUBY* / TM_SAPPHIRE*, [tmBagReturnWatch]). */
class TmRubySapphireTest {
    @Test fun eachCartGetsItsOwnTable() {
        assertSame(TM_RUBY, tmFnsFor(GameKind.EMERALD, "AXVE", 1, hack = false))
        assertSame(TM_RUBY, tmFnsFor(GameKind.EMERALD, "AXVE", 2, hack = false))
        assertSame(TM_RUBY_REV0, tmFnsFor(GameKind.EMERALD, "AXVE", 0, hack = false))
        assertSame(TM_SAPPHIRE, tmFnsFor(GameKind.EMERALD, "AXPE", 1, hack = false))
        assertSame(TM_SAPPHIRE_REV0, tmFnsFor(GameKind.EMERALD, "AXPE", 0, hack = false))
    }

    @Test fun theirCodeIsWhereTheTablesSay() {
        val dir = File(RETAIL_ROM_DIR)
        var n = 0
        for ((rom, tm) in listOf(
            "Pokemon - Ruby Version (USA, Europe).gba" to TM_RUBY_REV0,
            "Pokemon - Ruby Version (USA, Europe) (Rev 1).gba" to TM_RUBY,
            "Pokemon - Ruby Version (USA, Europe) (Rev 2).gba" to TM_RUBY,
            "Pokemon - Sapphire Version (USA, Europe).gba" to TM_SAPPHIRE_REV0,
            "Pokemon - Sapphire Version (USA, Europe) (Rev 1).gba" to TM_SAPPHIRE,
            "Pokemon - Sapphire Version (USA, Europe) (Rev 2).gba" to TM_SAPPHIRE,
        )) {
            val bytes = File(dir, rom).takeIf { it.isFile }?.readBytes() ?: continue
            assertTrue(rom, tmCodeMatches(tm) { a, len -> bytes.copyOfRange((a and 0x1FFFFFFL).toInt(), (a and 0x1FFFFFFL).toInt() + len) })
            n++
        }
        assumeTrue("no Ruby / Sapphire here", n > 0)
    }

    @Test fun theBagsReturnBecomesTheFieldUntilTheFieldRuns() {
        val ram = HashMap<Long, Byte>()
        val g = object : GameCalls {
            override fun call(fn: Long, a0: Int, a1: Int, a2: Int, a3: Int) = 0L
            override fun read(addr: Long, len: Int) = ByteArray(len) { ram[addr + it] ?: 0 }
            override fun write(addr: Long, bytes: ByteArray): Boolean { bytes.forEachIndexed { i, b -> ram[addr + i] = b }; return true }
        }
        fun cb(v: Long) = g.write(TM_RUBY.mainCallback2, ByteArray(4) { (v shr (8 * it)).toByte() })
        fun cbNow() = (0..3).fold(0L) { a, i -> a or ((ram[TM_RUBY.mainCallback2 + i]!!.toLong() and 0xFF) shl (8 * i)) }
        cb(0x0806AEFDL)   // the party menu running: keep watching, nothing written
        assertTrue(tmBagReturnWatch(TM_RUBY, g))
        assertEquals(0x0806AEFDL, cbNow())
        cb(TM_RUBY.bagReturn or 1)   // its exit, back to the bag: the field instead, and done
        assertFalse(tmBagReturnWatch(TM_RUBY, g))
        assertEquals(TM_RUBY.returnToField or 1, cbNow())
        cb(TM_RUBY.overworld or 1)   // the field's own: done, nothing written
        assertFalse(tmBagReturnWatch(TM_RUBY, g))
    }
}
