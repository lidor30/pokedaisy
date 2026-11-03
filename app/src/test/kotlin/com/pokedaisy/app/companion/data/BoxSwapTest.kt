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

    /** The other languages (and English FireRed rev 0): each port's own table, under its bag table's key, with its config's
     * storage; Japanese has none (its code was rebuilt). Emerald's languages run their hand-written configs. */
    @Test fun eachPortGetsItsOwnTable() {
        val emerald = mapOf("BPED0" to NATIVE_EMERALD_DE, "BPEF0" to NATIVE_EMERALD_FR, "BPEI0" to NATIVE_EMERALD_IT, "BPES0" to NATIVE_EMERALD_ES)
        assertEquals(13, BOX_SWAP_PORTS.size)
        for ((key, fns) in BOX_SWAP_PORTS) {
            val cfg = emerald[key] ?: RETAIL_PORTS[key]?.invoke() ?: retail(NATIVE_FIRERED_REV0)
            assertSame(key, fns, boxSwapFor(FIELD_ITEMS_PORTS[key], cfg))
        }
        for (key in listOf("BPRJ0", "BPRJ1", "BPGJ0", "BPGJ1", "BPEJ0")) assertNull(key, BOX_SWAP_PORTS[key])
        // A port's table never goes with another storage (a ROM under that code whose hash isn't retail's: the Poller zeroes it).
        assertNull(boxSwapFor(FIELD_ITEMS_PORTS["BPRD0"], RETAIL_PORTS.getValue("BPRD0")().copy(pokemonStoragePtr = 0)))
    }

    /** The hacks: rev 0's moves where they run rev 0's bag table on vanilla boxes (the in-place edits, checked headless with
     * scripts/verify_box_swap.py), none on CFRU's compressed boxes or Odyssey / Gaia's plaintext ones. */
    @Test fun hacksOnRetailCode() {
        val rev0 = FIELD_ITEMS_PORTS["BPRE0"]
        for (cfg in listOf(NATIVE_ULTRA_VIOLET, NATIVE_ROCKET_EDITION, NATIVE_ASH_GRAY, NATIVE_GHOST_GREY)) {
            assertSame(BOX_SWAP_PORTS["BPRE0"], boxSwapFor(rev0, cfg))
        }
        for (cfg in listOf(NATIVE_RADICAL_RED_V4_1, NATIVE_AMETHYST, NATIVE_GS_CHRONICLES, NATIVE_SWORD_SHIELD, NATIVE_ODYSSEY, NATIVE_GAIA_V3_2)) {
            assertNull(boxSwapFor(rev0, cfg))
        }
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
