package com.pokedaisy.app.companion.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.zip.CRC32

/**
 * TmTeach.kt's and Flash.kt's rules against a fake game (what the game's functions return), and their addresses against
 * the retail ROMs (checked live headless on the user's saves: HM05 taught through the game's own TM screen, FLASH in Rock
 * Tunnel / Granite Cave).
 */
class TmFlashTest {
    private val zeroCrc = CRC32().apply { update(ByteArray(32)) }.value

    private val fns = FIELD_ITEMS_FIRERED_REV1.let { f ->
        f.copy(codeCrcs = f.codeCrcs.map { zeroCrc }, party = f.party!!.copy(codeCrcs = f.party!!.codeCrcs.map { zeroCrc }))
    }
    private val tm = TM_FIRERED_REV1.copy(codeCrcs = TM_FIRERED_REV1.codeCrcs.map { zeroCrc })
    private val flash = FLASH_FIRERED_REV1.copy(codeCrcs = FLASH_FIRERED_REV1.codeCrcs.map { zeroCrc })

    /** The game: a party, a bag, the field, FLASH's map. */
    private inner class FakeGame : GameCalls {
        inner class Mon(var personality: Long, var species: Int, val moves: IntArray, var egg: Boolean = false)

        val p = fns.party!!
        var locked = false
        val bag = mutableMapOf(TM_ITEM to 1, HM_ITEM to 1)
        val party = mutableListOf<Mon>()
        val fades = mutableListOf<Int>()
        var cleaned = false
        var noRoom = false

        // FLASH's side.
        var badge = true
        var cave = true
        var lit = false
        val ram = mutableMapOf<Long, Byte>()
        val called = mutableListOf<Long>()

        private fun mon(addr: Int) = party[((addr.toLong() and 0xFFFFFFFFL) - p.party).toInt() / 100]

        override fun call(fn: Long, a0: Int, a1: Int, a2: Int, a3: Int): Long {
            called += fn
            return when (fn) {
                fns.fieldControlsLocked -> if (locked) 1 else 0
                fns.checkBagHasItem -> if ((bag[a0] ?: 0) >= a1) 1 else 0
                fns.removeBagItem -> if ((bag[a0] ?: 0) >= a1) { bag[a0] = bag[a0]!! - a1; if (bag[a0] == 0) bag.remove(a0); 1 } else 0
                p.getMonData -> mon(a0).let { m ->
                    when (a1) {
                        0 -> m.personality
                        11 -> m.species.toLong()
                        12 -> 0
                        in 13..16 -> m.moves[a1 - 13].toLong()
                        45 -> if (m.egg) 1 else 0
                        else -> error("field $a1")
                    }
                }
                tm.lockControls -> { locked = true; 0x080000C1 }
                tm.unlockControls -> { locked = false; 0x080000C1 }
                tm.fadeScreen -> { fades += a0; 0x080000C1 }
                tm.cleanupOverworld -> { cleaned = true; 0x080000C1 }
                tm.showPartyMenuForItemUse -> {
                    // InitPartyMenu: the bag as its way out, the party menu on its way (or straight out with no room).
                    write(tm.partyMenu, word(BAG_EXIT))
                    write(tm.mainCallback2, word(if (noRoom) BAG_EXIT else tm.initPartyMenu or 1))
                    0x080000C1
                }
                tm.setMainCallback2 -> { write(tm.mainCallback2, word(a0.toLong() and 0xFFFFFFFFL)); 0x080000C1 }
                flash.flagGet -> when (a0) {
                    flash.badgeFlag -> if (badge) 1 else 0
                    flash.litFlag -> if (lit) 1 else 0
                    else -> 0
                }
                flash.setUpFieldMoveFlash -> if (cave && !lit) {
                    // The party menu's callbacks, as the game sets them.
                    write(flash.menuFieldCallback, word(0x08056A39))
                    write(flash.postMenuFieldCallback, word(postCallback))
                    1
                } else 0
                flash.fieldCallbacks[0] or 1 -> { lit = true; 0x080000C1 }
                else -> -1
            }
        }

        var postCallback = flash.fieldCallbacks[0] or 1

        override fun read(addr: Long, len: Int): ByteArray = when {
            addr == p.partyCount -> byteArrayOf(party.size.toByte())
            addr == flash.mapHeader + 0x15 -> byteArrayOf(if (cave) 1 else 0)
            addr >= 0x08000000L -> ByteArray(len)
            else -> ByteArray(len) { ram[addr + it] ?: 0 }
        }

        override fun write(addr: Long, bytes: ByteArray): Boolean {
            bytes.forEachIndexed { i, b -> ram[addr + i] = b }
            return true
        }

        fun word(v: Long) = ByteArray(4) { (v shr (8 * it)).toByte() }
        fun u32(addr: Long) = read(addr, 4).let { b -> (0..3).fold(0L) { v, i -> v or ((b[i].toLong() and 0xFF) shl (8 * i)) } }
    }

