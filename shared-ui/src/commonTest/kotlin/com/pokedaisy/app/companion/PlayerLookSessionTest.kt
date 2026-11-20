package com.pokedaisy.app.companion

import com.pokedaisy.app.Prefs
import com.pokedaisy.app.companion.data.GameKind
import com.pokedaisy.app.companion.data.LookChar
import com.pokedaisy.app.companion.data.MonView
import com.pokedaisy.app.companion.data.POKEDEX_EMERALD
import com.pokedaisy.app.companion.data.PlayerLook
import com.pokedaisy.app.companion.data.PlayerLookAssets
import com.pokedaisy.app.companion.data.PokedexState
import com.pokedaisy.app.companion.data.SnapshotView
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Runnable
import okio.Path.Companion.toPath
import kotlin.coroutines.CoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PlayerLookSessionTest {
    @Test fun partyObservedDuringVerificationIsUsedWhenTheCatalogArrives() = withSession {
        prefs.setPlayerLookFor("first", "MON:25")
        load("first")
        look.observe(party(25))
        worker.drain()
        assertFalse(look.supported)
        assertTrue(partyReads.isEmpty())

        main.drain()
        assertTrue(look.supported)
        assertEquals("BRENDAN", look.own)
        finishWork()
        assertEquals(listOf(listOf(25)), partyReads)
        assertEquals("MON:25", look.selected)
        assertEquals(listOf(40), patches.last())
    }

    @Test fun aRejectedCatalogKeepsTheLookUnavailableWithPartyTelemetryPresent() = withSession {
        load("unverified")
        look.observe(party(25))
        finishWork()
        assertEquals(listOf("unverified"), catalogReads)
        assertFalse(look.supported)
        assertTrue(look.choices.isEmpty())
        assertTrue(partyReads.isEmpty())
        assertTrue(patches.all { it == null })
    }

    @Test fun switchingGamesDiscardsACompletedCatalogAndThePreviousGamesTelemetry() = withSession {
        prefs.setPlayerLookFor("first", "RED")
        prefs.setPlayerLookFor("second", "LEAF")
        load("first")
        look.observe(party(25))
        worker.drain()
        assertEquals(listOf("first"), catalogReads)

        load("second", initialFemale = false)
        main.drain()
        assertFalse(look.supported)
        assertNull(look.selected)
        assertTrue(patches.all { it == null })

        finishWork()
        assertEquals(listOf("first", "second"), catalogReads)
        assertEquals(listOf("LEAF"), look.choices)
        assertEquals("LEAF", look.selected)
        assertTrue(partyReads.isEmpty())
        assertEquals(listOf(20), patches.last())
        assertEquals("RED", prefs.playerLookFor("first"))
    }

    @Test fun anOlderPartyResultCannotOfferCostumesAfterThePartyChanges() = withSession {
        load("first", initialFemale = false)
        finishWork()
        look.observe(party(25))
        main.drain()
        worker.drain()
        assertEquals(listOf(listOf(25)), partyReads)

        look.observe(party(26))
        main.drain()
        assertFalse("MON:25" in look.choices)
        finishWork()
        assertEquals(listOf("RED", "MON:26"), look.choices)
    }

    @Test fun closingDropsACompletedCostumeAndDisablesLaterLoadsAndSelections() = withSession {
        prefs.setPlayerLookFor("first", "RED")
        load("first", initialFemale = false)
        look.observe(party(25))
        finishWork()
        assertEquals(listOf(10), patches.last())

        look.select("MON:25")
        main.drain()
        worker.drain()
        assertEquals(1, costumeBuilds)
        assertEquals(listOf(10), patches.last())

        look.close()
        main.drain()
        assertFalse(look.supported)
        assertTrue(look.choices.isEmpty())
        assertNull(look.selected)
        assertNull(patches.last())
        assertFalse(listOf(40) in patches)

        look.select(null)
        load("second", initialFemale = false)
        finishWork()
        assertEquals("MON:25", prefs.playerLookFor("first"))
        assertEquals(listOf("first"), catalogReads)
        assertFalse(look.supported)
    }

    private class Queue : CoroutineDispatcher() {
        private val jobs = ArrayDeque<Runnable>()
        val pending get() = jobs.isNotEmpty()

        override fun dispatch(context: CoroutineContext, block: Runnable) {
            jobs.addLast(block)
        }

        fun drain() {
            while (jobs.isNotEmpty()) jobs.removeFirst().run()
        }
    }

    private class Harness {
        val prefs = Prefs(PrefsCompanionSettingsTest.Store())
        val main = Queue()
        val worker = Queue()
        val patches = mutableListOf<List<Int>?>()
        val catalogReads = mutableListOf<String>()
        val partyReads = mutableListOf<List<Int>>()
        var costumeBuilds = 0
        val look = PlayerLookSession(
            prefs = prefs,
            files = "unused-player-look-test".toPath(),
            mainDispatcher = main,
            workerDispatcher = worker,
            prepareCatalog = prepare@{ rom, engine, _, _, _, _ ->
                catalogReads += rom.name
                if (rom.name == "unverified") return@prepare null
                val hero = if (rom.name == "first") LookChar.RED else LookChar.LEAF
                val offset = if (rom.name == "first") 10 else 20
                PlayerLookAssets.Catalog(
                    checkNotNull(engine),
                    PlayerLook.Scan(byteArrayOf(), emptyMap(), emptyMap(), 0),
                    emptyMap(),
                    mapOf(Triple(false, hero, false) to patch(offset)),
                    emptyMap(),
                )
            },
            offeredCostumes = { _, species ->
                partyReads += species
                species.associateWith { "Costume $it" }
            },
            prepareCostume = { _, _ ->
                costumeBuilds++
                patch(40)
            },
            onPatch = { patches += it?.blocks?.map { block -> block.off } },
        )

        fun load(key: String, initialFemale: Boolean? = null) {
            look.load(key.toPath(), "BPEE", key, initialFemale = initialFemale) { emptyList() }
            main.drain()
        }

        fun finishWork() {
            while (main.pending || worker.pending) {
                main.drain()
                worker.drain()
            }
        }

        fun party(species: Int) = SnapshotView(
            connected = true,
            game = GameKind.EMERALD,
            party = listOf(MonView(species, "Test", 5, 10, 10, "", emptyList(), null)),
            pokedex = PokedexState(POKEDEX_EMERALD, emptySet(), emptySet(), false),
            playerGender = 0,
        )

        private fun patch(offset: Int) =
            PlayerLook.Patch(listOf(PlayerLook.Block(offset, 0, byteArrayOf(1))), emptyList())
    }

    private fun withSession(test: Harness.() -> Unit) {
        val harness = Harness()
        try {
            harness.test()
        } finally {
            harness.look.close()
            harness.finishWork()
        }
    }
}
