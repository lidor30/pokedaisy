package com.pokedaisy.app.companion.data.save

import com.pokedaisy.app.companion.data.NdsArt
import com.pokedaisy.app.companion.data.RETAIL_ROM_DIR
import java.io.File
import okio.Path.Companion.toOkioPath
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * HeartGold / SoulSilver saves: a made-up one built here by its own writer (the layout from pret/pokeheartgold, written
 * independently of [Gen4SaveFile]), and, when they're on this machine, the player's real save with tables from the cart.
 */
class Gen4SaveTest {

    // ---- A synthetic save ----

    private val game = Gen4GameData(
        species = List(494) { if (it == 0) "" else "MON$it" },
        moveNames = List(468) { "MOVE$it" },
        items = List(537) { "ITEM$it" },
        abilityNames = List(124) { "ABILITY$it" },
        personal = List(494) { s ->
            if (s == 0) null else ByteArray(0x2C).also { r ->
                for (i in 0 until 6) r[i] = (40 + s % 60 + i * 5).toByte()
                r[0x10] = if (s == 150) 255.toByte() else 127   // Mewtwo genderless, the rest half and half
                r[0x13] = 0                                       // medium fast
                r[0x16] = 65; r[0x17] = if (s == 150) 0 else 34
                r[0x1C] = 1                                       // TM01
            }
        },
        levelUp = List(494) { listOf(1 to 33, 7 to 45, 20 to 22) },
        preEvolution = IntArray(494),
        tmMoves = IntArray(100) { 264 + it },
        movePp = IntArray(468) { if (it == 0) 0 else 20 },
        itemPockets = SyntheticGen4.demoItemPockets,
        // KADABRA (64) evolves by trading; ONIX (95) holding METAL COAT (233).
        evolutions = List(494) { s -> when (s) { 64 -> listOf(Triple(5, 0, 65)); 95 -> listOf(Triple(6, 233, 208)); else -> emptyList() } },
    )

    private fun parse(bytes: ByteArray) = (Gen4SaveFile.parse(bytes) as Gen4SaveFile.Parsed.Ok).save

    private fun editor() = Gen4SaveEditor(parse(SyntheticGen4.save()), game)

    private fun reread(e: Gen4SaveEditor) = parse(e.build()).let { Gen4SaveData(game, it.general, it.pc) }

    private fun refused(block: () -> Unit) {
        try { block() } catch (e: IllegalArgumentException) { return } catch (e: SaveEditRefused) { return }
        fail("allowed")
    }

    @Test
    fun readsTheNewerHalf() {
        val s = parse(SyntheticGen4.save())
        assertEquals(1, s.half)
        assertEquals(8L, s.counter)
        val d = Gen4SaveData(game, s.general, s.pc)
        assertEquals("RED", d.trainerName)
        assertEquals(12345, d.trainerId)
        assertEquals(2, d.partyCount)
        assertEquals(listOf(154, 25), d.partyMons.map { it.mon.species })
        assertEquals(50, d.partyMons[0].level)
        assertEquals(listOf(1, 4), d.boxMons(0).take(2).map { it.species })
        assertTrue(d.boxMons(0)[2].isEmpty)
        assertNull(d.mismatch())
    }

    @Test
    fun aDamagedHalfIsSkipped() {
        val bytes = SyntheticGen4.save()
        bytes[0x40000 + 0x100] = (bytes[0x40000 + 0x100] + 1).toByte()   // half 1's general block no longer sums
        assertEquals(0, parse(bytes).half)
    }

