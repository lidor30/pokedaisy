package com.pokedaisy.app.companion.data

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.io.RandomAccessFile
import java.util.zip.CRC32

/**
 * [RetailEngine] over the ROMs at hand (each case skips when its ROM isn't there): the retail builds prove themselves,
 * the hacks edited in place prove retail's RAM and rebuild the EXPAND rows we made for them by hand, every rebuild of
 * the game is refused.
 */
class RetailEngineTest {
    private val wideRows: Map<Int, IntArray> by lazy {
        val f = listOf(File("src/main/cpp/pk_wide_games.h"), File("app/src/main/cpp/pk_wide_games.h")).first { it.isFile }
        Regex("""\{ 0x([0-9a-f]{8}), PK_WIDE_FAMILY_\w+, \{ ([^}]*)\}""").findAll(f.readText()).associate { m ->
            m.groupValues[1].toLong(16).toInt() to
                m.groupValues[2].split(',').map { it.trim() }.filter { it.isNotEmpty() }.map { v -> (if (v.startsWith("-")) -1 else 1) * v.removePrefix("-").removePrefix("0x").toLong(16).toInt() }.toIntArray()
        }
    }

    private class Rom(val file: File) {
        fun proof(): RetailEngine.Proof? = RandomAccessFile(file, "r").use { raf ->
            val head = ByteArray(0xC0).also { raf.readFully(it) }
            // Ultra Violet is FireRed 1.0 under a game code of its own.
            val code = String(head, 0xAC, 4, Charsets.ISO_8859_1).let { if (it == "PUVV") "BPRE" else it }
            RetailEngine.check(code, head[0xBC].toInt() and 0xFF) { off, len ->
                if (off < 0 || off >= raf.length()) null
                else ByteArray(minOf(len.toLong(), raf.length() - off).toInt()).also { raf.seek(off.toLong()); raf.readFully(it) }
            }
        }

        val crc: Int by lazy { CRC32().apply { update(file.readBytes()) }.value.toInt() }
    }

    private fun rom(name: String): Rom {
        val f = File(RETAIL_ROM_DIR, name)
        assumeTrue("$name not here", f.isFile)
        return Rom(f)
    }

    private fun isAddress(v: Int): Boolean {
        val u = v.toLong() and 0xFFFFFFFFL
        return u in 0x02000000L until 0x02040000L || u in 0x03000000L until 0x03008000L || u in 0x08000000L until 0x0A000000L
    }

    @Test fun retailProvesItself() {
        for (name in listOf(
            "Pokemon - FireRed Version (USA).gba", "Pokemon - FireRed Version (USA, Europe) (Rev 1).gba",
            "Pokemon - Emerald Version (USA, Europe).gba",
        )) {
            val r = rom(name)
            val p = r.proof()
            assertNotNull(name, p)
            assertTrue("$name: ${p!!.why}", p.ramProven)
            assertEquals(name, p.build.retailCrc, r.crc)
            assertArrayEquals(name, p.build.row, p.wideRow)
        }
    }

