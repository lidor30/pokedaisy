package com.pokedaisy.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.nio.file.Files

/** Linking several ROMs folders ([RomFolder.coveredBy], [RomFolder.withFolder]): each file is looked through once. */
class RomFolderTest {
    private val gba = "/storage/emulated/0/ROMs/gba"
    private val nds = "/storage/emulated/0/ROMs/nds"

    @Test fun aFolderBesideTheOthersIsAdded() {
        assertNull(RomFolder.coveredBy(listOf(gba), nds))
        assertEquals(listOf(gba, nds), RomFolder.withFolder(listOf(gba), nds))
    }

    @Test fun theSameFolderOrOneInsideIsAlreadyLinked() {
        assertEquals(gba, RomFolder.coveredBy(listOf(gba, nds), gba))
        assertEquals(gba, RomFolder.coveredBy(listOf(gba, nds), "$gba/Retail"))
        assertEquals(gba, RomFolder.coveredBy(listOf(gba), "$gba/"))
    }

    @Test fun aNameThatOnlyStartsTheSameIsNotInside() {
        assertNull(RomFolder.coveredBy(listOf(gba), "${gba}c"))
    }

    @Test fun aFolderHoldingLinkedOnesTakesTheirPlace() {
        val roms = "/storage/emulated/0/ROMs"
        val sd = "/storage/XXXX-XXXX/gb"
        assertEquals(listOf(sd, roms), RomFolder.withFolder(listOf(gba, sd, nds), roms))
    }

    /** One folder under two names (/sdcard is /storage/emulated/0): already linked, and a folder holding the other name
     * replaces it (a player's library listed every ROM twice). */
    @Test fun aFolderLinkedUnderAnotherNameIsTheSame() {
        val root = Files.createTempDirectory("roms").toFile()
        val real = java.io.File(root, "emulated/ROMs/gba").apply { mkdirs() }
        val alias = java.io.File(root, "sdcard")
        Files.createSymbolicLink(alias.toPath(), java.io.File(root, "emulated").toPath())
        val viaAlias = java.io.File(alias, "ROMs/gba").path
        assertEquals(real.path, RomFolder.coveredBy(listOf(real.path), viaAlias))
        assertEquals(real.path, RomFolder.coveredBy(listOf(real.path), "$viaAlias/Retail"))
        assertEquals(listOf(java.io.File(alias, "ROMs").path), RomFolder.withFolder(listOf(real.path), java.io.File(alias, "ROMs").path))
        root.deleteRecursively()
    }
}
