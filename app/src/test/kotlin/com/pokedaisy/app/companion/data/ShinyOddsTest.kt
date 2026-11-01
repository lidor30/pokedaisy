package com.pokedaisy.app.companion.data

import com.pokedaisy.app.companion.data.save.Gen3BoxMon
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

class ShinyOddsTest {

    private val emerald = ShinyOdds.targetFor(NATIVE_EMERALD_RETAIL)!!

    /** A fixture's RAM, writable: what the game's memory is to the engine. */
    private class Ram(fixture: String) {
        private val src = FixtureMemoryReader.load(fixture)
        val ewram = src.readCoreMemory(0x02000000L, 0x40000)
        val iwram = src.readCoreMemory(0x03000000L, 0x8000)
        var writes = 0

        private fun at(addr: Long): Pair<ByteArray, Int> =
            if (addr >= 0x03000000L) iwram to (addr - 0x03000000L).toInt() else ewram to (addr - 0x02000000L).toInt()

        fun read(addr: Long, len: Int): ByteArray { val (b, off) = at(addr); return b.copyOfRange(off, off + len) }
        fun write(addr: Long, bytes: ByteArray): Boolean { val (b, off) = at(addr); bytes.copyInto(b, off); writes++; return true }
        fun setInBattle(addr: Long, on: Boolean) {
            val (b, off) = at(addr)
            b[off] = (if (on) b[off].toInt() or 2 else b[off].toInt() and 2.inv()).toByte()
        }
    }

    private fun u32(b: ByteArray, off: Int) =
        (b[off].toLong() and 0xFF) or ((b[off + 1].toLong() and 0xFF) shl 8) or
            ((b[off + 2].toLong() and 0xFF) shl 16) or ((b[off + 3].toLong() and 0xFF) shl 24)

    @Test fun retailConfigsHaveTargets() {
        // FireRed rev 0's config names no gEnemyParty: it sits right before gPlayerParty, for the FireRed family only.
        assertNull(ShinyOdds.targetFor(NATIVE_FIRERED_REV0))
        assertEquals(0x0202402CL, ShinyOdds.targetFor(NATIVE_FIRERED_REV0, fireRedFamily = true)!!.enemyParty)
        assertEquals(0x0202402CL, ShinyOdds.targetFor(NATIVE_FIRERED_REV1)!!.enemyParty)
        assertEquals(0x02024744L, emerald.enemyParty)
        assertFalse(emerald.saveBlock2Static)
        // Ruby / Sapphire keep gSaveBlock2 in place, not behind a pointer.
        assertTrue(ShinyOdds.targetFor(NATIVE_RUBY)!!.saveBlock2Static)
        // A layout whose size isn't the party's stride is never touched.
        assertNull(ShinyOdds.targetFor(NATIVE_EMERALD_RETAIL.copy(monStride = 104)))
    }

    /** The hacks' own boxes: each read where its layout keeps the species, egg flags and shininess. */
    @Test fun hackLayoutsHaveTargets() {
        val sg = ShinyOdds.targetFor(NATIVE_SOULGOLD, national = true)!!
        assertTrue(sg.plaintext); assertEquals(96, sg.stride); assertEquals(0x07FF, sg.speciesMask); assertEquals(0x1F, sg.shinyModifier)
        val q = ShinyOdds.targetFor(NATIVE_QUETZAL)!!
        assertTrue(q.plaintext); assertEquals(0x68, q.stride); assertEquals(0x28, q.plainSpecies); assertEquals(0x50, q.eggIvWord)
        assertEquals(0, q.eggMask)
        val er = ShinyOdds.targetFor(NATIVE_ELITE_REDUX_V2_65_4_1B)!!
        assertTrue(er.plaintext); assertEquals(0x10, er.plainSpecies); assertEquals(0x17, er.shinyBits); assertEquals(0x04, er.shinyBitSet)
        val tr = ShinyOdds.targetFor(NATIVE_TEAM_ROCKET)!!
        assertFalse(tr.plaintext); assertEquals(0x12, tr.flags); assertEquals(0x20, tr.eggMask); assertEquals(0, tr.badEggMask)
        assertEquals(0x7FF, ShinyOdds.targetFor(NATIVE_TMT2)!!.speciesMask)
    }

