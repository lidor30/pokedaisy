package com.pokedaisy.app.companion.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.zip.CRC32

/**
 * FieldItems' rules, against a fake game (what the functions return), and its
 * addresses against the retail ROMs (checked live headless: see FieldItems.kt).
 */
class FieldItemsTest {
    /** A fake game: the field, a bag ([bag] item -> count, at most [bagKinds] kinds) and a party, behind
     * [fns]' function addresses. Its ROM is zeros, so [fns] carry the CRC of 32 zero bytes. */
    private class FakeGame(val fns: FieldItemFns) : GameCalls {
        class Mon(
            var personality: Long, var species: Int = 1, var held: Int = 0, var egg: Boolean = false,
            var hp: Int = 10, var maxHp: Int = 10, var status: Int = 0,
            val moves: IntArray = intArrayOf(1, 2, 0, 0), val pp: IntArray = intArrayOf(5, 5, 0, 0), val maxPp: Int = 5,
        )

        val p = fns.party!!
        var locked = false
        var repel = 0
        /** Every VarSet, in order: (variable, value). */
        val varSets = mutableListOf<Pair<Int, Int>>()
        val bag = mutableMapOf(86 to 1)
        var bagKinds = 30
        val party = mutableListOf<Mon>()
        var failSetMonData = false
        val ram = mutableMapOf<Long, Byte>()
        val calls = mutableListOf<Long>()
        val bagSnapshots = mutableListOf<Map<Int, Int>>()

        private fun mon(addr: Int) = party[((addr.toLong() and 0xFFFFFFFFL) - p.party).toInt() / 100]

        /** Whether [item] would do something for [m] (move [move]): a Potion below full HP, an Antidote
         * on poison, an Ether on a move short of PP, a vitamin always. */
        fun helps(m: Mon, item: Int, move: Int): Boolean = when (item) {
            13 -> m.hp in 1 until m.maxHp
            14 -> m.status != 0
            34 -> m.moves[move] != 0 && m.pp[move] < m.maxPp
            else -> true
        }

        override fun call(fn: Long, a0: Int, a1: Int, a2: Int, a3: Int): Long {
            calls += fn
            return when (fn) {
                fns.fieldControlsLocked -> if (locked) 1 else 0
                fns.varGet -> { assertEquals(fns.repelVar, a0); repel.toLong() }
                fns.varSet -> { varSets += a0 to a1; if (a0 == fns.repelVar) repel = a1; 1 }
                fns.checkBagHasItem -> if ((bag[a0] ?: 0) >= a1) 1 else 0
                fns.removeBagItem -> if ((bag[a0] ?: 0) >= a1) { bag[a0] = bag[a0]!! - a1; if (bag[a0] == 0) bag.remove(a0); 1 } else 0
                p.addBagItem -> if (a0 in bag || bag.size < bagKinds) { bag[a0] = (bag[a0] ?: 0) + a1; 1 } else 0
                p.getMonData -> mon(a0).let { m ->
                    // pokeemerald's ids; a build that moved them (monFieldShift) is asked for the moved ones.
                    when (if (a1 >= 11) a1 - p.monFieldShift else a1) {
                        0 -> m.personality
                        11 -> m.species.toLong()
                        12 -> m.held.toLong()
                        in 13..16 -> m.moves[a1 - 13].toLong()
                        45 -> if (m.egg) 1 else 0
                        else -> error("field $a1")
                    }
                }
                p.setMonData -> {
                    assertEquals(12 + p.monFieldShift, a1)
                    if (!failSetMonData) mon(a0).held = read(a2.toLong() and 0xFFFFFFFFL, 2).let { (it[0].toInt() and 0xFF) or ((it[1].toInt() and 0xFF) shl 8) }
                    0x080000C1
                }
                p.itemNoEffect -> if (helps(mon(a0), a1, a3)) 0 else 1
                p.itemEffect -> mon(a0).let { m ->
                    if (!helps(m, a1, a3)) return 1
                    when (a1) {
                        13 -> m.hp = minOf(m.maxHp, m.hp + 20)
                        14 -> m.status = 0
                        34 -> m.pp[a3] = m.maxPp
                    }
                    0
                }
                else -> -1
            }
        }

        /** A party Pokémon's struct as the dry run copies it: a snapshot, by number. */
        private val snapshots = mutableListOf<Mon>()
        private fun Mon.copyOf() = Mon(personality, species, held, egg, hp, maxHp, status, moves.copyOf(), pp.copyOf(), maxPp)
        private fun monIndex(addr: Long) = ((addr - p.party) / 100).toInt().takeIf { addr >= p.party && (addr - p.party) % 100 == 0L && it < party.size }