    /** A party: slot 1 has CUT, an egg last; nobody knows FLASH until [flashGame]. */
    private fun game() = FakeGame().apply {
        party += Mon(0x11, species = 1, moves = intArrayOf(33, 45, 0, 0))
        party += Mon(0x22, species = 2, moves = intArrayOf(33, 15, 45, 98))
        party += Mon(0x33, species = 3, moves = intArrayOf(33, 45, 0, 0))
        party += Mon(0x55, species = 1, moves = intArrayOf(0, 0, 0, 0), egg = true)
    }

    @Test
    fun aTmOpensTheGamesOwnScreenAndComesBackToTheField() {
        val g = game()
        assertEquals(ItemUseResult.USED, tmStart(fns, tm, TM_ITEM, false, g))
        assertTrue(g.locked)
        assertEquals(listOf(1), g.fades) // to black
        assertTrue(tmFinish(tm, TM_ITEM, g))
        assertTrue(g.cleaned)
        // The bag's way in: the TM, the party menu's action, the cursor on the first slot.
        assertEquals(TM_ITEM, g.read(tm.specialVarItemId, 2).let { (it[0].toInt() and 0xFF) or ((it[1].toInt() and 0xFF) shl 8) })
        assertEquals(tm.itemUseCbTmhm or 1, g.u32(tm.itemUseCb))
        assertEquals(0, g.read(tm.partyMenu + tm.partyMenuSlotOff, 1)[0].toInt())
        // ... and out to the field, not to a bag that never opened.
        assertEquals(tm.returnToField or 1, g.u32(tm.partyMenu))
        assertEquals(tm.initPartyMenu or 1, g.u32(tm.mainCallback2))
        // The game's screen does the rest: nothing taught, nothing used up here.
        assertEquals(1, g.bag[TM_ITEM])
    }

    @Test
    fun noRoomForTheMenuGoesToTheFieldInstead() {
        val g = game().apply { noRoom = true }
        tmStart(fns, tm, TM_ITEM, false, g)
        assertTrue(tmFinish(tm, TM_ITEM, g))
        assertEquals(tm.returnToField or 1, g.u32(tm.mainCallback2))
    }

    @Test
    fun theFieldComesBackIfTheGameLeftItDuringTheFade() {
        val g = game()
        tmStart(fns, tm, TM_ITEM, false, g)
        assertFalse(tmFinish(tm, TM_ITEM, g, leftField = true))
        assertFalse(tm.showPartyMenuForItemUse in g.called)
    }

    @Test
    fun theTmScreenWaitsForTheField() {
        assertEquals(ItemUseResult.NOT_NOW, tmStart(fns, tm, TM_ITEM, false, game().apply { locked = true }))
        assertEquals(ItemUseResult.NOT_NOW, tmStart(fns, tm, TM_ITEM, true, game()))
        assertEquals(ItemUseResult.FAILED, tmStart(fns, tm, 13, false, game())) // a Potion isn't a TM
        assertEquals(ItemUseResult.NONE_LEFT, tmStart(fns, tm, TM_ITEM, false, game().apply { bag.clear() }))
        assertEquals(ItemUseResult.FAILED, tmStart(fns, tm, TM_ITEM, false, game().apply { party.clear() }))
        // Nothing locked or faded by a refusal.
        val g = game().apply { bag.clear() }
        tmStart(fns, tm, TM_ITEM, false, g)
        assertFalse(g.locked)
        assertTrue(g.fades.isEmpty())
    }

    private fun flashGame() = game().apply { party[1].moves[2] = flash.flashMove }