    @Test
    fun anEditIsSavedIntoTheOtherHalf() {
        val raw = SyntheticGen4.save()
        val e = Gen4SaveEditor(parse(raw), game)
        e.setIvs(MonRef.Party(0), IntArray(6) { 31 })
        val out = e.build()
        val back = parse(out)
        assertEquals(0, back.half)
        assertEquals(9L, back.counter)
        // The half it was loaded from is the game's fallback, byte for byte.
        assertArrayEquals(raw.copyOfRange(0x40000, 0x80000), out.copyOfRange(0x40000, 0x80000))
        // Every box marked changed, so the game's next save rewrites them all.
        assertEquals(Gen4SaveFile.ALL_BOXES_MODIFIED, SyntheticSave.rd32(back.pc, Gen4SaveFile.PC_MODIFIED_FLAGS))
        assertEquals(IntArray(6) { 31 }.toList(), Gen4SaveData(game, back.general, back.pc).partyMons[0].mon.ivs.toList())
    }

    @Test
    fun valuesStayInTheGamesLimits() {
        val e = editor()
        val p0 = MonRef.Party(0)
        e.setEvs(p0, intArrayOf(252, 252, 6, 0, 0, 0))
        refused { e.setEvs(p0, intArrayOf(255, 255, 1, 0, 0, 0)) }
        refused { e.setIvs(p0, intArrayOf(32, 0, 0, 0, 0, 0)) }
        val before = e.data.partyMons[0].level
        e.setLevel(p0, 60)
        refused { e.setLevel(p0, before - 1) }
        e.setFriendship(p0, 255)
        val d = reread(e)
        val p = d.partyMons[0]
        assertEquals(60, p.level)
        assertEquals(60, p.mon.level)
        assertEquals(255, p.mon.friendship)
        assertEquals(game.stats(p.mon, 60).toList(), p.stats.toList())
        assertEquals(p.stats[0], p.hp)   // it was at full HP, and stays there
    }

    @Test
    fun theTraitsFollowThePersonality() {
        val e = editor()
        val box = MonRef.Pc(BoxSlot(0, 0))
        val before = e.monAt(box)!!
        e.reroll(box, nature = (before.nature + 3) % 25, shiny = true)
        var m = reread(e).mon(BoxSlot(0, 0))
        assertEquals((before.nature + 3) % 25, m.nature)
        assertTrue(m.isShiny)
        assertEquals(before.ivs.toList(), m.ivs.toList())
        assertEquals(before.exp, m.exp)
        e.reroll(box, abilityNum = 1)
        m = reread(e).mon(BoxSlot(0, 0))
        assertEquals(34, m.ability)
        assertEquals(1L, m.personality and 1L)
        val other = if (m.gender == Gen3Gender.MALE) Gen3Gender.FEMALE else Gen3Gender.MALE
        e.reroll(box, gender = other)
        assertEquals(other, reread(e).mon(BoxSlot(0, 0)).gender)
        refused { e.reroll(MonRef.Pc(BoxSlot(1, 0)), gender = Gen3Gender.MALE) }   // genderless
        // A party Pokémon under a new personality keeps its party fields (re-encrypted with the new key).
        e.reroll(MonRef.Party(1), nature = 10)
        val p = reread(e).partyMons[1]
        assertEquals(10, p.mon.nature)
        assertEquals(10, p.level)
    }

    @Test
    fun movesFromWhatItCouldLearn() {
        val e = editor()
        val p0 = MonRef.Party(0)
        assertTrue(22 in e.learnable(p0) && 264 in e.learnable(p0))
        e.setMoves(p0, listOf(33, 22, 264))
        refused { e.setMoves(p0, listOf(100)) }
        val m = reread(e).partyMons[0].mon
        assertEquals(listOf(33, 22, 264, 0), m.moves.toList())
        assertEquals(listOf(35, 20, 20, 0), m.pp.toList())   // the move it kept keeps its PP
    }

