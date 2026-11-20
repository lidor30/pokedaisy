package com.pokedaisy.app.companion.coop

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import kotlin.test.*

class CoopGameStartupTest {
    @Test fun framesStayGatedUntilTheStoryLockIsAdopted() = runBlocking {
        val events = mutableListOf<String>()
        val lookup = CompletableDeferred<String>()
        val startup = CoopGameStartup { events += if (it) "held" else "released" }

        startup.start(this, resolve = { lookup.await() }) {
            assertEquals("held", events.last())
            events += it
        }
        yield()
        assertEquals("held", events.last())
        lookup.complete("story lock adopted")
        yield()
        assertEquals(listOf("held", "story lock adopted", "released"), events.takeLast(3))
    }

    @Test fun oldLookupCannotAdoptOrReleaseTheReplacementGame() = runBlocking {
        var held = false
        val adopted = mutableListOf<String>()
        val first = CompletableDeferred<String>()
        val second = CompletableDeferred<String>()
        val startup = CoopGameStartup { held = it }

        startup.start(this, resolve = { first.await() }, adopt = adopted::add)
        yield()
        startup.start(this, resolve = { second.await() }, adopt = adopted::add)
        first.complete("old game")
        yield()
        assertTrue(held)
        assertTrue(adopted.isEmpty())

        second.complete("current game")
        yield()
        assertEquals(listOf("current game"), adopted)
        assertEquals(false, held)
    }
}
