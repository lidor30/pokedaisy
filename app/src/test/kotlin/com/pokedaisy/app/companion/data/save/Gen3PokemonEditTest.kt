package com.pokedaisy.app.companion.data.save

import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * The save editor's Pokémon page, its party moves, box tools, Pokédex and event items: each change written as the game
 * stores it, read back from the rebuilt file, and only what the game could have done.
 */
class Gen3PokemonEditTest {

    private fun editor(format: Gen3SaveFormat, fill: (ByteArray, ByteArray, ByteArray) -> Unit = { _, _, _ -> }): Gen3SaveEditor {
        val (sb2, sb1, storage) = SyntheticSave.blocks(format)
        fill(sb2, sb1, storage)
        val bytes = SyntheticSave.file(format, SyntheticSave.Slot(3, 1, sb2, sb1, storage), null)
        return Gen3SaveEditor((Gen3SaveFile.parse(bytes, format) as Gen3SaveFile.Parsed.Ok).save)
    }

    private fun reread(e: Gen3SaveEditor) = Gen3SaveData((Gen3SaveFile.parse(e.build(), e.file.format) as Gen3SaveFile.Parsed.Ok).save)

    private fun refused(block: () -> Unit) {
        try { block() } catch (e: IllegalArgumentException) { return } catch (e: SaveEditRefused) { return }
        fail("allowed")
    }

    private val box0 = MonRef.Pc(BoxSlot(0, 0))   // Bulbasaur (two genders, one ability)
    private val party0 = MonRef.Party(0)          // Charizard, level 50

    @Test
    fun statsFollowTheGamesFormula() {
        // Charizard (78 / 84 / 78 / 100 / 109 / 85), level 50, IVs 31, no EVs, Adamant (+Atk -SpA).
        val s = Gen3StatCalc.stats(6, 50, IntArray(6) { 31 }, IntArray(6), nature = 3)
        assertEquals(listOf(153, 114, 98, 120, 116, 105), s.toList())
        // Shedinja always has 1 HP.
        assertEquals(1, Gen3StatCalc.stats(Gen3Species.SHEDINJA, 100, IntArray(6) { 31 }, IntArray(6), 0)[0])
    }

    @Test
    fun aNewNatureKeepsEverythingElseThePersonalityDecides() {
        val e = editor(Gen3SaveFormat.EMERALD)
        val before = e.monAt(box0)!!
        val nature = (before.nature + 7) % 25
        e.reroll(box0, nature = nature)
        val after = reread(e).mon(BoxSlot(0, 0))
        assertTrue(after.checksumOk)
        assertEquals(nature, after.nature)
        assertNotEquals(before.personality, after.personality)
        assertEquals(before.isShiny, after.isShiny)
        assertEquals(before.gender, after.gender)
        assertEquals(before.ivs.toList(), after.ivs.toList())
        assertEquals(before.exp, after.exp)
        assertEquals(before.moves.toList(), after.moves.toList())
        assertEquals(before.nicknameBytes.toList(), after.nicknameBytes.toList())
    }

    @Test
    fun shinyGenderAndAbilityComeFromThePersonality() {
        val e = editor(Gen3SaveFormat.FIRERED_LEAFGREEN)
        val nature = e.monAt(box0)!!.nature
        e.reroll(box0, shiny = true)
        var m = e.monAt(box0)!!
        assertTrue(m.isShiny)
        assertEquals(nature, m.nature)
        val other = if (m.gender == Gen3Gender.MALE) Gen3Gender.FEMALE else Gen3Gender.MALE
        e.reroll(box0, gender = other)
        m = e.monAt(box0)!!
        assertEquals(other, m.gender)
        assertTrue(m.isShiny)
        // Mewtwo (box 2) is genderless: no other gender to give it.
        refused { e.reroll(MonRef.Pc(BoxSlot(1, 0)), gender = Gen3Gender.MALE) }
        // A two-ability species: the ability bit follows bit 0 of the personality.
        val p = Gen3Personality.find(SyntheticMon.of(species = 54), abilityNum = 1, random = Random(4))!!
        assertEquals(1L, p and 1L)
    }

    @Test
    fun unownKeepsItsLetter() {
        val unown = SyntheticMon.of(species = Gen3Species.UNOWN, personality = 0x0302_0103L)
        val p = Gen3Personality.find(unown, nature = 4, random = Random(1))!!
        assertEquals(Gen3Personality.unownLetter(unown.personality), Gen3Personality.unownLetter(p))
        assertEquals(4L, p % 25)
    }

