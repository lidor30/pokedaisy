package com.pokedaisy.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

/**
 * [CompanionSupport.looksPokemon] (GitHub #943): the unsupported ROMs a linked folder lists, by file name or header title,
 * on headers built here (no ROM needed).
 */
class CompanionSupportPokemonTest {
    private val dir = Files.createTempDirectory("roms").toFile().apply { deleteOnExit() }

    private fun rom(name: String, size: Int, fill: (ByteArray) -> Unit) =
        java.io.File(dir, name).apply { writeBytes(ByteArray(size).also(fill)); deleteOnExit() }

    private fun put(b: ByteArray, at: Int, s: String) = s.toByteArray(Charsets.US_ASCII).copyInto(b, at)

    /** A Game Boy header: the logo's first bytes at 0x104, [title] at 0x134. */
    private fun gb(name: String, title: String) = rom(name, 0x8000) { b ->
        byteArrayOf(0xCE.toByte(), 0xED.toByte(), 0x66, 0x66).copyInto(b, 0x104)
        put(b, 0x134, title)
    }

    /** A DS header: [title] at 0, the logo's CRC (CF56) at 0x15C. */
    private fun nds(name: String, title: String) = rom(name, 0x1000) { b ->
        put(b, 0, title)
        b[0x15C] = 0x56; b[0x15D] = 0xCF.toByte()
    }

    /** A GBA header: [title] at 0xA0, 0x96 at 0xB2. */
    private fun gba(name: String, title: String) = rom(name, 0x1000) { b ->
        put(b, 0xA0, title)
        b[0xB2] = 0x96.toByte()
    }

    @Test fun byHeaderTitle() {
        assertTrue(CompanionSupport.looksPokemon(gb("brown.gb", "POKEMON RED")))
        assertTrue(CompanionSupport.looksPokemon(gb("crystal-hack.gbc", "PM_CRYSTAL")))
        assertTrue(CompanionSupport.looksPokemon(gb("tcg.gbc", "POKECARD")))
        assertTrue(CompanionSupport.looksPokemon(nds("ranger.nds", "POKEMON RANG")))
        assertTrue(CompanionSupport.looksPokemon(gba("hack.gba", "POKEMON FIRE")))
    }

    @Test fun byFileName() {
        assertTrue(CompanionSupport.looksPokemon(gb("Pokemon Ambrosia.gbc", "AMBROSIA")))
        assertTrue(CompanionSupport.looksPokemon(nds("Pokémon Light Platinum.nds", "LIGHT PLAT")))
        assertTrue(CompanionSupport.looksPokemon(gb("Pocket Monsters - Midori.gb", "MIDORI")))
    }

    /** A zip holding [entry] with [bytes]. */
    private fun zip(name: String, entry: String, bytes: ByteArray) = java.io.File(dir, name).apply {
        java.util.zip.ZipOutputStream(outputStream()).use { z ->
            z.putNextEntry(java.util.zip.ZipEntry(entry)); z.write(bytes); z.closeEntry()
        }
        deleteOnExit()
    }

    /** A Pokémon game of another console zipped (N64's Pokemon Snap / Stadium): nothing in it the app plays, so the
     * library leaves it out, whatever its name (a player's report: they showed up under GBA). A zipped GB game still counts. */
    @Test fun archivesWithNoGameOfOursStayOut() {
        val n64 = ByteArray(0x1000).also { b -> byteArrayOf(0x80.toByte(), 0x37, 0x12, 0x40).copyInto(b, 0); put(b, 0x20, "POKEMON SNAP") }
        assertFalse(CompanionSupport.looksPokemon(zip("Pokemon Snap (USA).zip", "Pokemon Snap (USA).z64", n64)))
        assertTrue(CompanionSupport.looksPokemon(zip("Pokemon Ambrosia.zip", "Pokemon Ambrosia.gbc", gb("ambrosia-src.gbc", "AMBROSIA").readBytes())))
    }

    @Test fun otherGamesStayOut() {
        assertFalse(CompanionSupport.looksPokemon(gb("Zelda.gb", "ZELDA")))
        assertFalse(CompanionSupport.looksPokemon(nds("Mario Kart DS.nds", "MARIO KART")))
        assertFalse(CompanionSupport.looksPokemon(gba("Metroid Fusion.gba", "METROID4USA")))
    }
}
