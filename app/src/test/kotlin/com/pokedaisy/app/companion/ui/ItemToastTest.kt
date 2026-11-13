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
}
