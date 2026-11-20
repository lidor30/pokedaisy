package com.pokedaisy.app.n3ds

import com.pokedaisy.app.companion.data.GameKind
import com.pokedaisy.app.companion.data.RETAIL_ROM_DIR
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Omega Ruby / Alpha Sapphire and Sun / Moon read through the 3DS readers with their own [N3dsLayout] (docs/3DS_GAMES_PLAN.md):
 * names, moves, types, zones, wild Pokemon and icons from the player's own carts (`3ds/` beside the GBA folder), checked
 * against the games' known facts. Skipped without the carts.
 */
class OrasSmCartTest {
    private val dir = File(File(RETAIL_ROM_DIR).parentFile, "3ds")
    private val or = File(dir, "Pokemon Omega Ruby (USA) (En,Ja,Fr,De,Es,It,Ko) (Rev 2).3ds")
    private val asCart = File(dir, "Pokemon Alpha Sapphire (USA) (En,Ja,Fr,De,Es,It,Ko) (Rev 2).3ds")
    private val sun = File(dir, "Pokemon Sun (USA) (En,Ja,Fr,De,Es,It,Zh,Ko).3ds")
    private val moon = File(dir, "Pokemon Moon (USA) (En,Ja,Fr,De,Es,It,Zh,Ko).3ds")

    @Test fun cartsAreKnown() {
        assertEquals(GameKind.POKEMON_OMEGA_RUBY, N3dsCart.kindOf(N3dsCart.POKEMON_OR_USA))
        assertEquals(GameKind.POKEMON_MOON, N3dsCart.kindOf(N3dsCart.POKEMON_MOON_USA))
        assertEquals(N3dsLayout.ORAS, N3dsLayout.forTitle(N3dsCart.POKEMON_AS_USA))
        assertEquals(N3dsLayout.SM, N3dsLayout.forTitle(N3dsCart.POKEMON_SUN_USA))
        assertNotNull(Gen6Reader.configFor(N3dsCart.POKEMON_OR_USA))
        for ((cart, id) in listOf(or to N3dsCart.POKEMON_OR_USA, asCart to N3dsCart.POKEMON_AS_USA, sun to N3dsCart.POKEMON_SUN_USA, moon to N3dsCart.POKEMON_MOON_USA)) {
            if (!cart.isFile) continue
            assertEquals(id, N3dsCart.header(cart)!!.titleId)
            assertTrue(N3dsCart.supported(cart))
        }
    }

    @Test fun omegaRubyReadsItsCart() {
        assumeTrue(or.isFile)
        val l = N3dsLayout.ORAS
        val t = Gen6Text.load(or, 2, l)!!
        assertEquals("Bulbasaur", t.species[1])
        assertEquals("Groudon", t.species[383])
        assertEquals("Pound", t.moves[1])
        assertEquals("Potion", t.items[17])
        val moves = Gen6Moves.load(or, l)!!
        assertEquals(50, moves[33].power)        // Tackle
        assertEquals(90, moves[85].power)        // Thunderbolt
        assertEquals(12, moves[85].type)         // ELECTRIC
        val personal = Gen6Personal.load(or, l)!!
        assertEquals(listOf(11, 3), personal[1].toList()) // Bulbasaur GRASS / POISON
        val zones = Gen6Zones.load(or, l)!!
        assertEquals("Littleroot Town", Gen6Zones.placeName(zones, t, 0))
        assertEquals("Oldale Town", Gen6Zones.placeName(zones, t, 7))
        assertEquals("Route 101", Gen6Zones.placeName(zones, t, 23))
        // The players' saves stood in Slateport's Pokemon Center (zone 265) and a Littleroot house (224).
        assertEquals("Slateport City", Gen6Zones.placeName(zones, t, 265))
        assertEquals("Littleroot Town", Gen6Zones.placeName(zones, t, 224))
        val wild = Gen6Wild.load(or, l)!!
        assertEquals(setOf(265, 263, 261), wild[23].first { it.method == "GRASS / CAVE" }.slots.map { it.species }.toSet())
        assertTrue(wild[23].any { it.method == "HORDES" })
        assertEquals(1, l.iconIndex(1, 0, false))
        assertEquals(4, l.iconIndex(3, 0, false))
        assertEquals(3, l.iconIndex(3, 1, false))
        val icons = RomFs(or).use { it.read(l.iconPath) }!!.let { Gen6Text.garcFiles(it) }
        assertEquals(974, icons.size)
        assertNotNull(Gen6Icons.decode(icons[1]))
    }