        override fun read(addr: Long, len: Int): ByteArray = when {
            len == 100 && monIndex(addr) != null -> {
                snapshots += party[monIndex(addr)!!].copyOf()
                ByteArray(100).also { it[0] = (snapshots.size - 1).toByte() }
            }
            // All of EWRAM (TAKE's room check, put back after): the bag lives there, so a copy of it stands in -
            // byte 0 names the copy, so what's read after a change differs and the write-back restores it.
            addr == 0x02000000L && len == 0x40000 -> {
                bagSnapshots += bag.toMap()
                ByteArray(len).also { it[0] = (bagSnapshots.size - 1).toByte() }
            }
            addr == p.partyCount -> byteArrayOf(party.size.toByte())
            addr == fns.items + 86L * fns.itemSize + fns.itemParamOff -> byteArrayOf(100)
            addr >= 0x08000000L -> ByteArray(len)
            else -> ByteArray(len) { ram[addr + it] ?: 0 }
        }

        override fun write(addr: Long, bytes: ByteArray): Boolean {
            if (bytes.size == 100 && monIndex(addr) != null) {
                party[monIndex(addr)!!] = snapshots[bytes[0].toInt()].copyOf()
                return true
            }
            if (addr == 0x02000000L) {
                bag.clear()
                bag.putAll(bagSnapshots[bytes[0].toInt()])
                return true
            }
            bytes.forEachIndexed { i, b -> ram[addr + i] = b }
            return true
        }

        fun target(slot: Int, give: Boolean = false, item: Int = 13): PartyTarget =
            partyTargets(support(this), item, give, false, this).targets[slot]
    }

    private companion object {
        val ZERO_CRC = CRC32().apply { update(ByteArray(32)) }.value
        val FAKE_FNS = FIELD_ITEMS_FIRERED_REV1.copy(
            codeCrcs = List(5) { ZERO_CRC },
            party = FIELD_ITEMS_FIRERED_REV1.party!!.copy(codeCrcs = List(5) { ZERO_CRC }),
        )
        /** Potions, Antidotes, Ethers and two held items (LEFTOVERS 200, EXP. SHARE 182) count as holdable. */
        fun support(g: FakeGame) = FieldItemSupport(g.fns, setOf(13, 14, 34, 200, 182, 206))
    }

    private val rom = File(RomFileReader.EMERALD_PATH).takeIf { it.isFile }?.readBytes()
    private val frRom = File(RomFileReader.FIRERED_REV1_PATH).takeIf { it.isFile }?.readBytes()
    private fun romReader(bytes: ByteArray) = { a: Long, n: Int -> bytes.copyOfRange((a - 0x08000000).toInt(), (a - 0x08000000).toInt() + n) }

    // --- Repels ---------------------------------------------------------------

    @Test
    fun `a Repel sets the step count and leaves the bag`() {
        val g = FakeGame(FAKE_FNS)
        assertEquals(ItemUseOutcome(ItemUseResult.USED, 100), useFieldItem(g.fns, 86, false, g))
        assertEquals(100, g.repel)
        assertFalse(86 in g.bag)
        // A second one while the first still works: the game's own "still lingering" rule.
        g.bag[83] = 1
        assertEquals(ItemUseResult.STILL_ACTIVE, useFieldItem(g.fns, 83, false, g).result)
        assertEquals(1, g.bag[83])
    }

    @Test
    fun `SoulGold's Lure bit doesn't count as a Repel`() {
        val fns = FIELD_ITEMS_SOULGOLD_V1_2
        assertEquals(0, fns.repelSteps(0))
        assertEquals(87, fns.repelSteps(87))
        // A Lure working (bit 15 over its steps): no Repel steps, and a Repel may go on (its ItemUseOutOfBattle_Repel).
        assertEquals(0, fns.repelSteps(0x8000 or 120))
        // The other games keep the whole value.
        assertEquals(0x8000 or 120, FAKE_FNS.repelSteps(0x8000 or 120))
    }

    @Test
    fun `never while the game is busy`() {
        val g = FakeGame(FAKE_FNS).apply { locked = true }
        assertEquals(ItemUseResult.NOT_NOW, useFieldItem(g.fns, 86, false, g).result)
        g.locked = false
        assertEquals(ItemUseResult.NOT_NOW, useFieldItem(g.fns, 86, busy = true, g = g).result)
        assertEquals(0, g.repel)
        assertEquals(ItemUseResult.FAILED, useFieldItem(g.fns, 13, false, g).result) // a Potion isn't a Repel
    }

    // --- USE on a Pokémon -------------------------------------------------------

