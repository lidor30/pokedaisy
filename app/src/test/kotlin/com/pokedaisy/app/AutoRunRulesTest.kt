package com.pokedaisy.app

import com.pokedaisy.app.companion.data.GAMES_WITH_OWN_AUTO_RUN
import com.pokedaisy.app.companion.data.GameKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoRunRulesTest {
    /** Under the sea B is what surfaces: flipped while walking, the player came back up at once (GitHub #338). */
    @Test fun leavesBAloneWhileSurfingOrDiving() {
        assertEquals(MgbaCore.FIELD_SURF, EmulatorEngine.AUTO_RUN_BLOCKERS and MgbaCore.FIELD_SURF)
    }

    /** Their own auto-run starts on, so flipping B there walked and B ran. */
    @Test fun leavesGamesThatRunByThemselves() {
        assertTrue(GameKind.SOULGOLD in GAMES_WITH_OWN_AUTO_RUN)
        assertTrue(GameKind.ODYSSEY in GAMES_WITH_OWN_AUTO_RUN)
    }
}
