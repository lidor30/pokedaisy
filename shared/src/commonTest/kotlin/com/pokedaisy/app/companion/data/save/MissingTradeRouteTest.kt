package com.pokedaisy.app.companion.data.save

import com.pokedaisy.app.companion.data.save.MissingDex.Way
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class MissingTradeRouteTest {
    private val sources = listOf("other", "partner")
    private val party = MonRef.Party(2)
    private val slot = BoxSlot(1, 3)
    private val pc = MonRef.Pc(slot)
    private val mon = Gen3BoxMon.read(ByteArray(Gen3BoxMon.SIZE), 0)

    @Test fun tradeAndIncomingEvolutionSelectThePartnerSide() {
        for (way in listOf(Way.TRADE, Way.EVOLVES)) {
            val route = MissingTradeRoute.resolve(owned(way, party), sources, changed = false, supportsTransfers = false)
            assertEquals(MissingTradeRoute.Trade("partner", secondPick = party), route)
        }
    }

    @Test fun tradeBackSelectsTheEditedSaveSide() {
        val route = MissingTradeRoute.resolve(owned(Way.TRADE_BACK, pc), sources, changed = false, supportsTransfers = false)
        assertEquals(MissingTradeRoute.Trade("partner", firstPick = pc), route)
    }

    @Test fun changedSaveMustBeSavedBeforeOpeningAnotherEditor() {
        val route = MissingTradeRoute.resolve(owned(Way.TRADE, party), emptyList<String>(), changed = true, supportsTransfers = false)
        assertEquals(MissingTradeRoute.SaveChangesFirst, route)
    }

    @Test fun staleOrBlockedSourcesDoNotOpenAnotherEditor() {
        assertNull(MissingTradeRoute.resolve(owned(Way.TRADE, party), sources.take(1), changed = false, supportsTransfers = true))
        val blocked = MissingDex.Owned(1, party, mon, 1, "blocked", Way.TRADE)
        assertNull(MissingTradeRoute.resolve(blocked, sources, changed = false, supportsTransfers = true))
    }

    @Test fun crossGenerationTransfersSelectTheSourceBoxSlot() {
        val palPark = MissingTradeRoute.resolve(owned(Way.PAL_PARK, pc), sources, changed = false, supportsTransfers = true)
        assertEquals(MissingTradeRoute.PalPark("partner", slot), palPark)
        val pokeTransfer = MissingTradeRoute.resolve(owned(Way.POKE_TRANSFER, pc), sources, changed = false, supportsTransfers = true)
        assertEquals(MissingTradeRoute.PokeTransfer("partner", slot), pokeTransfer)
    }

    @Test fun transfersRequirePlatformSupportAndABoxSlot() {
        for (way in listOf(Way.PAL_PARK, Way.POKE_TRANSFER)) {
            assertNull(MissingTradeRoute.resolve(owned(way, party), sources, changed = false, supportsTransfers = true))
            assertNull(MissingTradeRoute.resolve(owned(way, pc), sources, changed = false, supportsTransfers = false))
        }
    }

    private fun owned(way: Way, at: MonRef) = MissingDex.Owned(1, at, mon, 1, null, way)
}
