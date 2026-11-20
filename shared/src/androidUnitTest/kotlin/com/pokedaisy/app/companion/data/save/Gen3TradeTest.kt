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

class Gen3TradeTest {

    private val kadabra = 64
    private val alakazam = 65
    private val onix = 95
    private val steelix = 208
    private val metalCoat = 199
    private val treecko = 277
    private val bulbasaur = 1
    private val mew = 151

    /** A save of [format] with box 1 holding [mons], and the National Dex / FR-LG's Sevii flag as asked. */
    private fun side(
        format: Gen3SaveFormat, code: String, mons: List<ByteArray> = emptyList(),
        national: Boolean = true, sevii: Boolean = true, label: String = code,
    ): TradeSide {
        val (sb2, sb1, storage) = SyntheticSave.blocks(format)
        storage.fill(0, 4, 4 + 14 * 30 * 80)
        mons.forEachIndexed { i, m -> m.copyInto(storage, 4 + i * 80) }
        if (national) {
            sb2[format.nationalMagicOff] = format.nationalMagic.toByte()
            SyntheticSave.le16(sb1, format.varsOff + (format.nationalVar - 0x4000) * 2, format.nationalVarValue)
            setFlag(sb1, format, format.nationalFlag)
        }
        if (sevii && format.linkRsFlag >= 0) setFlag(sb1, format, format.linkRsFlag)
        val bytes = SyntheticSave.file(format, SyntheticSave.Slot(7, 2, sb2, sb1, storage), null)
        val file = (Gen3SaveFile.parse(bytes, format) as Gen3SaveFile.Parsed.Ok).save
        return TradeSide(label, Gen3SaveEditor(file), code)
    }

    private fun setFlag(sb1: ByteArray, f: Gen3SaveFormat, id: Int) {
        val o = f.flagsOff + id / 8
        sb1[o] = (sb1[o].toInt() or (1 shl (id % 8))).toByte()
    }

    private fun mon(species: Int, item: Int = 0, nickname: String = "PIKA", egg: Boolean = false, fateful: Boolean = false, pid: Long = 0x1234_5678L) =
        SyntheticSave.mon(pid, 0x0BAD_F00DL, species, exp = 2000, item = item, nickname = nickname, isEgg = egg, fateful = fateful)

    private val first = BoxSlot(0, 0)
    private val free = BoxSlot(3, 7)

    private fun refused(block: () -> Unit): String {
        try { block() } catch (e: TradeRefused) { return e.message.orEmpty() }
        fail("the trade went through"); return ""
    }

    @Test
    fun aSendMovesTheRecordAndFillsThePokedex() {
        val a = side(Gen3SaveFormat.EMERALD, "BPEE", listOf(mon(treecko)))
        val b = side(Gen3SaveFormat.EMERALD, "BPEE")
        val record = a.ed3.record(first)
        assertFalse(b.ed3.caught(treecko))
        Gen3Trade.send(a, first, b, free)
        assertTrue(a.ed3.data.mon(first).isEmpty)
        assertArrayEquals(record, b.ed3.record(free))
        assertTrue(b.ed3.caught(treecko))
        // Both still write and read back as the game would.
        for (s in listOf(a, b)) assertTrue(Gen3SaveFile.parse(s.editor.build(), s.format) is Gen3SaveFile.Parsed.Ok)
    }

