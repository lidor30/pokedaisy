package com.pokedaisy.app.game

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RomPickerTest {
    @Test
    fun aGameKeepsItsSaveInSaves() {
        val rom = "${RomPicker.romsDir}/Pokemon Emerald (USA).gba"
        assertTrue(RomPicker.romsDir.endsWith("/roms"))
        val save = RomPicker.savePathFor(rom)
        assertEquals("Pokemon Emerald (USA).sav", save.substringAfterLast('/'))
        assertTrue(save.substringBeforeLast('/').endsWith("/saves"))
    }
}