    @Test
    fun thePartyAndThePc() {
        val e = editor()
        e.move(MonRef.Pc(BoxSlot(0, 0)), MonRef.Party(5))   // WITHDRAW to the end
        var d = reread(e)
        assertEquals(3, d.partyCount)
        assertEquals(1, d.partyMons[2].mon.species)
        assertEquals(d.partyMons[2].stats[0], d.partyMons[2].hp)
        assertTrue(d.mon(BoxSlot(0, 0)).isEmpty)
        e.move(MonRef.Party(1), MonRef.Pc(BoxSlot(0, 10)))   // DEPOSIT; the party closes up
        d = reread(e)
        assertEquals(2, d.partyCount)
        assertEquals(25, d.mon(BoxSlot(0, 10)).species)
        assertEquals(listOf(154, 1), d.partyMons.map { it.mon.species })
        assertNull(d.mismatch())
        e.move(MonRef.Party(1), MonRef.Pc(BoxSlot(0, 11)))
        refused { e.move(MonRef.Party(0), MonRef.Pc(BoxSlot(0, 12))) }   // the last one able to battle
        e.sortBox(0, BoxSort.LEVEL) { "" }
        e.release(BoxSlot(0, 0))
        assertNull(reread(e).mismatch())
    }

    @Test
    fun giveAndTakeGoThroughTheBag() {
        val e = editor()
        val p0 = MonRef.Party(0)
        assertEquals(listOf(234, 17), e.holdable())
        e.setHeldItem(p0, 234)   // GIVE: the only LEFTOVERS leaves the bag
        assertEquals(listOf(17), e.holdable())
        e.setHeldItem(p0, 17)    // a POTION for it: the LEFTOVERS back in the bag
        var d = reread(e)
        assertEquals(17, d.partyMons[0].mon.heldItem)
        assertEquals(listOf(234, 17), e.holdable())
        val medicine = 0x644 + (165 + 50 + 101 + 12) * 4
        val g = parse(e.build()).general
        assertEquals(2, SyntheticSave.rd32(g, medicine).toInt() shr 16)
        e.setHeldItem(p0, 0)     // TAKE
        d = reread(e)
        assertEquals(0, d.partyMons[0].mon.heldItem)
        assertEquals(3, SyntheticSave.rd32(parse(e.build()).general, medicine).toInt() shr 16)
        refused { e.setHeldItem(p0, 155) }   // not in the bag
    }

    @Test
    fun namesInTheGamesText() {
        val e = editor()
        val p0 = MonRef.Party(0)
        e.setNickname(p0, "Sparky")
        var m = reread(e).partyMons[0].mon as Gen4BoxMon
        assertEquals("Sparky", m.nickname)
        e.setNickname(p0, "MON154")   // the species' own name: no longer nicknamed
        m = reread(e).partyMons[0].mon as Gen4BoxMon
        assertEquals("MON154", m.nickname)
        refused { e.setNickname(p0, "Far too long") }
        refused { e.setNickname(MonRef.Pc(BoxSlot(1, 0)), "Mew") }   // another trainer's
        e.renameBox(2, "Team 3")
        assertEquals("Team 3", reread(e).boxName(2))
    }

    @Test
    fun moneyAndTheBag() {
        val e = editor()
        assertEquals(3000L, e.money)
        e.setMoney(999_999)
        refused { e.setMoney(1_000_000) }
        assertEquals(999_999L, Gen4SaveData(game, parse(e.build()).general, parse(e.build()).pc).money)
        assertEquals(PocketKind.entries, e.pockets.map { it.kind })
        assertEquals(listOf(ItemStack(234, 1)), e.pocket(PocketKind.ITEMS)!!.stacks)
        assertEquals(listOf(ItemStack(17, 3)), e.pocket(PocketKind.MEDICINE)!!.stacks)
        assertEquals(listOf(234, 233, 229).sorted(), e.addable(PocketKind.ITEMS).sorted())
        e.setQuantity(PocketKind.MEDICINE, 0, 999)
        refused { e.setQuantity(PocketKind.MEDICINE, 0, 1000) }
        e.addItem(PocketKind.BERRIES, 155, 5)
        refused { e.addItem(PocketKind.BERRIES, 17, 1) }   // a POTION isn't a berry
        refused { e.addItem(PocketKind.ITEMS, 234, 1) }    // a stack of it is there already
        e.setQuantity(PocketKind.ITEMS, 0, 0)              // the LEFTOVERS out
        assertEquals(emptyList<ItemStack>(), e.pocket(PocketKind.ITEMS)!!.stacks)
        val back = parse(e.build())
        val reread = Gen4SaveEditor(back, game)
        assertEquals(listOf(ItemStack(17, 999)), reread.pocket(PocketKind.MEDICINE)!!.stacks)
        assertEquals(listOf(ItemStack(155, 5)), reread.pocket(PocketKind.BERRIES)!!.stacks)
        assertTrue(reread.fixedStack(PocketKind.TMHM, 420))   // HM01
        assertTrue(reread.fixedStack(PocketKind.KEY, 1))
    }

