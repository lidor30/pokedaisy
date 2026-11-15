package com.pokedaisy.app.n3ds

import com.pokedaisy.app.companion.data.RETAIL_ROM_DIR
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

class Gen6Test {
    private val orders = arrayOf(
        "ABCD", "ABDC", "ACBD", "ACDB", "ADBC", "ADCB", "BACD", "BADC", "BCAD", "BCDA", "BDAC", "BDCA",
        "CABD", "CADB", "CBAD", "CBDA", "CDAB", "CDBA", "DABC", "DACB", "DBAC", "DBCA", "DCAB", "DCBA",
    )

    /** What the game stores: [plain] (0x104 bytes, checksum set) shuffled and encrypted, the party stats apart. */
    private fun encrypt(plain: ByteArray): ByteArray {
        val p = ByteBuffer.wrap(plain).order(ByteOrder.LITTLE_ENDIAN)
        val ec = p.getInt(0).toLong() and 0xFFFFFFFFL
        val order = orders[(((ec ushr 13) and 31) % 24).toInt()]
        val stored = plain.copyOf()
        for (k in 0 until 4) System.arraycopy(plain, 8 + 56 * "ABCD".indexOf(order[k]), stored, 8 + 56 * k, 56)
        val s = ByteBuffer.wrap(stored).order(ByteOrder.LITTLE_ENDIAN)
        var seed = ec
        var i = 8
        while (i < Gen6Reader.PARTY_SIZE) {
            if (i == Gen6Reader.STORED_SIZE) seed = ec
            seed = (seed * 0x41C64E6DL + 0x6073L) and 0xFFFFFFFFL
            s.putShort(i, ((s.getShort(i).toInt() xor (seed ushr 16).toInt()) and 0xFFFF).toShort())
            i += 2
        }
        return stored
    }

    private fun mon(ec: Int): ByteArray {
        val b = ByteBuffer.allocate(Gen6Reader.PARTY_SIZE).order(ByteOrder.LITTLE_ENDIAN)
        b.putInt(0, ec)
        b.putShort(0x08, 658) // species
        b.putShort(0x0A, 17)  // held item
        b.putInt(0x10, 1_059_860)
        b.put(0x14, 67)
        "Greninja".toByteArray(Charsets.UTF_16LE).copyInto(b.array(), 0x40)
        intArrayOf(518, 122, 326, 56).forEachIndexed { i, m -> b.putShort(0x5A + i * 2, m.toShort()) }
        b.put(0xEC, 100)
        b.putShort(0xF0, 250); b.putShort(0xF2, 289)
        var sum = 0
        for (i in 8 until Gen6Reader.STORED_SIZE step 2) sum += b.getShort(i).toInt() and 0xFFFF
        b.putShort(6, sum.toShort())
        return b.array()
    }

    @Test fun decodesWhatTheGameStores() {
        for (ec in intArrayOf(0x011DE1FB, 0x53E9935E, 0x9564DDE7.toInt(), 0x7FFFE000)) {
            val m = Gen6Reader.decode(encrypt(mon(ec)))!!
            assertEquals(658, m.species)
            assertEquals(17, m.heldItem)
            assertEquals("Greninja", m.nickname)
            assertArrayEquals(intArrayOf(518, 122, 326, 56), m.moves)
            assertEquals(100, m.level)
            assertEquals(250, m.hp)
            assertEquals(289, m.maxHp)
        }
    }

    @Test fun refusesABrokenChecksum() {
        val stored = encrypt(mon(0x011DE1FB))
        stored[0x20] = (stored[0x20] + 1).toByte()
        assertNull(Gen6Reader.decode(stored))
    }

    /** The cart's own text, where the player's Pokemon X sits (`3ds/` beside the GBA folder): skipped without it. */
    @Test fun readsTheCartsText() {
        val cart = File(File(RETAIL_ROM_DIR).parentFile, "3ds/Pokemon X (USA) (En,Ja,Fr,De,Es,It,Ko).3ds")
        assumeTrue(cart.isFile)
        val it = Gen6Text.load(cart, 4)!!
        assertEquals("Greninja", it.species[658])
        assertEquals("Azione", it.moves[33])
        assertEquals("Pozione", it.items[17])
        val en = Gen6Text.load(cart, 2)!!
        assertEquals("Bulbasaur", en.species[1])
        assertEquals("Tackle", en.moves[33])
        assertEquals("Potion", en.items[17])
        assertTrue(en.types.contains("Fairy"))
    }