    /** Elite Redux: the shiny bit set, the personality and everything else as it was. */
    @Test fun storedShinyBitIsSet() {
        val t = ShinyOdds.targetFor(NATIVE_ELITE_REDUX_V2_65_4_1B)!!.copy(saveBlock2Static = true, saveBlock2Ptr = 0x02030000L)
        val ram = HashMap<Long, Byte>()
        fun put(a: Long, v: Long, n: Int) { for (i in 0 until n) ram[a + i] = (v ushr (8 * i)).toByte() }
        put(t.enemyParty, 0x613DEBBAL, 4); put(t.enemyParty + 4, 0x29089024L, 4)
        put(t.enemyParty + 0x10, 0x5B5E0107L, 4); put(t.enemyParty + 0x14, 0x002A4C00L, 4)
        put(0x02030000L + t.trainerIdOff, 0x29089024L, 4)
        val read = { a: Long, n: Int -> ByteArray(n) { ram[a + it] ?: 0 } }
        val write = { a: Long, b: ByteArray -> b.forEachIndexed { i, v -> ram[a + i] = v }; true }
        val roller = ShinyOdds.Roller(t, Random(1))
        assertEquals(1, roller.tick(1, read, write) + roller.tick(1, read, write))
        assertEquals(0x042A4C00L, u32(read(t.enemyParty + 0x14, 4), 0))
        assertEquals(0x613DEBBAL, u32(read(t.enemyParty, 4), 0))
        // Shiny already: left be.
        assertEquals(0, roller.tick(1, read, write) + roller.tick(1, read, write))
    }

    /** The other retail versions' hashes (scripts/verify_shiny.py's passes): 45 of them, none the English FireRed / Emerald
     * ones the Poller already knows, and none a hack's. */
    @Test fun retailHashesAreTheOtherRetailVersions() {
        assertEquals(45, ShinyOdds.RETAIL_SHA1S.size)
        assertTrue(ShinyOdds.RETAIL_SHA1S.all { Regex("[0-9a-f]{40}").matches(it) })
        assertTrue(ShinyOdds.RETAIL_SHA1S.none { it in TelemetrySampler.RETAIL_SHA1S })
        assertTrue(ShinyOdds.RETAIL_SHA1S.none { it in TelemetrySampler.SUPPORTED_HACK_SHA1S })
        // ...and every one a retail cart the library names as such.
        assertTrue(ShinyOdds.RETAIL_SHA1S.all { com.pokedaisy.app.GameTitles.isRetail(it) })
    }

    /** The hacks (verify_shiny.py's passes): each a hack the library knows by that hash, at a shiny check of 8 or more. */
    @Test fun hackHashesAreKnownHacks() {
        assertEquals(46, ShinyOdds.HACK_SHINY_BELOW.size)
        assertTrue(ShinyOdds.PLAINTEXT_SHA1S.all { it in ShinyOdds.HACK_SHINY_BELOW })
        assertTrue(ShinyOdds.NATIONAL_SHA1S.all { it in ShinyOdds.HACK_SHINY_BELOW })
        for ((sha1, below) in ShinyOdds.HACK_SHINY_BELOW) {
            assertNotNull(sha1, com.pokedaisy.app.GameTitles.BY_SHA1[sha1])
            assertFalse(sha1, com.pokedaisy.app.GameTitles.isRetail(sha1))
            assertTrue(sha1, below >= 8)
        }
    }

