package com.pokedaisy.app.companion

import com.pokedaisy.app.MgbaCore
import com.pokedaisy.app.SaveStates
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import java.io.File

class DsCoreTest {
    @Test
    fun autoPicksDsperateOnWeakOr32BitDevices() {
        assertEquals(MgbaCore.NDS_CORE_MELONDS, DsCore.AUTO.runs(weak = false, sixtyFour = true))
        assertEquals(MgbaCore.NDS_CORE_DSPERATE, DsCore.AUTO.runs(weak = true, sixtyFour = true))
        assertEquals(MgbaCore.NDS_CORE_DSPERATE, DsCore.AUTO.runs(weak = false, sixtyFour = false))
    }

    @Test
    fun aPickIsKeptWhateverTheDevice() {
        for (weak in listOf(false, true)) for (sixtyFour in listOf(false, true)) {
            assertEquals(MgbaCore.NDS_CORE_MELONDS, DsCore.MELONDS.runs(weak, sixtyFour))
            assertEquals(MgbaCore.NDS_CORE_DSPERATE, DsCore.DSPERATE.runs(weak, sixtyFour))
        }
    }

    @Test
    fun idsRoundTripAndUnknownIsAuto() {
        for (c in DsCore.entries) assertEquals(c, DsCore.of(c.id))
        assertEquals(DsCore.AUTO, DsCore.of(99))
    }

    @Test
    fun eachCoreParksItsResumeStateApart() {
        val resume = File("/states/1234abcd", "resume")
        val melon = SaveStates.parkedResume(resume, MgbaCore.NDS_CORE_MELONDS)
        val dsp = SaveStates.parkedResume(resume, MgbaCore.NDS_CORE_DSPERATE)
        assertNotEquals(melon, dsp)
        assertNotEquals(resume, melon)
        assertEquals(resume.parentFile, melon.parentFile)
        assertEquals(resume.parentFile, dsp.parentFile)
    }
}