    @Test
    fun flashRunsTheGamesFieldMoveAndLeavesNoMenuCallbacks() {
        val g = flashGame()
        g.write(flash.menuFieldCallback, g.word(0))
        g.write(flash.postMenuFieldCallback, g.word(0))
        assertEquals(FlashResult.OK, useFlash(fns, flash, false, g))
        assertTrue(g.lit)
        // The cut-in shows the Pokémon that knows it; the party menu's callbacks are gone again.
        assertEquals(1, g.read(flash.partyMenuSlotId, 1)[0].toInt())
        assertEquals(0L, g.u32(flash.menuFieldCallback))
        assertEquals(0L, g.u32(flash.postMenuFieldCallback))
        assertEquals(FlashResult.ALREADY_LIT, useFlash(fns, flash, false, g))
    }

    @Test
    fun flashSaysWhyNot() {
        assertEquals(FlashResult.NO_FLASHER, useFlash(fns, flash, false, game()))
        assertEquals(FlashResult.NO_BADGE, useFlash(fns, flash, false, flashGame().apply { badge = false }))
        assertEquals(FlashResult.NOT_NOW, useFlash(fns, flash, false, flashGame().apply { locked = true }))
        assertEquals(FlashResult.NOT_NOW, useFlash(fns, flash, true, flashGame()))
        val outside = flashGame().apply { cave = false }
        assertEquals(FlashResult.CANT_USE_HERE, useFlash(fns, flash, false, outside))
        assertFalse(outside.lit)
    }

    @Test
    fun noBadgeWhereTheHackTookTheCheckOut() {
        assertEquals(FlashResult.OK, useFlash(fns, flash.copy(badgeFlag = NO_BADGE), false, flashGame().apply { badge = false }))
    }

    @Test
    fun aHacksOwnBadgeRuleIsAsked() {
        val rule = 0x08A0325DL
        val asked = mutableListOf<Int>()
        fun g(yes: Boolean): GameCalls {
            val d = flashGame().apply { badge = false }
            return object : GameCalls by d {
                override fun call(fn: Long, a0: Int, a1: Int, a2: Int, a3: Int): Long =
                    if (fn == rule) { asked += a0; if (yes) 1 else 0 } else d.call(fn, a0, a1, a2, a3)
            }
        }
        val own = flash.copy(badgeFn = rule)
        assertEquals(FlashResult.OK, useFlash(fns, own, false, g(true)))
        assertEquals(FlashResult.NO_BADGE, useFlash(fns, own, false, g(false)))
        assertEquals(listOf(0, 0), asked)   // FIELD_MOVE_FLASH
    }

    @Test
    fun theHacksOnRetailCodeGoByItsTables() {
        assertSame(TM_FIRERED_REV0, tmFnsFor(GameKind.UNBOUND, "BPRE", 0x9E, hack = false))   // Unbound FR's revision byte
        assertSame(TM_FIRERED_REV0, tmFnsFor(GameKind.FIRERED, "PUVV", 0, hack = false))      // Ultra Violet
        assertSame(TM_FIRERED_REV0, tmFnsFor(GameKind.FIRERED, "BPRE", 0, hack = false))      // Ghost Grey, Let's Go...
        assertSame(TM_EMERALD, tmFnsFor(GameKind.GLAZED, "BPEE", 0, hack = false))
        assertNull(tmFnsFor(GameKind.AMETHYST, "BPRE", 0, hack = false))
        assertNull(tmFnsFor(GameKind.EMERALD, "BPEE", 0, hack = true))                         // Recharged Emerald, Legacy
        assertSame(FLASH_FIRERED_REV0, flashFnsFor(GameKind.GAIA, "BPRE", 0, hack = false))
        assertSame(FLASH_EMERALD, flashFnsFor(GameKind.GLAZED, "BPEE", 0, hack = false))
    }