    /** The zones' place names from the cart (zone numbers as the RP6 read them, 2026-11-15: 67 outside in Lumiose's
     * south, 359 its Pokemon Center). */
    @Test fun readsTheCartsZones() {
        val cart = File(File(RETAIL_ROM_DIR).parentFile, "3ds/Pokemon X (USA) (En,Ja,Fr,De,Es,It,Ko).3ds")
        assumeTrue(cart.isFile)
        val zones = Gen6Zones.load(cart)!!
        assertEquals(360, zones.size)
        val en = Gen6Text.load(cart, 2)!!
        assertEquals("Vaniville Town", Gen6Zones.placeName(zones, en, 0))
        assertEquals("Lumiose City", Gen6Zones.placeName(zones, en, 67))
        assertEquals("Lumiose City", Gen6Zones.placeName(zones, en, 359))
        assertEquals("", Gen6Zones.placeName(zones, en, 9999))
        assertEquals("Luminopoli", Gen6Zones.placeName(zones, Gen6Text.load(cart, 4), 67))
    }

    /** Gen 6's chart: Fairy's matchups, Steel no longer resisting Ghost / Dark, two types multiplied. */
    @Test fun typeChart() {
        fun t(n: String) = Gen6Moves.TYPES.indexOf(n)
        fun pct(a: String, d1: String, d2: String = d1) = Gen6Types.multiplierPct(t(a), t(d1), t(d2))
        assertEquals(200, pct("FAIRY", "DRAGON"))
        assertEquals(0, pct("DRAGON", "FAIRY"))
        assertEquals(50, pct("FAIRY", "FIRE"))
        assertEquals(200, pct("POISON", "FAIRY"))
        assertEquals(100, pct("GHOST", "STEEL"))
        assertEquals(100, pct("DARK", "STEEL"))
        assertEquals(400, pct("ICE", "GROUND", "FLYING"))
        assertEquals(0, pct("ELECTRIC", "WATER", "GROUND"))
        assertEquals(25, pct("GRASS", "FIRE", "FLYING"))
        assertEquals(100, pct("FIRE", "WATER", "GRASS"))
        assertEquals(100, Gen6Types.multiplierPct(99, 0, 0))
    }

    /** The zones' wild Pokemon from the cart: Route 2 (zone 259) is grass alone, its levels 2-4. */
    @Test fun readsTheCartsWildPokemon() {
        val cart = File(File(RETAIL_ROM_DIR).parentFile, "3ds/Pokemon X (USA) (En,Ja,Fr,De,Es,It,Ko).3ds")
        assumeTrue(cart.isFile)
        val wild = Gen6Wild.load(cart)!!
        val route2 = wild[259]
        assertEquals(listOf("GRASS / CAVE"), route2.map { it.method })
        val grass = route2[0].slots
        assertEquals(12, grass.size)
        assertEquals(setOf(659, 263, 661, 16, 664, 13), grass.map { it.species }.toSet())
        assertEquals(100, grass.sumOf { it.percent })
        assertEquals(2, grass.minOf { it.minLevel })
        assertEquals(4, grass.maxOf { it.maxLevel })
        assertTrue(wild[67].isEmpty())
    }

    /** The Town Map's places from the cart (checked against the map: Vaniville, Route 2, Lumiose's centre for its
     * Pokemon Center). */
    @Test fun readsTheCartsTownMap() {
        val cart = File(File(RETAIL_ROM_DIR).parentFile, "3ds/Pokemon X (USA) (En,Ja,Fr,De,Es,It,Ko).3ds")
        assumeTrue(cart.isFile)
        val spots = Gen6TownMap.spots(cart)
        assertEquals(360, spots.size)
        fun at(zone: Int) = spots.getValue(zone).let { it.x.toInt() to it.y.toInt() }
        assertEquals(212 to 180, at(0))
        assertEquals(212 to 145, at(259))
        assertEquals(166 to 78, at(359))
    }

    /** The species' types from the cart's personal records. */
    @Test fun readsTheCartsSpeciesTypes() {
        val cart = File(File(RETAIL_ROM_DIR).parentFile, "3ds/Pokemon X (USA) (En,Ja,Fr,De,Es,It,Ko).3ds")
        assumeTrue(cart.isFile)
        val personal = Gen6Personal.load(cart)!!
        fun types(species: Int) = personal[species].map { Gen6Moves.TYPES[it] }
        assertEquals(listOf("FIRE", "FLYING"), types(6))
        assertEquals(listOf("ELECTRIC", "ELECTRIC"), types(25))
        assertEquals(listOf("PSYCHIC", "FAIRY"), types(282))
        assertEquals(listOf("FAIRY", "FAIRY"), types(716))
    }

