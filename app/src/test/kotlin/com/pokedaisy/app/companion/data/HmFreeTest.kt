package com.pokedaisy.app.companion.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.zip.CRC32
import okio.Path.Companion.toOkioPath

/**
 * NO HMS NEEDED's two places, found in each ROM by their code ([HmFree.locate]): ScrCmd_checkpartymove (rewritten from
 * scripts/hm_free.s) and PartyHasMonWithSurf's `beq`. What they call must be the GetMonData of that build's field-item
 * table (found its own way: port_field_items.py, the decomps). Played headless with mgba_dump's `rompatch` on FireRed rev 1
 * and Emerald. Skips a ROM that isn't here.
 */
class HmFreeTest {
    private val cases = listOf(
        "Pokemon - FireRed Version (USA, Europe) (Rev 1).gba" to true,
        "Pokemon - FireRed Version (USA).gba" to true,
        "Pokemon - LeafGreen Version (USA).gba" to true,
        "Pokemon - Emerald Version (USA, Europe).gba" to true,
        "Pokemon - Ruby Version (USA, Europe) (Rev 1).gba" to true,
        "Pokemon - Sapphire Version (USA, Europe) (Rev 2).gba" to true,
        // Hacks that kept that code.
        "Pokemon Emerald Kaizo (v2.0).gba" to true,
        "Pokemon Emerald Legacy (v1.1.4).gba" to true,
        "Pokemon Glazed (v9.2.0).gba" to true,
        "Pokemon Unbound (v2.1.1.1).gba" to true,
        "Pokemon Radical Red (v4.1).gba" to true,
        "Pokemon Ultra Violet (v1.22).gba" to true,
        // gcc builds: another shape.
        "Pokemon Lazarus (v2.0).gba" to false,
        "Pokemon SoulGold (v1.1.4).gba" to false,
        "Pokemon Recharged Emerald (v2.2.8).gba" to false,
        "Pokemon Emerald Rogue (v2.2.1-EX).gba" to false,
        // Celia keeps the command, not Surf's check: none (all or nothing).
        "Pokemon Celia's Stupid Romhack (v1.1.4).gba" to false,
    )

    @Test
    fun findsEachGame() {
        for ((name, want) in cases) {
            val rom = File(RETAIL_ROM_DIR + name)
            if (!rom.isFile) continue
            assertEquals(name, want, HmFree.locate(rom.toOkioPath()) != null)
        }
    }

    @Test
    fun retailFireRedAndEmerald() {
        val fr = File(RETAIL_ROM_DIR + "Pokemon - FireRed Version (USA, Europe) (Rev 1).gba")
        if (fr.isFile) check(fr, check = 0x0806C0BCL, surf = 0x0805C850L, PARTY_ITEMS_FIRERED_REV1, crc = 0x7C34D925L)
        val em = File(RETAIL_ROM_DIR + "Pokemon - Emerald Version (USA, Europe).gba")
        if (em.isFile) check(em, check = 0x0809B3DCL, surf = 0x0808BE00L, PARTY_ITEMS_EMERALD, crc = 0xF28EE489L)
    }

    /** [rom]'s two places, its calls / party, and the rewritten command's bytes as the headless runs had them. */
    private fun check(rom: File, check: Long, surf: Long, party: PartyItemFns, crc: Long) {
        val f = HmFree.locate(rom.toOkioPath())
        assertNotNull(rom.name, f)
        f!!
        assertEquals("%08X".format(check), "%08X".format(f.check))
        assertEquals("%08X".format(surf), "%08X".format(f.surf))
        assertEquals("%08X".format(party.getMonData), "%08X".format(f.getMonData))
        assertEquals("%08X".format(party.party), "%08X".format(f.party))
        val bytes = rom.readBytes()
        val patched = bytes.copyOf()
        f.patch.writes.forEach { w ->
            val at = (w.addr - 0x08000000L).toInt()
            assertTrue(rom.name, w.expect.contentEquals(bytes.copyOfRange(at, at + w.expect.size)))
            w.value.copyInto(patched, at)
        }
        val c = (check - 0x08000000L).toInt()
        assertEquals("%08X".format(crc), "%08X".format(CRC32().apply { update(patched, c, 124) }.value))
        // Surf's `beq` is a `nop` now, nothing else of it changed.
        val s = (surf - 0x08000000L).toInt()
        assertEquals(0xC0, patched[s + 0x1E].toInt() and 0xFF)
        assertEquals(0x46, patched[s + 0x1F].toInt() and 0xFF)
        assertTrue((0 until 0x50).filter { it !in 0x1E..0x1F }.all { patched[s + it] == bytes[s + it] })
    }

    /** Every retail cart in the retail ROM dir (-DmultiRomDir=...): found, calling its build's GetMonData. */
    @Test
    fun everyRetailBuild() {
        val dir = File(System.getProperty("multiRomDir") ?: RETAIL_ROM_DIR)
        assumeTrue(dir.isDirectory)
        var checked = 0
        for (f in retailRomFiles(dir).filter { listOf("Pirate", "Debug").none { x -> x in it.name } }) {
            val h = f.inputStream().use { ByteArray(0xC0).also { b -> it.read(b) } }
            val found = HmFree.locate(f.toOkioPath())
            assertNotNull(f.name, found)
            val party = fieldItemsFor(String(h, 0xAC, 4), h[0xBC].toInt())?.party ?: continue
            assertEquals(f.name, "%08X".format(party.getMonData), "%08X".format(found!!.getMonData))
            assertEquals(f.name, "%08X".format(party.party), "%08X".format(found.party))
            checked++
        }
        assertTrue("checked $checked", checked >= 30)
    }

    @Test
    fun nothingInAnEmptyRom() {
        assertNull(HmFree.locate(LittleEndianBytes(ByteArray(0x10000))))
    }
}