    @Test
    fun ivsEvsLevelAndFriendshipStayInTheGamesLimits() {
        val e = editor(Gen3SaveFormat.EMERALD)
        e.setIvs(party0, IntArray(6) { 31 })
        e.setEvs(party0, intArrayOf(252, 252, 6, 0, 0, 0))
        refused { e.setEvs(party0, intArrayOf(255, 255, 1, 0, 0, 0)) }
        refused { e.setIvs(party0, intArrayOf(32, 0, 0, 0, 0, 0)) }
        e.setLevel(party0, 60)
        refused { e.setLevel(party0, 49) }
        e.setFriendship(party0, 255)
        val p = reread(e).party[0]
        assertEquals(60, p.level)
        assertEquals(60, p.mon.level)
        assertEquals(IntArray(6) { 31 }.toList(), p.mon.ivs.toList())
        assertEquals(255, p.mon.friendship)
        // The party record's stats follow (CalculateMonStats), HP raised by as much as its max.
        val stats = Gen3StatCalc.stats(p.mon, 60)
        assertEquals(stats.toList(), p.stats.toList())
        assertEquals(150 + stats[0] - 150, p.hp)
    }

    @Test
    fun onlyItsOwnTrainerRenamesIt() {
        val e = editor(Gen3SaveFormat.EMERALD) { sb2, sb1, storage ->
            // Box 1 slot 4: one of RED's own (TID 12345 / SID 54321).
            SyntheticSave.mon(0x777L, (54321L shl 16) or 12345L, 25, 1000, ot = "RED").copyInto(storage, 4 + 3 * 80)
        }
        val own = MonRef.Pc(BoxSlot(0, 3))
        e.setNickname(own, "Sparky")
        assertEquals("Sparky", com.pokedaisy.app.companion.data.Gen3Text.decode(reread(e).mon(BoxSlot(0, 3)).nicknameBytes, 0, 10))
        refused { e.setNickname(box0, "Leafy") }          // ASH's
        refused { e.setNickname(own, "Way too long a name") }
    }

    @Test
    fun theParty() {
        val e = editor(Gen3SaveFormat.FIRERED_LEAFGREEN)
        // WITHDRAW: box 1's first Pokémon to the end of the party, at full HP.
        e.move(box0, MonRef.Party(2))
        var d = reread(e)
        assertEquals(3, d.partyCount)
        assertTrue(d.mon(BoxSlot(0, 0)).isEmpty)
        assertEquals(1, d.party[2].mon.species)
        assertEquals(d.party[2].stats[0], d.party[2].hp)
        // DEPOSIT: Pikachu into an empty slot; the party closes up.
        e.move(MonRef.Party(1), MonRef.Pc(BoxSlot(0, 10)))
        d = reread(e)
        assertEquals(2, d.partyCount)
        assertEquals(25, d.mon(BoxSlot(0, 10)).species)
        assertEquals(1, d.party[1].mon.species)
        assertTrue(d.mismatch() == null)
        // Party reorder.
        e.move(MonRef.Party(1), MonRef.Party(0))
        assertEquals(1, reread(e).party[0].mon.species)
        // The last one able to battle stays.
        e.move(MonRef.Party(1), MonRef.Pc(BoxSlot(0, 11)))
        refused { e.move(MonRef.Party(0), MonRef.Pc(BoxSlot(0, 12))) }
    }

    @Test
    fun aFullPartyTakesNoMore() {
        val e = editor(Gen3SaveFormat.EMERALD)
        repeat(4) { e.move(MonRef.Pc(BoxSlot(if (it < 3) 0 else 1, if (it < 3) it else 0)), MonRef.Party(5)) }
        assertEquals(6, reread(e).partyCount)
        e.move(party0, MonRef.Pc(BoxSlot(2, 0)))   // room again
        refused { e.move(MonRef.Pc(BoxSlot(5, 5)), MonRef.Party(5)) }   // nothing there
    }

    @Test
    fun tradesFromAndIntoTheParty() {
        val a = TradeSide("A", editor(Gen3SaveFormat.EMERALD) { sb2, sb1, _ -> SyntheticSave.progress(Gen3SaveFormat.EMERALD, sb2, sb1) }, "BPEE")
        val b = TradeSide("B", editor(Gen3SaveFormat.EMERALD) { sb2, sb1, _ -> SyntheticSave.progress(Gen3SaveFormat.EMERALD, sb2, sb1) }, "BPEE")
        // Charizard from A's party to B's party (its end), keeping its HP.
        Gen3Trade.send(a, MonRef.Party(0), b, MonRef.Party(2))
        assertEquals(1, a.ed3.data.partyCount)
        assertEquals(3, b.ed3.data.partyCount)
        assertEquals(6, b.ed3.data.party[2].mon.species)
        // A's last Pokémon that isn't an egg stays.
        try { Gen3Trade.send(a, MonRef.Party(0), b, MonRef.Pc(BoxSlot(3, 0))); fail("allowed") } catch (_: TradeRefused) {}
        // A swap of a party Pokémon and a PC one.
        Gen3Trade.swap(b, MonRef.Party(0), a, box0)
        assertEquals(1, b.ed3.data.party[0].mon.species)
        assertEquals(6, a.ed3.data.mon(BoxSlot(0, 0)).species)
        assertTrue(Gen3SaveData((Gen3SaveFile.parse(b.editor.build(), Gen3SaveFormat.EMERALD) as Gen3SaveFile.Parsed.Ok).save).mismatch() == null)
    }