    /** The icon table as the game's party screen drew it (the RP6, 2026-11-15), and the cart's icons decoded. */
    @Test fun iconTableAndTheCartsIcons() {
        assertEquals(1, Gen6IconTable.index(1, 0, female = false))
        assertEquals(4, Gen6IconTable.index(3, 0, female = true))         // Venusaur: no female icon
        assertEquals(3, Gen6IconTable.index(3, 1, female = false))        // its Mega, before it
        assertEquals(7, Gen6IconTable.index(6, 1, female = false))
        assertEquals(8, Gen6IconTable.index(6, 2, female = false))
        assertEquals(22, Gen6IconTable.index(18, 0, female = false))      // Pidgeot: 8-bit pixels
        assertEquals(833, Gen6IconTable.index(668, 0, female = true))     // Pyroar's female
        assertEquals(834, Gen6IconTable.index(668, 0, female = false))
        assertEquals(886, Gen6IconTable.index(658, 0, female = false))    // the player's Greninja
        assertNull(Gen6IconTable.index(722, 0, female = false))
        val cart = File(File(RETAIL_ROM_DIR).parentFile, "3ds/Pokemon X (USA) (En,Ja,Fr,De,Es,It,Ko).3ds")
        assumeTrue(cart.isFile)
        val files = RomFs(cart).use { it.read("a/0/9/3") }!!.let { Gen6Text.garcFiles(it) }
        assertEquals(945, files.size)
        for (i in listOf(1, 22, 886, 944)) {
            val img = Gen6Icons.decode(files[i])!!
            assertTrue("icon $i has pixels", img.argb.count { it != 0 } > 100)
        }
    }

    /** The field menu's bytes ([Gen6Reader.Config.fieldMenuRoot]) as the RP6 read them: the menu's pointer and the byte at +0x24. */
    private fun fieldMenu(pointer: Int, flag: Int): ByteArray =
        ByteBuffer.allocate(Gen6Reader.FIELD_MENU_BYTES).order(ByteOrder.LITTLE_ENDIAN).apply {
            putInt(0, pointer)
            put(0x24, flag.toByte())
        }.array()

    @Test
    fun sceneFromModulesAndTheFieldMenu() {
        fun scene(modules: String, menu: ByteArray?) = Gen6Reader.sceneOf(modules, menu)
        // The field (also in dialogue and through doors: both 0) and its X menu (2026-11-15, two saves).
        assertEquals(Gen6Reader.Scene.FIELD, scene("DllField\nDllUSC02", fieldMenu(0, 0)))
        assertEquals(Gen6Reader.Scene.FIELD, scene("DllField\nDllPssMessageWindow", fieldMenu(0, 0)))
        assertEquals(Gen6Reader.Scene.MENU, scene("DllField\nDllUSC02", fieldMenu(0x086511A4, 1)))
        assertEquals(Gen6Reader.Scene.MENU, scene("DllField", fieldMenu(0x0862C97C, 1)))
        // Half of it isn't the menu.
        assertEquals(Gen6Reader.Scene.FIELD, scene("DllField", fieldMenu(0x086511A4, 0)))
        assertEquals(Gen6Reader.Scene.FIELD, scene("DllField", fieldMenu(0, 1)))
        assertEquals(Gen6Reader.Scene.FIELD, scene("DllField", null))
        // Save's question waits on the touch screen alone (the menu's marker cleared meanwhile; the Thor, 2026-11-15).
        assertEquals(Gen6Reader.Scene.MENU, scene("DllField\nDllUSC02\nDllReport", fieldMenu(0, 0)))
        // A screen of the game's own: the party, the Pokedex, the trainer card, the options.
        assertEquals(Gen6Reader.Scene.SCREEN, scene("DllPokeList", null))
        assertEquals(Gen6Reader.Scene.SCREEN, scene("DllTownmap\nDllZukan\nDllBackGround", null))
        assertEquals(Gen6Reader.Scene.SCREEN, scene("DllTrainerCase", null))
        assertEquals(Gen6Reader.Scene.SCREEN, scene("DllConfig", null))
        // Between two screens and on the title: can't tell.
        // START's PSS menu (and a page of it) waits on the touch screen: MENU, as the X menu; its notes don't.
        assertEquals(Gen6Reader.Scene.MENU, scene("DllField\nDllPssMainMenu", fieldMenu(0, 0)))
        assertEquals(Gen6Reader.Scene.MENU, scene("DllField\nDllUSC02\nDllPssCharaMenu", fieldMenu(0, 0)))
        assertEquals(Gen6Reader.Scene.FIELD, scene("DllField\nDllUSC02\nDllPssMessageWindow", fieldMenu(0, 0)))
        assertNull(scene("", null))
        assertNull(scene("DllTitle\nDllBackGround\nDllSequence", null))
        assertNull(scene("DllStartMenu", null))
    }
}
