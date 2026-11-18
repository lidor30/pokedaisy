package com.pokedaisy.app.ios

import com.pokedaisy.app.GameSaves
import com.pokedaisy.app.companion.data.StateFiles
import com.pokedaisy.app.companion.data.SnapshotCache
import com.pokedaisy.app.game.RomPicker
import kotlin.random.Random
import kotlin.test.*
import okio.*
import okio.Path.Companion.toPath

class IosGameSavesTest {
    @Test fun nativeHostKeepsItsLocationsAndBackupWhileUsingSharedImportRules() {
        val fs = FileSystem.SYSTEM
        val nonce = Random.nextLong().toULong()
        val dir = FileSystem.SYSTEM_TEMPORARY_DIRECTORY / "pokedaisy-native-save-import-$nonce"
        fs.createDirectories(dir)
        val rom = dir / "Diagnostic-$nonce.gba"
        fs.write(rom) { write(Random.nextBytes(1024)) }
        val current = RomPicker.savePathFor(rom.toString()).toPath()
        val stateDir = RomPicker.statesDir.toPath() / StateFiles.romKey(rom)
        assertFalse(fs.exists(current)); assertFalse(fs.exists(stateDir))
        val states = StateFiles(stateDir)
        val base = current.name.substringBeforeLast('.')
        try {
            fs.createDirectories(current.parent!!)
            val old = ByteArray(512) { 1 }; val picked = ByteArray(512) { 2 }
            fs.write(current) { write(old) }
            fs.write(states.resumeFile) { writeUtf8("old automatic state") }
            fs.write(SnapshotCache.fileFor(states.resumeFile)) { writeUtf8("old companion") }
            val source = dir / "picked.sav"
            fs.write(source) { write(picked) }
            assertNull(PokeDaisyHost.loadSave(rom.toString(), source.toString()))
            assertContentEquals(picked, fs.read(current) { readByteArray() })
            val named = GameSaves.backups(current.parent!!, base).single()
            assertContentEquals(old, fs.read(named) { readByteArray() })
            assertTrue(fs.exists(states.freshBootFile)); assertFalse(fs.exists(states.resumeFile))
            assertFalse(fs.exists(SnapshotCache.fileFor(states.resumeFile)))
            assertNotNull(PokeDaisyHost.loadSave(rom.toString(), byteArrayOf()))
            assertContentEquals(picked, fs.read(current) { readByteArray() })
        } finally {
            fs.delete(current)
            GameSaves.backups(current.parent!!, base).forEach { fs.delete(it) }
            val backups = current.parent!! / "pokedaisy-backups"
            fs.listOrNull(backups).orEmpty().filter { it.name.startsWith(current.name + ".") }.forEach { fs.delete(it) }
            fs.deleteRecursively(stateDir); fs.deleteRecursively(dir)
        }
    }
}