    @Test
    fun `the no-effect check decides which Pokemon light up`() {
        val g = FakeGame(FAKE_FNS)
        g.bag[13] = 2
        g.party += FakeGame.Mon(0x111, hp = 3) // hurt
        g.party += FakeGame.Mon(0x222) // healthy
        g.party += FakeGame.Mon(0x333, egg = true, hp = 1)
        g.party += FakeGame.Mon(0x444, hp = 0) // fainted: a Potion won't help
        val t = partyTargets(support(g), 13, give = false, busy = false, g = g)
        assertEquals(ItemUseResult.USED, t.result)
        assertEquals(listOf(SlotVerdict.OK, SlotVerdict.NO_EFFECT, SlotVerdict.EGG, SlotVerdict.NO_EFFECT), t.targets.map { it.verdict })
        assertEquals(listOf(0x111L, 0x222L, 0x333L, 0x444L), t.targets.map { it.personality })
    }

    @Test
    fun `Emerald's no-effect check is the effect itself, undone`() {
        val fns = FAKE_FNS.copy(party = FAKE_FNS.party!!.copy(itemNoEffect = null))
        val g = FakeGame(fns)
        g.bag[13] = 1
        g.party += FakeGame.Mon(0x111, hp = 3, maxHp = 40)
        g.party += FakeGame.Mon(0x222)
        val t = partyTargets(FieldItemSupport(fns, emptySet()), 13, false, false, g)
        assertEquals(listOf(SlotVerdict.OK, SlotVerdict.NO_EFFECT), t.targets.map { it.verdict })
        // The pick changed nothing: the hurt one is still at 3 HP, the Potion still in the bag.
        assertEquals(3, g.party[0].hp)
        assertEquals(1, g.bag[13])
        // USE: the check (undone), then the effect for real.
        assertEquals(ItemUseResult.USED, useOnMon(FieldItemSupport(fns, emptySet()), 13, t.targets[0], 0, false, g).result)
        assertEquals(23, g.party[0].hp)
        assertFalse(13 in g.bag)
    }

    @Test
    fun `a Potion heals, then leaves the bag`() {
        val g = FakeGame(FAKE_FNS)
        g.bag[13] = 2
        g.party += FakeGame.Mon(0x111, hp = 3, maxHp = 40)
        val out = useOnMon(support(g), 13, g.target(0), 0, busy = false, g = g)
        assertEquals(ItemUseResult.USED, out.result)
        assertEquals(23, g.party[0].hp)
        assertEquals(1, g.bag[13])
    }

    @Test
    fun `no effect leaves the bag and the Pokemon alone`() {
        val g = FakeGame(FAKE_FNS)
        g.bag[14] = 1
        g.party += FakeGame.Mon(0x111) // no status: an Antidote does nothing
        val out = useOnMon(support(g), 14, PartyTarget(0, 0x111, 0, SlotVerdict.OK), 0, busy = false, g = g)
        assertEquals(ItemUseResult.NO_EFFECT, out.result)
        assertEquals(1, g.bag[14])
        assertFalse(g.calls.contains(g.p.itemEffect))
    }

    @Test
    fun `HP Up does nothing for Shedinja, as the party menu says`() {
        val g = FakeGame(FAKE_FNS)
        g.bag[63] = 1
        g.party += FakeGame.Mon(0x111, species = 303)
        g.party += FakeGame.Mon(0x222)
        assertEquals(listOf(SlotVerdict.NO_EFFECT, SlotVerdict.OK), partyTargets(support(g), 63, false, false, g).targets.map { it.verdict })
        assertEquals(ItemUseResult.NO_EFFECT, useOnMon(support(g), 63, PartyTarget(0, 0x111, 0, SlotVerdict.OK), 0, false, g).result)
        assertEquals(1, g.bag[63])
    }

    @Test
    fun `an Ether goes on the move it was picked for`() {
        val g = FakeGame(FAKE_FNS)
        g.bag[34] = 1
        g.party += FakeGame.Mon(0x111, pp = intArrayOf(5, 1, 0, 0))
        val t = g.target(0, item = 34)
        // Move 1 is short of PP; move 0 is full; moves 2-3 are empty slots.
        assertEquals(listOf(false, true, false, false), t.moves)
        assertEquals(ItemUseResult.USED, useOnMon(support(g), 34, t, 1, false, g).result)
        assertEquals(5, g.party[0].pp[1])
        assertFalse(34 in g.bag)
    }

