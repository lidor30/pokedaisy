package com.pokedaisy.app.companion.data.save

import com.pokedaisy.app.companion.data.Gen3Text
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** TRADE AND BACK: a trade-evolving Pokémon sent to another save and straight back, evolved, by the trades' own rules. */
class TradeBackTest {
    private val kadabra = 64
    private val alakazam = 65
    private val onix = 95
    private val steelix = 208
    private val metalCoat = 199
    private val everstone = 195
    private val treecko = 277
    private val torchic = 280

    private fun side(format: Gen3SaveFormat, code: String, mons: List<ByteArray> = emptyList(), national: Boolean = true, fill: Boolean = false): TradeSide {
        val (sb2, sb1, storage) = SyntheticSave.blocks(format)
        storage.fill(0, 4, 4 + 14 * 30 * 80)
        mons.forEachIndexed { i, m -> m.copyInto(storage, 4 + i * 80) }
        // A full PC: every slot a Treecko.
        if (fill) for (i in mons.size until 14 * 30) mon(treecko, pid = 0x1000L + i).copyInto(storage, 4 + i * 80)
        if (national) SyntheticSave.progress(format, sb2, sb1)
        else if (format.linkRsFlag >= 0) {
            val o = format.flagsOff + format.linkRsFlag / 8
            sb1[o] = (sb1[o].toInt() or (1 shl (format.linkRsFlag % 8))).toByte()
        }
        val bytes = SyntheticSave.file(format, SyntheticSave.Slot(7, 2, sb2, sb1, storage), null)
        return TradeSide(code, Gen3SaveEditor((Gen3SaveFile.parse(bytes, format) as Gen3SaveFile.Parsed.Ok).save), code)
    }

    private fun mon(species: Int, item: Int = 0, nickname: String? = null, pid: Long = 0x1234_5678L) =
        SyntheticSave.mon(pid, 0x0BAD_F00DL, species, exp = 2000, item = item,
            nickname = nickname ?: com.pokedaisy.app.companion.data.speciesNames[species]!!.uppercase())

    private val first = BoxSlot(0, 0)

    private fun refused(block: () -> Unit): String {
        try { block() } catch (e: TradeRefused) { return e.message.orEmpty() }
        fail("it went through"); return ""
    }

    @Test
    fun itComesHomeEvolvedWhereItWas() {
        val home = side(Gen3SaveFormat.EMERALD, "BPEE", listOf(mon(kadabra), mon(onix, item = metalCoat)))
        val partner = side(Gen3SaveFormat.FIRERED_LEAFGREEN, "BPRE")
        val before = home.ed3.data.mon(first)
        assertNull(TradeBack.blocker(home, MonRef.Pc(first), partner))
        assertEquals(alakazam, TradeBack.run(home, MonRef.Pc(first), partner).into)
        val after = home.ed3.data.mon(first)
        assertEquals(alakazam, after.species)
        assertEquals(before.personality, after.personality)
        assertEquals(before.otId, after.otId)
        assertEquals(before.exp, after.exp)
        assertEquals("ALAKAZAM", Gen3Text.decode(after.nicknameBytes, 0, 10))   // it was the species' own name
        assertTrue(after.checksumOk)
        // The partner kept nothing and registered both; home registered the new one.
        assertTrue((0 until 14).all { b -> partner.ed3.data.boxMons(b).all { it.isEmpty } })
        assertTrue(partner.ed3.caught(kadabra) && partner.ed3.caught(alakazam))
        assertTrue(home.ed3.caught(alakazam))
        // The item it needed is used up.
        TradeBack.run(home, MonRef.Pc(BoxSlot(0, 1)), partner)
        assertEquals(steelix, home.ed3.data.mon(BoxSlot(0, 1)).species)
        assertEquals(0, home.ed3.data.mon(BoxSlot(0, 1)).heldItem)
        for (s in listOf(home, partner)) assertNull(Gen3SaveData((Gen3SaveFile.parse(s.editor.build(), s.format) as Gen3SaveFile.Parsed.Ok).save).mismatch())
    }