    /** The party menu's badge rule read from each hack edited into FireRed rev 0 (the ROMs the user has). */
    @Test
    fun theBadgeRuleIsReadFromTheRom() {
        val want = mapOf(
            "Pokemon - FireRed Version (USA).gba" to "retail", "Pokemon Ghost Grey (v1.0.4).gba" to "retail",
            "Pokemon Ultra Violet (v1.22).gba" to "retail", "Pokemon Odyssey (v4.1.1).gba" to "retail",
            "Pokemon Let's Go Pikachu.gba" to "retail", "Pokemon Gaia (v3.2).gba" to "none",
            "Pokemon Orange Islands.gba" to "none", "Pokemon FireRed Rocket Edition.gba" to "none",
            "Pokemon Unbound (v2.1.1.1).gba" to "own 0x08A0325C", "Pokemon Radical Red (v4.1).gba" to "own 0x090B4E28",
            "Pokemon GS Chronicles (v2.7.6).gba" to "own 0x09CBCAE8", "Pokemon Amethyst (v1.4.1).gba" to "own",
            "Pokemon Sword and Shield Ultimate Plus (v1.2.1.2) (Casual Patch).gba" to "own 0x09484ED0",
        ).filterKeys { File(RETAIL_ROM_DIR + it).isFile }
        assumeTrue(want.size == 13)
        for ((file, rule) in want) {
            val rom = File(RETAIL_ROM_DIR + file).readBytes()
            val got = flashBadgeRule(FLASH_FIRERED_REV0) { a, n -> val o = (a - 0x08000000L).toInt(); rom.copyOfRange(o, o + n) }
            val seen = when {
                got == null -> "off"
                got.badgeFn != 0L -> if (rule == "own") "own" else "own 0x%08X".format(got.badgeFn)
                got.badgeFlag == NO_BADGE -> "none"
                else -> "retail"
            }
            assertEquals(file, rule, seen)
        }
    }

    @Test
    fun anUnknownCallbackIsNeverCalled() {
        val g = flashGame().apply { postCallback = 0x08123457 }
        assertEquals(FlashResult.FAILED, useFlash(fns, flash, false, g))
        assertFalse(0x08123457L in g.called)
    }

    @Test
    fun flashDarkReadsTheCaveAndTheLight() {
        val g = flashGame()
        assertEquals(true, flashDark(flash, g::read) { false })
        assertEquals(false, flashDark(flash, g::read) { true })
        assertEquals(false, flashDark(flash, flashGame().apply { cave = false }::read) { false })
        assertEquals(null, flashDark(flash, g::read) { null })
    }

    @Test
    fun retailCodeIsWhereTheRecordsSay() {
        val fr = File(RomFileReader.FIRERED_REV1_PATH).takeIf { it.isFile }?.readBytes()
        val em = File(RomFileReader.EMERALD_PATH).takeIf { it.isFile }?.readBytes()
        assumeTrue(fr != null && em != null)
        fun reader(rom: ByteArray) = { a: Long, n: Int -> val o = (a - 0x08000000L).toInt(); rom.copyOfRange(o, o + n) }
        assertTrue(tmCodeMatches(TM_FIRERED_REV1, reader(fr!!)))
        assertTrue(flashCodeMatches(FLASH_FIRERED_REV1, reader(fr)))
        assertTrue(tmCodeMatches(TM_EMERALD, reader(em!!)))
        assertTrue(flashCodeMatches(FLASH_EMERALD, reader(em)))
        assertFalse(tmCodeMatches(TM_EMERALD, reader(fr)))
        assertFalse(flashCodeMatches(FLASH_FIRERED_REV1, reader(em)))
    }

    /** Every record against its own retail cart (each built byte for byte by pret's decomps), and never against another. */
    @Test
    fun everyCartsCodeIsWhereItsRecordsSay() {
        val carts = listOf(
            "Pokemon - FireRed Version (USA).gba" to (TM_FIRERED_REV0 to FLASH_FIRERED_REV0),
            "Pokemon - FireRed Version (USA, Europe) (Rev 1).gba" to (TM_FIRERED_REV1 to FLASH_FIRERED_REV1),
            "Pokemon - LeafGreen Version (USA).gba" to (TM_LEAFGREEN_REV0 to FLASH_LEAFGREEN_REV0),
            "Pokemon - LeafGreen Version (USA, Europe) (Rev 1).gba" to (TM_LEAFGREEN_REV1 to FLASH_LEAFGREEN_REV1),
            "Pokemon - Emerald Version (USA, Europe).gba" to (TM_EMERALD to FLASH_EMERALD),
            "Pokemon - Ruby Version (USA, Europe).gba" to (null to FLASH_RUBY_REV0),
            "Pokemon - Ruby Version (USA, Europe) (Rev 1).gba" to (null to FLASH_RUBY),
            "Pokemon - Ruby Version (USA, Europe) (Rev 2).gba" to (null to FLASH_RUBY),
            "Pokemon - Sapphire Version (USA, Europe).gba" to (null to FLASH_SAPPHIRE_REV0),
            "Pokemon - Sapphire Version (USA, Europe) (Rev 1).gba" to (null to FLASH_SAPPHIRE),
            "Pokemon - Sapphire Version (USA, Europe) (Rev 2).gba" to (null to FLASH_SAPPHIRE),
        ).mapNotNull { (file, recs) -> File(RETAIL_ROM_DIR + file).takeIf { it.isFile }?.let { it.readBytes() to recs } }
        assumeTrue(carts.size == 11)
        fun reader(rom: ByteArray) = { a: Long, n: Int -> val o = (a - 0x08000000L).toInt(); rom.copyOfRange(o, o + n) }
        val tms = carts.mapNotNull { it.second.first }.distinct()
        val flashes = carts.map { it.second.second }.distinct()
        for ((rom, recs) in carts) {
            val (tm, flash) = recs
            for (t in tms) assertEquals(t === tm, tmCodeMatches(t, reader(rom)))
            for (f in flashes) assertEquals(f === flash, flashCodeMatches(f, reader(rom)))
        }
    }

