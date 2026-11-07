package com.pokedaisy.app.companion.data

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * INSTANT TEXT's patch, found in each ROM by its code ([InstantText.find]). The bytes were played headless with
 * mgba_dump's `rompatch` on FireRed, Emerald and Ruby: pages, scrolling, battle messages. Skips a ROM that isn't here.
 */
class InstantTextTest {
    private fun hex(s: String) = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    private fun rom(size: Int, at: Int, code: ByteArray): ByteBuffer {
        val b = ByteArray(size)
        code.copyInto(b, at)
        return ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN)
    }

    // FireRed rev 1's RenderFont, its call at +0x16.
    private val font = hex("10b5041c61790848026848004018800080180168201ce0f1b9fe0004000c0228f0d010bc02bc0847")

    @Test fun `RenderFont is rewritten with its call kept`() {
        val at = 0x2E90
        val patch = InstantText.find(rom(0x8000, at, font))
        assertNotNull(patch)
        val out = rom(0x8000, at, font).array()
        patch!!.writes.forEach { w ->
            val o = (w.addr - 0x08000000L).toInt()
            assertArrayEquals(w.expect, out.copyOfRange(o, o + w.expect.size))
            w.value.copyInto(out, o)
        }
        val code = out.copyOfRange(at, at + 0x28)
        // The call moved 4 bytes up, to the same place: its offset grows by 2 halfwords.
        assertArrayEquals(hex("10b5041c61790c20414307480068415820" + "1ce0f1bbfe" + "410805d2f3d1607721680978f729eed310bd"), code)
    }

    @Test fun `a function off a word boundary is left alone`() {
        assertNull(InstantText.find(rom(0x8000, 0x2E92, font)))
    }

    @Test fun `two copies of the code are not trusted`() {
        val r = rom(0x8000, 0x2E90, font)
        font.copyInto(r.array(), 0x4000)
        assertNull(InstantText.find(r))
    }

    @Test fun `no known code means no patch`() {
        assertNull(InstantText.find(ByteBuffer.allocate(0x8000).order(ByteOrder.LITTLE_ENDIAN)))
    }

    private val cases = listOf(
        "Pokemon - FireRed Version (USA, Europe) (Rev 1).gba" to true,
        "Pokemon - FireRed Version (USA).gba" to true,
        "Pokemon - LeafGreen Version (USA, Europe) (Rev 1).gba" to true,
        "Pokemon - Emerald Version (USA, Europe).gba" to true,
        "Pokemon - Ruby Version (USA, Europe) (Rev 1).gba" to true,
        "Pokemon - Ruby Version (USA, Europe) (Rev 2).gba" to true,
        "Pokemon - Sapphire Version (USA, Europe) (Rev 2).gba" to true,
        "Pokemon Emerald Legacy (v1.1.4).gba" to true,
        "Pokemon Unbound (v2.1.1.1).gba" to true,
        "Pokemon Radical Red (v4.1).gba" to true,
        "Pokemon Quetzal (Alpha 9.0) (English).gba" to true,
        // gcc's builds of the code: another shape.
        "Pokemon Lazarus (v2.0).gba" to false,
        "Pokemon SoulGold (v1.1.4).gba" to false,
        "Pokemon Recharged Yellow (v1.9.7).gba" to false,
    )

    @Test fun `each ROM gets the patch it should`() {
        var seen = 0
        for ((name, want) in cases) {
            val f = File(RETAIL_ROM_DIR, name)
            if (!f.exists()) continue
            seen++
            assertEquals(name, want, InstantText.find(f) != null)
        }
        assumeTrue("no ROMs here", seen > 0)
    }

    @Test fun `Ruby and Sapphire in every language are one branch`() {
        val roms = retailRomFiles().toList()
            .filter { Regex("Rubin|Rubi|Saphir|Zafiro|Zaffiro|Rubis").containsMatchIn(it.name) && "Debug" !in it.name }
        assumeTrue("no ROMs here", roms.isNotEmpty())
        for (f in roms) assertEquals(f.name, 1, InstantText.find(f)?.writes?.size)
    }
}
