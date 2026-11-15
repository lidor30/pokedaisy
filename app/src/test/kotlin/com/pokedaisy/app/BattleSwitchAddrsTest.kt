package com.pokedaisy.app

import com.pokedaisy.app.companion.TelemetryStore
import com.pokedaisy.app.companion.data.GameKind
import org.junit.Assert.*
import org.junit.Test

class BattleSwitchAddrsTest {
    private val telemetry = TelemetryStore()

    @Test fun fireRedRequiresTheVerifiedRevision() {
        assertNull(battleSwitchAddrsFor(telemetry, GameKind.FIRERED, "BPRE", 0))
        assertEquals(0x0203B0A0L, battleSwitchAddrsFor(telemetry, GameKind.FIRERED, "BPRE", 1)?.partyMenu)
        assertNull(battleSwitchAddrsFor(telemetry, null, "BPRE", 1))
    }

    @Test fun emeraldAndPackedGridHacksKeepTheirOwnLayouts() {
        val emerald = battleSwitchAddrsFor(telemetry, GameKind.EMERALD, "BPEE", 0)!!
        assertEquals(0x0203CEC8L, emerald.partyMenu)
        assertEquals(100, emerald.monStride)
        assertFalse(emerald.grid)
        val rowe = battleSwitchAddrsFor(telemetry, GameKind.ROWE, "BPEE", 0)!!
        assertEquals(0x4C, rowe.monStride)
        assertTrue(rowe.grid)
    }
}
