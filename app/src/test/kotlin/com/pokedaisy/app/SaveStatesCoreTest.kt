package com.pokedaisy.app

import com.pokedaisy.app.companion.DsCore
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** A DS state's core by its first word (pk_nds_cores.h's PK_NDS_STATE_MAGIC_*), and where a resume left on one waits. */
class SaveStatesCoreTest {
    private fun state(dir: File, name: String, magic: String?) = File(dir, name).apply { writeBytes(magic?.toByteArray(Charsets.US_ASCII)?.plus(ByteArray(12)) ?: ByteArray(2)) }

    @Test
    fun `each core's states are told apart, and their resumes kept apart`() {
        val dir = Files.createTempDirectory("states").toFile()
        try {
            assertEquals(DsCore.CORE_MELONDS, SaveStates.stateCore(state(dir, "a", "PKDS")))
            assertEquals(DsCore.CORE_DSPERATE, SaveStates.stateCore(state(dir, "b", "PKDP")))
            assertEquals(DsCore.CORE_BANANADS, SaveStates.stateCore(state(dir, "c", "PKDB")))
            // Too short, or a word nobody writes: melonDS's (as before there were three cores).
            assertEquals(DsCore.CORE_MELONDS, SaveStates.stateCore(state(dir, "d", null)))
            assertEquals(DsCore.CORE_MELONDS, SaveStates.stateCore(File(dir, "missing")))
            val resume = File(dir, "resume.ss")
            assertEquals("resume.melonds", SaveStates.parkedResume(resume, DsCore.CORE_MELONDS).name)
            assertEquals("resume.dsperate", SaveStates.parkedResume(resume, DsCore.CORE_DSPERATE).name)
            assertEquals("resume.bananads", SaveStates.parkedResume(resume, DsCore.CORE_BANANADS).name)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `BananaDS is offered only where it can start`() {
        val before = DsCore.bananaRuns
        try {
            DsCore.bananaRuns = false
            assertEquals(listOf(DsCore.AUTO, DsCore.MELONDS, DsCore.DSPERATE), DsCore.offered())
            DsCore.bananaRuns = true
            assertEquals(DsCore.entries.toList(), DsCore.offered())
            // Its features as the rows show them: EXPAND and DS CPU BOOST, no DS RESOLUTION (no Gengis here).
            assertEquals(true, DsCore.hasExpand(DsCore.CORE_BANANADS))
            assertEquals(true, DsCore.boosts(DsCore.CORE_BANANADS))
            assertEquals(false, DsCore.hasResolution(DsCore.CORE_BANANADS))
        } finally {
            DsCore.bananaRuns = before
        }
    }
}