    @Test
    fun `a Pokemon that changed since the pick is left alone`() {
        val g = FakeGame(FAKE_FNS)
        g.bag[13] = 1
        g.party += FakeGame.Mon(0x111, hp = 3)
        val t = g.target(0)
        g.party[0] = FakeGame.Mon(0x999, hp = 3) // switched in the game meanwhile
        assertEquals(ItemUseResult.PARTY_CHANGED, useOnMon(support(g), 13, t, 0, false, g).result)
        assertEquals(3, g.party[0].hp)
        assertEquals(1, g.bag[13])
    }

    @Test
    fun `not now in a battle or a menu, none left without one in the bag`() {
        val g = FakeGame(FAKE_FNS)
        g.party += FakeGame.Mon(0x111, hp = 3)
        assertEquals(ItemUseResult.NONE_LEFT, partyTargets(support(g), 13, false, false, g).result)
        g.bag[13] = 1
        assertEquals(ItemUseResult.NOT_NOW, partyTargets(support(g), 13, false, busy = true, g = g).result)
        g.locked = true
        assertEquals(ItemUseResult.NOT_NOW, useOnMon(support(g), 13, PartyTarget(0, 0x111, 0, SlotVerdict.OK), 0, false, g).result)
        assertEquals(3, g.party[0].hp)
    }

    // --- GIVE -----------------------------------------------------------------

    @Test
    fun `give to an empty-handed Pokemon`() {
        val g = FakeGame(FAKE_FNS)
        g.bag[200] = 1
        g.party += FakeGame.Mon(0x111)
        val out = giveToMon(support(g), 200, g.target(0, give = true, item = 200), false, g)
        assertEquals(ItemUseOutcome(ItemUseResult.GIVEN, swapped = 0), out)
        assertEquals(200, g.party[0].held)
        assertFalse(200 in g.bag)
        // SetMonData's argument went through the scratch word, which is put back afterwards.
        assertEquals(0, g.read(g.p.scratch, 2).sumOf { it.toInt() })
    }

    @Test
    fun `give to a Pokemon holding something and its item goes back to the bag`() {
        val g = FakeGame(FAKE_FNS)
        g.bag[200] = 2
        g.party += FakeGame.Mon(0x111, held = 182)
        val out = giveToMon(support(g), 200, g.target(0, give = true, item = 200), false, g)
        assertEquals(ItemUseOutcome(ItemUseResult.GIVEN, swapped = 182), out)
        assertEquals(200, g.party[0].held)
        assertEquals(1, g.bag[200])
        assertEquals(1, g.bag[182])
    }

    @Test
    fun `no room for the old item and nothing changes`() {
        val g = FakeGame(FAKE_FNS)
        g.bag.clear()
        g.bag[200] = 2
        g.bagKinds = 1 // full: EXP. SHARE can't come back in
        g.party += FakeGame.Mon(0x111, held = 182)
        val out = giveToMon(support(g), 200, g.target(0, give = true, item = 200), false, g)
        assertEquals(ItemUseResult.BAG_FULL, out.result)
        assertEquals(182, g.party[0].held)
        assertEquals(mapOf(200 to 2), g.bag)
    }

    @Test
    fun `giving away the last of a stack frees the slot the old item needs`() {
        val g = FakeGame(FAKE_FNS)
        g.bag.clear()
        g.bag[200] = 1
        g.bagKinds = 1 // full - until the LEFTOVERS leave it (the game's own switch order)
        g.party += FakeGame.Mon(0x111, held = 182)
        assertEquals(ItemUseResult.GIVEN, giveToMon(support(g), 200, g.target(0, give = true, item = 200), false, g).result)
        assertEquals(200, g.party[0].held)
        assertEquals(mapOf(182 to 1), g.bag)
    }

    @Test
    fun `a SetMonData that didn't take puts everything back`() {
        val g = FakeGame(FAKE_FNS)
        g.bag[200] = 1
        g.party += FakeGame.Mon(0x111, held = 182)
        val t = g.target(0, give = true, item = 200)
        g.failSetMonData = true
        assertEquals(ItemUseResult.FAILED, giveToMon(support(g), 200, t, false, g).result)
        assertEquals(182, g.party[0].held)
        assertEquals(1, g.bag[200])
        assertNull(g.bag[182])
    }

    @Test
    fun `eggs, Mail holders and the same item can't be picked to hold`() {
        val g = FakeGame(FAKE_FNS)
        g.bag[200] = 1
        g.party += FakeGame.Mon(0x111, egg = true)
        g.party += FakeGame.Mon(0x222, held = 121) // ORANGE MAIL
        g.party += FakeGame.Mon(0x333, held = 200)
        g.party += FakeGame.Mon(0x444, held = 182)
        assertEquals(
            listOf(SlotVerdict.EGG, SlotVerdict.HOLDS_MAIL, SlotVerdict.HOLDS_IT, SlotVerdict.OK),
            partyTargets(support(g), 200, give = true, busy = false, g = g).targets.map { it.verdict },
        )
        assertEquals(listOf(0, 121, 200, 182), partyTargets(support(g), 200, true, false, g).targets.map { it.heldItem })
        // Even asked directly (a stale pick), the Mail stays where it is.
        assertEquals(ItemUseResult.FAILED, giveToMon(support(g), 200, PartyTarget(1, 0x222, 121, SlotVerdict.OK), false, g).result)
        assertEquals(121, g.party[1].held)
        assertEquals(1, g.bag[200])
    }