    @Test
    fun thePokedex() {
        val e = editor()
        assertEquals(0, e.dex.caught)
        e.registerOwned()
        // TYPHLOSION, PIKACHU, BULBASAUR, CHARMANDER, MEWTWO: the party and the PC.
        assertEquals(5, e.dex.caught)
        assertEquals(5, e.dex.seen)
        assertTrue(!e.dex.national)
        e.markAllDex(caught = false)
        assertEquals(493, e.dex.seen)    // no regional numbers here: every species
        assertEquals(5, e.dex.caught)
        e.markAllDex(caught = true)
        assertEquals(493, Gen4SaveEditor(parse(e.build()), game).dex.caught)
        // With regional numbers and no National Pokédex: only the regional ones.
        val johto = Gen4GameData(
            species = List(494) { "MON$it" }, moveNames = List(468) { "MOVE$it" }, items = List(537) { "ITEM$it" }, abilityNames = List(124) { "A" },
            personal = List(494) { if (it == 0) null else ByteArray(0x2C).also { r -> r[0x10] = 127 } }, levelUp = List(494) { emptyList() },
            preEvolution = IntArray(494), tmMoves = IntArray(100), movePp = IntArray(468) { 20 },
            regional = IntArray(494) { if (it in 1..10) it else 0 },
        )
        val j = Gen4SaveEditor(parse(SyntheticGen4.save()), johto)
        j.markAllDex(caught = true)
        assertEquals("JOHTO", j.dex.regionLabel)
        assertEquals(10, j.dex.caught)
        assertEquals(10, j.dex.regionalCaught)
    }

    /** Two synthetic saves, A's box 3 holding the given records from slot 0. */
    private fun sides(vararg boxed: ByteArray): Pair<TradeSide, TradeSide> {
        val a = SyntheticGen4.save()
        boxed.forEachIndexed { i, r -> for (half in 0..1) r.copyInto(a, half * 0x40000 + 0xF700 + 2 * 0x1000 + i * 0x88) }
        fun fixed(b: ByteArray) = b.also {
            for (half in 0..1) for ((off, size, slot) in listOf(Triple(0, 0xF628, 0), Triple(0xF700, 0x12310, 1))) {
                val base = half * 0x40000 + off
                SyntheticGen4.footer(it, base, size, SyntheticSave.rd32(it, base + size - 16), slot)
            }
        }
        return TradeSide("A", Gen4SaveEditor(parse(fixed(a)), game), "IPKE") to TradeSide("B", Gen4SaveEditor(parse(SyntheticGen4.save()), game), "IPGE")
    }

    private val dexCaught = 0x12B8 + 0x04
    private fun caught(side: TradeSide, species: Int) =
        parse(side.editor.build()).general.let { (it[dexCaught + (species - 1) / 8].toInt() shr ((species - 1) % 8)) and 1 == 1 }

