package com.pokedaisy.app.cheats

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Cheat files and codes (Cheats.kt). mGBA's own code check is native: the
 * headless half is native-capture/mgba_dump's `cheat` command. */
class CheatsTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test
    fun `libretro cht with Windows line endings`() {
        // RetroArch's newer layout: quoted values, its own extra keys, CRLF.
        val cht = listOf(
            "cheats = 3", "",
            "cheat0_desc = \"Infinite Money\"",
            "cheat0_code = \"82025838+FFFF+82025840 0001\"",
            "cheat0_enable = \"true\"",
            "cheat0_address = \"0\"", "",
            "cheat1_desc = \"Master Code\"",
            "cheat1_code = \"000014D1 000A+1003DBB8 0007\"",
            "cheat1_enable = false", "",
            "cheat2_desc = \"No code\"",
        ).joinToString("\r\n")
        val cheats = CheatFiles.parse(cht, "fallback")
        assertEquals(2, cheats.size)
        assertEquals(Cheat("Infinite Money", listOf("82025838 FFFF", "82025840 0001"), "", true), cheats[0])
        assertEquals(Cheat("Master Code", listOf("000014D1 000A", "1003DBB8 0007"), "", false), cheats[1])
    }

    @Test
    fun `mgba cheats - names, disabled, directives until reset`() {
        val text = """
            !GSAv1
            # Walk through walls
            12345678 9ABCDEF0
            !disabled
            # Second
            0FEDCBA9 87654321
            !reset
            # Plain
            82025838 FFFF
        """.trimIndent()
        val cheats = CheatFiles.parse(text, "fallback")
        assertEquals(
            listOf(
                Cheat("Walk through walls", listOf("12345678 9ABCDEF0"), "GSAv1", true),
                Cheat("Second", listOf("0FEDCBA9 87654321"), "GSAv1", false),
                Cheat("Plain", listOf("82025838 FFFF"), "", true),
            ),
            cheats,
        )
    }

    @Test
    fun `bare codes are one cheat named after the file`() {
        val cheats = CheatFiles.parse("82025838 FFFF\n82025840:01\n", "Money")
        assertEquals(listOf(Cheat("Money", listOf("82025838 FFFF", "82025840:01"), "", true)), cheats)
    }

    @Test
    fun `write and parse round trip`() {
        val cheats = listOf(
            Cheat("A", listOf("82025838 FFFF"), "", true),
            Cheat("B\nC", listOf("12345678 9ABCDEF0", "0FEDCBA9 87654321"), "PARv3 raw", false),
            Cheat("D", listOf("3203F004 00AB"), "", false),
        )
        val back = CheatFiles.parse(CheatFiles.write(cheats), "x")
        assertEquals(cheats.map { it.copy(name = it.name.replace('\n', ' ')) }, back)
        // The core only ever gets the enabled ones.
        assertEquals("!reset\n# A\n82025838 FFFF\n", CheatFiles.coreText(cheats))
        assertEquals("", CheatFiles.coreText(cheats.map { it.copy(enabled = false) }))
    }

    @Test
    fun `typed codes split like mGBA's libretro core`() {
        assertEquals(listOf("82025838 FFFF"), CheatCodes.normalize("82025838 ffff"))
        assertEquals(listOf("82025838 FFFF", "12345678 9ABCDEF0"), CheatCodes.normalize("82025838FFFF\n123456789abcdef0"))
        assertEquals(listOf("12345678 9ABCDEF0", "0FEDCBA9 87654321"), CheatCodes.normalize("12345678 9ABCDEF0+0FEDCBA9 87654321"))
        assertEquals(listOf("02025838:FF"), CheatCodes.normalize("  02025838:ff  "))
        // A line with something else in it stays whole, so mGBA's check can say which line.
        assertEquals(listOf("1234 NOTHEX", "82025838 FFFF"), CheatCodes.normalize("1234 NOTHEX\n82025838 FFFF"))
        assertEquals(listOf("82025838 FFFF", "82025840 0001"), CheatCodes.normalize("82025838 FFFF 82025840 0001"))
        assertTrue(CheatCodes.normalize(" \n + ").isEmpty())
    }

    @Test
    fun `store keeps a ROM's cheats by CRC`() {
        val store = CheatStore.forCrc(tmp.root, "deadbeef")
        assertEquals(emptyList<Cheat>(), store.load())
        val list = listOf(Cheat("Money", listOf("82025838 FFFF"), "", true), Cheat("Off", listOf("1003DBB8 0007"), "", false))
        store.save(list)
        assertEquals("deadbeef.cheats", store.file.name)
        assertEquals(list, CheatStore.forCrc(tmp.root, "deadbeef").load())
        store.save(emptyList())
        assertTrue(!store.file.exists())
    }

    @Test
    fun `DS codes - two 8-digit words a line, checked without mGBA, sent as words per cheat`() {
        val lines = CheatCodes.normalize("94000130 fcff0000\n02000000000003e7 d2000000 00000000")
        assertEquals(listOf("94000130 FCFF0000", "02000000 000003E7", "D2000000 00000000"), lines)
        val ok = CheatCheck.checkDs(Cheat("MONEY", lines))
        assertEquals(lines, ok.cheat?.lines)
        assertTrue(ok.bad.isEmpty())
        // A GBA-style line (8 + 4 digits) isn't an Action Replay DS line.
        val bad = CheatCheck.check(Cheat("X", listOf("82025838 FFFF")), ds = true)
        assertEquals(null, bad.cheat)
        assertEquals(listOf("82025838 FFFF"), bad.bad)

        val off = Cheat("OFF", listOf("12345678 00000001"), enabled = false)
        val text = CheatFiles.write(listOf(ok.cheat!!.copy(enabled = true), off))
        val w = DsCheats.fromText(CheatFiles.coreText(CheatFiles.parseMgba(text.lines(), "CHEAT")))
        assertEquals(listOf(6), w.lengths.toList())
        assertEquals(0x94000130.toInt(), w.words[0])
        assertEquals(0xFCFF0000.toInt(), w.words[1])
        assertEquals(0x3E7, w.words[3])
    }

    @Test
    fun `DS import - a cht's DS codes come in, GBA ones are left out`() {
        val store = CheatStore(tmp.newFile("ds.cheats").also { it.delete() })
        val cht = "cheats = 2\ncheat0_desc = \"MONEY\"\ncheat0_code = \"02000000 000003E7\"\ncheat1_desc = \"GBA\"\ncheat1_code = \"82025838 FFFF\"\n"
        val r = store.import(cht, "X", ds = true)
        assertEquals(1, r.added)
        assertEquals(1, r.badCheats)
        assertEquals(listOf("MONEY"), store.load().map { it.name })
    }
}