    // --- TAKE -----------------------------------------------------------------

    @Test
    fun `take puts the held item in the bag and empties the Pokemon's hand`() {
        val g = FakeGame(FAKE_FNS)
        g.party += FakeGame.Mon(0x111, held = 182)
        g.party += FakeGame.Mon(0x222)
        g.party += FakeGame.Mon(0x333, held = 121) // Mail stays (the game asks about the PC first)
        g.party += FakeGame.Mon(0x444, held = 200, egg = true)
        val held = partyHeldItems(support(g), g)
        assertEquals(listOf(182, 0, 121, 200), held.targets.map { it.heldItem })
        assertEquals(listOf(SlotVerdict.OK, SlotVerdict.NO_EFFECT, SlotVerdict.HOLDS_MAIL, SlotVerdict.EGG), held.targets.map { it.verdict })
        assertEquals(mapOf(86 to 1), g.bag) // the room check took nothing in
        assertEquals(ItemUseOutcome(ItemUseResult.TAKEN, swapped = 182), takeFromMon(support(g), held.targets[0], false, g))
        assertEquals(0, g.party[0].held)
        assertEquals(1, g.bag[182])
        assertEquals(ItemUseResult.NO_EFFECT, takeFromMon(support(g), held.targets[1], false, g).result)
        assertEquals(ItemUseResult.FAILED, takeFromMon(support(g), held.targets[2], false, g).result)
        assertEquals(121, g.party[2].held)
    }

    @Test
    fun `take with a full bag, or a SetMonData that didn't take, changes nothing`() {
        val g = FakeGame(FAKE_FNS)
        g.bag.clear()
        g.bag[13] = 1
        g.bagKinds = 1
        g.party += FakeGame.Mon(0x111, held = 182)
        // The summary knows before TAKE is pressed: no room, no TAKE - and asking left the bag as it was.
        assertEquals(SlotVerdict.BAG_FULL, partyHeldItems(support(g), g).targets[0].verdict)
        assertEquals(mapOf(13 to 1), g.bag)
        val t = partyHeldItems(support(g), g).targets[0].copy(verdict = SlotVerdict.OK) // a stale pick
        assertEquals(ItemUseOutcome(ItemUseResult.BAG_FULL, swapped = 182), takeFromMon(support(g), t, false, g))
        assertEquals(182, g.party[0].held)
        g.bagKinds = 30
        g.failSetMonData = true
        assertEquals(ItemUseResult.FAILED, takeFromMon(support(g), t, false, g).result)
        assertEquals(182, g.party[0].held)
        assertEquals(mapOf(13 to 1), g.bag)
        g.failSetMonData = false
        assertEquals(ItemUseResult.NOT_NOW, takeFromMon(support(g), t, busy = true, g = g).result)
        assertEquals(182, g.party[0].held)
    }

    // --- What gets a button and a mark ---------------------------------------------

    @Test
    fun `the mark is for medicine, PP items, Repels and holdable items, on a ROM with the party actions`() {
        val s = FieldItemSupport(FAKE_FNS, holdable = setOf(13, 200))
        assertTrue(s.marked(13)) // POTION: USE and GIVE
        assertTrue(s.marked(86)) // REPEL
        assertTrue(s.marked(36)) // ELIXIR
        assertTrue(s.marked(69)) // PP UP
        assertTrue(s.marked(200)) // LEFTOVERS: GIVE
        assertFalse(s.marked(68)) // RARE CANDY: the party menu's level-up / evolution aren't done here
        assertFalse(s.marked(93)) // SUN STONE
        assertFalse(s.marked(259)) // a key item
        assertTrue(s.picksMove(34) && s.picksMove(69) && !s.picksMove(36) && !s.picksMove(13))
        assertTrue(s.picksMon(13) && !s.picksMon(86))
        // A ROM with the Repels' USE but no party actions: no marks.
        val repelsOnly = FieldItemSupport(FIELD_ITEMS_EMERALD.copy(party = null), holdable = emptySet())
        assertTrue(repelsOnly.canUse(86))
        assertFalse(repelsOnly.marked(86) || repelsOnly.canUse(13) || repelsOnly.canGive(200) || repelsOnly.canTake)
    }