    @Test
    fun aTradeRegistersAndEvolves() {
        val (a, b) = sides(SyntheticGen4.mon(0x51, 64, 8000), SyntheticGen4.mon(0x52, 95, 8000, item = 233), SyntheticGen4.mon(0x53, 64, 8000, item = 229))
        val src = MonRef.Pc(BoxSlot(2, 0))
        assertEquals(65, Gen4Trade.evolution(a.editor.monAt(src)!!, b)?.into)
        Gen4Trade.send(a, src, b, MonRef.Pc(BoxSlot(0, 5)))
        val got = (b.editor as Gen4SaveEditor).data.mon(BoxSlot(0, 5))
        assertEquals(65, got.species)
        assertEquals("MON65", got.nickname)   // not nicknamed: renamed to its new species
        assertTrue(caught(b, 64) && caught(b, 65))
        assertTrue(a.editor.monAt(src) == null)
        // ONIX holding its METAL COAT: evolves, the item used up.
        Gen4Trade.send(a, MonRef.Pc(BoxSlot(2, 1)), b, MonRef.Pc(BoxSlot(0, 6)))
        val steelix = (b.editor as Gen4SaveEditor).data.mon(BoxSlot(0, 6))
        assertEquals(208, steelix.species)
        assertEquals(0, steelix.heldItem)
        // An Everstone stops it.
        assertNull(Gen4Trade.evolution(a.editor.monAt(MonRef.Pc(BoxSlot(2, 2)))!!, b))
        for (side in listOf(a, b)) {
            val f = parse(side.editor.build())
            assertNull(Gen4SaveData(game, f.general, f.pc).mismatch())
        }
    }

    @Test
    fun partyTradesKeepTheGamesRules() {
        val (a, b) = sides()
        Gen4Trade.send(a, MonRef.Party(1), b, MonRef.Party(5))   // to the end of B's party
        assertEquals(1, a.editor.data.partyCount)
        assertEquals(3, b.editor.data.partyCount)
        assertEquals(25, b.editor.data.partyMons[2].mon.species)
        // A party Pokémon keeps its HP (full here, its max recalculated as CalculateMonStats does).
        b.editor.data.partyMons[2].let { assertEquals(it.stats[0], it.hp) }
        try { Gen4Trade.send(a, MonRef.Party(0), b, MonRef.Pc(BoxSlot(4, 0))); fail("allowed") } catch (_: TradeRefused) {}
        // ... even in a swap (trade.c counts the rest of the party, not what comes in).
        try { Gen4Trade.swap(a, MonRef.Party(0), b, MonRef.Pc(BoxSlot(0, 0))); fail("allowed") } catch (_: TradeRefused) {}
        Gen4Trade.swap(b, MonRef.Party(0), a, MonRef.Pc(BoxSlot(0, 0)))
        assertEquals(1, b.editor.data.partyMons[0].mon.species)
        assertEquals(154, (a.editor as Gen4SaveEditor).data.mon(BoxSlot(0, 0)).species)
    }

    @Test
    fun mailOnlyGoesPartyToParty() {
        val (a, b) = sides()
        val p1 = MonRef.Party(1)
        // Give PIKACHU a letter straight in the record (the editor never does), then trade it.
        val ea = a.editor as Gen4SaveEditor
        ea.putIncoming(p1, ea.monAt(p1)!!.withChanges(heldItem = 137), ea.partyRecord(1))
        try { Gen4Trade.send(a, p1, b, MonRef.Pc(BoxSlot(0, 9))); fail("allowed") } catch (_: TradeRefused) {}
        Gen4Trade.send(a, p1, b, MonRef.Party(5))
        assertEquals(137, b.editor.data.partyMons[2].mon.heldItem)
    }

    // ---- Platinum ----

    private val ptGame = Gen4GameData(
        species = List(494) { if (it == 0) "" else "MON$it" }, moveNames = List(468) { "MOVE$it" }, items = List(537) { "ITEM$it" },
        abilityNames = List(124) { "ABILITY$it" }, personal = game.let { g -> List(494) { s -> if (s == 0) null else ByteArray(0x2C).also { r ->
            for (i in 0 until 6) r[i] = (40 + s % 60 + i * 5).toByte()
            r[0x10] = if (s == 150) 255.toByte() else 127; r[0x16] = 65; r[0x17] = if (s == 150) 0 else 34; r[0x1C] = 1
        } } },
        levelUp = List(494) { listOf(1 to 33, 7 to 45, 20 to 22) }, preEvolution = IntArray(494), tmMoves = IntArray(100) { 264 + it },
        movePp = IntArray(468) { if (it == 0) 0 else 20 },
        // As Platinum's: no METAL COAT here, the rest as HG / SS's.
        itemPockets = SyntheticGen4.demoItemPockets.copyOf().also { it[233] = -1 },
        evolutions = List(494) { s -> if (s == 64) listOf(Triple(5, 0, 65)) else emptyList() },
        layout = Gen4Layout.PLATINUM,
    )

