package com.pokedaisy.app.companion.data.save

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class Gen3SaveFileTest {

    private fun parse(bytes: ByteArray, format: Gen3SaveFormat): Gen3SaveFile =
        when (val p = Gen3SaveFile.parse(bytes, format)) {
            is Gen3SaveFile.Parsed.Ok -> p.save
            is Gen3SaveFile.Parsed.Failed -> throw AssertionError(p.reason)
        }

    private fun twoSlots(format: Gen3SaveFormat, newer: Int = 0, counter: Long = 41, rotation: Int = 3): ByteArray {
        val (a2, a1, aS) = SyntheticSave.blocks(format, seed = 1)
        val (b2, b1, bS) = SyntheticSave.blocks(format, seed = 2)
        val new = SyntheticSave.Slot(counter, rotation, a2, a1, aS)
        val old = SyntheticSave.Slot(counter - 1, (rotation + 13) % 14, b2, b1, bS)
        return if (newer == 0) SyntheticSave.file(format, new, old) else SyntheticSave.file(format, old, new)
    }

    @Test
    fun readsTheNewerSlotOfEveryFormat() {
        for (format in Gen3SaveFormat.entries) for (newer in 0..1) {
            val save = parse(twoSlots(format, newer), format)
            assertEquals(format.label, newer, save.slot)
            assertEquals(41L, save.counter)
            assertEquals(3, save.rotation)
            assertEquals(Gen3SaveFile.SlotStatus.OK, save.otherSlot)
            val data = Gen3SaveData(save)
            assertNull(data.mismatch())
            assertEquals("RED", data.trainerName)
            assertEquals(12345, data.trainerId)
            assertEquals(54321, data.secretId)
            assertEquals("12:34", data.playTime)
            assertEquals(3000L, data.money)
            assertEquals(listOf(6, 25), data.party.map { it.mon.species })
            assertEquals(listOf(50, 10), data.party.map { it.level })
            assertEquals(1, data.currentBox)
            assertEquals("BOX1", data.boxes[0].name)
            assertEquals("BOX14", data.boxes[13].name)
            assertEquals(listOf(1, 4, 7), data.boxes[0].mons.filter { !it.isEmpty }.map { it.species })
            // seed 1 = the newer slot's personalities.
            assertEquals(0x101L, data.boxes[0].mons[0].personality)
        }
    }

    @Test
    fun counterWrapsLikeTheGame() {
        val f = Gen3SaveFormat.EMERALD
        val (a2, a1, aS) = SyntheticSave.blocks(f, seed = 1)
        val (b2, b1, bS) = SyntheticSave.blocks(f, seed = 2)
        // 0 is newer than 0xFFFFFFFF.
        val bytes = SyntheticSave.file(f, SyntheticSave.Slot(0xFFFFFFFFL, 0, b2, b1, bS), SyntheticSave.Slot(0, 1, a2, a1, aS))
        val save = parse(bytes, f)
        assertEquals(1, save.slot)
        assertEquals(0L, save.counter)
        val edited = parse(save.withBlocks(save.saveBlock2, save.saveBlock1, save.storage), f)
        assertEquals(0, edited.slot)
        assertEquals(1L, edited.counter)
    }

    @Test
    fun aDamagedSlotFallsBackAndTwoFail() {
        val f = Gen3SaveFormat.FIRERED_LEAFGREEN
        val bytes = twoSlots(f, newer = 0)
        // One byte of the newer slot's storage: that sector's checksum fails, so the game loads the older slot.
        val damaged = bytes.copyOf().also { it[5 * 0x1000 + 100] = (it[5 * 0x1000 + 100] + 1).toByte() }
        val save = parse(damaged, f)
        assertEquals(1, save.slot)
        assertEquals(40L, save.counter)
        assertEquals(Gen3SaveFile.SlotStatus.CORRUPT, save.otherSlot)
        // Both damaged: nothing to load.
        val both = damaged.copyOf().also { it[14 * 0x1000 + 100] = (it[14 * 0x1000 + 100] + 1).toByte() }
        assertTrue(Gen3SaveFile.parse(both, f) is Gen3SaveFile.Parsed.Failed)
    }

    @Test
    fun emptyAndShortFilesAreRefused() {
        val f = Gen3SaveFormat.EMERALD
        val empty = Gen3SaveFile.parse(ByteArray(0x20000) { 0xFF.toByte() }, f)
        assertTrue((empty as Gen3SaveFile.Parsed.Failed).reason.contains("empty"))
        assertTrue(Gen3SaveFile.parse(ByteArray(0x10000), f) is Gen3SaveFile.Parsed.Failed)
    }

    @Test
    fun aFreshSaveWithOneSlotCanBeEdited() {
        val f = Gen3SaveFormat.EMERALD
        val (s2, s1, st) = SyntheticSave.blocks(f)
        val save = parse(SyntheticSave.file(f, SyntheticSave.Slot(0, 0, s2, s1, st), null), f)
        assertEquals(Gen3SaveFile.SlotStatus.EMPTY, save.otherSlot)
        val edited = parse(save.withBlocks(s2, s1, st), f)
        assertEquals(1, edited.slot)
        assertEquals(1L, edited.counter)
        assertEquals(Gen3SaveFile.SlotStatus.OK, edited.otherSlot)
    }

    @Test
    fun writingLooksLikeTheGameSaving() {
        for (format in Gen3SaveFormat.entries) {
            val before = twoSlots(format, newer = 1, rotation = 13)
            val save = parse(before, format)
            val after = save.withBlocks(save.saveBlock2, save.saveBlock1, save.storage)
            assertEquals(before.size, after.size)
            val again = parse(after, format)
            assertEquals(0, again.slot)
            assertEquals(42L, again.counter)
            assertEquals(0, again.rotation)   // 13 + 1 wraps
            assertArrayEquals(save.saveBlock2, again.saveBlock2)
            assertArrayEquals(save.saveBlock1, again.saveBlock1)
            assertArrayEquals(save.storage, again.storage)
            // The slot that was loaded, the extra sectors and the RTC footer are untouched.
            val slot1 = 14 * 0x1000 until 28 * 0x1000
            for (i in slot1) assertEquals(before[i], after[i])
            for (i in 28 * 0x1000 until before.size) assertEquals(before[i], after[i])
            // Every sector of the new slot: signature, counter, a valid id; unused bytes zero as the game leaves them.
            for (s in 0 until 14) {
                val o = s * 0x1000
                assertEquals(0x08012025L, SyntheticSave.rd32(after, o + 0xFF8))
                assertEquals(42L, SyntheticSave.rd32(after, o + 0xFFC))
                val id = (after[o + 0xFF4].toInt() and 0xFF)
                for (i in format.chunkSize(id) until 0xFF4) assertEquals(0.toByte(), after[o + i])
            }
        }
    }

    @Test
    fun theGameStillHasThePreviousSaveToFallBackTo() {
        val f = Gen3SaveFormat.EMERALD
        val save = parse(twoSlots(f, newer = 0), f)
        val editor = Gen3SaveEditor(save)
        editor.move(BoxSlot(0, 0), BoxSlot(5, 29))
        val after = editor.build()
        // Damage the new slot: the game would load the save as it was before the edit.
        val broken = after.copyOf().also { it[14 * 0x1000 + 10] = (it[14 * 0x1000 + 10] + 1).toByte() }
        val fallback = parse(broken, f)
        assertEquals(0, fallback.slot)
        assertArrayEquals(save.storage, fallback.storage)
    }

    @Test
    fun movingInThePcMovesTheRecordByteForByte() {
        val f = Gen3SaveFormat.FIRERED_LEAFGREEN
        val save = parse(twoSlots(f), f)
        val editor = Gen3SaveEditor(save)
        assertFalse(editor.changed)
        val record = save.storage.copyOfRange(4, 84)
        editor.move(BoxSlot(0, 0), BoxSlot(13, 29))
        assertTrue(editor.changed)
        // Swapping onto an occupied slot swaps the two.
        editor.move(BoxSlot(0, 1), BoxSlot(1, 0))
        val again = Gen3SaveData(parse(editor.build(), f))
        assertTrue(again.mon(BoxSlot(0, 0)).isEmpty)
        assertEquals(150, again.mon(BoxSlot(0, 1)).species)
        assertEquals(4, again.mon(BoxSlot(1, 0)).species)
        assertEquals(1, again.mon(BoxSlot(13, 29)).species)
        val off = Gen3SaveData.recordOff(BoxSlot(13, 29))
        assertArrayEquals(record, parse(editor.build(), f).storage.copyOfRange(off, off + 80))
        // The party and trainer are untouched.
        assertArrayEquals(save.saveBlock1, parse(editor.build(), f).saveBlock1)
        assertArrayEquals(save.saveBlock2, parse(editor.build(), f).saveBlock2)
    }

    @Test
    fun editsChainAcrossSaves() {
        val f = Gen3SaveFormat.RUBY_SAPPHIRE
        var bytes = twoSlots(f)
        repeat(5) { n ->
            val save = parse(bytes, f)
            val editor = Gen3SaveEditor(save)
            editor.move(BoxSlot(0, 0), BoxSlot(0, 10))
            editor.move(BoxSlot(0, 10), BoxSlot(0, 0))
            bytes = editor.build()
            assertEquals(42L + n, parse(bytes, f).counter)
        }
    }

    @Test
    fun aBadEggOrAnEmptySlotDoesNotMove() {
        val f = Gen3SaveFormat.EMERALD
        val (s2, s1, st) = SyntheticSave.blocks(f)
        // Corrupt one record's encrypted data: its checksum no longer matches (a Bad Egg in game).
        st[4 + 2 * 80 + 0x30] = (st[4 + 2 * 80 + 0x30] + 1).toByte()
        val save = parse(SyntheticSave.file(f, SyntheticSave.Slot(3, 0, s2, s1, st), null), f)
        val data = Gen3SaveData(save)
        assertTrue(data.mon(BoxSlot(0, 2)).isBadEgg)
        assertNull(data.mismatch())
        val editor = Gen3SaveEditor(save)
        for ((from, to) in listOf(BoxSlot(0, 2) to BoxSlot(1, 5), BoxSlot(2, 0) to BoxSlot(1, 5), BoxSlot(0, 0) to BoxSlot(0, 2))) {
            try { editor.move(from, to); fail("moved $from -> $to") } catch (_: IllegalArgumentException) {}
        }
        assertFalse(editor.changed)
        // The unreadable record re-encodes to its own bytes.
        assertArrayEquals(st.copyOfRange(4 + 160, 4 + 240), data.mon(BoxSlot(0, 2)).encode())
    }

    @Test
    fun recordsDecodeAndEncodeBack() {
        for (p in 0L until 48L) {
            val personality = 0x9E37_79B9L * (p + 1) and 0xFFFFFFFFL
            val bytes = SyntheticSave.mon(
                personality, otId = 0x1357_9BDFL, species = 1 + p.toInt() * 8, exp = 1000L * p, item = p.toInt(),
                moves = intArrayOf(1, 2, 3, 4), isEgg = p % 7 == 0L, fateful = p % 5 == 0L,
            )
            val mon = Gen3BoxMon.read(bytes, 0)
            assertTrue(mon.checksumOk)
            assertEquals(1 + p.toInt() * 8, mon.species)
            assertEquals(p.toInt(), mon.heldItem)
            assertEquals(1000L * p, mon.exp)
            assertArrayEquals(intArrayOf(1, 2, 3, 4), mon.moves)
            assertArrayEquals(intArrayOf(31, 0, 15, 7, 1, 30), mon.ivs)
            assertEquals(p % 7 == 0L, mon.isEgg)
            assertEquals(p % 5 == 0L, mon.fatefulEncounter)
            assertEquals(88, mon.metLocation)
            assertEquals(5, mon.metLevel)
            assertEquals(4, mon.metGame)
            assertEquals(4, mon.ball)
            assertEquals(70, mon.friendship)
            assertArrayEquals(bytes, mon.encode())
        }
        assertTrue(Gen3BoxMon.read(ByteArray(80), 0).isEmpty)
    }

    @Test
    fun levelComesFromExperience() {
        // Bulbasaur (medium slow): 135 EXP is level 5, 134 still 4.
        assertEquals(5, Gen3BoxMon.levelFor(1, 135))
        assertEquals(4, Gen3BoxMon.levelFor(1, 134))
        assertEquals(100, Gen3BoxMon.levelFor(1, 10_000_000))
    }

    /** The slots a party shrank from hold ZeroMonData's record: zeroes but the mail field, MAIL_NONE (0xFF) - every real
     * save whose party ever got smaller (a player's Emerald save was refused). Anything else there still refuses it. */
    @Test
    fun aPartySlotEmptiedByTheGameIsAccepted() {
        val f = Gen3SaveFormat.EMERALD
        val (s2, s1, st) = SyntheticSave.blocks(f)
        s1[f.partyCountOff] = 1
        for (i in 1 until 6) {
            for (b in 0 until 100) s1[f.partyOff + i * 100 + b] = 0
            s1[f.partyOff + i * 100 + 0x55] = 0xFF.toByte()
        }
        val emptied = parse(SyntheticSave.file(f, SyntheticSave.Slot(3, 0, s2, s1, st), null), f)
        assertNull(Gen3SaveData(emptied).mismatch())
        assertEquals(1, Gen3SaveData(emptied).party.size)
        s1[f.partyOff + 2 * 100 + 0x56] = 0x0A
        val other = parse(SyntheticSave.file(f, SyntheticSave.Slot(3, 0, s2, s1, st), null), f)
        assertNotNull(Gen3SaveData(other).mismatch())
    }

    @Test
    fun anotherGamesLayoutIsNoticed() {
        // An Emerald save read as FireRed: the party sits elsewhere, so the check refuses it.
        val bytes = twoSlots(Gen3SaveFormat.EMERALD)
        val asFrlg = Gen3SaveFile.parse(bytes, Gen3SaveFormat.FIRERED_LEAFGREEN)
        // Its chunk sizes differ too, so FRLG's checksums may already fail - either way it's not offered.
        if (asFrlg is Gen3SaveFile.Parsed.Ok) assertNotNull(Gen3SaveData(asFrlg.save).mismatch())
        // Boxes full of noise (a hack's own record format) are noticed as well.
        val f = Gen3SaveFormat.FIRERED_LEAFGREEN
        val (s2, s1, st) = SyntheticSave.blocks(f)
        val rnd = kotlin.random.Random(3)
        for (i in 4 until 4 + 20 * 80) st[i] = rnd.nextInt().toByte()
        val save = parse(SyntheticSave.file(f, SyntheticSave.Slot(9, 2, s2, s1, st), null), f)
        assertNotNull(Gen3SaveData(save).mismatch())
    }

    /** pokeemerald-expansion's records (Emerald Seaglass, GitHub #1167) keep 21 bits of experience with a nickname's 11th
     * letter above them: read as retail, more than any level 100 has. Such a save is another engine's, refused. */
    @Test
    fun experiencePastLevel100MeansAnotherEngine() {
        val f = Gen3SaveFormat.EMERALD
        fun withPcMon(exp: Long): Gen3SaveFile {
            val (s2, s1, st) = SyntheticSave.blocks(f)
            SyntheticSave.mon(0x1234_5678L, 0x0BAD_F00DL, 123, exp, nickname = "SCYTHER").copyInto(st, 4)
            return parse(SyntheticSave.file(f, SyntheticSave.Slot(3, 0, s2, s1, st), null), f)
        }
        assertNull(Gen3SaveData(withPcMon(1_000_000)).mismatch())
        assertNull(Gen3SaveData(withPcMon(Gen3SaveData.MAX_EXP)).mismatch())
        // Its 11th letter 0xFF over 135 EXP (an expansion record's u32 at growth +4).
        assertNotNull(Gen3SaveData(withPcMon(0x1FE00087L)).mismatch())
    }

    @Test
    fun aTamperedWriteIsCaught() {
        val f = Gen3SaveFormat.EMERALD
        val save = parse(twoSlots(f), f)
        val good = save.withBlocks(save.saveBlock2, save.saveBlock1, save.storage)
        assertNull(save.verifyWritten(good, save.saveBlock2, save.saveBlock1, save.storage))
        // A byte outside the new slot (Hall of Fame) changing is reported.
        val hof = good.copyOf().also { it[29 * 0x1000] = (it[29 * 0x1000] + 1).toByte() }
        assertNotNull(save.verifyWritten(hof, save.saveBlock2, save.saveBlock1, save.storage))
        // So is a block that isn't what was asked for.
        val other = save.storage.copyOf().also { it[100] = 1 }
        assertNotNull(save.verifyWritten(good, save.saveBlock2, save.saveBlock1, other))
    }

    @Test
    fun formatsFollowTheGameCode() {
        assertEquals(Gen3SaveFormat.FIRERED_LEAFGREEN, Gen3SaveFormat.forGameCode("BPRE"))
        assertEquals(Gen3SaveFormat.FIRERED_LEAFGREEN, Gen3SaveFormat.forGameCode("BPGF"))
        assertEquals(Gen3SaveFormat.EMERALD, Gen3SaveFormat.forGameCode("BPED"))
        assertEquals(Gen3SaveFormat.RUBY_SAPPHIRE, Gen3SaveFormat.forGameCode("AXPE"))
        assertNull(Gen3SaveFormat.forGameCode("BPEJ"))
        assertNull(Gen3SaveFormat.forGameCode("PUVV"))
        assertNull(Gen3SaveFormat.forGameCode(null))
        // sSaveSlotLayout's chunks: storage's last is 0x7D0 bytes, SaveBlock1's 0xEE8 on FireRed.
        assertEquals(0x7D0, Gen3SaveFormat.EMERALD.chunkSize(13))
        assertEquals(0xEE8, Gen3SaveFormat.FIRERED_LEAFGREEN.chunkSize(4))
        assertEquals(0xF80, Gen3SaveFormat.RUBY_SAPPHIRE.chunkSize(2))
    }

    @Test
    fun aSaveBeforeTheStarterOrWithAnEggIsStillTheGames() {
        val f = Gen3SaveFormat.EMERALD
        // No Pokémon yet: count 0, the party zeroed.
        val (a2, a1, aS) = SyntheticSave.blocks(f)
        a1[f.partyCountOff] = 0
        a1.fill(0, f.partyOff, f.partyOff + 600)
        assertNull(Gen3SaveData(parse(SyntheticSave.file(f, SyntheticSave.Slot(1, 0, a2, a1, aS), null), f)).mismatch())
        // An egg in the party: its level byte isn't checked.
        val (b2, b1, bS) = SyntheticSave.blocks(f)
        SyntheticSave.mon(0x55L, 0x66L, species = 25, exp = 125, isEgg = true).copyInto(b1, f.partyOff + 100)
        b1[f.partyOff + 100 + 0x54] = 0
        assertNull(Gen3SaveData(parse(SyntheticSave.file(f, SyntheticSave.Slot(1, 0, b2, b1, bS), null), f)).mismatch())
    }
}
