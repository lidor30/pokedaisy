package com.pokedaisy.app.companion.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/** [M4aSongs] on the retail ROMs on this machine (skips without them). */
class M4aSongsTest {
    private fun songs(path: String): M4aSongs {
        val rom = File(path).takeIf { it.isFile }?.readBytes()
        assumeTrue(rom != null)
        return assertNotNullAnd(M4aSongs.locate(rom!!))
    }

    private fun <T> assertNotNullAnd(v: T?): T { assertNotNull(v); return v!! }

    @Test fun fireRed() {
        val s = songs(RomFileReader.FIRERED_REV1_PATH)
        assertEquals(0x081DD165L, s.songNumStart) // m4aSongNumStart, Thumb bit set
        assertEquals(297, s.songId("song_06d96f4")) // MUS_VS_TRAINER
        assertEquals(300, s.songId("song_06dd8a4")) // MUS_PALLET
        assertEquals(0x08172328L, s.idleLoop) // the first Thumb `b .`
        // The BGM-player songs (sound/song_table.inc's `song x, 0, 0`), not SEs like SE_SELECT (player 2).
        assertTrue(297 in s.bgmSongIds && 300 in s.bgmSongIds)
        assertFalse(5 in s.bgmSongIds)
    }

    @Test fun emerald() {
        val s = songs(RomFileReader.EMERALD_PATH)
        assertEquals(0x082E0131L, s.songNumStart)
        assertEquals(360, s.songId("song_09092f8")) // MUS_ROUTE110 (Route 114, see FfMusicKeyTest)
    }

    @Test fun emeraldRogueGccBuild() {
        // m4aSongNumStart compiled by gcc (the second signature); song 647 is the
        // hub's music - headless: forced on the render core after the title
        // music is up, it plays with the same header the live game's BGM shows.
        val s = songs(File(File(RomFileReader.FIRERED_REV1_PATH).parentFile, "Pokemon Emerald Rogue (v2.2.1-EX).gba").path)
        assertEquals(0x08009B41L, s.songNumStart)
        assertEquals(0x092D4258L, s.songTable)
        assertEquals(647, s.songId("song_1d50948"))
        assertEquals(0x08087824L, s.idleLoop)
    }

    @Test fun rechargedYellowGccBuild() {
        // Its gcc m4aSongNumStart; headless, Viridian City's BGM was song 526's header, and `call 0x08000B69 300`
        // switched the BGM player to song 300's. Its song ids follow Emerald's table: sFanfares[0] is 367.
        val dir = File(RomFileReader.FIRERED_REV1_PATH).parentFile
        val s = songs(File(dir, "Pokemon Recharged Yellow (v1.9.7).gba").path)
        assertEquals(0x08000B69L, s.songNumStart)
        assertEquals(0x08B7EC90L, s.songTable)
        assertEquals(526, s.songId("song_0e1da7c"))
        for (v in listOf("1.8.0", "1.9.0", "1.9.4", "1.9.7")) {
            assertTrue(v, isRechargedYellowRom(File(dir, "Pokemon Recharged Yellow (v$v).gba").readBytes().copyOf(0x200)))
        }
        assertFalse(isRechargedYellowRom(File(RomFileReader.FIRERED_REV1_PATH).readBytes().copyOf(0x200)))
    }

    @Test fun newerExpansionBuilds() {
        // m4aSongNumStart with expansion's ident check (the third signature).
        val dir = File(RomFileReader.FIRERED_REV1_PATH).parentFile
        val lazarus = songs(File(dir, "Pokemon Lazarus (v2.0).gba").path)
        assertEquals(0x08009FA1L, lazarus.songNumStart)
        assertEquals(0x08FF5F48L, lazarus.songTable)
        val soulGold = songs(File(dir, "Pokemon SoulGold (v1.1.4).gba").path)
        assertEquals(0x095FD42CL, soulGold.songTable)
        val soulGold12 = songs(File(dir, "Pokemon SoulGold (v1.2).gba").path)
        assertEquals(0x081A2981L, soulGold12.songNumStart)
        assertEquals(0x096086FCL, soulGold12.songTable)
        val soulGold12b = songs(File(dir, "Pokemon SoulGold (v1.2) (Alt 1).gba").path)
        assertEquals(0x081A2915L, soulGold12b.songNumStart)
        assertEquals(0x09608658L, soulGold12b.songTable)
        val soulGold121 = songs(File(dir, "Pokemon SoulGold (v1.2.1).gba").path)
        assertEquals(0x081A5441L, soulGold121.songNumStart)
        assertEquals(0x09642470L, soulGold121.songTable)
    }

    @Test fun heartAndSoulAlternateSoundtrack() {
        // Expansion's m4aSongNumStart plus a second argument: set, it plays the song's entry in
        // an alternate table instead (the fourth signature). Headless: the title screen plays
        // song 559 from gSongTable; `call 0x0817d4a9 559 1` switched the BGM to 0x09E2BE10.
        val s = songs(File(File(RomFileReader.FIRERED_REV1_PATH).parentFile, "Pokemon Heart and Soul (v2.0.6).gba").path)
        assertEquals(0x0817D4A9L, s.songNumStart)
        assertEquals(0x09356068L, s.songTable)
        assertEquals(559, s.songId("song_1d3a6e8"))
        val alt = s.songId("song_1e2be10")!!
        assertEquals(559 or M4aSongs.ALT, alt)
        assertEquals(559, M4aSongs.number(alt))
        assertEquals(1, M4aSongs.alt(alt))
        assertEquals(0, M4aSongs.alt(559))
        // Both soundtracks are rendered in the background.
        assertTrue(559 in s.bgmSongIds && alt in s.bgmSongIds)
    }

    @Test fun superMariomon() {
        // Agbcc's m4aSongNumStart in every release; the field's music in WORLD 1-1 (headless
        // mgba_dump `bgm`: the BGM player's song header) is a BGM-player song of its table.
        val dir = File(RomFileReader.FIRERED_REV1_PATH).parentFile
        for ((file, header) in listOf(
            "Super Mariomon (v1.5.2 Anniversary).gba" to "song_0f8ecc8", "Super Mariomon (v1.5.1 Anniversary).gba" to "song_0f8e7e0",
            "Super Mariomon (v1.3).gba" to "song_0f6c124", "Super Mariomon (v1.2.2).gba" to "song_0f6c11c", "Super Mariomon (v1.2.1).gba" to "song_0f6c13c",
        )) {
            val f = File(dir, file)
            if (!f.isFile) continue
            val s = assertNotNullAnd(M4aSongs.locate(f.readBytes()))
            val id = s.songId(header)
            assertNotNull(file, id)
            assertTrue(file, id!! in s.bgmSongIds)
        }
    }
}