    @Test
    fun readsAPlatinumSave() {
        val s = parse(SyntheticGen4.savePlatinum())
        assertEquals(Gen4Layout.PLATINUM, s.layout)
        assertEquals(1, s.half)
        assertEquals(1, s.pcHalf)
        assertEquals(5L, s.counter)
        val d = Gen4SaveData(ptGame, s.general, s.pc, s.layout)
        assertEquals("RED", d.trainerName)
        assertEquals(12345, d.trainerId)
        assertEquals(3000L, d.money)
        assertEquals(listOf(154, 25), d.partyMons.map { it.mon.species })
        assertEquals(listOf(1, 4), d.boxMons(0).take(2).map { it.species })
        assertEquals(150, d.boxMons(1)[0].species)
        assertEquals("BOX 2", d.boxName(1))
        assertNull(d.mismatch())
        // HG / SS's layout over it reads nothing sensible: the editor tells the two apart by the save's own footers.
        assertTrue((Gen4SaveFile.parse(SyntheticGen4.save()) as Gen4SaveFile.Parsed.Ok).save.layout == Gen4Layout.HGSS)
    }

    @Test
    fun aPlatinumBlockIsPickedOnItsOwn() {
        val bytes = SyntheticGen4.savePlatinum()
        bytes[0x40000 + 0xCF2C + 0x100] = (bytes[0x40000 + 0xCF2C + 0x100] + 1).toByte()   // the backup's PC block no longer sums
        // The backup's general block (global 5) can't go with the primary's PC block (global 4): damaged, as the game says.
        assertTrue(Gen4SaveFile.parse(bytes) is Gen4SaveFile.Parsed.Failed)
    }

    @Test
    fun aPlatinumEditIsAFullSave() {
        val raw = SyntheticGen4.savePlatinum()
        val e = Gen4SaveEditor(parse(raw), ptGame)
        e.setIvs(MonRef.Party(0), IntArray(6) { 31 })
        e.move(MonRef.Pc(BoxSlot(0, 0)), MonRef.Party(5))
        e.setHeldItem(MonRef.Party(1), 234)
        e.renameBox(3, "Keep")
        val out = e.build()
        val back = parse(out)
        assertEquals(0, back.half)
        assertEquals(0, back.pcHalf)
        assertEquals(6L, back.counter)
        // The backup the game loaded is untouched.
        assertArrayEquals(raw.copyOfRange(0x40000, 0x80000), out.copyOfRange(0x40000, 0x80000))
        // Each block's own count went up by one.
        assertEquals(11L, SyntheticSave.rd32(out, 0xCF2C - 20 + 4))
        assertEquals(16L, SyntheticSave.rd32(out, 0xCF2C + 0x121E4 - 20 + 4))
        val d = Gen4SaveData(ptGame, back.general, back.pc, back.layout)
        assertNull(d.mismatch())
        assertEquals(listOf(154, 25, 1), d.partyMons.map { it.mon.species })
        assertEquals(31, d.partyMons[0].mon.ivs[5])
        assertEquals(234, d.partyMons[1].mon.heldItem)
        assertTrue(d.mon(BoxSlot(0, 0)).isEmpty)
        assertEquals("Keep", d.boxName(3))
        // Its bag (0x630, 100 TM slots): the LEFTOVERS given away.
        assertEquals(0, SyntheticSave.rd32(back.general, 0x630).toInt() and 0xFFFF)
        // A second edit goes back the other way.
        val again = Gen4SaveEditor(back, ptGame).apply { setFriendship(MonRef.Party(0), 200) }.build()
        assertEquals(1, parse(again).half)
        assertEquals(7L, parse(again).counter)
    }