    /** Retail edited in place, whose hand-made row is retail's row with its own tables (or retail's own). */
    @Test fun inPlaceHacksRebuildTheirRows() {
        val sameRow = listOf(
            "Pokemon Emerald Kaizo (v2.0).gba", "Pokemon Glazed (v9.2.0).gba",
            "Pokemon Ghost Grey (v1.0.1).gba", "Pokemon Ghost Grey (v1.0.2).gba", "Pokemon Ghost Grey (v1.0.4).gba",
        )
        // Their own rows draw more (16-bit graphics ids, the people's tables they moved behind hooks): only the address
        // slots are compared, and an optional one may come out 0 (the far-off people not drawn).
        val ownTables = listOf(
            "Pokemon Ultra Violet (v1.22).gba", "Pokemon FireRed Rocket Edition.gba", "Pokemon Ash Gray (v4.5.3).gba",
            "Pokemon Orange Islands.gba", "Pokemon Orange Islands (Beta 5.7).gba", "Pokemon Let's Go Pikachu.gba",
            "Pokemon Let's Go Eevee.gba",
        )
        // CFRU's: their battle code rewritten in places, so a global may keep too few retail users to be proven (Odyssey's
        // move cursor: 11 of 16). Refusing is safe; what they do prove must be right.
        val cfru = listOf(
            "Pokemon Odyssey (v4.1.1).gba", "Pokemon Radical Red (v4.1).gba", "Pokemon Sword and Shield Ultimate Plus (v1.2.1.2).gba",
            "Pokemon Gaia (v3.2).gba", "Pokemon Amethyst (v1.4.1).gba", "Pokemon GS Chronicles (v2.7.6).gba",
        )
        var checked = 0
        for (name in sameRow + ownTables + cfru) {
            val f = File(RETAIL_ROM_DIR, name)
            if (!f.isFile) continue
            val r = Rom(f)
            val p = r.proof()!!
            if (name in cfru && !p.ramProven) continue
            assertTrue("$name: ${p.why}", p.ramProven)
            assertNotNull("$name: ${p.why}", p.wideRow)
            val row = p.wideRow!!
            val hand = wideRows[r.crc] ?: error("$name has no row in pk_wide_games.h")
            if (name in sameRow) assertArrayEquals(name, hand, row)
            else for (i in row.indices) {
                if (!isAddress(p.build.row[i])) continue
                val ok = row[i] == hand[i] || (i in p.build.optionalSlots && row[i] == 0)
                assertTrue("$name slot $i: %08X, its row %08X".format(row[i], hand[i]), ok)
            }
            checked++
        }
        assumeTrue("no in-place hack here", checked > 0)
    }

    @Test fun randomizedRetailIsRetail() {
        for (name in listOf(
            "Pokemon FireRed Randomized (v4.6.1) (Rev 0, seed 19756451470906).gba",
            "Pokemon Emerald Randomized (v4.6.1) (seed 13578136444066).gba",
        )) {
            val f = File(RETAIL_ROM_DIR, name)
            if (!f.isFile) continue
            val p = Rom(f).proof()!!
            assertTrue(name, p.ramProven)
            assertArrayEquals(name, p.build.row, p.wideRow)
        }
    }

    /** Rebuilds of the game (their own RAM) and Unbound (CFRU moved a global it loads three times). */
    @Test fun rebuildsAreRefused() {
        var checked = 0
        for (name in listOf(
            "Pokemon Emerald Legacy (v1.1.4).gba", "Pokemon Recharged Emerald (v2.2.8).gba", "Pokemon Recharged Yellow (v1.9.7).gba",
            "Pokemon FireRed QoL (2026-09-27 build).gba", "Pokemon Monomyth (v1.1).gba", "Pokemon Elite Redux (2.65.4.1b).gba",
            "Pokemon Emerald Seaglass (v3.0).gba", "Pokemon Lazarus (v2.0).gba", "Pokemon SoulGold (v1.2.1).gba",
            "Pokemon Celia's Stupid Romhack (v1.1.4).gba", "Super Mariomon (v1.5.2 Anniversary).gba", "Pokemon Unbound (v2.1.1.1).gba",
        )) {
            val f = File(RETAIL_ROM_DIR, name)
            if (!f.isFile) continue
            val p = Rom(f).proof()!!
            assertFalse(name, p.ramProven)
            assertNull(name, p.wideRow)
            checked++
        }
        assumeTrue("no rebuild here", checked > 0)
    }

    @Test fun otherCodesHaveNoBuild() {
        assertNull(RetailEngine.check("AXVE", 1) { _, _ -> null })
        assertNull(RetailEngine.check("BPRE", 2) { _, _ -> null })
    }

    @Test fun nothingReadIsNoProof() {
        val p = RetailEngine.check("BPEE", 0) { _, _ -> null }!!
        assertFalse(p.ramProven)
    }

    @Test fun buildsParse() {
        for (b in RETAIL_ENGINE_BUILDS) {
            assertTrue(b.id, b.ramSites.isNotEmpty() && b.romSites.isNotEmpty())
            assertTrue(b.id, b.ramSites.any { it.first == b.actionCursor } && b.ramSites.any { it.first == b.partyMenu })
            assertTrue(b.id, b.romSites.all { it.first in b.row.indices })
        }
    }
}
