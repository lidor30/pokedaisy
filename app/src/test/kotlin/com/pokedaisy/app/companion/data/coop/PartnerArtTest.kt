package com.pokedaisy.app.companion.data.coop

import com.pokedaisy.app.companion.data.LookArt
import com.pokedaisy.app.companion.data.LookBlob
import com.pokedaisy.app.companion.data.LookChar
import com.pokedaisy.app.companion.data.LookPart
import com.pokedaisy.app.companion.data.PlayerLook
import com.pokedaisy.app.companion.data.RETAIL_ROM_DIR
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.zip.CRC32
import kotlin.random.Random

/**
 * The partner's art ([PartnerArt]): 42 frames of 32x32 and the palette, packed from a hero's graphics in a fixed order.
 * The packing is checked on made-up graphics; the real ROM (`emerald.gba` in romDir, skipped without it) gives the surf
 * blob and writes app/build/partner/brendan.bin and may.bin for the headless tests.
 */
class PartnerArtTest {
    /** Every graphic of [c] at its real size, filled with noise (each part its own), so frames can't be mistaken. */
    private fun art(c: LookChar): Map<LookPart, ByteArray> =
        LookBlob.entries.filter { it.char == c }.associate { b -> b.part to Random(c.ordinal * 100 + b.part.ordinal).nextBytes(b.size) }

    private val blob = Random(7).nextBytes(3 * 512)

    private fun frame(art: ByteArray, i: Int) = art.copyOfRange(i * 512, (i + 1) * 512)
    private fun sheetFrame(data: ByteArray, part: LookPart, i: Int) = data.copyOfRange(i * part.frameBytes, (i + 1) * part.frameBytes)

    private fun rom(): ByteArray? = File(RETAIL_ROM_DIR + "emerald.gba").takeIf { it.isFile }?.readBytes()

    @Test fun packHasFixedSize() {
        val own = art(LookChar.BRENDAN)
        val packed = PartnerArt.assemble(own, null, LookChar.BRENDAN, null, blob)!!
        assertEquals(21536, packed.size)
        assertEquals(PartnerArt.ART_BYTES, packed.size)
        assertArrayEquals(own[LookPart.OW_PAL], packed.copyOfRange(42 * 512, 42 * 512 + 32))
    }

    @Test fun walkingFramesAreWidenedInOrder() {
        val own = art(LookChar.MAY)
        val packed = PartnerArt.assemble(own, null, LookChar.MAY, null, blob)!!
        for (i in 0..8) {
            assertArrayEquals("walk $i", PlayerLook.widen(sheetFrame(own[LookPart.EM_WALKING]!!, LookPart.EM_WALKING, i)), frame(packed, i))
            assertArrayEquals("run $i", PlayerLook.widen(sheetFrame(own[LookPart.EM_RUNNING]!!, LookPart.EM_RUNNING, i)), frame(packed, 9 + i))
        }
    }

    @Test fun machAndAcroTakeFirstNine() {
        val own = art(LookChar.BRENDAN)
        val packed = PartnerArt.assemble(own, null, LookChar.BRENDAN, null, blob)!!
        assertArrayEquals(sheetFrame(own[LookPart.EM_MACH_BIKE]!!, LookPart.EM_MACH_BIKE, 0), frame(packed, 18))
        assertArrayEquals(sheetFrame(own[LookPart.EM_MACH_BIKE]!!, LookPart.EM_MACH_BIKE, 8), frame(packed, 26))
        assertArrayEquals(sheetFrame(own[LookPart.EM_ACRO_BIKE]!!, LookPart.EM_ACRO_BIKE, 0), frame(packed, 27))
        assertArrayEquals(sheetFrame(own[LookPart.EM_ACRO_BIKE]!!, LookPart.EM_ACRO_BIKE, 8), frame(packed, 35))
        // The game's pic table: S / N / W are the surf sheet's frames 0, 2, 4 (of six).
        for ((i, f) in listOf(0, 2, 4).withIndex()) {
            assertArrayEquals("surf $i", sheetFrame(own[LookPart.EM_SURFING]!!, LookPart.EM_SURFING, f), frame(packed, 36 + i))
        }
        for (i in 0..2) {
            assertArrayEquals("blob $i", frame(blob, i), frame(packed, 39 + i))
        }
    }

