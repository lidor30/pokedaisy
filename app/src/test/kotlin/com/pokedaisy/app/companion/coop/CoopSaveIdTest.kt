package com.pokedaisy.app.companion.coop

import com.pokedaisy.app.companion.data.save.Gen3SaveFormat
import com.pokedaisy.app.companion.data.save.SyntheticSave
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class CoopSaveIdTest {
    @get:Rule val tmp = TemporaryFolder()

    private val may = byteArrayOf(0xC7.toByte(), 0xBB.toByte(), 0xD3.toByte(), 0xFF.toByte())

    private fun emeraldFile(name: String = "emerald.sav", nameBytes: ByteArray = may): File {
        val f = Gen3SaveFormat.EMERALD
        val (sb2, sb1, storage) = SyntheticSave.blocks(f)
        sb2.fill(0, 0, 8)
        nameBytes.copyInto(sb2, 0)
        sb2[0x0A] = 0x34; sb2[0x0B] = 0x12
        sb2[0x0C] = 0xCD.toByte(); sb2[0x0D] = 0xAB.toByte()
        val bytes = SyntheticSave.file(f, SyntheticSave.Slot(5, 0, sb2, sb1, storage), null)
        return tmp.newFile(name).also { it.writeBytes(bytes) }
    }

    @Test fun readsIdFromSave() {
        val id = CoopSaveId.read(emeraldFile())!!
        assertEquals(0x1234, id.trainerId)
        assertEquals(0xABCD, id.secretId)
        assertEquals("1234abcd-", id.key.take(9))
        assertEquals("c7bbd3ff00000000", id.key.drop(9))
    }

    @Test fun sameIdAfterMovingTheFile() {
        val a = emeraldFile("a.sav")
        val dir = tmp.newFolder("other")
        val b = File(dir, "renamed.srm").also { a.copyTo(it) }
        assertEquals(CoopSaveId.read(a), CoopSaveId.read(b))
    }

    @Test fun emptyOrForeignSaveIsNull() {
        assertNull(CoopSaveId.read(tmp.newFile("zero.sav").also { it.writeBytes(ByteArray(0x20000)) }))
        assertNull(CoopSaveId.read(File(tmp.root, "missing.sav")))
        val fr = Gen3SaveFormat.FIRERED_LEAFGREEN
        val (sb2, sb1, storage) = SyntheticSave.blocks(fr)
        val frBytes = SyntheticSave.file(fr, SyntheticSave.Slot(5, 0, sb2, sb1, storage), null)
        // A real FireRed save fills its last SaveBlock1 sector; Emerald's longer chunk then sums other bytes and fails its checksum.
        frBytes[4 * 0x1000 + fr.chunkSize(4) + 1] = 0x55
        assertNull(CoopSaveId.read(tmp.newFile("fr.sav").also { it.writeBytes(frBytes) }))
    }

    @Test fun emptyTrainerNameIsNull() {
        assertNull(CoopSaveId.read(emeraldFile("noname.sav", byteArrayOf(0xFF.toByte()))))
    }

    @Test fun fromHelloIsTheHellosSave() {
        val id = CoopSaveId.read(emeraldFile())!!
        assertNotNull(id)
        assertEquals(id, CoopSaveId(0x1234, 0xABCD, id.name))
    }
}
