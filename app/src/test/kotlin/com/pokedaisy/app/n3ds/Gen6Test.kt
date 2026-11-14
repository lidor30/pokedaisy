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

    /** The field object's 0x20 bytes as the RP6 read them (+0x10 the flags, +4 the pointer that goes with an app). */
    private fun fieldObject(flags: Int, pointer: Int = 0x082AA72C, size: Int = 0x20): ByteArray =
        ByteBuffer.allocate(0x20).order(ByteOrder.LITTLE_ENDIAN).apply {
            putInt(0, size)
            putInt(4, pointer)
            putInt(8, 0x01010010)
            putInt(0x10, flags)
        }.array()

    @Test
    fun sceneFromModulesAndTheFieldObject() {
        val s = Gen6Reader.Scene.values()
        fun scene(modules: String, obj: ByteArray?) = Gen6Reader.sceneOf(modules, obj)
        // The field and its X menu (the flags seen on the RP6, 2026-11-15).
        assertEquals(Gen6Reader.Scene.FIELD, scene("DllField\nDllUSC02", fieldObject(0x3D1)))
        assertEquals(Gen6Reader.Scene.MENU, scene("DllField\nDllUSC02", fieldObject(0x3D3)))
        assertEquals(Gen6Reader.Scene.MENU, scene("DllField\nDllUSC02\nDllPssMessageWindow", fieldObject(0x3D3)))
        // The field loading (the menu's bit set already): not the menu.
        assertEquals(Gen6Reader.Scene.FIELD, scene("DllField\nDllUSC02", fieldObject(0x153)))
        assertEquals(Gen6Reader.Scene.FIELD, scene("DllField", fieldObject(0xD3)))
        // On its way to an app: the field's pointer gone.
        assertEquals(Gen6Reader.Scene.FIELD, scene("DllField", fieldObject(0x102, pointer = 0)))
        // Something else at that address: the field.
        assertEquals(Gen6Reader.Scene.FIELD, scene("DllField", fieldObject(0x3D3, size = 0x40)))
        assertEquals(Gen6Reader.Scene.FIELD, scene("DllField", null))
        // A screen of the game's own.
        assertEquals(Gen6Reader.Scene.SCREEN, scene("DllPokeList", null))
        // Between two screens and on the title: can't tell.
        assertNull(scene("", null))
        assertNull(scene("DllTitle\nDllBackGround\nDllSequence", null))
        assertNull(scene("DllStartMenu", null))
        assertEquals(3, s.size)
    }
}