    @Test fun rerollChanceMakesTheOddsAddUp() {
        assertEquals(0.0, ShinyOdds.rerollChance(ShinyOdds.NORMAL), 0.0)
        assertEquals(1.0, ShinyOdds.rerollChance(1), 0.0)
        val base = 8.0 / 65536
        for (odds in listOf(4096, 512, 128, 4)) {
            val q = ShinyOdds.rerollChance(odds)
            assertEquals(1.0 / odds, base + (1 - base) * q, 1e-12)
        }
        // A game whose own odds are better already (Odyssey: shiny below 255, about 1 in 257) rerolls nothing at 1 in 512.
        assertEquals(0.0, ShinyOdds.rerollChance(512, shinyBelow = 255), 0.0)
        val own = 255 / 65536.0
        assertEquals(1.0 / 128, own + (1 - own) * ShinyOdds.rerollChance(128, shinyBelow = 255), 1e-12)
    }

    @Test fun shinyPersonalityKeepsWhatThePersonalityDecides() {
        val r = Random(7)
        repeat(2000) {
            val old = r.nextInt().toLong() and 0xFFFFFFFFL
            val ot = r.nextInt().toLong() and 0xFFFFFFFFL
            val species = listOf(1, 201, 290, 300)[it % 4]
            val p = ShinyOdds.shinyPersonality(old, ot, species, r) ?: run {
                assertEquals("only UNOWN can run out", 201, species); return@repeat
            }
            assertTrue(shinyValue(p, ot) < 8)
            assertEquals(old % 25, p % 25)                 // nature
            assertEquals(old and 1, p and 1)               // ability bit
            if (species != 201 && species != 290) assertEquals(old and 0xFF, p and 0xFF)   // gender
            if (species == 290) assertEquals((old and 0xFF) < 127, (p and 0xFF) < 127)
            if (species == 201) assertEquals(unown(old), unown(p))
            if (species == 290) assertEquals((old ushr 16) % 10 <= 4, (p ushr 16) % 10 <= 4)
        }
    }

    private fun unown(p: Long) =
        ((((p ushr 24) and 3) shl 6) or (((p ushr 16) and 3) shl 4) or (((p ushr 8) and 3) shl 2) or (p and 3)) % 28

    /** A real wild foe (European Emerald: English's RAM), as it sits before the battle is drawn. */
    @Test fun wildFoeTurnsShinyAndKeepsEverythingElse() {
        val ram = Ram("emerald_de_battle")
        val foe = emerald.enemyParty
        val before = ram.read(foe, 100)
        val save2 = u32(ram.read(emerald.saveBlock2Ptr, 4), 0)
        assertEquals("the fixture's foe is wild (the player's OT ID)", u32(ram.read(save2 + 0x0A, 4), 0), u32(before, 4))
        val was = decodePartyMon(before, 0)!!
        ram.setInBattle(emerald.inBattle, false)

        val roller = ShinyOdds.Roller(emerald, Random(1))
        // The first frame only notes the new record; the next, with the same bytes, decides.
        assertEquals(0, roller.tick(1, ram::read, ram::write))
        assertEquals(1, roller.tick(1, ram::read, ram::write))
        val after = ram.read(foe, 100)
        val now = decodePartyMon(after, 0)!!
        assertTrue(shinyValue(now.personality, u32(after, 4)) < 8)
        assertNotEquals(was.personality, now.personality)
        assertEquals(was.species, now.species)
        assertEquals(was.level, now.level)
        assertEquals(was.hp, now.hp)
        assertArrayEquals(was.moves, now.moves)
        assertArrayEquals(was.pp, now.pp)
        assertEquals(was.exp, now.exp)
        assertEquals(was.heldItem, now.heldItem)
        assertEquals(was.stats, now.stats)               // IVs, EVs, nature, stats
        assertTrue(Gen3BoxMon.read(after, 0).checksumOk)
        // The party fields past the box record aren't touched.
        assertArrayEquals(before.copyOfRange(80, 100), after.copyOfRange(80, 100))

        // Seen once: the next frames leave it be.
        assertEquals(0, roller.tick(1, ram::read, ram::write))
    }

