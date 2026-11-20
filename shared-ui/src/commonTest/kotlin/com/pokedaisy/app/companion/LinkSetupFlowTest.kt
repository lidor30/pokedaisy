package com.pokedaisy.app.companion

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LinkSetupFlowTest {
    @Test fun aDuplicateCartridgeHasItsOwnSlotAndCanBeRemoved() {
        val setup = LinkSetupFlow<String>()
        setup.seed("Emerald")
        repeat(3) {
            val scan = assertNotNull(setup.begin(setup.slots.size))
            assertTrue(setup.ready(scan, listOf("Emerald")))
            setup.choose(assertNotNull(setup.choice), "Emerald")
        }
        assertEquals(listOf("Emerald", "Emerald", "Emerald", "Emerald"), setup.slots.toList())
        assertEquals("Emerald, save 4", setup.rowLabel(3, { it }, { it }))
        assertNull(setup.begin(4))
        assertTrue(setup.ready(assertNotNull(setup.begin(1)), listOf("Emerald")))
        setup.choose(assertNotNull(setup.choice), null)
        assertEquals(3, setup.slots.size)
    }

    @Test fun lateScansAndDismissedSelectionsCannotChangeTheCurrentSlots() {
        val setup = LinkSetupFlow<String>()
        setup.seed("Ruby")
        val old = assertNotNull(setup.begin(0))
        val current = assertNotNull(setup.begin(1))
        assertFalse(setup.ready(old, listOf("Sapphire")))
        assertTrue(setup.ready(current, listOf("Emerald")))
        val choice = assertNotNull(setup.choice)
        setup.dismiss(choice)
        setup.choose(choice, "Emerald")
        assertEquals(listOf("Ruby"), setup.slots.toList())
        val pending = assertNotNull(setup.begin(1))
        setup.clearPicker()
        assertFalse(setup.ready(pending, listOf("Emerald")))
    }
}