    /** Omega Ruby's Hoenn: Littleroot, Route 101 just north, Oldale above it, Ever Grande the far east; a house in
     * Littleroot (224) on its town, Slateport's Pokemon Center (265) on Slateport. Writes the picture with every spot
     * marked to build/n3ds-oras-map.png. */
    @Test fun omegaRubysTownMap() {
        assumeTrue(or.isFile)
        val spots = Gen6TownMap.spots(or, N3dsLayout.ORAS)
        fun at(zone: Int) = spots.getValue(zone).let { it.x.toInt() to it.y.toInt() }
        assertEquals(83 to 186, at(6))
        assertEquals(83 to 175, at(23))
        assertEquals(83 to 164, at(7))
        assertEquals(336 to 164, at(21))
        assertEquals(at(6), at(224))
        assertEquals(at(14), at(265))
        val dir = java.nio.file.Files.createTempDirectory("oras-map").toFile()
        assertTrue(Gen6TownMap.extractTo(or, dir, N3dsLayout.ORAS))
        val png = File(dir, Gen6TownMap.ORAS.map)
        assertTrue(png.length() > 1000)
        val img = Gen6TownMap.picture(or, N3dsLayout.ORAS)!!
        assertEquals(380, img.width)
        for (s in spots.values) for (dx in -1..1) for (dy in -1..1) {
            val x = s.x.toInt() + dx
            val y = s.y.toInt() + dy
            if (x in 0 until img.width && y in 0 until img.height) img.argb[y * img.width + x] = 0xFFFF0000.toInt()
        }
        File("build/n3ds-oras-map.png").writeBytes(img.png())
    }

    @Test fun alphaSapphireIsOmegaRubysLayout() {
        assumeTrue(or.isFile && asCart.isFile)
        val l = N3dsLayout.ORAS
        assertEquals(Gen6Text.load(or, 2, l)!!.species, Gen6Text.load(asCart, 2, l)!!.species)
        assertEquals(Gen6Zones.load(or, l)!!.toList(), Gen6Zones.load(asCart, l)!!.toList())
        assertEquals(Gen6Wild.load(or, l)!!.size, Gen6Wild.load(asCart, l)!!.size)
    }

    @Test fun sunReadsItsCart() {
        assumeTrue(sun.isFile)
        val l = N3dsLayout.SM
        val t = Gen6Text.load(sun, 2, l)!!
        assertEquals("Bulbasaur", t.species[1])
        assertEquals("Rowlet", t.species[722])
        assertEquals("Pound", t.moves[1])
        assertEquals("Potion", t.items[17])
        val moves = Gen6Moves.load(sun, l)!!
        assertEquals(40, moves[33].power)        // Tackle, Gen 7's
        val personal = Gen6Personal.load(sun, l)!!
        assertEquals(listOf(11, 2), personal[722].toList()) // Rowlet GRASS / FLYING
        val zones = Gen6Zones.load(sun, l)!!
        assertEquals("Route 1", Gen6Zones.placeName(zones, t, 0))
        assertEquals("Iki Town", Gen6Zones.placeName(zones, t, 10))
        val wild = Gen6Wild.load(sun, l) ?: throw AssertionError("no wild", Gen6Wild.lastError)
        // Per zone, from the area's patches: Route 1 (zone 1) its grass, Route 1's outskirts (zone 0) the beach's
        // Slowpoke / Wingull and surfing, Melemele Sea (zone 6) the surfing alone.
        fun species(z: Int, method: String) = wild[z].firstOrNull { it.method == method }?.slots?.map { it.species }?.toSet().orEmpty()
        val route1 = species(1, "GRASS / CAVE")
        assertTrue(route1.containsAll(setOf(734, 731, 10, 165))) // Yungoos, Pikipek, Caterpie, Ledyba
        assertTrue(79 !in route1) // no Slowpoke
        assertTrue(wild[1].first { it.method == "AT NIGHT" }.slots.any { it.species == 19 && it.form == 1 }) // Alolan Rattata
        assertTrue(species(0, "GRASS / CAVE").containsAll(setOf(79, 278))) // Slowpoke, Wingull
        assertEquals(setOf(72, 456, 278), species(6, "SURFING")) // Tentacool, Finneon, Wingull
        assertTrue(species(6, "GRASS / CAVE").isEmpty())
        // Route 3 (zone 2) and Kala'e Bay (zone 4), one area: Delibird on the route, Bagon in the bay.
        assertTrue(225 in species(2, "GRASS / CAVE") && 371 !in species(2, "GRASS / CAVE").minus(species(4, "GRASS / CAVE")))
        assertTrue(371 in species(4, "GRASS / CAVE") && 225 !in species(4, "GRASS / CAVE"))
        assertEquals(1, l.iconIndex(1, 0, false))
        val icons = RomFs(sun).use { it.read(l.iconPath) }!!.let { Gen6Text.garcFiles(it) }
        assertEquals(1134, icons.size)
        val bulbasaur = Gen6Icons.decode7(icons[1])!!
        assertTrue(bulbasaur.argb.count { it != 0 } > 150)
    }