    /** The SHINY marks: headless battles against a foe SHINY ODDS rewrote (the fixtures' READMEs), and a plain wild one. */
    @Test fun battleFoeIsMarkedShinyWhereTheCheckIsKnown() {
        for ((fixture, cfg) in listOf("emerald_shiny_battle" to NATIVE_EMERALD_RETAIL, "firered_shiny_battle" to NATIVE_FIRERED_REV1)) {
            val ram = FixtureMemoryReader.load(fixture)
            val marked = readNativeTelemetry(ram, cfg.copy(shinyBelow = 8))
            assertTrue(fixture, marked.battleMons[BATTLE_POS_OPPONENT_LEFT].shiny)
            assertTrue(fixture, buildSnapshotView(marked).battleOpponent.first().shiny)
            // A config the Poller didn't mark (a hack copying retail's) never says so.
            assertFalse(fixture, readNativeTelemetry(ram, cfg).battleMons[BATTLE_POS_OPPONENT_LEFT].shiny)
        }
        val plain = readNativeTelemetry(FixtureMemoryReader.load("emerald_de_battle"), NATIVE_EMERALD_RETAIL.copy(shinyBelow = 8))
        assertFalse(plain.battleMons[BATTLE_POS_OPPONENT_LEFT].shiny)
    }

    /** Two frames, as a new foe needs (its record unchanged across them). */
    private fun ShinyOdds.Roller.settle(odds: Int, ram: Ram) = tick(odds, ram::read, ram::write) + tick(odds, ram::read, ram::write)

    /** Shiny icons: the foe party (the FOE TEAM, a wild foe's stats) reads shiny too where the check is known. */
    @Test fun foePartyReadsShinyWhereTheCheckIsKnown() {
        for ((fixture, cfg) in listOf("emerald_shiny_battle" to NATIVE_EMERALD_RETAIL, "firered_shiny_battle" to NATIVE_FIRERED_REV1)) {
            val ram = FixtureMemoryReader.load(fixture)
            assertTrue(fixture, readNativeTelemetry(ram, cfg.copy(shinyBelow = 8)).battleFoes.first().shiny)
            assertFalse(fixture, readNativeTelemetry(ram, cfg).battleFoes.first().shiny)
        }
    }

    /** The shiny icon's colours: every species' shiny battle palette is found right after the normal ones (its tag says
     * so), and PIKACHU's icon colours change. Needs the retail ROMs on this machine. */
    @Test fun shinyIconPalettesComeFromTheRom() {
        for ((path, cfg) in listOf(RomFileReader.FIRERED_REV1_PATH to NATIVE_FIRERED_REV1, RomFileReader.EMERALD_PATH to NATIVE_EMERALD_RETAIL)) {
            val rom = RomFileReader.load(path)
            org.junit.Assume.assumeTrue("$path not on this machine", rom != null)
            val t = cfg.copy(shinyBelow = 8).iconTables
            assertNotEquals(0L, t.battlePalettes)
            val rd = { a: Long, n: Int -> rom!!.readCoreMemory(a, n) }
            fun iconPal(species: Int): ByteArray {
                val idx = rd(t.monIconPaletteIndices + species, 1)[0].toInt() and 0xFF
                return rd(u32(rd(t.monIconPaletteTable + idx * 8L, 4), 0), 32)
            }
            // Gen 3's ids 252..276 are empty slots between the Johto and Hoenn species.
            for (species in (1..251) + (277..411)) assertNotNull("$path species $species", shinyIconPalette(t, species, iconPal(species), rd))
            val pikachu = iconPal(25)
            assertFalse(path, shinyIconPalette(t, 25, pikachu, rd)!!.contentEquals(pikachu))
            // Without the threshold (a hack on retail's RAM) there are no battle palettes, so no shiny icons.
            assertNull(shinyIconPalette(cfg.iconTables, 25, pikachu, rd))
        }
    }

