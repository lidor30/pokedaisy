package com.pokedaisy.app.companion.data

import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import okio.Path.Companion.toOkioPath

/**
 * A DS cart's names read from its own text (GitHub #230: a French Sacred Gold, an IPKE HeartGold, showed English ones):
 * on the US carts they must be the app's own tables, line for line. Skips without the ROMs (`-DromDir=`'s ../nds/).
 */
class NdsNamesTest {
    private val nds = File(File(RETAIL_ROM_DIR).parentFile, "nds")

    private fun names(rom: String): NdsArt.Names {
        val f = File(nds, rom)
        assumeTrue("no $f", f.isFile)
        return NdsArt.readNames(f.toOkioPath())!!
    }

    @Test fun heartGold() = check(names("Pokemon - HeartGold Version (USA).nds"), itemNamesHeartGold, GEN4_HEARTGOLD_US)

    @Test fun platinum() = check(names("Pokemon - Platinum Version (USA) (Rev 1).nds"), itemNamesPlatinum, GEN4_PLATINUM_US)

    private fun check(n: NdsArt.Names, items: Map<Int, String>, cfg: Gen4Config) {
        assertEquals('E', n.language)
        for (id in 1..493) assertEquals("species $id", speciesNamesPlatinum[id], n.species(id))
        for ((id, m) in moveDataPlatinum) assertEquals("move $id", m.name, n.move(id))
        for ((id, name) in items) if (id > 0) assertEquals("item $id", name, n.item(id))
        // Every map header's place through the cart's location names = the app's English one.
        for ((header, name) in cfg.mapPlaces) assertEquals("header $header", name, n.place(cfg.mapPlaceIds.getValue(header)))
    }
}