    @Test
    fun beforeTheNationalDexOnlyTheRegionalDexTrades() {
        // An Emerald without the National Dex takes Hoenn's Pokémon only, and no eggs.
        val b = side(Gen3SaveFormat.EMERALD, "BPEE", national = false)
        assertNotNull(Gen3Trade.blocker(Gen3BoxMon.read(mon(bulbasaur), 0), side(Gen3SaveFormat.EMERALD, "BPEE"), b))
        assertNotNull(Gen3Trade.blocker(Gen3BoxMon.read(mon(treecko, egg = true), 0), side(Gen3SaveFormat.EMERALD, "BPEE"), b))
        assertNull(Gen3Trade.blocker(Gen3BoxMon.read(mon(treecko), 0), side(Gen3SaveFormat.EMERALD, "BPEE"), b))
        // A FireRed without it sends Kanto's only.
        val fr = side(Gen3SaveFormat.FIRERED_LEAFGREEN, "BPRE", national = false)
        val fr2 = side(Gen3SaveFormat.FIRERED_LEAFGREEN, "BPRE")
        assertNull(Gen3Trade.blocker(Gen3BoxMon.read(mon(bulbasaur), 0), fr, fr2))
        assertNotNull(Gen3Trade.blocker(Gen3BoxMon.read(mon(treecko), 0), fr, fr2))
        // Ruby / Sapphire's own code doesn't check.
        val ruby = side(Gen3SaveFormat.RUBY_SAPPHIRE, "AXVE", national = false)
        assertNull(Gen3Trade.blocker(Gen3BoxMon.read(mon(bulbasaur), 0), ruby, side(Gen3SaveFormat.RUBY_SAPPHIRE, "AXPE")))
    }

    @Test
    fun fireRedLinksWithHoennOnlyAfterTheSeviiIslands() {
        val e = side(Gen3SaveFormat.EMERALD, "BPEE")
        val early = side(Gen3SaveFormat.FIRERED_LEAFGREEN, "BPRE", sevii = false)
        assertNotNull(Gen3Trade.linkBlocker(early, e))
        assertNull(Gen3Trade.linkBlocker(side(Gen3SaveFormat.FIRERED_LEAFGREEN, "BPRE"), e))
        // FireRed with LeafGreen needs nothing.
        assertNull(Gen3Trade.linkBlocker(early, side(Gen3SaveFormat.FIRERED_LEAFGREEN, "BPGE", sevii = false)))
    }

    @Test
    fun mewTradesOnlyFromAnEvent() {
        val a = side(Gen3SaveFormat.EMERALD, "BPEE", listOf(mon(mew), mon(mew, fateful = true)))
        val b = side(Gen3SaveFormat.EMERALD, "BPEE")
        refused { Gen3Trade.send(a, first, b, free) }
        Gen3Trade.send(a, BoxSlot(0, 1), b, free)
        assertEquals(mew, b.ed3.data.mon(free).species)
    }

    @Test
    fun mailStaysWithItsSave() {
        val a = side(Gen3SaveFormat.EMERALD, "BPEE", listOf(mon(treecko, item = 121)))
        refused { Gen3Trade.send(a, first, side(Gen3SaveFormat.EMERALD, "BPEE"), free) }
        assertFalse(a.editor.changed)
    }

    @Test
    fun tradeEvolutionsHappenOnArrival() {
        val a = side(Gen3SaveFormat.FIRERED_LEAFGREEN, "BPRE", listOf(
            mon(kadabra, nickname = "KADABRA"), mon(kadabra, nickname = "SPOON"), mon(onix, item = metalCoat, nickname = "ONIX"),
            mon(onix), mon(kadabra, item = 195),
        ))
        val b = side(Gen3SaveFormat.FIRERED_LEAFGREEN, "BPRE")
        for (i in 0 until 5) Gen3Trade.send(a, BoxSlot(0, i), b, BoxSlot(1, i))
        val got = (0 until 5).map { b.ed3.data.mon(BoxSlot(1, it)) }
        // KADABRA -> ALAKAZAM, its name with it (it was the species' own); a nickname stays.
        assertEquals(alakazam, got[0].species)
        assertEquals("ALAKAZAM", Gen3Text.decode(got[0].nicknameBytes, 0, 10))
        assertEquals(alakazam, got[1].species)
        assertEquals("SPOON", Gen3Text.decode(got[1].nicknameBytes, 0, 10))
        // ONIX holding a METAL COAT -> STEELIX, the coat used up; without it, no change.
        assertEquals(steelix, got[2].species)
        assertEquals(0, got[2].heldItem)
        assertEquals(onix, got[3].species)
        // An EVERSTONE stops it.
        assertEquals(kadabra, got[4].species)
        assertTrue(b.ed3.caught(alakazam) && b.ed3.caught(kadabra) && b.ed3.caught(steelix))
        // Every changed record still reads (checksum, encryption).
        assertTrue(got.all { it.checksumOk })
    }

