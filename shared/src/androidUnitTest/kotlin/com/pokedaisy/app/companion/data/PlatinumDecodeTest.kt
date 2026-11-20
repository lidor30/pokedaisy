package com.pokedaisy.app.companion.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/** Pokémon Platinum (Nintendo DS) on a new game played headless - see the platinum fixture's README. */
class PlatinumDecodeTest {
    private fun read(): Telemetry {
        activeGame = GameKind.PLATINUM
        romLanguage = 'E'
        romGameCode = ""
        return readGen4Telemetry(FixtureMemoryReader.load("platinum"), GEN4_PLATINUM_US)
    }

    @Test
    fun `party - Gen 4's encrypted, shuffled blocks and the party fields`() {
        val t = read()
        assertFalse(t.inBattle)
        assertEquals(listOf("TURTWIG"), t.party.map { speciesName(it.species) })
        val turtwig = t.party[0]
        assertEquals(387 to 6, turtwig.species to turtwig.level)
        assertEquals(24 to 24, turtwig.hp to turtwig.maxHp)
        assertEquals(listOf("Tackle", "Withdraw", "-", "-"), turtwig.moves.map { lookupMove(it).name })
        assertEquals(204L, turtwig.exp)
        assertEquals(0x16794027L, turtwig.personality)
        assertEquals(0L, turtwig.status)
        assertFalse(turtwig.isEgg)
    }

    @Test
    fun `bag, money, gender and the place`() {
        val t = read()
        assertEquals(listOf(Triple("Potion", 3, POCKET_ITEMS), Triple("Poké Ball", 5, POCKET_POKE_BALLS)),
            t.items.map { Triple(itemName(it.itemId), it.quantity, it.pocket) })
        assertEquals(0, t.playerGender)
        assertEquals(414, t.mapNum)
        assertEquals(6 to 8, t.x to t.y)
        assertEquals("Twinleaf Town", t.mapSecName)
    }

    @Test
    fun `the save's Pokedex - seen TURTWIG and the rival's CHIMCHAR, caught TURTWIG, no National Dex`() {
        val dex = read().pokedex!!
        assertEquals(POKEDEX_PLATINUM, dex.tables)
        assertEquals(setOf(387, 390), dex.seen)
        assertEquals(setOf(387), dex.caught)
        assertFalse(dex.national)
    }
}

/** A party Pokémon the game holds decrypted in RAM (its flags say so) reads the same as the encrypted one. */
class PlatinumDecryptedMonTest {
    private val main = javaClass.classLoader!!.getResourceAsStream("fixtures/platinum/mainram.bin")!!.readBytes()
    private val turtwig = (0x0227E178 + GEN4_PLATINUM_US.partyOff + 8 - 0x02000000).let { main.copyOfRange(it, it + 0xEC) }

    private fun u16(b: ByteArray, o: Int) = (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8)

    /** The games' XOR stream over [len] bytes from [off], in place (Pokemon_EnterDecryptionContext's own). */
    private fun xor(b: ByteArray, off: Int, len: Int, seed: Long) {
        var s = seed
        for (i in 0 until len step 2) {
            s = (s * 0x41C64E6DL + 0x6073L) and 0xFFFFFFFFL
            val w = u16(b, off + i) xor (s ushr 16).toInt()
            b[off + i] = w.toByte()
            b[off + i + 1] = (w shr 8).toByte()
        }
    }

    @Test
    fun `the game's decrypted flags are honored`() {
        activeGame = GameKind.PLATINUM
        val plain = decodeGen4PartyMon(turtwig, 0)!!
        val held = turtwig.copyOf()
        xor(held, 8, 0x80, u16(held, 6).toLong())
        xor(held, 0x88, 0x64, (u16(held, 0).toLong() or (u16(held, 2).toLong() shl 16)))
        held[4] = 3   // partyDecrypted | boxDecrypted
        assertEquals(plain.toString(), decodeGen4PartyMon(held, 0).toString())   // Mon's IntArrays compare by reference
    }
}

