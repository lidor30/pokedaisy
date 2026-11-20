package com.pokedaisy.app.companion

import com.pokedaisy.app.cheats.Cheat
import com.pokedaisy.app.cheats.CheatStore
import kotlin.random.Random
import kotlin.test.*
import okio.Buffer
import okio.FileSystem
import okio.Path
import okio.SYSTEM

class GameCheatsTest {
    private inline fun withStore(test: (CheatStore) -> Unit) {
        val dir = FileSystem.SYSTEM_TEMPORARY_DIRECTORY / "pokedaisy-game-cheats-${Random.nextLong().toULong()}"
        FileSystem.SYSTEM.createDirectories(dir)
        try { test(CheatStore(dir / "test.cheats")) }
        finally { FileSystem.SYSTEM.deleteRecursively(dir) }
    }

    @Test fun listAndMasterSwitchProduceOnlyEnabledTextAndSurviveReopening() = withStore { store ->
        val cheat = Cheat("Diagnostic byte", listOf("02000010:7F"), enabled = true)
        store.save(listOf(cheat))
        var enabled = false
        val texts = mutableListOf<String>()
        val session = GameCheats(true, { store }, { enabled }, { texts += it })
        session.start()
        assertEquals(listOf(cheat), session.list); assertEquals(listOf(""), texts)
        enabled = true; session.apply(); assertTrue(texts.last().contains("02000010:7F"))
        session.setEnabled(0, false); assertEquals("", texts.last()); assertFalse(store.load().single().enabled)
        session.remove(0); assertTrue(session.list.isEmpty()); assertFalse(FileSystem.SYSTEM.exists(store.file))
        session.add(cheat)
        val reopened = GameCheats(true, { store }, { true }, {})
        reopened.refresh(); assertEquals(listOf(cheat), reopened.list)
        val before = texts.size; session.setEnabled(9, true); assertEquals(before, texts.size)
    }

    @Test fun importingUsesTheSameValidationAndKeepsNewCodesDisabled() = withStore { store ->
        val texts = mutableListOf<String>()
        val session = GameCheats(true, { store }, { true }, { texts += it }).also { it.start() }
        val text = "\uFEFF# Diagnostic\n02000010 0000007F\n"
        val result = assertNotNull(session.import(Buffer().writeUtf8(text), "fallback", ds = true))
        assertEquals(1, result.added); assertFalse(session.list.single().enabled)
        assertEquals("", texts.last())
        val again = assertNotNull(session.import(Buffer().writeUtf8(text), "fallback", ds = true))
        assertEquals(1, again.duplicates); assertEquals(1, session.list.size)
    }

    @Test fun oversizedAndEmptySourcesNeverChangeTheStoredListOrCore() = withStore { store ->
        val cheat = Cheat("Keep", listOf("02000010:7F"), enabled = true)
        store.save(listOf(cheat))
        var submissions = 0
        val session = GameCheats(true, { store }, { true }, { submissions++ }).also { it.refresh() }
        val source = Buffer().write(ByteArray(GameCheats.MAX_FILE_BYTES + 64) { 65 })
        assertNull(session.import(source, "too large"))
        assertEquals(63L, source.size)
        assertNull(session.import(Buffer(), "empty"))
        assertEquals(listOf(cheat), store.load()); assertEquals(listOf(cheat), session.list)
        assertEquals(0, submissions)
    }

    @Test fun unavailableGamesDoNotOpenStorageOrSubmit() {
        var opened = false; var submitted = false
        val session = GameCheats(false, { opened = true; null }, { true }, { submitted = true })
        session.start(); session.refresh(); session.apply()
        assertFalse(opened); assertFalse(submitted); assertFalse(session.editable)
    }

    @Test fun closingRejectsDelayedEditsAndImportsWithoutWritingOrSubmitting() = withStore { store ->
        val cheat = Cheat("Diagnostic byte", listOf("02000010:7F"), enabled = true)
        store.save(listOf(cheat))
        val submitted = mutableListOf<String>()
        val session = GameCheats(true, { store }, { true }, { submitted += it })
        session.start(); val applied = submitted.toList()
        session.close(); session.close()
        session.setEnabled(0, false); session.remove(0); session.add(cheat.copy(name = "Late"))
        assertNull(session.import(Buffer().writeUtf8("02000010:01"), "Late"))
        session.refresh(); session.start(); session.apply()
        assertFalse(session.editable)
        assertEquals(listOf(cheat), store.load())
        assertEquals(applied, submitted)
    }
}
