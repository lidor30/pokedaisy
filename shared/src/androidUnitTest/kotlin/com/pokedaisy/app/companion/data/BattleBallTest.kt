package com.pokedaisy.app.companion.data

import okio.Path.Companion.toOkioPath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** LAST BALL's functions ([BattleBall.find]), pinned on FireRed rev 1 and Emerald at what was played headless, and its
 * picks. Skips a ROM that isn't here. */
class BattleBallTest {
    private fun hex(v: Long) = "%08X".format(v)

    @Test
    fun retailFireRedRev1AndEmerald() {
        File(RETAIL_ROM_DIR + "Pokemon - FireRed Version (USA, Europe) (Rev 1).gba").takeIf { it.isFile }?.let { rom ->
            val f = BattleBall.find(rom.toOkioPath(), NATIVE_FIRERED_REV1.completeWhenChoseItem, NATIVE_FIRERED_REV1.battlerControllerFuncs)!!
            assertEquals("08030714", hex(f.openBag))
            assertEquals("0203AD30", hex(f.itemIdVar))
            assertEquals("02037AB8", hex(f.paletteFade))
            assertEquals("0807059C", hex(f.beginNormalPaletteFade))
        }
        File(RETAIL_ROM_DIR + "Pokemon - Emerald Version (USA, Europe).gba").takeIf { it.isFile }?.let { rom ->
            val f = BattleBall.find(rom.toOkioPath(), NATIVE_EMERALD_RETAIL.completeWhenChoseItem, NATIVE_EMERALD_RETAIL.battlerControllerFuncs)!!
            assertEquals("080598A4", hex(f.openBag))
            assertEquals("0203CE7C", hex(f.itemIdVar))
            assertEquals("02037FD4", hex(f.paletteFade))
            assertEquals("080A1AD4", hex(f.beginNormalPaletteFade))
        }
    }

    /** Every retail FireRed / LeafGreen / Emerald cart here, in every language: its config's functions read from the cart
     * (2026-11-18), Ruby / Sapphire's too. FireRed rev 1, Emerald, Japanese Emerald, German FireRed and Ruby rev 1 were played
     * headless; the others share agbcc's code, the shape checked. */
    @Test
    fun everyRetailCartFindsItsFunctions() {
        var n = 0
        retailRomFiles().filter { listOf("Pirate", "Debug").none { x -> x in it.name } }.forEach { rom ->
            val h = rom.inputStream().use { it.readNBytes(0xC0) }
            val code = String(h, 0xAC, 4, Charsets.ISO_8859_1)
            if (listOf("BPR", "BPG", "BPE", "AXV", "AXP").none { code.startsWith(it) }) return@forEach
            val rev = h[0xBC].toInt() and 0xFF
            val cfg = when (code) {
                "BPRE" -> if (rev == 1) NATIVE_FIRERED_REV1 else NATIVE_FIRERED_REV0_RETAIL
                "BPEE" -> NATIVE_EMERALD_RETAIL
                else -> TelemetrySampler.otherRetailConfig(code, rev) ?: if (code == "AXVE") NATIVE_RUBY else if (code == "AXPE") NATIVE_SAPPHIRE else null
            } ?: return@forEach
            val f = BattleBall.find(rom.toOkioPath(), cfg.completeWhenChoseItem, cfg.battlerControllerFuncs)
            assertNotNull("${rom.name} ($code$rev)", f)
            // OpenBagAndChooseItem: 0x3C before CompleteWhenChoseItem, Ruby / Sapphire's 0x38.
            assertEquals(rom.name, (cfg.completeWhenChoseItem and 0xFFFFFFFEL) - if (code.startsWith("AX")) 0x38 else 0x3C, f!!.openBag)
            System.err.println("BB $code$rev ctl=${hex(f.controllerFuncs)} open=${hex(f.openBag)} cwci=${hex(f.completeWhenChose)} item=${hex(f.itemIdVar)} fade=${hex(f.paletteFade)} bnpf=${hex(f.beginNormalPaletteFade)}")
            n++
        }
        if (File(RETAIL_ROM_DIR).isDirectory) assertTrue("only $n carts", n == 0 || n >= 45)
    }

    @Test
    fun nothingInAnEmptyRom() {
        assertNull(BattleBall.find(LittleEndianBytes(ByteArray(0x40000)), 0x08030750L, 0x03004FE0L))
    }

    private fun ball(id: Int, n: Int) = ItemView(id, "B$id", n, null, pocket = POCKET_POKE_BALLS)

    @Test
    fun picksTheLastBallElseTheFirst() {
        val bag = listOf(ItemView(13, "P", 5, null), ball(4, 10), ball(2, 3))
        assertEquals(2, BattleBall.pick(bag, last = 2)?.itemId)
        assertEquals(4, BattleBall.pick(bag, last = 3)?.itemId)
        assertEquals(4, BattleBall.pick(bag, last = null)?.itemId)
        assertNull(BattleBall.pick(listOf(ItemView(13, "P", 5, null)), last = null))
        assertEquals(2, BattleBall.thrown(bag, listOf(ball(4, 10), ball(2, 2))))
        assertEquals(2, BattleBall.thrown(bag, listOf(ball(4, 10))))
        assertNull(BattleBall.thrown(bag, bag))
    }

    @Test
    fun onlyWhereTheGameWouldLetYou() {
        val mon = MonView(species = 25, name = "A", level = 5, hp = 10, maxHp = 10, status = "", types = emptyList(), iconAsset = null)
        val wild = SnapshotView(connected = true, inBattle = true, isTrainerBattle = false, battleOpponent = listOf(mon), party = listOf(mon))
        assertTrue(BattleBall.canThrow(wild))
        assertFalse(BattleBall.canThrow(wild.copy(isTrainerBattle = true)))
        assertFalse(BattleBall.canThrow(wild.copy(battleOpponent = listOf(mon, mon))))
        assertFalse(BattleBall.canThrow(wild.copy(inBattle = false)))
        val full = wild.copy(party = List(6) { mon })
        assertFalse(BattleBall.canThrow(full))
        assertTrue(BattleBall.canThrow(full.copy(pcBoxes = BoxesView(0, listOf(BoxView("1", 0, List(30) { if (it == 29) null else mon }))))))
        assertFalse(BattleBall.canThrow(full.copy(pcBoxes = BoxesView(0, listOf(BoxView("1", 0, List(30) { mon }))))))
    }
}