    @Test
    fun `retail code is where the tables say, and another ROM is left alone`() {
        assumeTrue(rom != null && frRom != null)
        assertTrue(fieldItemCodeMatches(FIELD_ITEMS_EMERALD, romReader(rom!!)))
        assertTrue(fieldItemCodeMatches(FIELD_ITEMS_FIRERED_REV1, romReader(frRom!!)))
        assertFalse(fieldItemCodeMatches(FIELD_ITEMS_FIRERED_REV1, romReader(rom)))
        assertNull(fieldItemSupport(FIELD_ITEMS_FIRERED_REV1, romReader(rom)))
        // gItems: REPEL's 100 steps, SUPER / MAX REPEL's 200 / 250, at the address each table names.
        for ((bytes, fns) in listOf(frRom to FIELD_ITEMS_FIRERED_REV1, rom to FIELD_ITEMS_EMERALD)) {
            assertEquals(listOf(100, 200, 250), listOf(86, 83, 84).map { romReader(bytes)(fns.items + it * 44 + 0x13, 1)[0].toInt() and 0xFF })
        }
    }

    @Test
    fun `Emerald's holdable items come from its gItems`() {
        assumeTrue(rom != null)
        val s = fieldItemSupport(FIELD_ITEMS_EMERALD, romReader(rom!!))!!
        // Items, Poké Balls and Berries; not key items (MACH BIKE 259), TMs / HMs (289, 339) or Mail (121).
        for (id in listOf(13, 4, 200, 182, 133, 139, 86)) assertTrue("$id", s.canGive(id))
        for (id in listOf(259, 289, 339, 121, 0)) assertFalse("$id", s.canGive(id))
        assertTrue(s.marked(13) && s.canTake && s.picksMove(34))
    }

    /** FireRed Rocket Edition, Ash Gray (both builds), Ghost Grey (three) and Let's Go (four) keep rev 0's code: rev 0's
     * table, its CRCs, their own gItems (Ghost Grey's 86 lasts 250 steps, not 100). */
    @Test
    fun `the FireRed hacks edited in place run rev 0's table`() {
        val fns = fieldItemsForGame(GameKind.FIRERED, "BPRE", 0)!!
        var ran = 0
        for (file in listOf("Pokemon FireRed Rocket Edition.gba", "Pokemon Ash Gray (v4.5.3).gba", "Pokemon Ash Gray Fork Bug Fix (v1.0).gba",
            "Pokemon Ghost Grey (v1.0.1).gba", "Pokemon Ghost Grey (v1.0.2).gba", "Pokemon Ghost Grey (v1.0.4).gba",
            "Pokemon Let's Go Pikachu.gba", "Pokemon Let's Go Pikachu (Translation Only).gba", "Pokemon Let's Go Eevee.gba",
            "Pokemon Let's Go Eevee (Translation Only).gba")) {
            val bytes = File(RETAIL_ROM_DIR + file).takeIf { it.isFile }?.readBytes() ?: continue
            val s = fieldItemSupport(fns, romReader(bytes))
            assertTrue(file, s != null && s.marked(13) && s.canUse(13) && s.canGive(200) && s.canTake)
            val steps = if ("Ghost Grey" in file) listOf(250, 200, 250) else listOf(100, 200, 250)
            assertEquals(file, steps, listOf(86, 83, 84).map { romReader(bytes)(fns.items + it * 44 + 0x13, 1)[0].toInt() and 0xFF })
            ran++
        }
        assumeTrue(ran > 0)
    }