    /** Sun's islands' maps and where the pin goes: Heahea City's Pokemon Center (where Sun's save stands) on its icon,
     * every island's main-world zones on land. Writes Akala with the pin to build/n3ds-sm-akala.png. */
    @Test fun sunsTownMap() {
        assumeTrue(sun.isFile)
        val pictures = SmTownMap.readPictures(sun)
        assertEquals(setOf(1, 2, 3, 4), pictures.keys)
        assertTrue(pictures.values.all { it.width == 256 && it.height == 256 })
        val zones = SmTownMap.zones(sun)!!
        assertEquals(2, zones.island[77])
        assertTrue(zones.outdoor[77])   // Heahea City
        assertTrue(!zones.outdoor[134]) // its Pokemon Center
        assertEquals(4, zones.island[238])
        val (x, y) = SmTownMap.spot(15253f, 30524f)
        assertEquals(78, x.toInt())
        assertEquals(154, y.toInt())
        val akala = pictures.getValue(2)
        assertTrue((akala.argb[y.toInt() * 256 + x.toInt()] ushr 24) > 128)
        for (dx in -2..2) for (dy in -2..2) akala.argb[(y.toInt() + dy) * 256 + x.toInt() + dx] = 0xFFFF0000.toInt()
        File("build/n3ds-sm-akala.png").writeBytes(akala.png())
    }

    @Test fun moonHasItsOwnWildPokemon() {
        assumeTrue(sun.isFile && moon.isFile)
        val l = N3dsLayout.SM
        val s = Gen6Wild.load(sun, l)!!
        val m = Gen6Wild.load(moon, l)!!
        assertEquals(s.size, m.size)
        assertEquals(Gen6Text.load(sun, 2, l)!!.species, Gen6Text.load(moon, 2, l)!!.species)
    }
}

/** Sun / Moon's scene and bag reads ([Gen6Reader.POKEMON_SM_USA]): no cart needed. */
class SmReaderTest {
    private val c = Gen6Reader.POKEMON_SM_USA

    private fun word(v: Int, hold: Int = 2) = byteArrayOf(v.toByte(), hold.toByte())

    @Test fun scenesFromItsModules() {
        assertEquals(Gen6Reader.Scene.FIELD, Gen6Reader.sceneOf("FieldRo\nFieldMenu\nFieldGimmickPokecen", word(0), c))
        assertEquals(Gen6Reader.Scene.MENU, Gen6Reader.sceneOf("FieldRo\nFieldMenu", word(1), c))
        // A text box sets the menu byte too: held, so the field.
        assertEquals(Gen6Reader.Scene.FIELD, Gen6Reader.sceneOf("FieldRo\nFieldMenu", word(1, hold = 3), c))
        assertEquals(Gen6Reader.Scene.BATTLE, Gen6Reader.sceneOf("Battle\nBackground", null, c))
        assertEquals(Gen6Reader.Scene.BATTLE, Gen6Reader.sceneOf("DllBattle\nDllBackGround", null, Gen6Reader.POKEMON_X_USA))
        assertEquals(Gen6Reader.Scene.SCREEN, Gen6Reader.sceneOf("Bag", null, c))
        assertEquals(null, Gen6Reader.sceneOf("TitleMenu", null, c))
    }