    /** CFRU's plaintext box (Radical Red's wild foe): only the personality changes, and the record reads the same. */
    @Test fun plaintextFoeKeepsEverythingButItsPersonality() {
        val ram = Ram("radical_red_battle")
        val cfru = ShinyOdds.targetFor(NATIVE_RADICAL_RED_V4_1, fireRedFamily = true, plaintext = true)!!
        ram.setInBattle(cfru.inBattle, false)
        val before = ram.read(cfru.enemyParty, 100)
        val was = decodePartyMon(before, 0)!!
        val roller = ShinyOdds.Roller(cfru, Random(5))
        assertEquals(0, roller.tick(1, ram::read, ram::write))
        assertEquals(1, roller.tick(1, ram::read, ram::write))
        val after = ram.read(cfru.enemyParty, 100)
        assertTrue(shinyValue(u32(after, 0), u32(after, 4)) < 8)
        assertArrayEquals(before.copyOfRange(4, 100), after.copyOfRange(4, 100))
        val now = decodePartyMon(after, 0)!!
        assertEquals(was.species, now.species)
        assertEquals(was.level, now.level)
        assertArrayEquals(was.moves, now.moves)
        assertEquals(u32(before, 0) % 25, u32(after, 0) % 25)   // nature
    }

    @Test fun nothingChangesInBattleOrAtNormalOdds() {
        val ram = Ram("emerald_de_battle")
        ram.setInBattle(emerald.inBattle, true)
        assertEquals(0, ShinyOdds.Roller(emerald).settle(1, ram))
        ram.setInBattle(emerald.inBattle, false)
        assertEquals(0, ShinyOdds.Roller(emerald).settle(ShinyOdds.NORMAL, ram))
        assertEquals(0, ram.writes)
    }

    /** The RP6 report: a frame can end with the game halfway through making the foe (its substructs not yet written).
     * Such a record isn't decided on - and isn't written off - until the game has finished it. */
    @Test fun aFoeStillBeingMadeIsWaitedFor() {
        val ram = Ram("emerald_de_battle")
        ram.setInBattle(emerald.inBattle, false)
        val foe = emerald.enemyParty
        val done = ram.read(foe, 100)
        // Mid-CreateMon: the header written, the substructs still zero.
        ram.write(foe, done.copyOfRange(0, 0x20) + ByteArray(80 - 0x20))
        ram.writes = 0
        val roller = ShinyOdds.Roller(emerald, Random(3))
        assertEquals(0, roller.tick(1, ram::read, ram::write))
        // The next frame the game finishes it: new bytes, so it waits one more frame...
        ram.write(foe, done.copyOfRange(0, 80))
        ram.writes = 0
        assertEquals(0, roller.tick(1, ram::read, ram::write))
        assertEquals(0, ram.writes)
        // ...then, unchanged, it's made shiny.
        assertEquals(1, roller.tick(1, ram::read, ram::write))
        assertTrue(shinyValue(u32(ram.read(foe, 4), 0), u32(ram.read(foe + 4, 4), 0)) < 8)
    }

    @Test fun aTrainersFoeIsNeverTouched() {
        val ram = Ram("emerald_de_battle")
        ram.setInBattle(emerald.inBattle, false)
        // Another OT ID: a trainer's Pokémon (OT_ID_RANDOM_NO_SHINY).
        val save2 = u32(ram.read(emerald.saveBlock2Ptr, 4), 0)
        val id = ram.read(save2 + 0x0A, 4)
        id[0] = (id[0] + 1).toByte()
        ram.write(save2 + 0x0A, id)
        ram.writes = 0
        assertEquals(0, ShinyOdds.Roller(emerald).settle(1, ram))
        assertFalse(ram.writes > 0)
    }
}
