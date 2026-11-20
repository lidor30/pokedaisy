package com.pokedaisy.app.companion.data.coop

import java.lang.reflect.Proxy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The after-battle queue against a partner game that may already have beaten a trainer. */
class CoopOpQueueTest {
    private val calls = ArrayList<String>()
    private var beaten = HashSet<Int>()

    /** Every CoopCore call logged; the flag answers 2 for a beaten trainer, 1 once set; everything else succeeds. */
    private val core: CoopCore = Proxy.newProxyInstance(CoopCore::class.java.classLoader, arrayOf(CoopCore::class.java)) { _, m, args ->
        calls += m.name + (args?.let { a -> a.filterIsInstance<Int>().joinToString(prefix = " ") } ?: "")
        when (m.name) {
            "onFreeField" -> true
            "setTrainerFlag" -> { val id = args!![0] as Int; if (!beaten.add(id)) 2 else 1 }
            "prize" -> 1500
            "saveGame", "addMoney", "writePartyMon", "whiteOut", "giveMon", "removeItem", "evolve" -> 1
            else -> null
        }
    } as CoopCore

    private fun run(ops: List<CoopOp>): List<String> {
        val q = CoopOpQueue(core, { true }, { }, { }, { 0L })
        q.load(ops)
        repeat(ops.size + 2) { q.step() }
        assertTrue(q.empty)
        return calls.toList()
    }

    @Test fun notBeatenYetIsFlaggedPaidOnceAndSaved() {
        val got = run(listOf(CoopOp.TrainerFlag(100), CoopOp.Prize(100), CoopOp.Save))
        assertEquals(1, got.count { it.startsWith("setTrainerFlag") })
        assertEquals(1, got.count { it.startsWith("prize") })
        assertEquals(1, got.count { it.startsWith("addMoney") })
        assertEquals(1, got.count { it == "saveGame" })
    }

    @Test fun alreadyBeatenGetsNoPrizeNoSave() {
        beaten += 100
        val got = run(listOf(CoopOp.TrainerFlag(100), CoopOp.Prize(100), CoopOp.Save))
        assertFalse(got.any { it.startsWith("prize") || it.startsWith("addMoney") || it == "saveGame" })
        assertEquals(setOf(100), beaten)                   // untouched
    }

    @Test fun oneOfTwoTrainersBeatenPaysTheOtherAndSaves() {
        beaten += 10
        val got = run(listOf(CoopOp.TrainerFlag(10), CoopOp.Prize(10), CoopOp.TrainerFlag(20), CoopOp.Prize(20), CoopOp.Save))
        assertEquals(1, got.count { it.startsWith("prize") })
        assertTrue(got.contains("prize 20"))
        assertEquals(1, got.count { it == "saveGame" })
    }

    @Test fun otherChangesStillSaveWhenTheTrainerWasBeaten() {
        beaten += 100
        val got = run(listOf(CoopOp.WhiteOut, CoopOp.TrainerFlag(100), CoopOp.Prize(100), CoopOp.Save))
        assertTrue(got.contains("saveGame"))
        assertFalse(got.any { it.startsWith("prize") })
    }
}