    @Test
    fun theOtherLanguagesGoByTheirPorts() {
        assertSame(TM_PORTS.getValue("BPRF0"), tmFnsFor(GameKind.FIRERED, "BPRF", 0, hack = false))
        assertSame(TM_PORTS.getValue("BPEJ0"), tmFnsFor(GameKind.EMERALD, "BPEJ", 0, hack = false))
        assertSame(FLASH_PORTS.getValue("AXVD1"), flashFnsFor(GameKind.EMERALD, "AXVD", 1, hack = false))
        assertNull(tmFnsFor(GameKind.EMERALD, "AXVD", 1, hack = false))   // Ruby / Sapphire: no TM screen
        assertNull(tmFnsFor(GameKind.FIRERED, "BPRF", 0, hack = true))
        assertNull(flashFnsFor(GameKind.FIRERED, "BPRF", 0, hack = true))
        assertNull(flashFnsFor(GameKind.FIRERED, "BPRF", 1, hack = false))   // no such cart
    }

    /** Every ported cart the user has: its own table's code is where it says, and no other language's or English's is. */
    @Test
    fun everyPortedCartsCodeIsWhereItsTableSays() {
        val carts = (File(RETAIL_ROM_DIR).listFiles() ?: emptyArray())
            .filter { it.name.endsWith(".gba") && listOf("Debug", "Pirate", "Pokemon Box").none(it.name::contains) }
            .mapNotNull { f ->
                val head = f.inputStream().use { s -> ByteArray(0xC0).also { s.read(it) } }
                val key = String(head, 0xAC, 4, Charsets.US_ASCII) + head[0xBC].toInt()
                if (key in FLASH_PORTS) key to f else null
            }
        assumeTrue(carts.size >= 37)
        fun reader(rom: ByteArray) = { a: Long, n: Int -> val o = (a - 0x08000000L).toInt(); rom.copyOfRange(o, o + n) }
        val english = listOf(TM_FIRERED_REV0, TM_FIRERED_REV1, TM_LEAFGREEN_REV0, TM_LEAFGREEN_REV1, TM_EMERALD)
        for ((key, file) in carts) {
            val rom = file.readBytes()   // one at a time: 37 carts don't fit the test heap
            assertTrue("$key FLASH (${file.name})", flashCodeMatches(FLASH_PORTS.getValue(key), reader(rom)))
            TM_PORTS[key]?.let { assertTrue("$key TM (${file.name})", tmCodeMatches(it, reader(rom))) }
            if (key.startsWith("BP")) assertTrue("$key has a TM table", key in TM_PORTS)
            assertFalse("$key runs an English TM table", english.any { tmCodeMatches(it, reader(rom)) })
        }
    }