    @Test fun crossGameLookIsRemappedIntoOwnSlot() {
        val own = art(LookChar.BRENDAN)
        val red = art(LookChar.RED)
        val packed = PartnerArt.assemble(own, red, LookChar.BRENDAN, LookChar.RED, blob)!!
        val worn = PlayerLook.wear(LookChar.BRENDAN, LookChar.RED, red) { own[it] }!!
        assertArrayEquals(worn.pal, packed.copyOfRange(42 * 512, 42 * 512 + 32))
        val walk = PlayerLook.sheet(LookPart.EM_WALKING, LookChar.BRENDAN, LookChar.RED, red) { own[it] }!!
        assertArrayEquals(PlayerLook.remap(PlayerLook.widen(walk[0]), worn.map), frame(packed, 0))
        val surf = PlayerLook.sheet(LookPart.EM_SURFING, LookChar.BRENDAN, LookChar.RED, red) { own[it] }!!
        assertArrayEquals(PlayerLook.remap(surf[0], worn.map), frame(packed, 36))
        assertArrayEquals(PlayerLook.remap(surf[2], worn.map), frame(packed, 37))
        assertArrayEquals(PlayerLook.remap(surf[4], worn.map), frame(packed, 38))
        // The blob keeps the hero's own colours: it isn't remapped.
        assertArrayEquals(frame(blob, 0), frame(packed, 39))
    }

    @Test fun sameGameLookKeepsItsPalette() {
        val own = art(LookChar.BRENDAN)
        val may = art(LookChar.MAY)
        val packed = PartnerArt.assemble(own, may, LookChar.BRENDAN, LookChar.MAY, blob)!!
        assertArrayEquals(may[LookPart.OW_PAL], packed.copyOfRange(42 * 512, 42 * 512 + 32))
        assertArrayEquals(PlayerLook.widen(sheetFrame(may[LookPart.EM_WALKING]!!, LookPart.EM_WALKING, 4)), frame(packed, 4))
    }

    @Test fun refusesARomWithoutTheBlob() {
        val rom = ByteArray(CoopEmeraldGen.SURF_BLOB_PIC + 3 * 512)
        assertNull(PartnerArt.pack(art(LookChar.BRENDAN), null, LookChar.BRENDAN, null, rom))
        assertNull(PartnerArt.pack(art(LookChar.BRENDAN), null, LookChar.BRENDAN, null, ByteArray(16)))
    }

    @Test fun missingLookIsNullSoThePlainHeroIsAskedForInstead() {
        val rom = rom()
        assumeTrue("no emerald.gba in romDir", rom != null)
        val dir = Files.createTempDirectory("partner").toFile()
        try {
            // MAY kept (made up, so no scan), LEAF not.
            val may = File(LookArt.dir(dir), "may").apply { mkdirs() }
            art(LookChar.MAY).forEach { (p, b) -> File(may, "${p.name.lowercase()}.bin").writeBytes(b) }
            val plain = PartnerArt.build(dir, rom!!, female = true, look = null)
            assertNotNull(plain)
            assertNull(PartnerArt.build(dir, rom, female = true, look = LookChar.LEAF))
            assertArrayEquals(PartnerArt.assemble(art(LookChar.MAY), null, LookChar.MAY, null, frame3(rom)), plain)
        } finally {
            dir.deleteRecursively()
        }
    }

    private fun frame3(rom: ByteArray) = rom.copyOfRange(CoopEmeraldGen.SURF_BLOB_PIC, CoopEmeraldGen.SURF_BLOB_PIC + 3 * 512)

    @Test fun writesHeadlessArtFromRealRom() {
        val rom = rom()
        assumeTrue("no emerald.gba in romDir", rom != null)
        val dir = Files.createTempDirectory("partner").toFile()
        try {
            val out = File("build/partner").apply { mkdirs() }
            for ((female, name) in listOf(false to "brendan", true to "may")) {
                val art = PartnerArt.build(dir, rom!!, female, look = null)
                assertNotNull(name, art)
                assertEquals(PartnerArt.ART_BYTES, art!!.size)
                // The hero's own overworld palette (the ROM's, by its fingerprint's CRC).
                val pal = LookBlob.valueOf("${name.uppercase()}_OW_PAL")
                assertEquals(name, pal.crc, CRC32().apply { update(art, 42 * 512, 32) }.value)
                File(out, "$name.bin").writeBytes(art)
            }
            // The scan kept the ROM's heroes for next time.
            assertNotNull(LookArt.load(dir, LookChar.BRENDAN))
        } finally {
            dir.deleteRecursively()
        }
    }
}
