package com.pokedaisy.app.companion.data.save

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** Money, coins, the bag, held items and moves: each written as the game stores it, and only what the game allows. */
class Gen3SaveEditTest {

    private val potion = 13
    private val pokeBall = 4
    private val tm06 = 294
    private val hm01 = 339
    private val cheri = 133
    private val machBike = 259
    private val leftovers = 200
    private val orangeMail = 121

    private fun editor(format: Gen3SaveFormat, fill: (ByteArray, ByteArray, ByteArray) -> Unit = { _, _, _ -> }): Gen3SaveEditor {
        val (sb2, sb1, storage) = SyntheticSave.blocks(format)
        fill(sb2, sb1, storage)
        val bytes = SyntheticSave.file(format, SyntheticSave.Slot(3, 1, sb2, sb1, storage), null)
        return Gen3SaveEditor((Gen3SaveFile.parse(bytes, format) as Gen3SaveFile.Parsed.Ok).save)
    }

    /** The edited file, read back the way the game would. */
    private fun reread(e: Gen3SaveEditor): Gen3SaveFile = (Gen3SaveFile.parse(e.build(), e.file.format) as Gen3SaveFile.Parsed.Ok).save

    private fun refused(block: () -> Unit) {
        try { block() } catch (e: IllegalArgumentException) { return }
        fail("allowed")
    }

    @Test
    fun moneyAndCoinsAreStoredXoredWithTheKey() {
        val e = editor(Gen3SaveFormat.EMERALD)
        e.setMoney(123_456)
        e.setCoins(777)
        val saved = reread(e)
        val key = SyntheticSave.rd32(saved.saveBlock2, 0xAC)
        assertEquals(123_456L, SyntheticSave.rd32(saved.saveBlock1, 0x490) xor key)
        assertEquals(777, (SyntheticSave.rd32(saved.saveBlock1, 0x494).toInt() and 0xFFFF) xor (key and 0xFFFF).toInt())
        assertEquals(123_456L, Gen3SaveData(saved).money)
        assertEquals(777, Gen3SaveData(saved).coins)
        refused { e.setMoney(1_000_000) }
        refused { e.setMoney(-1) }
        refused { e.setCoins(10_000) }
        // Ruby / Sapphire keep them plain.
        val r = editor(Gen3SaveFormat.RUBY_SAPPHIRE)
        r.setMoney(42)
        assertEquals(42L, SyntheticSave.rd32(reread(r).saveBlock1, 0x490))
    }

    @Test
    fun theBagReadsAndWritesLikeTheGame() {
        val e = editor(Gen3SaveFormat.EMERALD) { sb2, sb1, _ ->
            SyntheticSave.bag(sb2, sb1, 0xAC, 0x560, listOf(potion to 5, 22 to 3))
            SyntheticSave.bag(sb2, sb1, 0xAC, 0x690, listOf(tm06 to 1, hm01 to 1))
            SyntheticSave.bag(sb2, sb1, 0xAC, 0x5D8, listOf(machBike to 1))
        }
        assertEquals(listOf(ItemStack(potion, 5), ItemStack(22, 3)), e.data.pocket(BagPocket.ITEMS))
        assertEquals(listOf(ItemStack(machBike, 1)), e.data.pocket(BagPocket.KEY))
        e.setQuantity(BagPocket.ITEMS, 0, 99)
        e.addItem(BagPocket.ITEMS, 23, 2)
        e.setQuantity(BagPocket.ITEMS, 1, 0)   // out: the rest moves up
        e.addItem(BagPocket.TMHM, 290, 3)       // TMs stay in id order
        e.addItem(BagPocket.BALLS, pokeBall, 10)
        e.addItem(BagPocket.BERRIES, cheri, 999)
        val saved = Gen3SaveData(reread(e))
        assertEquals(listOf(ItemStack(potion, 99), ItemStack(23, 2)), saved.pocket(BagPocket.ITEMS))
        assertEquals(listOf(290, tm06, hm01), saved.pocket(BagPocket.TMHM).map { it.item })
        assertEquals(listOf(ItemStack(cheri, 999)), saved.pocket(BagPocket.BERRIES))
        // Raw: packed, the slot after the last zeroed, quantities under the key.
        val sb1 = reread(e).saveBlock1
        val key = (SyntheticSave.rd32(reread(e).saveBlock2, 0xAC) and 0xFFFF).toInt()
        assertEquals(23, sb1[0x564].toInt() and 0xFF)
        assertEquals(2, ((sb1[0x566].toInt() and 0xFF) or ((sb1[0x567].toInt() and 0xFF) shl 8)) xor key)
        assertEquals(0L, SyntheticSave.rd32(sb1, 0x568))

        refused { e.setQuantity(BagPocket.ITEMS, 0, 100) }      // Emerald stacks hold 99
        refused { e.addItem(BagPocket.ITEMS, potion, 1) }        // one stack per item from here
        refused { e.addItem(BagPocket.ITEMS, pokeBall, 1) }      // a ball goes in the BALLS pocket
        refused { e.addItem(BagPocket.KEY, 260, 1) }             // key items aren't handed out
        refused { e.addItem(BagPocket.ITEMS, machBike, 1) }
        refused { e.addItem(BagPocket.BERRIES, ITEM_ENIGMA_BERRY, 1) }
        refused { e.setQuantity(BagPocket.TMHM, 2, 2) }          // one of an HM
        refused { e.setQuantity(BagPocket.TMHM, 2, 0) }          // and never taken away
        refused { e.setQuantity(BagPocket.KEY, 0, 0) }
        refused { e.addItem(BagPocket.ITEMS, 2000, 1) }
    }

