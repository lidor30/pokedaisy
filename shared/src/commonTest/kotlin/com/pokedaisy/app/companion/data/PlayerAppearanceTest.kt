package com.pokedaisy.app.companion.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class PlayerAppearanceTest {
    private val own = PlayerLook.Patch(emptyList(), emptyList(), byteArrayOf(1))
    private val hero = patch(1)
    private val rival = patch(2)
    private val text = block(3)
    private val controller = PlayerAppearance(
        PlayerLookAssets.Catalog(
            LookEngine.EMERALD, PlayerLook.Scan(byteArrayOf(), emptyMap(), emptyMap(), 0), emptyMap(),
            mapOf(
                Triple(false, null, false) to own,
                Triple(false, LookChar.RED, false) to hero,
                Triple(false, LookChar.RED, true) to rival,
                Triple(true, LookChar.LEAF, false) to hero,
            ),
            mapOf(LookEngine.FIRERED to listOf(text)),
        ),
    )

    @Test
    fun offersBothGendersBeforeTheIntroAndAppliesTextIndependently() {
        val before = select("LEAF", female = null, style = "FIRERED")
        assertEquals(listOf(LookChar.RED, LookChar.LEAF), before.heroes)
        assertEquals(LookChar.LEAF, before.pickedHero)
        assertNull(before.own)
        assertNull(before.wornHero)
        assertEquals(listOf(text), before.patch?.blocks)

        val after = select("LEAF")
        assertEquals(listOf(LookChar.RED), after.heroes)
        assertEquals(LookChar.BRENDAN, after.own)
        assertNull(after.pickedHero)
        assertSame(own, after.patch)
        assertSame(hero, select("LEAF", female = true, dressRival = true).patch)
        assertSame(rival, select("RED", dressRival = true).patch)
    }

    @Test
    fun keepsThePreviousAppearanceWhileCostumeBuildsAndAppliesNewText() {
        assertSame(hero, select("RED").patch)
        val pending = select("MON:25", listOf(25, 26), style = "FIRERED")
        val request = assertNotNull(pending.costumeToPrepare)
        assertEquals(25, pending.pickedCostume)
        assertEquals(LookChar.RED, pending.wornHero)
        assertEquals(listOf(hero.blocks.single(), text), pending.patch?.blocks)

        assertNull(select("MON:25", listOf(26, 25)).costumeToPrepare)
        val costume = patch(4)
        assertTrue(controller.completeCostume(request, costume))
        val ready = select("MON:25", listOf(25, 26), vehicle = RideVehicle.SCOOTER)
        assertSame(costume, ready.patch)
        assertNull(ready.wornHero)

        val newParty = select("MON:25", listOf(25, 27))
        assertSame(costume, newParty.patch)
        val updatedRequest = assertNotNull(newParty.costumeToPrepare)
        assertNotSame(request, updatedRequest)
        assertFalse(controller.completeCostume(request, patch(5)))
        assertTrue(controller.completeCostume(updatedRequest, null))
        val failed = select("MON:25", listOf(25, 27))
        assertSame(own, failed.patch)
        assertNull(failed.pickedCostume)
        assertNull(failed.costumeToPrepare)
    }

    @Test
    fun removingTheSelectedCostumeFallsBackAndInvalidatesItsWorker() {
        val pending = select("MON:25", listOf(25))
        val request = assertNotNull(pending.costumeToPrepare)
        val removed = select("MON:25", listOf(26))
        assertNull(removed.pickedCostume)
        assertSame(own, removed.patch)
        assertFalse(controller.completeCostume(request, patch(4)))
        assertNotNull(select("MON:25", listOf(25)).costumeToPrepare)
    }

    private fun select(
        savedLook: String?,
        party: List<Int> = emptyList(),
        female: Boolean? = false,
        dressRival: Boolean = false,
        vehicle: RideVehicle = RideVehicle.BIKE,
        style: String? = null,
    ) = controller.select(female, savedLook, party, dressRival, vehicle, style)

    private fun patch(offset: Int) = PlayerLook.Patch(listOf(block(offset)), emptyList())
    private fun block(offset: Int) = PlayerLook.Block(offset, 0, byteArrayOf(offset.toByte()))
}
