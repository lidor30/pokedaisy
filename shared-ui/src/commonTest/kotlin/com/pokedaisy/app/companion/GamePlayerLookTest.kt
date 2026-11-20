package com.pokedaisy.app.companion

import com.pokedaisy.app.companion.data.PlayerLook
import com.pokedaisy.app.companion.data.TextLook
import com.pokedaisy.app.game.PlayerLookCore
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GamePlayerLookTest {
    @Test fun equivalentChoicesSkipReapplyAndOnlyInstalledPalettesAreRestored() {
        val core = Core()
        val look = GamePlayerLook(core)
        val palette = ByteArray(32) { 0x11 }
        fun patch(bytes: ByteArray) = PlayerLook.Patch(
            listOf(PlayerLook.Block(0x20, -1, bytes)),
            listOf(PlayerLook.Pal(listOf(ByteArray(32)), palette)),
            palette,
        )

        look.select(patch(byteArrayOf(1, 2)))
        assertTrue(look.service())
        assertContentEquals(intArrayOf(0x08000020), core.addresses)
        assertContentEquals(byteArrayOf(1, 2), core.bytes)
        look.select(patch(byteArrayOf(1, 2)))
        assertFalse(look.service())
        look.settle()
        assertEquals(2, core.swaps)
        look.fieldFrame()
        assertContentEquals(palette, core.lastPalette)

        core.reject = true
        look.select(patch(byteArrayOf(3)))
        assertTrue(look.service())
        look.settle()
        look.fieldFrame()
        assertEquals(2, core.swaps)
        assertNull(core.lastPalette)

        core.reject = false
        look.select(null)
        assertTrue(look.service())
        assertTrue(core.addresses.isEmpty())
    }

    @Test fun clearingTextLeavesTheChosenHeroInstalled() {
        val core = Core()
        val look = GamePlayerLook(core)
        val palette = ByteArray(32) { 0x11 }
        val hero = PlayerLook.Patch(listOf(PlayerLook.Block(0x20, -1, byteArrayOf(1, 2))), emptyList(), palette)
        val text = listOf(PlayerLook.Block(0x40, -1, byteArrayOf(3)))

        look.select(TextLook.withAppearance(null, text))
        assertTrue(look.service())
        assertContentEquals(intArrayOf(0x08000040), core.addresses)

        look.select(TextLook.withAppearance(hero, text))
        assertTrue(look.service())
        assertContentEquals(intArrayOf(0x08000020, 0x08000040), core.addresses)
        assertContentEquals(byteArrayOf(1, 2, 3), core.bytes)
        look.fieldFrame()
        assertContentEquals(palette, core.lastPalette)

        look.select(TextLook.withAppearance(hero, emptyList()))
        assertTrue(look.service())
        assertContentEquals(intArrayOf(0x08000020), core.addresses)
        assertContentEquals(byteArrayOf(1, 2), core.bytes)
    }

    private class Core : PlayerLookCore {
        var reject = false
        var addresses = intArrayOf()
        var bytes = byteArrayOf()
        var swaps = 0
        var lastPalette: ByteArray? = null

        override fun replaceLookBlocks(addresses: IntArray, lengths: IntArray, crcs: IntArray, data: ByteArray): Int {
            this.addresses = addresses
            bytes = data
            return if (reject) -1 else addresses.size
        }

        override fun repaletteLook(from: ByteArray, to: ByteArray): Int {
            swaps++
            return 1
        }

        override fun refreshLook(palette: ByteArray?): Boolean {
            lastPalette = palette
            return true
        }
    }
}