    /** The rebuilds' records: Emerald Legacy's from its ELF, Recharged Emerald's three builds from the finder; each
     * build's code is where its own records say and nowhere another's do (tmCandidatesFor's CRC pick). */
    @Test
    fun theRebuildsCodeIsWhereTheirRecordsSay() {
        val carts = listOf(
            "Pokemon Emerald Legacy (v1.1.4).gba" to (TM_EMERALD_LEGACY to FLASH_EMERALD_LEGACY),
            "Pokemon Recharged Emerald (v2.2.5).gba" to (TM_RECHARGED_EMERALD_V2_2_5 to FLASH_RECHARGED_EMERALD_V2_2_5),
            "Pokemon Recharged Emerald (v2.2.8).gba" to (TM_RECHARGED_EMERALD_V2_2_8 to FLASH_RECHARGED_EMERALD_V2_2_8),
            "Pokemon Recharged Emerald Rebalanced (v2.2.8).gba" to
                (TM_RECHARGED_EMERALD_REBALANCED_V2_2_8 to FLASH_RECHARGED_EMERALD_REBALANCED_V2_2_8),
        ).filter { File(RETAIL_ROM_DIR + it.first).isFile }
        assumeTrue(carts.size == 4)
        val tms = carts.map { it.second.first } + TM_EMERALD
        val flashes = carts.map { it.second.second } + FLASH_EMERALD
        for ((file, recs) in carts) {
            val rom = File(RETAIL_ROM_DIR + file).readBytes()
            val read = { a: Long, n: Int -> val o = (a - 0x08000000L).toInt(); rom.copyOfRange(o, o + n) }
            for (t in tms) assertEquals("$file ${tms.indexOf(t)}", t === recs.first, tmCodeMatches(t, read))
            for (f in flashes) assertEquals("$file ${flashes.indexOf(f)}", f === recs.second, flashCodeMatches(f, read))
            assertSame(recs.second, flashBadgeRule(recs.second, read))   // retail's badge rule, no check address
        }
        assertEquals(3, tmCandidatesFor(GameKind.EMERALD, "BPEE", 0, rebuild = true).size)
        if (!emeraldLegacy) assertEquals(listOf(TM_EMERALD), tmCandidatesFor(GameKind.EMERALD, "BPEE", 0, rebuild = false))
        assertEquals(22, FLASH_RECHARGED_EMERALD_V2_2_8.monMoveField)   // Recharged's MON_DATA ids sit 9 past vanilla's
    }

    /** FLASH in the gcc-built Emerald hacks (scripts/find_flash_expansion.py): each table's code in its own ROM and in no
     * other build's; SoulGold's five go through flashCandidatesFor. */
    @Test
    fun theExpansionHacksFlashIsWhereTheirRecordsSay() {
        val carts = listOf(
            "Pokemon Lazarus (v2.0).gba" to FLASH_LAZARUS, "Pokemon Emerald Imperium (v1.3.1).gba" to FLASH_IMPERIUM,
            "Pokemon Heart and Soul (v2.0.6).gba" to FLASH_HEART_AND_SOUL, "Pokemon SoulGold (v1.1.4).gba" to FLASH_SOULGOLD,
            "Pokemon SoulGold (v1.2).gba" to FLASH_SOULGOLD_V1_2, "Pokemon SoulGold (v1.2) (Alt 1).gba" to FLASH_SOULGOLD_V1_2B,
            "Pokemon SoulGold (v1.2) (Alt 2).gba" to FLASH_SOULGOLD_V1_2C, "Pokemon SoulGold (v1.2.1).gba" to FLASH_SOULGOLD_V1_2_1,
        ).filter { File(RETAIL_ROM_DIR + it.first).isFile }
        assumeTrue(carts.size == 8)
        val all = carts.map { it.second }.distinct()
        for ((file, flash) in carts) {
            val rom = File(RETAIL_ROM_DIR + file).readBytes()
            val read = { a: Long, n: Int -> val o = (a - 0x08000000L).toInt(); rom.copyOfRange(o, o + n) }
            for (f in all) assertEquals("$file ${all.indexOf(f)}", f === flash, flashCodeMatches(f, read))
        }
        assertSame(FLASH_LAZARUS, flashFnsFor(GameKind.LAZARUS, "BPEE", 0, hack = false))
        assertEquals(5, flashCandidatesFor(GameKind.SOULGOLD, "BPEE", 0, rebuild = false).size)
        assertEquals(0x16, FLASH_SOULGOLD.caveOff)
        // flashDark reads SoulGold's cave byte at +0x16
        val ram = mapOf(FLASH_SOULGOLD.mapHeader + 0x16 to 1.toByte())
        val read = { a: Long, n: Int -> ByteArray(n) { ram[a + it] ?: 0 } }
        assertEquals(true, flashDark(FLASH_SOULGOLD, read) { false })
        assertEquals(false, flashDark(FLASH_SOULGOLD, read) { true })
    }

    private companion object {
        const val TM_ITEM = 289 + 5   // TM06
        const val HM_ITEM = 339 + 4   // HM05
        const val BAG_EXIT = 0x08124E19L
    }
}