/** Platinum's Repel, used the way its bag does (useGen4Repel), on the platinum fixture's RAM. */
class PlatinumRepelTest {
    private val main = javaClass.classLoader!!.getResourceAsStream("fixtures/platinum/mainram.bin")!!.readBytes()
    private val calls = object : GameCalls {
        override fun call(fn: Long, a0: Int, a1: Int, a2: Int, a3: Int) = -1L
        override fun read(addr: Long, len: Int) = main.copyOfRange((addr - 0x02000000L).toInt(), (addr - 0x02000000L).toInt() + len)
        override fun write(addr: Long, bytes: ByteArray): Boolean {
            System.arraycopy(bytes, 0, main, (addr - 0x02000000L).toInt(), bytes.size)
            return true
        }
    }
    private val body = 0x0227E178L
    private val items = body + GEN4_PLATINUM_US.bagOff
    private fun u16(addr: Long) = calls.read(addr, 2).let { (it[0].toInt() and 0xFF) or ((it[1].toInt() and 0xFF) shl 8) }
    private fun put(slot: Int, item: Int, qty: Int) =
        calls.write(items + slot * 4, byteArrayOf(item.toByte(), (item shr 8).toByte(), qty.toByte(), (qty shr 8).toByte()))
    private fun steps() = calls.read(body + GEN4_PLATINUM_US.repelStepsOff!!, 1)[0].toInt() and 0xFF

    @Test
    fun `the last one empties its slot and the next item moves up`() {
        put(0, 79, 1)          // REPEL x1
        put(1, 76, 2)          // SUPER REPEL x2
        val out = useGen4Repel(GEN4_PLATINUM_US, calls, 79, 100, fieldFree = true)
        assertEquals(ItemUseResult.USED to 100, out.result to out.steps)
        assertEquals(100, steps())
        assertEquals(listOf(76, 2, 0, 0), listOf(u16(items), u16(items + 2), u16(items + 4), u16(items + 6)))
    }

    @Test
    fun `one off a stack, and not while one works or the field is busy`() {
        put(0, 77, 3)          // MAX REPEL x3
        assertEquals(ItemUseResult.NOT_NOW, useGen4Repel(GEN4_PLATINUM_US, calls, 77, 250, fieldFree = false).result)
        assertEquals(ItemUseResult.USED, useGen4Repel(GEN4_PLATINUM_US, calls, 77, 250, fieldFree = true).result)
        assertEquals(250, steps())
        assertEquals(77 to 2, u16(items) to u16(items + 2))
        assertEquals(ItemUseResult.STILL_ACTIVE, useGen4Repel(GEN4_PLATINUM_US, calls, 77, 250, fieldFree = true).result)
        assertEquals(ItemUseResult.NONE_LEFT,
            useGen4Repel(GEN4_PLATINUM_US, calls.also { calls.write(body + GEN4_PLATINUM_US.repelStepsOff!!, byteArrayOf(0)) }, 79, 100, true).result)
    }
}

/** Platinum's battle read (Gen4Battle) on the rival's battle on Route 201 - see the platinum_battle fixture's README. */
class PlatinumBattleTest {
    private fun read(): Telemetry {
        activeGame = GameKind.PLATINUM
        romLanguage = 'E'
        romGameCode = ""
        return readGen4Telemetry(FixtureMemoryReader.load("platinum_battle"), GEN4_PLATINUM_US)
    }

    @Test
    fun `the battlers, their moves, stats and stages`() {
        val t = read()
        assertEquals(true, t.inBattle)
        assertFalse(t.isDoubleBattle)
        val me = t.battleMons[BATTLE_POS_PLAYER_LEFT]
        assertEquals(listOf(387, 5, 22, 22), listOf(me.species, me.level, me.hp, me.maxHp))
        assertEquals(listOf("Tackle", "Withdraw"), me.moves.take(2).map { lookupMove(it).name })
        assertEquals(listOf(35, 40), me.pp.take(2))
        assertEquals(12 to 12, me.type1 to me.type2)    // GRASS
        assertEquals(65, me.ability)                    // OVERGROW
        assertEquals(listOf(12, 11, 8, 10, 11), me.stats!!.toList())
        assertEquals(List(7) { 6 }, me.statStages!!.toList())
        val foe = t.battleMons[BATTLE_POS_OPPONENT_LEFT]
        assertEquals(listOf(390, 5, 19, 19), listOf(foe.species, foe.level, foe.hp, foe.maxHp))
        assertEquals(0, t.battleMons[BATTLE_POS_PLAYER_RIGHT].species)
    }

    @Test
    fun `the rival's team, and which one is out`() {
        val t = read()
        assertEquals(listOf("CHIMCHAR"), t.enemyParty.map { speciesName(it.species) })
        assertEquals(t.enemyParty, t.battleFoes)
        assertEquals(0, t.enemyActive)
    }
}
