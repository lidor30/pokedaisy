package com.pokedaisy.app

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import kotlin.random.Random

class SaveBackupsTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val flash = Random(7).nextBytes(131072)

    @Test
    fun backsUpOnlyWhenTheSaveChanged() {
        val save = tmp.newFile("Pokemon Unbound.srm").apply { writeBytes(flash) }
        val first = SaveBackups.snapshot(save, now = 1_000_000L)
        assertNotNull(first)
        assertArrayEquals(flash, first!!.readBytes())
        assertEquals(SaveBackups.DIR_NAME, first.parentFile!!.name)
        assertTrue(first.name.startsWith("Pokemon Unbound.backup-") && first.name.endsWith(".srm"))

        // Same data, only mGBA's RTC footer moved on: no new copy.
        save.writeBytes(flash + ByteArray(16) { 1 })
        assertNull(SaveBackups.snapshot(save, now = 2_000_000L))

        save.writeBytes(flash.copyOf().also { it[100] = (it[100] + 1).toByte() })
        assertNotNull(SaveBackups.snapshot(save, now = 3_000_000L))
        assertEquals(2, SaveBackups.list(save).size)
    }

    @Test
    fun keepsTheNewest() {
        val save = tmp.newFile("game.sav")
        repeat(SaveBackups.KEEP + 3) { i ->
            save.writeBytes(flash.copyOf().also { it[0] = i.toByte() })
            SaveBackups.snapshot(save, now = 1_000_000L * (i + 1))
        }
        val kept = SaveBackups.list(save)
        assertEquals(SaveBackups.KEEP, kept.size)
        assertEquals((SaveBackups.KEEP + 2).toByte(), kept.first().readBytes()[0])
    }

    @Test
    fun skipsMissingOrEmptySaves() {
        assertNull(SaveBackups.snapshot(tmp.root.resolve("none.sav")))
        assertNull(SaveBackups.snapshot(tmp.newFile("empty.sav")))
        assertFalse(SaveBackups.dir(tmp.root.resolve("x.sav")).exists())
    }

    @Test
    fun offTakesNoneButTheEditorStillDoes() {
        val save = tmp.newFile("off.sav").apply { writeBytes(flash) }
        val off = SaveBackups.Settings(enabled = false)
        assertNull(SaveBackups.snapshot(save, settings = off))
        assertFalse(SaveBackups.dir(save, off).exists())
        assertNotNull(SaveBackups.snapshot(save, settings = off, force = true))
    }

    @Test
    fun keepsWhatKeepPerGameSays() {
        val save = tmp.newFile("few.sav")
        val three = SaveBackups.Settings(keep = 3)
        repeat(6) { i ->
            save.writeBytes(flash.copyOf().also { it[0] = i.toByte() })
            SaveBackups.snapshot(save, now = 1_000_000L * (i + 1), settings = three)
        }
        assertEquals(3, SaveBackups.list(save, three).size)
        // Lowered later: the extra ones go at the next start, even with the save unchanged.
        val one = SaveBackups.Settings(keep = 1)
        assertNull(SaveBackups.snapshot(save, now = 9_000_000L, settings = one))
        assertEquals(1, SaveBackups.list(save, one).size)
        assertEquals(5.toByte(), SaveBackups.list(save, one).first().readBytes()[0])
    }

    @Test
    fun oneFolderForEveryGame() {
        val saves = tmp.newFolder("saves")
        val folder = tmp.newFolder("backups")
        val save = File(saves, "Pokemon Emerald.sav").apply { writeBytes(flash) }
        val beside = SaveBackups.snapshot(save, now = 1_000_000L)!!
        val custom = SaveBackups.Settings(folder = folder)
        // The folder has none yet: a copy goes there even though the one beside the save is the same.
        val moved = SaveBackups.snapshot(save, now = 2_000_000L, settings = custom)!!
        assertEquals(folder, moved.parentFile)
        assertTrue(beside.exists())
        // RESTORE BACKUP lists both places, newest first.
        assertEquals(listOf(moved, beside), SaveBackups.list(save, custom))
        // Another game's backups in the same folder aren't this one's.
        File(saves, "Pokemon Emerald Kaizo.sav").writeBytes(flash)
        SaveBackups.snapshot(File(saves, "Pokemon Emerald Kaizo.sav"), now = 3_000_000L, settings = custom)
        assertEquals(2, SaveBackups.list(save, custom).size)
    }
}
