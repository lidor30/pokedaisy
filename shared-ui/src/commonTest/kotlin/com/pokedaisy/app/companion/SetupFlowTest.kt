package com.pokedaisy.app.companion

import kotlin.test.*

class SetupFlowTest {
    @Test fun platformStepsDetermineNavigationAndEntryEffects() {
        for (steps in listOf(listOf("roms", "saves"), listOf("roms", "saves", "buttons", "covers"))) {
            val entered = mutableListOf<String>()
            val exits = mutableListOf<SetupFlow.Exit>()
            val flow = SetupFlow(steps, entered = { entered += it }, exited = { exits += it })
            assertFalse(flow.canGoBack)
            while (!flow.last) {
                flow.next(flow.visit)
                assertTrue(flow.canGoBack)
            }
            assertEquals(steps.drop(1), entered)
            flow.next(flow.visit)
            flow.next(flow.visit); flow.back(flow.visit)
            assertFalse(flow.active)
            assertEquals(listOf(SetupFlow.Exit.COMPLETE), exits)
        }
    }

    @Test fun busyBlocksButtonsAndHardwareBackUntilWorkFinishes() {
        val flow = SetupFlow(listOf("roms", "saves"))
        val roms = flow.visit
        flow.busy = true
        flow.next(roms); flow.back(roms); flow.select("saves")
        assertTrue(flow.active); assertSame(roms, flow.visit)
        flow.busy = false
        flow.next(roms)
        assertEquals("saves", flow.step)
        flow.busy = true
        flow.next(flow.visit); flow.back(flow.visit)
        assertTrue(flow.active); assertEquals("saves", flow.step)
        flow.busy = false
        flow.back(flow.visit)
        assertEquals("roms", flow.step)
    }

    @Test fun callbacksFromOldPagesAndReplacedRunsCannotNavigate() {
        val exits = mutableListOf<SetupFlow.Exit>()
        val flow = SetupFlow(listOf("roms", "saves"), exited = { exits += it })
        val firstRoms = flow.visit
        flow.next(firstRoms)
        val firstSaves = flow.visit
        flow.back(firstSaves)
        flow.next(firstRoms)
        assertEquals("roms", flow.step)
        flow.next(flow.visit)
        flow.next(firstSaves)
        assertTrue(flow.active)
        flow.close()
        flow.next(flow.visit); flow.back(flow.visit); flow.select("roms")
        assertEquals("saves", flow.step); assertTrue(exits.isEmpty())
        val replacement = SetupFlow(listOf("roms", "saves"))
        replacement.next(flow.visit)
        assertEquals("roms", replacement.step)
    }

    @Test fun firstStepBackReportsReasonAndNativeHostOwnsCompletionPolicy() {
        for (fromSettings in listOf(false, true)) {
            var exit: SetupFlow.Exit? = null
            lateinit var flow: SetupFlow<Int>
            flow = SetupFlow(listOf(0, 1), fromSettings, exited = {
                assertFalse(flow.active)
                exit = it
            })
            assertEquals(fromSettings, flow.canGoBack)
            flow.back(flow.visit)
            assertEquals(SetupFlow.Exit.BACK, exit)
        }
    }

    @Test fun unsupportedAndAmbiguousStepListsAreRejected() {
        assertFailsWith<IllegalArgumentException> { SetupFlow(emptyList<Int>()) }
        assertFailsWith<IllegalArgumentException> { SetupFlow(listOf(0, 0)) }
        val flow = SetupFlow(listOf(0, 1))
        assertFailsWith<IllegalArgumentException> { flow.select(2) }
        assertEquals(0, flow.step)
    }
}