    @Test
    fun fireRedStacksHold999AndAFullPocketTakesNoMore() {
        val f = Gen3SaveFormat.FIRERED_LEAFGREEN
        val e = editor(f) { sb2, sb1, _ ->
            SyntheticSave.bag(sb2, sb1, 0xF20, 0x430, listOf(1 to 1, 2 to 1, 3 to 1, 4 to 1, 5 to 1, 6 to 1, 7 to 1, 8 to 1, 9 to 1, 10 to 1, 11 to 1, 12 to 1))
        }
        e.setQuantity(BagPocket.BALLS, 3, 999)
        e.addItem(BagPocket.ITEMS, potion, 999)
        refused { e.setQuantity(BagPocket.BALLS, 3, 1000) }
        val f13 = editor(f) { sb2, sb1, _ -> SyntheticSave.bag(sb2, sb1, 0xF20, 0x310, (13..54).map { it to 1 }) }
        refused { f13.addItem(BagPocket.ITEMS, 68, 1) }   // 42 kinds already: the pocket is full
        assertEquals(42, f13.data.pocket(BagPocket.ITEMS).size)
    }

    @Test
    fun heldItemsAreOnlyOnesThatCanBeHeld() {
        val e = editor(Gen3SaveFormat.EMERALD)
        val pika = MonRef.Party(1)
        e.setHeldItem(pika, leftovers)
        assertEquals(leftovers, e.data.mon(pika)!!.first.heldItem)
        e.setHeldItem(MonRef.Pc(BoxSlot(0, 0)), cheri)
        e.setHeldItem(pika, 0)
        assertEquals(0, e.data.mon(pika)!!.first.heldItem)
        refused { e.setHeldItem(pika, machBike) }
        refused { e.setHeldItem(pika, hm01) }
        refused { e.setHeldItem(pika, orangeMail) }
        val saved = Gen3SaveData(reread(e))
        assertTrue(saved.party.all { it.mon.checksumOk })
        assertEquals(cheri, saved.mon(BoxSlot(0, 0)).heldItem)
    }

    @Test
    fun movesComeFromWhatTheSpeciesLearnsAndKeepTheirPp() {
        // Party 1 is a level-50 CHARIZARD knowing TACKLE and GROWL (35 PP each, from the synthetic record).
        val f = Gen3SaveFormat.FIRERED_LEAFGREEN
        val e = editor(f)
        val zard = MonRef.Party(0)
        val flamethrower = 53   // TM35
        val ember = 52          // CHARMANDER, level 7
        val fly = 19            // HM02
        val blastBurn = 307     // Cape Brink, FireRed / LeafGreen only
        e.setMoves(zard, listOf(45, flamethrower, ember, blastBurn))
        val mon = e.data.mon(zard)!!.first
        assertEquals(listOf(45, flamethrower, ember, blastBurn), mon.moves.toList())
        assertEquals(listOf(35, 15, 25, 5), mon.pp.toList())   // GROWL kept its 35; the new ones full
        assertEquals(0, mon.ppBonuses)
        e.setMoves(zard, listOf(fly))
        assertEquals(listOf(fly, 0, 0, 0), e.data.mon(zard)!!.first.moves.toList())
        refused { e.setMoves(zard, listOf(57)) }                 // SURF: not for a CHARIZARD
        refused { e.setMoves(zard, listOf(fly, fly)) }
        refused { e.setMoves(zard, emptyList()) }
        refused { e.setMoves(zard, listOf(1, 2, 3, 4, 5)) }
        // Emerald has no Cape Brink.
        refused { editor(Gen3SaveFormat.EMERALD).setMoves(zard, listOf(blastBurn)) }
        assertTrue(Gen3SaveData(reread(e)).party[0].mon.checksumOk)
    }