    @Test
    fun heartGoldAndPlatinumTrade() {
        val hgSide = TradeSide("HG", Gen4SaveEditor(parse(SyntheticGen4.save()), game), "IPKE")
        val pt = TradeSide("PT", Gen4SaveEditor(parse(SyntheticGen4.savePlatinum()), ptGame), "CPUE")
        Gen4Trade.send(hgSide, MonRef.Pc(BoxSlot(0, 0)), pt, MonRef.Pc(BoxSlot(5, 0)))
        assertEquals(1, (pt.editor as Gen4SaveEditor).data.mon(BoxSlot(5, 0)).species)
        val ptCaught = 0x1328 + 0x04
        assertTrue(parse(pt.editor.build()).general.let { (it[ptCaught].toInt() and 1) == 1 })
        // A Pokémon holding an item Platinum doesn't have stays.
        hgSide.editor.setHeldItem(MonRef.Party(1), 234)
        val withCoat = SyntheticGen4.mon(0x60, 95, 8000, item = 233)
        assertNotNull(Gen4Trade.blocker(Gen4BoxMon.read(withCoat, 0, game), hgSide, pt))
        assertNull(Gen4Trade.blocker(Gen4BoxMon.read(withCoat, 0, game), pt, hgSide))
        Gen4Trade.swap(hgSide, MonRef.Party(1), pt, MonRef.Party(1), evolveA = false, evolveB = false)
        assertEquals(25, (pt.editor as Gen4SaveEditor).data.partyMons[1].mon.species)
        for ((side, g) in listOf(hgSide to game, pt to ptGame)) {
            val f = parse(side.editor.build())
            assertNull(Gen4SaveData(g, f.general, f.pc, f.layout).mismatch())
        }
    }

    // ---- The player's own save, when it's here ----

    private val nds = File(File(RETAIL_ROM_DIR).parentFile, "nds")

    private fun realSave(): File? = nds.walkTopDown().firstOrNull { it.name == "Pokemon - HeartGold Version (USA).sav" }

    @Test
    fun aRealTradeBetweenHeartGoldAndSoulSilver() {
        val hgRom = File(nds, "Pokemon - HeartGold Version (USA).nds")
        val ssRom = File(nds, "Pokemon - SoulSilver Version (USA).nds")
        val hgSav = realSave()
        val ssSav = nds.walkTopDown().firstOrNull { it.name == "Pokemon - SoulSilver Version (USA).sav" }
        assumeTrue(hgRom.isFile && ssRom.isFile && hgSav != null && ssSav != null)
        val hgGame = NdsArt.readSaveTables(hgRom.toOkioPath())!!
        val ssGame = NdsArt.readSaveTables(ssRom.toOkioPath())!!
        val hg = TradeSide("HG", Gen4SaveEditor(parse(hgSav!!.readBytes()), hgGame), "IPKE")
        val ss = TradeSide("SS", Gen4SaveEditor(parse(ssSav!!.readBytes()), ssGame), "IPGE")
        val box = (0 until 30).first { !(hg.editor.data.boxMons(0)[it]).isEmpty }
        val empty = (0 until ss.editor.data.boxCount).asSequence().flatMap { b -> (0 until 30).asSequence().map { BoxSlot(b, it) } }
            .first { (ss.editor as Gen4SaveEditor).data.mon(it).isEmpty }
        val species = hg.editor.monAt(MonRef.Pc(BoxSlot(0, box)))!!.species
        Gen4Trade.send(hg, MonRef.Pc(BoxSlot(0, box)), ss, MonRef.Pc(empty), evolve = false)
        Gen4Trade.swap(hg, MonRef.Party(1), ss, MonRef.Party(1), evolveA = false, evolveB = false)
        for ((side, g) in listOf(hg to hgGame, ss to ssGame)) {
            val f = parse(side.editor.build())
            assertNull(Gen4SaveData(g, f.general, f.pc).mismatch())
        }
        assertEquals(species, (ss.editor as Gen4SaveEditor).data.mon(empty).species)
        assertTrue(caught(ss, species))
    }