    /** Every table against its own retail ROM (pret's builds of them, byte for byte), and never against another's. */
    @Test
    fun `each retail table matches its ROM and no other`() {
        val roms = listOf(
            Triple("Pokemon - FireRed Version (USA, Europe) (Rev 1).gba", "BPRE", 1),
            Triple("Pokemon - LeafGreen Version (USA).gba", "BPGE", 0),
            Triple("Pokemon - LeafGreen Version (USA, Europe) (Rev 1).gba", "BPGE", 1),
            Triple("Pokemon - Emerald Version (USA, Europe).gba", "BPEE", 0),
            Triple("Pokemon - Ruby Version (USA, Europe) (Rev 1).gba", "AXVE", 1),
            Triple("Pokemon - Ruby Version (USA, Europe) (Rev 2).gba", "AXVE", 2),
            Triple("Pokemon - Sapphire Version (USA, Europe) (Rev 1).gba", "AXPE", 1),
            Triple("Pokemon - Sapphire Version (USA, Europe) (Rev 2).gba", "AXPE", 2),
        ).mapNotNull { (file, code, rev) -> File(RETAIL_ROM_DIR + file).takeIf { it.isFile }?.let { Triple(it.readBytes(), code, rev) } }
        assumeTrue(roms.size == 8)
        val tables = roms.map { (_, code, rev) -> fieldItemsFor(code, rev)!! }.distinct()
        for ((bytes, code, rev) in roms) {
            val own = fieldItemsFor(code, rev)!!
            val read = romReader(bytes)
            val s = fieldItemSupport(own, read)
            assertTrue("$code rev $rev", s != null && s.marked(13) && s.canGive(200) && !s.canGive(259) && s.canTake)
            // Every medicine / PP / Repel id runs the game's own routine for it, in this ROM's gItems.
            val items = read(own.items, own.party!!.itemCount * own.itemSize)
            assertEquals("$code rev $rev", own.party!!.usable + REPEL_ITEMS, usableItems(own, items))
            // The Repels' steps where the table says gItems is.
            assertEquals(listOf(100, 200, 250), listOf(86, 83, 84).map { read(own.items + it * 44 + 0x13, 1)[0].toInt() and 0xFF })
            for (other in tables - own) {
                assertFalse("$code rev $rev matched another table", fieldItemCodeMatches(other, read))
            }
        }
        assertNull(fieldItemsFor("BPRE", 2)) // no such revision
    }

    /** The generated tables (scripts/port_field_items.py) against the other-language ROMs they came from: each
     * matches its own ROM's code and gItems, and is the only table that does. -DmultiRomDir=..., else the
     * retail ROM dir; skipped without it. */
    @Test
    fun `each ported table matches its ROM`() {
        val dir = File(System.getProperty("multiRomDir") ?: RETAIL_ROM_DIR)
        assumeTrue(dir.isDirectory)
        // By header only (~50 ROMs of up to 16 MB): each one read whole when its table comes up.
        val roms = retailRomFiles(dir).filter { listOf("Pirate", "Debug", "Pokemon Box").none { x -> x in it.name } }
            .associateBy { f -> f.inputStream().use { val h = ByteArray(0xC0); it.read(h); "${String(h, 0xAC, 4)}${h[0xBC].toInt()}" } }
        var checked = 0
        for ((key, fns) in FIELD_ITEMS_PORTS) {
            val bytes = roms[key]?.readBytes() ?: continue
            val read = romReader(bytes)
            val s = fieldItemSupport(fns, read)
            assertTrue(key, s != null && s.marked(13) && s.canGive(200) && s.canTake)
            val items = read(fns.items, fns.party!!.itemCount * fns.itemSize)
            assertEquals(key, fns.party!!.usable + REPEL_ITEMS, usableItems(fns, items))
            assertEquals(key, fns, fieldItemsFor(key.dropLast(1), key.last().digitToInt()))
            checked++
        }
        assertEquals(FIELD_ITEMS_PORTS.size, checked)
    }

    /** Recharged Emerald (scripts/find_field_items_recharged_emerald.py): each build's table matches its own ROM's code
     * and no other's, its gItems' 48-byte records give the Repels' steps at 0x15, its pockets keep key items and TMs out
     * of GIVE (and leave its Medicine pocket in), and its config hands the table over (it shares retail's game code). */
    @Test
    fun `each Recharged Emerald table matches its own build`() {
        val builds = listOf(
            Triple("Pokemon Recharged Emerald (v2.2.5).gba", FIELD_ITEMS_RECHARGED_EMERALD_V2_2_5, NATIVE_RECHARGED_EMERALD_V2_2_5),
            Triple("Pokemon Recharged Emerald (v2.2.8).gba", FIELD_ITEMS_RECHARGED_EMERALD_V2_2_8, NATIVE_RECHARGED_EMERALD_V2_2_8),
            Triple("Pokemon Recharged Emerald Rebalanced (v2.2.8).gba", FIELD_ITEMS_RECHARGED_EMERALD_REBALANCED_V2_2_8, NATIVE_RECHARGED_EMERALD_REBALANCED_V2_2_8),
        ).mapNotNull { (file, fns, cfg) -> File(RETAIL_ROM_DIR + file).takeIf { it.isFile }?.let { Triple(it.readBytes(), fns, cfg) } }
        assumeTrue(builds.size == 3)
        val tables = builds.map { it.second }
        for ((bytes, fns, cfg) in builds) {
            val read = romReader(bytes)
            assertTrue(fns === cfg.fieldItems)
            assertTrue(fns === fieldItemsForGame(GameKind.EMERALD, "BPEE", 0, cfg.fieldItems))
            val s = fieldItemSupport(fns, read)!!
            for (other in tables - fns) assertFalse(fieldItemCodeMatches(other, read))
            assertFalse(fieldItemCodeMatches(FIELD_ITEMS_EMERALD, read))
            assertEquals(listOf(100, 200, 250), listOf(86, 83, 84).map { read(fns.items + it * 48L + fns.itemParamOff, 1)[0].toInt() and 0xFF })
            val items = read(fns.items, fns.party!!.itemCount * fns.itemSize)
            assertEquals(fns.party!!.usable + REPEL_ITEMS, usableItems(fns, items))
            // Medicine-pocket items (13, its PEWTER ARARE 62), Poké Balls, Berries, held items; not key items (MACH BIKE),
            // TMs / HMs or Mail.
            for (id in listOf(13, 62, 4, 139, 182, 200, 86)) assertTrue("$id", s.canGive(id))
            for (id in listOf(259, 289, 339, 121, 0)) assertFalse("$id", s.canGive(id))
            assertTrue(s.canUse(13) && s.canUse(86) && s.picksMove(34) && s.canTake)
            // PEWTER ARARE runs the medicine routine but has no effect entry (the game: "It won't have any effect.");
            // RARE CANDY; its LURE (not done here).
            assertFalse(s.canUse(62) || s.canUse(68) || s.canUse(52))
        }
        // Retail Emerald keeps retail's table: no config table, so by kind and code.
        assertTrue(fieldItemsForGame(GameKind.EMERALD, "BPEE", 0, NATIVE_EMERALD_RETAIL.fieldItems) === FIELD_ITEMS_EMERALD)
    }