    @Test
    fun evolvingCanBeStoppedAndOtherLanguagesKeepTheirs() {
        val a = side(Gen3SaveFormat.EMERALD, "BPEE", listOf(mon(kadabra), mon(kadabra)))
        val english = side(Gen3SaveFormat.EMERALD, "BPEE")
        Gen3Trade.send(a, first, english, free, evolve = false)
        assertEquals(kadabra, english.ed3.data.mon(free).species)
        // A French Emerald would rename it to its French name, which this doesn't know: it arrives as it was.
        val french = side(Gen3SaveFormat.EMERALD, "BPEF")
        assertNull(Gen3Trade.evolution(a.ed3.data.mon(BoxSlot(0, 1)), french))
    }

    @Test
    fun rubyGetsItsNationalDexFromATradeWithEmerald() {
        val ruby = side(Gen3SaveFormat.RUBY_SAPPHIRE, "AXVE", listOf(mon(treecko)), national = false)
        val sapphire = side(Gen3SaveFormat.RUBY_SAPPHIRE, "AXPE", national = false)
        Gen3Trade.send(ruby, first, sapphire, free)
        assertFalse(ruby.ed3.nationalDex)
        assertFalse(sapphire.ed3.nationalDex)
        Gen3Trade.send(sapphire, free, side(Gen3SaveFormat.EMERALD, "BPEE"), free)
        assertTrue(sapphire.ed3.nationalDex)
    }

    @Test
    fun aSwapChecksBothWaysBeforeChangingAnything() {
        val a = side(Gen3SaveFormat.EMERALD, "BPEE", listOf(mon(treecko)))
        val b = side(Gen3SaveFormat.EMERALD, "BPEE", listOf(mon(mew)))
        refused { Gen3Trade.swap(a, first, b, first) }
        assertFalse(a.editor.changed || b.editor.changed)
        val c = side(Gen3SaveFormat.EMERALD, "BPEE", listOf(mon(bulbasaur, pid = 0x77L)))
        val recA = a.ed3.record(first)
        val recC = c.ed3.record(first)
        Gen3Trade.swap(a, first, c, first)
        assertArrayEquals(recA, c.ed3.record(first))
        assertArrayEquals(recC, a.ed3.record(first))
        assertTrue(a.ed3.caught(bulbasaur) && c.ed3.caught(treecko))
    }

    @Test
    fun unownsLookIsKeptForTheDexOnTheFirstOne() {
        val unown = 201
        val a = side(Gen3SaveFormat.EMERALD, "BPEE", listOf(mon(unown, pid = 0xCAFE_0001L), mon(unown, pid = 0xCAFE_0002L)))
        val b = side(Gen3SaveFormat.EMERALD, "BPEE")
        Gen3Trade.send(a, first, b, free)
        Gen3Trade.send(a, BoxSlot(0, 1), b, BoxSlot(3, 8))
        val sb2 = Gen3SaveFile.parse(b.editor.build(), b.format).let { (it as Gen3SaveFile.Parsed.Ok).save.saveBlock2 }
        assertEquals(0xCAFE_0001L, SyntheticSave.rd32(sb2, 0x18 + 0x04))
    }

    @Test
    fun theGeneratedTablesAgreeWithTheNames() {
        // Spot checks against the shared name tables (the generator asserts the rest).
        assertEquals("Kadabra", com.pokedaisy.app.companion.data.speciesNames[kadabra])
        assertEquals("Steelix", com.pokedaisy.app.companion.data.speciesNames[steelix])
        assertEquals("Metal Coat", com.pokedaisy.app.companion.data.itemNames[metalCoat])
        assertEquals("Everstone", com.pokedaisy.app.companion.data.itemNames[ITEM_EVERSTONE])
        assertEquals(252, speciesNationalDex[treecko])
        assertEquals(12, tradeEvolutions.size)
    }
}
