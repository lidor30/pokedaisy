package com.pokedaisy.app.companion.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import okio.Path.Companion.toOkioPath

/**
 * The save tools' DS party icons, read from the cart by [NdsArt.monIcons]: every species and the egg on each cart here,
 * the same pixels the companion's art ([NdsArt.extractTo]) draws, and a sheet a cart in app/build/nds-art/ to look at.
 * Skips the carts that aren't on this machine (`-DromDir=`'s ../nds/).
 */
class NdsMonIconsTest {
    private val nds = File(File(RETAIL_ROM_DIR).parentFile, "nds")

    private fun cart(name: String): File = File(nds, "$name.nds").also { assumeTrue("no cart at $it", it.isFile) }

    /** Every species 1..[last] and the egg have an icon; a sheet of a few goes to build/nds-art/icons-[tag].png. */
    private fun check(name: String, last: Int, tag: String): NdsArt.MonIcons {
        val icons = NdsArt.monIcons(cart(name).toOkioPath())
        assertNotNull("no icons for $name", icons)
        val missing = (1..last).filter { icons!!.get(it) == null }
        assertEquals("$name: species without an icon", emptyList<Int>(), missing)
        assertNotNull(icons!!.get(1, isEgg = true))
        assertNull(icons.get(last + 200))
        val shown = listOf(1, 25, 151, 249, 252, 387, 412, 487, 493, 494, 495, 650).filter { it <= last }
        val sheet = RomArt.Image(32 * (shown.size + 1), 32)
        (shown.map { icons.get(it) } + icons.get(1, isEgg = true)).forEachIndexed { i, img ->
            for (y in 0 until 32) for (x in 0 until 32) sheet.argb[y * sheet.width + i * 32 + x] = img!!.argb[y * 32 + x]
        }
        File("build/nds-art").apply { mkdirs() }.resolve("icons-$tag.png").writeBytes(sheet.png())
        return icons
    }

    @Test fun heartGold() { check("Pokemon - HeartGold Version (USA)", 493, "heartgold") }
    @Test fun soulSilver() { check("Pokemon - SoulSilver Version (USA)", 493, "soulsilver") }
    @Test fun heartGoldSpain() { check("Pokemon - Edicion Oro HeartGold (Spain) [b]", 493, "heartgold-es") }
    @Test fun platinum() { check("Pokemon - Platinum Version (USA) (Rev 1)", 493, "platinum") }
    @Test fun platinumFrance() { check("Pokemon - Version Platine (France)", 493, "platinum-fr") }
    @Test fun diamond() { check("Pokemon - Diamond Version (USA) (Rev 5)", 493, "diamond") }
    @Test fun pearl() { check("Pokemon - Pearl Version (USA) (Rev 5)", 493, "pearl") }
    @Test fun black() { check("Pokemon - Black Version (USA, Europe) (NDSi Enhanced)", 649, "black") }
    @Test fun white() { check("Pokemon - White Version (USA, Europe) (NDSi Enhanced) [b]", 649, "white") }
    @Test fun black2() { check("Pokemon - Black Version 2 (USA, Europe) (NDSi Enhanced) [b]", 649, "black2") }
    @Test fun white2() { check("Pokemon - White Version 2 (USA, Europe) (NDSi Enhanced) [b]", 649, "white2") }

    /** The companion's own extraction draws the same pixels (HeartGold, Black 2: one each of the two archive shapes). */
    @Test
    fun sameAsTheCompanionsArt() {
        for ((name, path) in listOf(
            "Pokemon - HeartGold Version (USA)" to NdsArt.HEARTGOLD_ICONS,
            "Pokemon - Black Version 2 (USA, Europe) (NDSi Enhanced) [b]" to "nds/black2/pokemon",
        )) {
            val rom = File(nds, "$name.nds")
            if (!rom.isFile) continue
            val dir = kotlin.io.path.createTempDirectory("nds-icons").toFile()
            try {
                assertTrue(NdsArt.extractTo(rom.toOkioPath(), dir.toOkioPath()))
                val icons = NdsArt.monIcons(rom.toOkioPath())!!
                for (species in listOf(1, 25, 150, 386, 493)) {
                    val png = File(dir, "$path/$species.png")
                    assertTrue("$png", png.isFile)
                    assertTrue("$name $species", png.readBytes().contentEquals(icons.get(species)!!.png()))
                }
            } finally {
                dir.deleteRecursively()
            }
        }
    }

    /** Diamond / Pearl's icons in Platinum's palette table: the same pictures as Platinum's for the species both have. */
    @Test
    fun diamondMatchesPlatinum() {
        val dp = NdsArt.monIcons(cart("Pokemon - Diamond Version (USA) (Rev 5)").toOkioPath())!!
        val pt = NdsArt.monIcons(cart("Pokemon - Platinum Version (USA) (Rev 1)").toOkioPath())!!
        val differ = (1..493).filter { !dp.get(it)!!.argb.contentEquals(pt.get(it)!!.argb) }
        println("Diamond icons that differ from Platinum's: $differ")
        // Those side by side (Diamond's over Platinum's), to look at.
        val sheet = RomArt.Image(32 * differ.size.coerceAtLeast(1), 64)
        differ.forEachIndexed { i, s ->
            for ((row, img) in listOf(dp.get(s)!!, pt.get(s)!!).withIndex())
                for (y in 0 until 32) for (x in 0 until 32) sheet.argb[(row * 32 + y) * sheet.width + i * 32 + x] = img.argb[y * 32 + x]
        }
        File("build/nds-art").apply { mkdirs() }.resolve("icons-diamond-vs-platinum.png").writeBytes(sheet.png())
        assertTrue("${differ.size} differ: $differ", differ.size <= 10)
    }
}