    @Test
    fun levelUpMovesWaitForTheirLevel() {
        // Box 1 slot 1: a BULBASAUR at 500 EXP, level 9 - LEECH SEED (7) yes, VINE WHIP (10) not yet.
        val e = editor(Gen3SaveFormat.EMERALD)
        val at = MonRef.Pc(BoxSlot(0, 0))
        assertEquals(9, e.data.mon(at)!!.second)
        e.setMoves(at, listOf(73, 33))
        refused { e.setMoves(at, listOf(22)) }
        assertTrue(22 in Gen3Legal.learnable(Gen3SaveFormat.EMERALD, 1, 10))
        // What it evolved from counts: an IVYSAUR at 10 still has BULBASAUR's moves.
        assertTrue(73 in Gen3Legal.learnable(Gen3SaveFormat.EMERALD, 2, 10))
        // Tutors: FireRed's and Emerald's, none on Ruby / Sapphire. BODY SLAM (34) for a BULBASAUR.
        assertTrue(34 in Gen3Legal.learnable(Gen3SaveFormat.FIRERED_LEAFGREEN, 1, 5))
        assertTrue(34 in Gen3Legal.learnable(Gen3SaveFormat.EMERALD, 1, 5))
        assertFalse(34 in Gen3Legal.learnable(Gen3SaveFormat.RUBY_SAPPHIRE, 1, 5))
        // SMEARGLE sketches anything but STRUGGLE.
        val smeargle = Gen3Legal.learnable(Gen3SaveFormat.EMERALD, 235, 1)
        assertEquals(Gen3Legal.LAST_MOVE - 1, smeargle.size)
        assertFalse(165 in smeargle)
    }

    @Test
    fun ppUpsFollowTheirMove() {
        val e = editor(Gen3SaveFormat.EMERALD)
        val at = MonRef.Pc(BoxSlot(0, 0))
        val mon = e.data.mon(at)!!.first
        // TACKLE with 3 PP Ups (56 PP), GROWL with 1.
        e.putRecord(BoxSlot(0, 0), mon.withChanges(moves = intArrayOf(33, 45, 0, 0), pp = intArrayOf(56, 48, 0, 0), ppBonuses = 0b0111))
        e.setMoves(at, listOf(45, 73, 33))
        val after = e.data.mon(at)!!.first
        assertEquals(listOf(48, 10, 56, 0), after.pp.toList())
        assertEquals(0b11_00_01, after.ppBonuses)
        assertEquals(56, Gen3Legal.ppWithBonus(33, 3))
    }

    @Test
    fun eggsAreNotEdited() {
        val e = editor(Gen3SaveFormat.EMERALD) { _, _, storage ->
            SyntheticSave.mon(0x77L, 0x0BAD_F00DL, 1, exp = 100, isEgg = true).copyInto(storage, 4 + 5 * 80)
        }
        val egg = MonRef.Pc(BoxSlot(0, 5))
        refused { e.setMoves(egg, listOf(33)) }
        refused { e.setHeldItem(egg, potion) }
        refused { e.setMoves(MonRef.Party(4), listOf(33)) }   // no fifth party Pokémon
        assertFalse(e.changed)
    }

    @Test
    fun theGeneratedTablesPutItemsInTheirPockets() {
        val f = Gen3SaveFormat.EMERALD
        assertEquals(BagPocket.ITEMS, Gen3Legal.pocketOf(f, potion))
        assertEquals(BagPocket.BALLS, Gen3Legal.pocketOf(f, pokeBall))
        assertEquals(BagPocket.TMHM, Gen3Legal.pocketOf(f, tm06))
        assertEquals(BagPocket.BERRIES, Gen3Legal.pocketOf(f, cheri))
        assertNull(Gen3Legal.pocketOf(f, machBike))
        assertNull(Gen3Legal.pocketOf(f, ITEM_ENIGMA_BERRY))
        assertTrue(Gen3Legal.holdable(f).let { leftovers in it && tm06 in it && hm01 !in it && orangeMail !in it })
        // MASTER BALL .. PREMIER BALL in every game.
        for (fmt in Gen3SaveFormat.entries) assertEquals(12, Gen3Legal.bagItems(fmt, BagPocket.BALLS).size)
    }
}
