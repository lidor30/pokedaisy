package com.pokedaisy.app.companion.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import java.util.zip.CRC32

/** SWAP's tables ([boxSwapFor]): each one's code where its retail ROM has it, and only for the games whose boxes are vanilla's. */
class BoxSwapTest {
    private fun retail(cfg: NativeConfig) = cfg.copy(pokemonStoragePtr = cfg.saveBlock2Ptr + 4)

    @Test fun eachRetailGameGetsItsOwnTable() {
        assertSame(BOX_SWAP_FIRERED_REV1, boxSwapFor(FIELD_ITEMS_FIRERED_REV1, retail(NATIVE_FIRERED_REV1)))
        assertSame(BOX_SWAP_LEAFGREEN_REV1, boxSwapFor(FIELD_ITEMS_LEAFGREEN_REV1, retail(NATIVE_LEAFGREEN_REV1)))
        assertSame(BOX_SWAP_LEAFGREEN_REV0, boxSwapFor(FIELD_ITEMS_LEAFGREEN_REV0, retail(NATIVE_LEAFGREEN_REV0)))
        assertSame(BOX_SWAP_EMERALD, boxSwapFor(FIELD_ITEMS_EMERALD, retail(NATIVE_EMERALD_RETAIL)))
    }

    /** No BOXES read (a hack or port copying the config) or other boxes (CFRU's compressed ones): no SWAP. */
    @Test fun nothingWithoutVanillaBoxes() {
        assertNull(boxSwapFor(FIELD_ITEMS_FIRERED_REV1, NATIVE_FIRERED_REV1))
        assertNull(boxSwapFor(FIELD_ITEMS_FIRERED_REV1, null))
        assertNull(boxSwapFor(FIELD_ITEMS_FIRERED_REV1, retail(NATIVE_FIRERED_REV1).copy(pcStorage = UNBOUND_PC_STORAGE)))
        assertNull(boxSwapFor(FIELD_ITEMS_PORTS["BPRE0"], NATIVE_UNBOUND_WITH_DEX))
    }

    /** The code each table's CRCs pin is in its retail ROM at those addresses (the same check the swap makes first). */
    @Test fun codeMatchesTheRetailRoms() {
        for ((file, fns) in listOf(
            "Pokemon - FireRed Version (USA, Europe) (Rev 1).gba" to BOX_SWAP_FIRERED_REV1,
            "Pokemon - LeafGreen Version (USA, Europe) (Rev 1).gba" to BOX_SWAP_LEAFGREEN_REV1,
            "Pokemon - LeafGreen Version (USA).gba" to BOX_SWAP_LEAFGREEN_REV0,
            "Pokemon - Emerald Version (USA, Europe).gba" to BOX_SWAP_EMERALD,
        )) {
            val rom = RomFileReader.load(RETAIL_ROM_DIR + file) ?: continue
            val crcs = listOf(fns.boxMonToMon, fns.boxMonRestorePP, fns.getBoxMonData).map { a ->
                CRC32().apply { update(rom.readCoreMemory(a, 32)) }.value
            }
            assertEquals(file, fns.codeCrcs, crcs)
        }
    }
}
