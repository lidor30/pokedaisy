package com.pokedaisy.app.companion.coop.lock

import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class CoopLockStoreTest {
    @get:Rule val tmp = TemporaryFolder()
    private fun file() = File(tmp.root, "coop/locks.json")
    @Test fun locksAPairAndSurvivesReload() {
        CoopLockStore(file()).lock("a", "b")
        val s = CoopLockStore(file())
        assertTrue(s.isLocked("a", "b")); assertFalse(s.isLocked("a", "c")); assertFalse(s.isLocked("x", "b"))
    }
    @Test fun unlockRemoves() { val s = CoopLockStore(file()); s.lock("a", "b"); s.lock("c", "d"); s.unlock("a")
        assertFalse(s.isLocked("a", "b")); assertTrue(s.isLocked("c", "d")) }
    @Test fun unreadableFileIsMovedAsideAndReadsEmpty() {
        val f = file(); f.parentFile.mkdirs(); f.writeText("not json{")
        val s = CoopLockStore(f)
        assertFalse(s.isLocked("a", "b")); assertTrue(File(f.parentFile, "locks.json.bad").isFile); assertFalse(f.exists())
    }
}
