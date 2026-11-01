package com.pokedaisy.app.companion.data

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Retail FireRed / Emerald run through Universal Pokémon Randomizer ZX (RandomizedRetail, docs/DEVELOPMENT.md's
 * "Randomized ROMs"). The rule is checked on the retail ROMs themselves (a byte changed where the randomizer writes
 * still matches, anywhere else doesn't), the ROM-read tables against the app's own on retail, and the real randomized
 * ROMs (`Pokemon FireRed Randomized (v4.6.1) (Rev 1, seed ...).gba` & co. in the ROM folder) with their fixtures. Every
 * part skips quietly where its ROM isn't on this machine.
 */
class RandomizedRetailTest {
    @After fun back() {
        randomizedRetail = null
        activeGame = GameKind.FIRERED
    }

    private class Retail(val name: String, val code: String, val rev: Int)

    private val retail = listOf(
        Retail("Pokemon - FireRed Version (USA).gba", "BPRE", 0),
        Retail("Pokemon - FireRed Version (USA, Europe) (Rev 1).gba", "BPRE", 1),
        Retail("Pokemon - Emerald Version (USA, Europe).gba", "BPEE", 0),
    )

    private fun bytes(name: String): ByteArray? = File(RETAIL_ROM_DIR + name).takeIf { it.isFile }?.readBytes()

    private fun match(b: ByteArray): RandomizedRetailRom? {
        val code = String(b, 0xAC, 4, Charsets.US_ASCII)
        val rev = b[0xBC].toInt() and 0xFF
        val fromFile = RandomizedRetail.matchStream(code, rev, b.size.toLong()) { b.inputStream() }
        // The game's bus gives the same answer as the file.
        val fromBus = RandomizedRetail.match(code, rev, b.size.toLong()) { off, len ->
            if (off + len > b.size) null else b.copyOfRange(off.toInt(), off.toInt() + len)
        }
        assertSame(fromFile, fromBus)
        return fromFile
    }

    private fun u32(b: ByteArray, o: Int) = (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8) or
        ((b[o + 2].toInt() and 0xFF) shl 16) or ((b[o + 3].toInt() and 0xFF) shl 24)

    /** The randomized ROMs in the ROM folder, by their file names. */
    private fun randomized(prefix: String): List<File> =
        File(RETAIL_ROM_DIR).listFiles { f -> f.name.startsWith(prefix) && f.name.endsWith(".gba") }.orEmpty().sortedBy { it.name }

    @Test
    fun `retail changed where the randomizer writes matches, anywhere else doesn't`() {
        var ran = 0
        for (r in retail) {
            val b = bytes(r.name) ?: continue
            ran++
            val rom = RANDOMIZED_RETAIL.single { it.code == r.code && it.rev == r.rev }
            // Retail itself hashes the same once its sites are zeroed (the Poller and the library take it as retail first).
            assertSame(r.name, rom, match(b))

            // Inside the mask: BULBASAUR's types, every trainer's party size, the type chart's first entry.
            val inside = b.copyOf()
            val stats = u32(b, 0x1BC) - 0x08000000
            for (o in listOf(stats + 28 + 6, stats + 28 + 7, rom.typeChart.toInt() - 0x08000000 + 2)) inside[o] = (inside[o] + 1).toByte()
            for (r0 in rom.ranges.take(50)) inside[r0.first] = (inside[r0.first].toInt() xor 0x5A).toByte()
            assertSame(r.name, rom, match(inside))

            // The species names in lower case (UPR's "lower-case names" tweak): folded back.
            val lower = b.copyOf()
            val names = rom.foldRanges.single()
            for (i in names) if ((lower[i].toInt() and 0xFF) in 0xBB..0xD4) lower[i] = ((lower[i].toInt() and 0xFF) + 0x1A).toByte()
            assertSame(r.name, rom, match(lower))

            // Outside: a byte of code, a species name spelled differently, a move name: not randomized retail.
            for (o in listOf(0x1000, names.first + 1, u32(b, 0x148) - 0x08000000 + 13 + 2)) {
                val outside = b.copyOf()
                outside[o] = (outside[o].toInt() xor 1).toByte()
                assertNull("${r.name} +0x${o.toString(16)}", match(outside))
            }
            // Too short: cut into its data.
            assertNull(match(b.copyOf(rom.end - 1)))
        }
        assumeTrue("no retail FireRed / Emerald here", ran > 0)
    }

    @Test
    fun `the tables read from retail are the app's own`() {
        var ran = 0
        for (r in retail) {
            val b = bytes(r.name) ?: continue
            ran++
            val rom = RANDOMIZED_RETAIL.single { it.code == r.code && it.rev == r.rev }
            val read = RandomizedRetail.read(RomFileReader(b), rom)
            for (s in 1..411) speciesTypeData[s]?.let { assertEquals("${r.name} species $s", it, read.types[s]) }
            for (m in 1..354) {
                assertEquals("${r.name} move $m", moveData[m], read.moves[m])
                // The bundled stats are FireRed's: Emerald's NATURE POWER (267) has accuracy 95 there (gen_move_stats.py).
                val bundled = vanillaMoveStats[m].let { if (r.code == "BPEE" && m == 267) it.copy(accuracy = 95) else it }
                assertEquals("${r.name} move $m", bundled, read.moveStats!![m])
            }
            assertEquals(r.name, typeEffectiveness, read.typeChart)
            assertTrue(r.name, (1..411).all { gen3GrowthRates[it] == read.growthRates!![it] })
            // The guide's sites hold the generated area data's items, trades and gifts on retail.
            val g = read.randomizedGuide!!
            val areas = allAreaThingsOf(if (r.code == "BPEE") GuideId.EMERALD else GuideId.FIRERED)
            for (t in areas.values.flatten()) when (t.kind) {
                AreaKind.ITEM, AreaKind.HIDDEN -> assertEquals("${r.name} flag ${t.flag}", t.id, g.items[t.flag])
                AreaKind.TRADE -> assertEquals(r.name, t.id to t.wants, g.trades["${t.id}/${t.wants}"])
                AreaKind.MON, AreaKind.EGG -> g.statics[t.id]?.let { assertEquals(r.name, t.id, it) }
                else -> Unit
            }
        }
        assumeTrue("no retail FireRed / Emerald here", ran > 0)
    }

    /** The generated area data as retail has it (no randomized game running). */
    private fun allAreaThingsOf(id: GuideId): Map<Int, List<AreaThing>> {
        randomizedRetail = null
        return allAreaThings(id)
    }

    @Test
    fun `randomized ROMs match, and their tables are read from them`() {
        val files = randomized("Pokemon FireRed Randomized (") + randomized("Pokemon Emerald Randomized (")
        assumeTrue("no randomized ROMs here", files.isNotEmpty())
        for (f in files) {
            val b = f.readBytes()
            val rom = match(b)
            assertNotNull(f.name, rom)
            rom!!
            val retailBytes = retail.single { it.code == rom.code && it.rev == rom.rev }.let { bytes(it.name) }
            val read = RandomizedRetail.read(RomFileReader(b), rom)
            // What it read is the ROM's bytes, and (every option on) not retail's.
            val stats = rom.baseStats.toInt() - 0x08000000
            for (s in 1..411) assertEquals(SpeciesTypes(b[stats + s * 28 + 6].toInt() and 0xFF, b[stats + s * 28 + 7].toInt() and 0xFF), read.types[s])
            val moves = rom.battleMoves.toInt() - 0x08000000
            for (m in 1..354) assertEquals(f.name, MoveInfo(moveData[m]!!.name, b[moves + m * 12 + 2].toInt() and 0xFF, b[moves + m * 12 + 1].toInt() and 0xFF), read.moves[m])
            assertNotEquals(speciesTypeData, read.types)
            assertNotEquals(typeEffectiveness, read.typeChart)   // the "update type effectiveness" tweak is on
            val g = read.randomizedGuide!!
            for ((flag, at) in rom.itemSites) {
                val o = (at - 0x08000000).toInt()
                assertEquals(f.name, (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8), g.items[flag])
            }
            if (retailBytes != null) {
                val was = RandomizedRetail.read(RomFileReader(retailBytes), rom).randomizedGuide!!
                assertNotEquals(f.name, was.items, g.items)
                assertNotEquals(f.name, was.trades, g.trades)
            }
        }
    }

    @Test
    fun `the field features find retail's code in a randomized ROM`() {
        val files = randomized("Pokemon FireRed Randomized (") + randomized("Pokemon Emerald Randomized (")
        assumeTrue("no randomized ROMs here", files.isNotEmpty())
        for (f in files) {
            val b = f.readBytes()
            val rom = match(b)!!
            val read = { addr: Long, len: Int -> b.copyOfRange((addr - 0x08000000).toInt(), (addr - 0x08000000).toInt() + len) }
            // USE / GIVE / TAKE, FLY and PC anywhere: every function's code CRC holds, as on retail.
            val fns = fieldItemsFor(rom.code, rom.rev)!!
            assertTrue(f.name, fieldItemCodeMatches(fns, read))
            assertTrue(f.name, fns.fly != null && flyCodeMatches(fns.fly!!, read))
            assertTrue(f.name, fns.pc != null && pcCodeMatches(fns.pc!!, read))
            // WILD ENCOUNTERS' switch and REUSABLE TMS' sites: found by their code, retail's.
            val retailBytes = retail.single { it.code == rom.code && it.rev == rom.rev }.let { bytes(it.name) } ?: continue
            val buf = { x: ByteArray -> java.nio.ByteBuffer.wrap(x).order(java.nio.ByteOrder.LITTLE_ENDIAN) }
            assertNotEquals(f.name, 0L, WildEncounters.find(buf(b)))
            assertEquals(f.name, WildEncounters.find(buf(retailBytes)), WildEncounters.find(buf(b)))
            assertEquals(f.name, ReusableTms.find(buf(retailBytes)).map { it.at }, ReusableTms.find(buf(b)).map { it.at })
        }
    }

    @Test
    fun `a randomized game reads as retail, with the ROM's own tables`() {
        val f = randomized("Pokemon FireRed Randomized (v4.6.1) (Rev 1").firstOrNull()
        assumeTrue("no randomized FireRed rev 1 here", f != null)
        val rom = RomFileReader(f!!.readBytes())
        val hit = match(f.readBytes())!!
        activeGame = GameKind.FIRERED
        randomizedRetail = RandomizedRetail.read(rom, hit)
        assertTrue(gameRandomized)
        PokedexSource.reader = rom
        PokedexSource.romChanged()

        // The guide tables are retail's place, but BROCK goes by another name: only the name-free probe holds.
        val t = NATIVE_FIRERED_REV1.guideTables!!
        assertFalse(guideTablesMatchRom(rom, t))
        assertTrue(guideTablesMatchRom(rom, t, byName = false))
        assertTrue(pokedexMatchesRom(rom, NATIVE_FIRERED_REV1.pokedex!!))

        // The field: the save's own party (UPR leaves the save alone), its types and moves as the ROM has them.
        val field = decodeNative("firered_randomized", NATIVE_FIRERED_REV1)
        assertTrue(field.party.isNotEmpty())
        for (m in field.party) {
            assertEquals(randomizedRetail!!.types[m.species], activeSpeciesTypeData[m.species])
            for (id in m.moves.filter { it > 0 }) assertEquals(randomizedRetail!!.moves[id]!!.power, lookupMove(id).power)
        }
        assertEquals(randomizedRetail!!.typeChart, activeTypeEffectiveness)
        assertNotNull(moveStats(1))
        assertEquals(randomizedRetail!!.moveStats!![1], moveStats(1))

        // The trainer battle: BROCK's record's team as the ROM has it, in gEnemyParty and gBattleMons.
        val battle = decodeNative("firered_randomized_trainer", NATIVE_FIRERED_REV1)
        assertTrue(battle.inBattle)
        val team = GuideRomSource.party(t, 414)!!
        assertEquals(team.map { it.species }, battle.enemyParty.map { it.species })
        assertEquals(team[0].species, battle.battleMons[BATTLE_POS_OPPONENT_LEFT].species)

        // DEX: the randomizer's TMs and evolutions (retail's checks on BULBASAUR don't hold, the hash vouches instead).
        val tmhm = DexDetails.tmhmMoves(NATIVE_FIRERED_REV1.pokedex!!)
        assertNotNull(tmhm)
        assertEquals(listOf(15, 19, 57, 70, 148, 249, 127, 291), tmhm!!.drop(50))
        assertNotNull(EvolutionSource.table(NATIVE_FIRERED_REV1.pokedex!!))

        // GUIDE: the hand-written gifts and legendaries are gone, the randomized line is there; HERE's items are the ROM's.
        val guide = gameGuide(GuideId.FIRERED)!!
        val headings = guide.pages.flatMap { p -> p.sections.map { it.heading } }
        assertFalse("GIFT POKéMON" in headings)
        assertFalse("LEGENDARY POKéMON" in headings)
        assertTrue("RANDOMIZED" in headings)
        assertFalse("RANDOMIZED" in gameGuide(GuideId.FIRERED, randomized = false)!!.pages.flatMap { p -> p.sections.map { it.heading } })
        val items = allAreaThings(GuideId.FIRERED).values.flatten().filter { it.kind == AreaKind.ITEM || it.kind == AreaKind.HIDDEN }
        for (i in items) assertEquals(randomizedRetail!!.randomizedGuide!!.items[i.flag], i.id)
    }

    @Test
    fun `the battle fixtures stop at the action menu with FIGHT on the cursor`() {
        listOf(
            "firered_randomized_battle" to NATIVE_FIRERED_REV1, "firered_randomized_trainer" to NATIVE_FIRERED_REV1,
            "emerald_randomized_battle" to NATIVE_EMERALD_RETAIL, "emerald_randomized_trainer" to NATIVE_EMERALD_RETAIL,
        ).forEach { (fixture, cfg) ->
            val ram = FixtureMemoryReader.load(fixture)
            assertEquals(fixture, 0, ram.readCoreMemory(cfg.actionCursor, 1)[0].toInt())
            assertTrue(fixture, readNativeTelemetry(ram, cfg).inBattle)
        }
        // FireRed rev 0 has no one-press picks (retail's config has no cursors), only the battle read.
        assertTrue(readNativeTelemetry(FixtureMemoryReader.load("firered_randomized_rev0_battle"), NATIVE_FIRERED_REV0).inBattle)
    }
}