    @Test
    fun aPartyPokemonKeepsItsPlace() {
        val home = side(Gen3SaveFormat.EMERALD, "BPEE", listOf(mon(kadabra), mon(treecko), mon(torchic)))
        for (i in 0 until 3) home.editor.move(MonRef.Pc(BoxSlot(0, i)), MonRef.Party(i))
        assertEquals(listOf(kadabra, treecko, torchic), home.ed3.data.party.map { it.mon.species })
        TradeBack.run(home, MonRef.Party(0), side(Gen3SaveFormat.EMERALD, "BPEE"))
        assertEquals(listOf(alakazam, treecko, torchic), home.ed3.data.party.map { it.mon.species })
    }

    @Test
    fun nothingHappensWhenEitherWayIsRefused() {
        // Nothing to evolve, an Everstone, a full partner PC: refused before anything changes.
        val home = side(Gen3SaveFormat.EMERALD, "BPEE", listOf(mon(treecko), mon(kadabra, item = everstone), mon(kadabra)))
        val partner = side(Gen3SaveFormat.EMERALD, "BPEE")
        assertNull(TradeBack.evolution(home, MonRef.Pc(first), partner))
        assertNull(TradeBack.evolution(home, MonRef.Pc(BoxSlot(0, 1)), partner))
        assertNotNull(TradeBack.blocker(home, MonRef.Pc(BoxSlot(0, 2)), side(Gen3SaveFormat.EMERALD, "BPEE", fill = true)))
        // A French partner would rename it in French: it wouldn't evolve on arrival.
        assertNull(TradeBack.evolution(home, MonRef.Pc(BoxSlot(0, 2)), side(Gen3SaveFormat.EMERALD, "BPEF")))
        // The way back refused: a FireRed with no National Dex sends its Onix away, but can't take a Steelix home.
        val fr = side(Gen3SaveFormat.FIRERED_LEAFGREEN, "BPRE", listOf(mon(onix, item = metalCoat)), national = false)
        val frPartner = side(Gen3SaveFormat.FIRERED_LEAFGREEN, "BPRE")
        val homeBytes = fr.editor.build()
        val partnerBytes = frPartner.editor.build()
        refused { TradeBack.run(fr, MonRef.Pc(first), frPartner) }
        assertArrayEquals(homeBytes, fr.editor.build())
        assertArrayEquals(partnerBytes, frPartner.editor.build())
        assertFalse(fr.editor.changed)
        assertFalse(frPartner.editor.changed)
    }

    @Test
    fun missingOffersBothKindsOfTradeEvolution() {
        // Emerald lacks ALAKAZAM and MACHAMP: its own KADABRA can go to FireRed and back; FireRed's MACHOKE evolves as it arrives.
        val machoke = 67
        val machamp = 68
        val home = side(Gen3SaveFormat.EMERALD, "BPEE", listOf(mon(kadabra)))
        val fr = side(Gen3SaveFormat.FIRERED_LEAFGREEN, "BPRE", listOf(mon(machoke)))
        val entries = MissingDex.find(home, listOf(fr)).associateBy { it.species }
        val alakazamWay = entries.getValue(alakazam).best!!
        assertEquals(MissingDex.Way.TRADE_BACK, alakazamWay.way)
        assertTrue(alakazamWay.reachable)
        assertEquals(MonRef.Pc(first), alakazamWay.at)
        val machampWay = entries.getValue(machamp).best!!
        assertEquals(MissingDex.Way.EVOLVES, machampWay.way)
        assertEquals(machoke, Gen3Species.nationalDex(machampWay.mon.species))
        // FireRed's MACHOKE fills its own gap by a plain trade, ahead of the evolving ones.
        assertEquals(MissingDex.Way.TRADE, entries.getValue(machoke).best!!.way)
    }
}
