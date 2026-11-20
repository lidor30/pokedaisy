package com.pokedaisy.app.companion.ui

import com.pokedaisy.app.companion.data.ItemView
import org.junit.Assert.assertEquals
import org.junit.Test

/** [itemTotals] / [itemGains]: what the bag toast counts as a new item. */
class ItemToastTest {
    private fun item(id: Int, quantity: Int, pocket: Int = 0) = ItemView(id, "item$id", quantity, null, pocket, "")

    @Test
    fun `the same item in two pockets adds up`() {
        assertEquals(mapOf(1 to 5), itemTotals(listOf(item(1, 2), item(1, 3, pocket = 3), item(0, 9))))
    }

    @Test
    fun `only increases are gains`() {
        val gains = itemGains(mapOf(1 to 2, 2 to 5), mapOf(1 to 4, 2 to 1, 3 to 1))
        assertEquals(mapOf(1 to 2, 3 to 1), gains)
    }

    @Test
    fun `a bag that appears whole is not a pickup`() {
        assertEquals(emptyMap<Int, Int>(), itemGainNotices(emptyMap(), mapOf(1 to 3, 2 to 1)))
    }

    @Test
    fun `a wholesale change is a reload, not a pile of pickups`() {
        val before = (1..20).associateWith { 1 }
        val after = before + (21..30).associateWith { 1 }
        assertEquals(emptyMap<Int, Int>(), itemGainNotices(before, after))
    }

    @Test
    fun `one pickup still gets its card`() {
        assertEquals(mapOf(1 to 2), itemGainNotices(mapOf(1 to 1, 2 to 1), mapOf(1 to 3, 2 to 1)))
    }

    @Test
    fun `an impossible stack is a torn read, not a pickup`() {
        assertEquals(emptyMap<Int, Int>(), itemGainNotices(mapOf(1 to 1), mapOf(1 to 1, 2 to 40000)))
    }

    @Test
    fun `a load from anywhere is a jump in the game's clock`() {
        assertEquals(true, playedOn(1000, 1060))
        assertEquals(false, playedOn(1000, 400)) // a state from earlier, REWIND, BOSS RETRY
        assertEquals(false, playedOn(1000, 1000 + 60L * 60 * 5)) // a state from five minutes on
    }
}
