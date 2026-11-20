package com.pokedaisy.app.companion.ui

import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.platform.LocalFocusManager
import kotlin.test.*
import kotlinx.coroutines.*

class OverlayPresenceTest {
    private class Focus : FocusManager {
        var clears = 0
        override fun clearFocus(force: Boolean) { assertTrue(force); clears++ }
        override fun moveFocus(focusDirection: FocusDirection) = false
    }

    private class EmptyApplier : AbstractApplier<Unit>(Unit) {
        override fun insertBottomUp(index: Int, instance: Unit) {}
        override fun insertTopDown(index: Int, instance: Unit) {}
        override fun move(from: Int, to: Int, count: Int) {}
        override fun remove(index: Int, count: Int) {}
        override fun onClear() {}
    }

    private class Scene(val composition: Composition, val recomposer: Recomposer, val clock: BroadcastFrameClock) {
        private var time = 0L
        suspend fun settle() {
            repeat(3) { Snapshot.sendApplyNotifications(); yield(); time += 16_000_000; clock.sendFrame(time) }
            recomposer.awaitIdle()
        }
    }

    private fun scene(test: suspend Scene.() -> Unit) = runBlocking {
        val clock = BroadcastFrameClock()
        val recomposer = Recomposer(coroutineContext + clock)
        val composition = Composition(EmptyApplier(), recomposer)
        val runner = launch(clock, start = CoroutineStart.UNDISPATCHED) { recomposer.runRecomposeAndApplyChanges() }
        try { Scene(composition, recomposer, clock).test() }
        finally { composition.dispose(); recomposer.cancel(); runner.join() }
    }

    @Test fun disposingTheOverlayReleasesItsHostEvenWhenItWasStillOpen() = scene {
        val captured = mutableListOf<Boolean>()
        composition.setContent { OverlayPresence(true) { captured += it } }
        settle()
        assertEquals(true, captured.last())
        composition.dispose()
        assertEquals(false, captured.last())
    }

    @Test fun removingAnOpenOverlayFromARetainedScreenReleasesItsHost() = scene {
        var visible by mutableStateOf(true)
        var captured = false
        composition.setContent { if (visible) OverlayPresence(true) { captured = it } }
        settle(); assertTrue(captured)
        visible = false
        settle(); assertFalse(captured)
    }

    @Test fun aNonModalKeptWindowCanReleaseInputWithoutLeavingComposition() = scene {
        var modal by mutableStateOf(true)
        var captured = false
        composition.setContent { OverlayPresence(modal) { captured = it } }
        settle(); assertTrue(captured)
        modal = false
        settle(); assertFalse(captured)
        modal = true
        settle(); assertTrue(captured)
    }

    @Test fun retainedCompanionHandlersDoNotConsumeTheGameOverlaysBackOrKeys() = scene {
        val companionBack = CompanionBack(); val overlayBack = CompanionBack()
        val companionKeys = CompanionKeys(); val overlayKeys = CompanionKeys()
        val handled = mutableListOf<String>()
        composition.setContent {
            CompositionLocalProvider(LocalCompanionBack provides overlayBack, LocalCompanionKeys provides overlayKeys) {
                CompanionBackHandler { handled += "overlay back" }
                CompanionKeyHandler { _, _ -> handled += "overlay key"; true }
            }
            CompositionLocalProvider(LocalCompanionBack provides companionBack, LocalCompanionKeys provides companionKeys) {
                CompanionBackHandler { handled += "companion back" }
                CompanionKeyHandler { _, _ -> handled += "companion key"; true }
            }
        }
        settle()
        assertTrue(overlayBack.back()); assertTrue(overlayKeys.key(MenuKey.A))
        assertEquals(listOf("overlay back", "overlay key"), handled)
        assertTrue(companionBack.back()); assertTrue(companionKeys.key(MenuKey.A))
        assertEquals(listOf("overlay back", "overlay key", "companion back", "companion key"), handled)
    }

    @Test fun aModalTakesTextFocusOnceBeforeNotifyingItsHost() = scene {
        val focus = Focus()
        var active by mutableStateOf(false)
        var revision by mutableStateOf(0)
        val acquiredAt = mutableListOf<Int>()
        composition.setContent {
            revision
            CompositionLocalProvider(LocalFocusManager provides focus) {
                OverlayPresence(active, takesFocus = true) { if (it) acquiredAt += focus.clears }
            }
        }
        settle(); assertEquals(0, focus.clears)
        active = true
        settle(); assertEquals(1, focus.clears); assertEquals(1, acquiredAt.first())
        // A later redraw must leave the overlay's own newly focused text field alone.
        revision++
        settle(); assertEquals(1, focus.clears)
        active = false
        settle(); assertEquals(1, focus.clears)
        active = true
        settle(); assertEquals(2, focus.clears)
        composition.dispose(); assertEquals(2, focus.clears)
    }

    @Test fun aNonModalPresenceDoesNotStealTextFocus() = scene {
        val focus = Focus()
        composition.setContent {
            CompositionLocalProvider(LocalFocusManager provides focus) {
                OverlayPresence(true) {}
            }
        }
        settle(); assertEquals(0, focus.clears)
        composition.dispose(); assertEquals(0, focus.clears)
    }
}