    @Test fun battleMenusFromTheirBytes() {
        assertEquals(com.pokedaisy.app.companion.data.BATTLE_INPUT_ACTION_SELECT, Gen6Reader.battleInputSm(5, 0))
        assertEquals(com.pokedaisy.app.companion.data.BATTLE_INPUT_MOVE_SELECT, Gen6Reader.battleInputSm(2, 1))
        assertEquals(com.pokedaisy.app.companion.data.BATTLE_INPUT_BAG_OPEN, Gen6Reader.battleInputSm(1, 3))
        assertEquals(com.pokedaisy.app.companion.data.BATTLE_INPUT_PARTY_OPEN, Gen6Reader.battleInputSm(1, 2))
        // A turn playing (3) and the field (0).
        assertEquals(com.pokedaisy.app.companion.data.BATTLE_INPUT_NONE, Gen6Reader.battleInputSm(3, 1))
        assertEquals(com.pokedaisy.app.companion.data.BATTLE_INPUT_NONE, Gen6Reader.battleInputSm(0, 0))
    }

    @Test fun battlerRecordsHaveTheirMovesLater() {
        // Sun's foe in the second battle: an Alolan Rattata Lv11, 30 / 30, Tackle / Tail Whip / Quick Attack... (its
        // record's moves at +0x1FC).
        val rec = ByteArray(Gen6Reader.battleMonBytes(c))
        val b = java.nio.ByteBuffer.wrap(rec).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        b.putShort(0xC, 19); b.putShort(0xE, 30); b.putShort(0x10, 30); rec[0x18] = 11
        for ((i, m) in listOf(39, 98, 116, 44).withIndex()) {
            b.putShort(0x1FC + i * 14, m.toShort()); rec[0x1FE + i * 14] = 30; rec[0x1FF + i * 14] = 30
        }
        val mon = Gen6Reader.battleMon(rec, c.battleMovesAt)!!
        assertEquals(19, mon.species)
        assertEquals(11, mon.level)
        assertEquals(listOf(39, 98, 116, 44), mon.moves.map { it.id })
    }

    /** The X menu's and the hold's bytes through their chains: Sun's own save (the field object at 0x30006278, its menu
     * byte 0x300064AD) and the Moon save (0x30004AE8 / 0x30004D1D) read the same way. */
    @Test fun menuBytesFollowTheirChains() {
        fun mem(vararg words: Pair<Int, Int>): (Int) -> Int? = { a -> words.toMap()[a] }
        val sun = mem(0x300002A0 to 0x3000760C, 0x3000760C + 0x2A8 to 0x30006278)
        assertEquals(0x300064AD, Gen6Reader.chained(c.fieldMenuWord, c.fieldMenuPath, sun))
        val moon = mem(0x300002A0 to 0x30005E7C, 0x30005E7C + 0x2A8 to 0x30004AE8)
        assertEquals(0x30004D1D, Gen6Reader.chained(c.fieldMenuWord, c.fieldMenuPath, moon))
        assertEquals(null, Gen6Reader.chained(c.fieldMenuWord, c.fieldMenuPath, mem()))
        // X / ORAS: a plain address.
        assertEquals(0x08D1E3FC, Gen6Reader.chained(0x08D1E3FC, null, mem()))
    }

    @Test fun cameraIsMapped() {
        val cam = c.camera!!
        assertTrue(cam.radians)
        assertEquals(false, cam.turnsPad)
        assertTrue(Gen6Reader.POKEMON_ORAS_USA.camera!!.turnsPad)
        assertTrue(Gen6Reader.inHeap(0x300065EC))
    }

    @Test fun bagIsGen7s() {
        val r = Gen6Reader.ranges(c)
        val bytes = ByteArray(r[1] + r[3] + r[5] + r[7])
        val b = java.nio.ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        val bagAt = r[1] + r[3]
        b.putInt(bagAt, 17 or (999 shl 10))                // a Potion x999 in the items pocket
        b.putInt(bagAt + 0x6B8, 1 or (1 shl 10) or (5 shl 20)) // a key item, with its free-space bits
        bytes[r[1] + 0x35] = 2
        b.putShort(bagAt + r[5], 263)
        val s = Gen6Reader.read(c, bytes)!!
        assertEquals(2, s.language)
        assertEquals(263, s.zone)
        assertEquals(listOf(17 to 999, 1 to 1), s.bag.map { it.id to it.count })
    }
}