    @Test
    fun `a Repel sets the game's other variables too, in its order`() {
        val fns = FAKE_FNS.copy(repelVar = 0x4021, repelClearVars = listOf(0x4055, 0x404E), lastRepelVar = 0x4052)
        val g = FakeGame(fns)
        g.bag[83] = 1
        assertEquals(ItemUseOutcome(ItemUseResult.USED, 100), useFieldItem(fns, 86, false, g))
        assertEquals(listOf(0x4055 to 0, 0x404E to 0, 0x4021 to 100, 0x4052 to 86), g.varSets)
        assertEquals(ItemUseResult.STILL_ACTIVE, useFieldItem(fns, 83, false, g).result)
        assertEquals(4, g.varSets.size)
    }

    @Test
    fun `a build whose Pokemon fields moved is asked for the moved ids`() {
        val fns = FAKE_FNS.copy(party = FAKE_FNS.party!!.copy(monFieldShift = 9))
        val g = FakeGame(fns)
        g.bag[13] = 1
        g.bag[200] = 1
        g.party += FakeGame.Mon(0x111, hp = 3, held = 182)
        g.party += FakeGame.Mon(0x222, egg = true)
        g.party += FakeGame.Mon(0x333, species = 303)
        val s = FieldItemSupport(fns, setOf(13, 200, 182, 63))
        assertEquals(listOf(SlotVerdict.OK, SlotVerdict.EGG, SlotVerdict.NO_EFFECT), partyTargets(s, 13, false, false, g).targets.map { it.verdict })
        g.bag[63] = 1
        assertEquals(SlotVerdict.NO_EFFECT, partyTargets(s, 63, false, false, g).targets[2].verdict) // HP UP on SHEDINJA
        assertEquals(ItemUseResult.USED, useOnMon(s, 13, partyTargets(s, 13, false, false, g).targets[0], 0, false, g).result)
        val give = partyTargets(s, 200, true, false, g).targets[0]
        assertEquals(182, give.heldItem)
        assertEquals(ItemUseOutcome(ItemUseResult.GIVEN, swapped = 182), giveToMon(s, 200, give, false, g))
        assertEquals(ItemUseOutcome(ItemUseResult.TAKEN, swapped = 200), takeFromMon(s, partyHeldItems(s, g).targets[0], false, g))
        assertEquals(0, g.party[0].held)
    }

    @Test
    fun `FireRed's holdable items are what its bag lets you GIVE`() {
        assumeTrue(frRom != null)
        val s = fieldItemSupport(FIELD_ITEMS_FIRERED_REV1, romReader(frRom!!))!!
        // Items, Poké Balls and Berries can be held; key items, TMs / HMs and Mail can't.
        for (id in listOf(13, 4, 200, 182, 133, 139, 86)) assertTrue("$id", s.canGive(id))
        for (id in listOf(259, 260, 289, 339, 121, 132, 0)) assertFalse("$id", s.canGive(id))
        assertTrue(s.canUse(13) && s.canGive(13) && s.marked(13))
        assertFalse(s.marked(259)) // MACH BIKE: nothing to do with it here
    }
}
