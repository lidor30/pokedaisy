package com.pokedaisy.app.companion.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.zip.CRC32

/**
 * Crystal's TRAINER CARD ([Gen2TrainerCard]): the save's side from the crystal fixtures, the pictures from the carts
 * (skipped without them). The pinned CRCs are the game's own card screens, captured headless in both carts (the crystal
 * fixture's save as it is: no POKéDEX, no badges; and with CHRIS's ID 60536, 999999 money, 12 caught, 123:09 and badges
 * 1 3 6 8 poked in), every frame of the badges' spin and both colon states: [Gen2TrainerCard.render] matched each of
 * them pixel for pixel. `CRYSTAL_CARD_SHOTS=<dir of those .ppm>` compares them again.
 */
class CrystalTrainerCardTest {
    private val dir = File(File(RETAIL_ROM_DIR).parentFile, "gbc")
    private fun art(name: String, t: Gen2TrainerCard.Tables): Gen2TrainerCard.Art {
        val f = File(dir, name)
        assumeTrue("$name not on this machine", f.isFile)
        return Gen2TrainerCard.parse(Gen2TrainerCard.extract(f.readBytes(), t, 0x3E to 0x4200)) ?: error("no card art")
    }
    private fun english() = art("Pokemon - Crystal Version (USA, Europe) (Rev 1).gbc", Gen2TrainerCard.CRYSTAL_TABLES)
    private fun german() = art("Pokemon - Kristall-Edition (Germany).gbc", Gen2TrainerCard.CRYSTAL_DE_TABLES)

    private val chris = listOf(0x82, 0x87, 0x91, 0x88, 0x92)
    private fun card(full: Boolean) = TrainerCardInfo(
        style = CardStyle.CRYSTAL, name = chris, female = false, trainerId = 60536,
        hours = if (full) 123 else 0, minutes = if (full) 9 else 4, money = if (full) 999999 else 3000,
        dexCaught = if (full) 12 else null, badges = if (full) 0xA5 else 0, stars = 0, hofDebut = 0,
    )

    @Test
    fun `the save's side - the crystal fixtures, English and German`() {
        for ((fixture, cfg) in listOf("crystal" to GEN2_CRYSTAL, "crystal_de" to GEN2_CRYSTAL_DE)) {
            val c = Gen2TrainerCard.read(FixtureMemoryReader.load(fixture), cfg) ?: error("$fixture: no card")
            assertEquals(fixture, CardStyle.CRYSTAL, c.style)
            assertEquals(fixture, chris, c.name)
            assertEquals(fixture, 60536, c.trainerId)
            assertEquals(fixture, 3000L, c.money)
            assertNull(fixture, c.dexCaught) // no POKéDEX yet: the card leaves the line out
            assertEquals(fixture, 0, c.badges)
            assertEquals(fixture, false, c.female)
        }
    }

    @Test
    fun `both pages, every frame - the game's own, pixel for pixel`() {
        val out = File("build/crystal-card").also { it.mkdirs() }
        for ((lang, art, pinned) in listOf(Triple("en", english(), 0x498BFDDEL), Triple("de", german(), 0x457395C3L))) {
            val crc = CRC32()
            for (full in listOf(true, false)) for (page2 in listOf(false, true)) for (phase in 0 until 8) {
                val img = Gen2TrainerCard.render(art, card(full), page2, phase)
                img.argb.forEach { crc.update(it) }
                if (phase == 0 || phase == 4) File(out, "$lang-${if (full) "full" else "plain"}-p${if (page2) 2 else 1}-$phase.png").writeBytes(img.png())
            }
            println("$lang 0x%08XL".format(crc.value))
            assertEquals(lang, pinned, crc.value)
        }
        val shots = System.getenv("CRYSTAL_CARD_SHOTS")?.let(::File) ?: return
        for ((lang, art) in listOf("en" to english(), "de" to german())) {
            for (f in shots.listFiles().orEmpty().filter { it.name.startsWith("${lang}_") && "_p" in it.name && it.name.endsWith(".ppm") }) {
                val ref = readPpm(f)
                val off = (0 until 8).minOf { ph ->
                    val img = Gen2TrainerCard.render(art, card("_full" in f.name), "_p2" in f.name, ph)
                    img.argb.indices.count { img.argb[it] != ref[it] }
                }
                assertEquals(f.name, 0, off)
            }
        }
    }

    private fun readPpm(f: File): IntArray {
        val b = f.readBytes()
        var o = 0
        val fields = ArrayList<String>()
        while (fields.size < 4) {
            while (b[o].toInt().toChar().isWhitespace()) o++
            val s = o
            while (!b[o].toInt().toChar().isWhitespace()) o++
            fields += String(b, s, o - s)
        }
        o++
        return IntArray(fields[1].toInt() * fields[2].toInt()) { i ->
            (0xFF shl 24) or ((b[o + 3 * i].toInt() and 0xFF) shl 16) or ((b[o + 3 * i + 1].toInt() and 0xFF) shl 8) or (b[o + 3 * i + 2].toInt() and 0xFF)
        }
    }
}