    @Test
    fun aRealRenegadePlatinumSave() {
        val rom = File(nds, "Pokemon Renegade Platinum (v1.3.0).nds")
        val sav = nds.walkTopDown().firstOrNull { it.name == "Pokemon Renegade Platinum (v1.3.0).sav" }
        assumeTrue(rom.isFile && sav != null)
        val g = NdsArt.readSaveTables(rom.toOkioPath())!!
        assertEquals(Gen4Layout.PLATINUM, g.layout)
        val s = parse(sav!!.readBytes())
        assertEquals(Gen4Layout.PLATINUM, s.layout)
        val d = Gen4SaveData(g, s.general, s.pc, s.layout)
        assertNull(d.mismatch())
        assertTrue(d.partyCount in 1..6)
        d.partyMons.forEach { p ->
            assertEquals(g.levelFor(p.mon.species, p.mon.exp), p.level)
            if (!p.mon.isEgg) assertEquals(g.stats(p.mon, p.level).toList(), p.stats.toList())
        }
        val same = Gen4SaveEditor(s, g)
        d.partyMons.forEachIndexed { i, p -> p.mon.nickname?.let { same.setNickname(MonRef.Party(i), it) } }
        (0 until d.boxCount).forEach { same.renameBox(it, d.boxName(it)) }
        assertTrue(!same.changed)
        val e = Gen4SaveEditor(s, g)
        e.setIvs(MonRef.Party(0), IntArray(6) { 31 })
        val out = parse(e.build())
        assertNotEquals(s.half, out.half)
        val after = Gen4SaveData(g, out.general, out.pc, out.layout)
        assertNull(after.mismatch())
        assertEquals(31, after.partyMons[0].mon.ivs[3])
    }

    @Test
    fun aRealHeartGoldSave() {
        val rom = File(nds, "Pokemon - HeartGold Version (USA).nds")
        val sav = realSave()
        assumeTrue(rom.isFile && sav != null)
        val tables = NdsArt.readSaveTables(rom.toOkioPath())
        assertNotNull(tables)
        val g = tables!!
        // Bulbasaur: 45 / 49 / 49 / 45 / 65 / 65, OVERGROW, one in eight female.
        assertEquals(listOf(45, 49, 49, 45, 65, 65), g.baseStats(1).toList())
        assertEquals(31, g.genderRatio(1))
        assertEquals(35, g.basePp(1))
        val s = parse(sav!!.readBytes())
        val d = Gen4SaveData(g, s.general, s.pc)
        assertNull(d.mismatch())
        assertTrue(d.partyCount in 1..6)
        d.partyMons.forEach { p ->
            assertTrue(p.mon.species in 1..493)
            assertEquals(g.levelFor(p.mon.species, p.mon.exp), p.level)
            // The party's stored stats are the formula's.
            if (!p.mon.isEgg) assertEquals(g.stats(p.mon, p.level).toList(), p.stats.toList())
        }
        // The text encoder writes the save's own nicknames byte for byte.
        val same = Gen4SaveEditor(s, g)
        d.partyMons.forEachIndexed { i, p -> p.mon.nickname?.let { same.setNickname(MonRef.Party(i), it) } }
        assertTrue(!same.changed)
        // An edit, saved and read back the way the game would.
        val e = Gen4SaveEditor(s, g)
        e.setIvs(MonRef.Party(0), IntArray(6) { 31 })
        e.reroll(MonRef.Party(0), nature = (d.partyMons[0].mon.nature + 1) % 25)
        val out = parse(e.build())
        assertNotEquals(s.half, out.half)
        val after = Gen4SaveData(g, out.general, out.pc)
        assertNull(after.mismatch())
        assertEquals(31, after.partyMons[0].mon.ivs[0])
        assertEquals(d.partyMons.map { it.mon.species }, after.partyMons.map { it.mon.species })
    }
}