    @Test
    fun boxTools() {
        val e = editor(Gen3SaveFormat.EMERALD)
        e.renameBox(0, "Starters")
        assertEquals("Starters", reread(e).boxName(0).trim())
        refused { e.renameBox(0, "Far too long") }
        // Box 1: species 1, 4, 7 by dex; sorted by level (their EXP: 500, 501, 502) they reverse.
        e.sortBox(0, BoxSort.LEVEL) { "" }
        e.sortBox(0, BoxSort.DEX) { "" }
        val d = reread(e)
        assertEquals(listOf(1, 4, 7), (0 until 3).map { d.mon(BoxSlot(0, it)).species })
        e.release(BoxSlot(0, 1))
        assertTrue(reread(e).mon(BoxSlot(0, 1)).isEmpty)
    }

    @Test
    fun thePokedex() {
        val e = editor(Gen3SaveFormat.FIRERED_LEAFGREEN)
        assertEquals(0, e.data.dexCounts.caught)
        e.registerOwned()
        assertEquals(6, reread(e).dexCounts.caught)   // the party's two and the PC's four
        e.markDex(e.allSpecies(regionalOnly = true), caught = false)
        val c = reread(e).dexCounts
        assertEquals(151, c.regionalSeen)
        assertEquals(151, c.regionalSize)
        assertTrue(e.caught(6) && !e.caught(10))
    }

    @Test
    fun eventItemsAsTheirDistributionsGaveThem() {
        val e = editor(Gen3SaveFormat.EMERALD)
        assertEquals(listOf(Gen3Event.EON_TICKET, Gen3Event.AURORA_TICKET, Gen3Event.MYSTIC_TICKET, Gen3Event.OLD_SEA_MAP), Gen3Event.forFormat(Gen3SaveFormat.EMERALD))
        assertEquals(Gen3Event.State.AVAILABLE, e.eventState(Gen3Event.AURORA_TICKET))
        e.giveEvent(Gen3Event.AURORA_TICKET)
        val d = reread(e)
        assertTrue(d.hasKeyItem(371))
        assertTrue(d.flag(0x8D5) && d.flag(0x13A))   // FLAG_ENABLE_SHIP_BIRTH_ISLAND, FLAG_RECEIVED_AURORA_TICKET
        assertEquals(Gen3Event.State.HAS, e.eventState(Gen3Event.AURORA_TICKET))
        refused { e.giveEvent(Gen3Event.AURORA_TICKET) }
        // FireRed has no Old Sea Map; Ruby / Sapphire only the Eon Ticket.
        assertFalse(Gen3SaveFormat.FIRERED_LEAFGREEN in Gen3Event.OLD_SEA_MAP.formats)
        val r = editor(Gen3SaveFormat.RUBY_SAPPHIRE)
        r.giveEvent(Gen3Event.EON_TICKET)
        assertTrue(reread(r).flag(0x853) && reread(r).hasKeyItem(275))
    }

    /** A whole Pokémon left past the party's count (a tool that shrank the party without clearing it) is still this
     * layout's data: the save opens (a FireRed save was refused for its party slot 5, Discord 2026-11-14). */
    @Test
    fun aWholePokemonPastThePartyStillOpens() {
        val e = editor(Gen3SaveFormat.FIRERED_LEAFGREEN) { _, sb1, _ ->
            val f = Gen3SaveFormat.FIRERED_LEAFGREEN
            sb1[f.partyCountOff] = 1   // Pikachu (slot 2) stays in the bytes past the count
        }
        assertNull(e.data.mismatch())
        assertEquals(1, e.data.party.size)
    }

    @Test
    fun aMonMovedThroughTheEditorStillDecodes() {
        val e = editor(Gen3SaveFormat.EMERALD)
        e.reroll(party0, nature = 10, shiny = true)
        e.move(party0, MonRef.Pc(BoxSlot(4, 4)))
        val m = reread(e).mon(BoxSlot(4, 4))
        assertTrue(m.checksumOk && m.isShiny && m.nature == 10)
        assertNull(reread(e).mismatch())
    }
}

/** A Gen3BoxMon built for the personality tests. */
object SyntheticMon {
    fun of(species: Int, personality: Long = 0x0102_0304L, otId: Long = 0x0BAD_F00DL): Gen3BoxMon =
        Gen3BoxMon.read(SyntheticSave.mon(personality, otId, species, 1000), 0)
}
