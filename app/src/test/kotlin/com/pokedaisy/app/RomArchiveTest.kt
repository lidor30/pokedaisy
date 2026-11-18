package com.pokedaisy.app

import org.apache.commons.compress.archivers.sevenz.SevenZOutputFile
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.random.Random

class RomArchiveTest {

    @get:Rule
    val tmp = TemporaryFolder()

    /** A 1 MiB stand-in for FireRed rev 1: random bytes under a real-looking header. */
    private val rom: ByteArray = Random(3).nextBytes(1 shl 20).also { b ->
        "POKEMON FIRE".toByteArray().copyInto(b, 0xA0)
        "BPRE".toByteArray().copyInto(b, 0xAC)
        b[0xB2] = 0x96.toByte()
        b[0xBC] = 1
    }

    private fun plain(): File = tmp.newFile("FireRed.gba").apply { writeBytes(rom) }

    @Test fun zippedDsAchievementsHashTheContainedCart() {
        val bytes = ByteArray(0x1000)
        bytes[0x15C] = 0x56; bytes[0x15D] = 0xCF.toByte()
        val plain = tmp.newFile("Diagnostic.nds").apply { writeBytes(bytes) }
        val archive = zip("Diagnostic.zip", "Diagnostic.nds" to bytes)
        assertEquals(18, RomIdentity.raConsole(archive))
        assertEquals(RomIdentity.ndsRaHash(plain), RomIdentity.raHash(archive))
    }

    private fun zip(name: String, vararg entries: Pair<String, ByteArray>): File =
        File(tmp.root, name).apply {
            ZipOutputStream(outputStream()).use { z ->
                for ((n, bytes) in entries) {
                    z.putNextEntry(ZipEntry(n)); z.write(bytes); z.closeEntry()
                }
            }
        }

    private fun sevenZ(name: String, vararg entries: Pair<String, ByteArray>): File =
        File(tmp.root, name).apply {
            SevenZOutputFile(this).use { z ->   // LZMA2, 7-Zip's default
                for ((n, bytes) in entries) {
                    val f = tmp.newFile().apply { writeBytes(bytes) }
                    z.putArchiveEntry(z.createArchiveEntry(f, n)); z.write(bytes); z.closeArchiveEntry()
                }
            }
        }

    private fun assertReadsAsTheRom(archive: File) {
        val gba = plain()
        assertTrue(RomArchive.isArchive(archive))
        assertEquals(rom.size.toLong(), RomArchive.romSize(archive))
        assertArrayEquals(rom, RomArchive.open(archive).use { it.readBytes() })
        assertEquals("BPRE", RomIdentity.gameCode(archive))
        assertEquals("POKEMON FIRE", RomIdentity.headerTitle(archive))
        assertEquals(1, RomIdentity.revision(archive))
        assertEquals(RomIdentity.sha1(gba), RomIdentity.sha1(archive))
        assertEquals(RomIdentity.md5(gba), RomIdentity.md5(archive))
        assertEquals(SaveStates.crc32(gba), SaveStates.crc32(archive))
        // Judged by the ROM inside: this made-up FireRed isn't a dump the companion knows, in an archive or not.
        assertFalse(CompanionSupport.isSupported(gba))
        assertEquals(CompanionSupport.isSupported(gba), CompanionSupport.isSupported(archive))
        val out = File(tmp.root, "out.gba")
        assertEquals("FireRed.gba", RomArchive.extract(archive, out)?.name)
        assertArrayEquals(rom, out.readBytes())
    }

    /** The frontend entry point plays only cart images: a GBA header, in an archive too; not other files. */
    @Test fun looksLikeRom() {
        assertTrue(RomIdentity.looksLikeRom(plain()))
        assertTrue(RomIdentity.looksLikeRom(zip("FireRed.zip", "FireRed.gba" to rom)))
        assertFalse(RomIdentity.looksLikeRom(tmp.newFile("prefs.xml").apply { writeText("<map><string name=\"token\">x</string></map>") }))
        assertFalse(RomIdentity.looksLikeRom(tmp.newFile("photo.gba").apply { writeBytes(Random(5).nextBytes(4096).also { it[0xB2] = 0 }) }))
    }

    /** ASK FOR SUPPORT fills the website's game request form through the #hash: name, code, size, SHA-1 - for a zip, the ROM inside. */
    @Test fun supportRequestUrl() {
        val url = RomSupportRequest.url(zip("My Hack (v1.0).zip", "My Hack (v1.0).gba" to rom), "abc123")
        assertTrue(url, url.startsWith("https://pokedaisy.com/feedback/#game=My%20Hack%20%28v1.0%29&"))
        assertTrue(url, "game_code=BPRE%20%28rev%201%29" in url)
        assertTrue(url, "size=${1 shl 20}" in url)
        assertTrue(url, url.endsWith("sha1=abc123"))
    }

    @Test fun zipReadsAsItsRom() = assertReadsAsTheRom(zip("FireRed.zip", "FireRed.gba" to rom))

    @Test fun sevenZReadsAsItsRom() = assertReadsAsTheRom(sevenZ("FireRed.7z", "FireRed.gba" to rom))

    @Test fun plainRomStillReadsTheSame() {
        val gba = plain()
        assertFalse(RomArchive.isArchive(gba))
        assertEquals("BPRE", RomIdentity.gameCode(gba))
        assertEquals(1, RomIdentity.revision(gba))
        assertEquals("POKEMON FIRE", RomIdentity.headerTitle(gba))
        assertEquals(rom.size.toLong(), RomArchive.romSize(gba))
        assertSame(gba, RomArchive.playable(gba, tmp.root))
    }

    @Test fun picksTheRomOverJunk() {
        val junk = Random(5).nextBytes(4096)
        val archive = zip(
            "Pack.zip",
            "readme.txt" to "hi".toByteArray(),
            "__MACOSX/._FireRed.gba" to junk,
            "tiny.bin" to ByteArray(0x100),
            "extras/patch.bin" to junk,
            "roms/FireRed.gba" to rom,
        )
        assertEquals("roms/FireRed.gba", RomArchive.romEntry(archive)?.name)
        assertEquals("BPRE", RomIdentity.gameCode(archive))
    }

    @Test fun archiveWithoutRom() {
        val archive = zip("Notes.zip", "notes.txt" to ByteArray(4096))
        assertNull(RomArchive.romEntry(archive))
        assertEquals(-1L, RomArchive.romSize(archive))
        assertNull(RomIdentity.gameCode(archive))
        assertNull(RomIdentity.sha1(archive))
        assertFalse(CompanionSupport.isSupported(archive))
        assertNull(RomArchive.playable(archive, tmp.newFolder()))
        assertNull(RomArchive.extract(archive, File(tmp.root, "x.gba")))
        try {
            RomArchive.open(archive).close(); throw AssertionError("expected IOException")
        } catch (_: IOException) {
        }
    }

    @Test fun notAnArchiveIsUnsupportedNotACrash() {
        val bogus = File(tmp.root, "Broken.zip").apply { writeBytes(Random(9).nextBytes(2048)) }
        assertNull(RomArchive.romEntry(bogus))
        assertNull(RomIdentity.gameCode(bogus))
        assertFalse(CompanionSupport.isSupported(bogus))
    }

    @Test fun sniffsByContent() {
        val z = zip("a.zip", "FireRed.gba" to rom).copyTo(File(tmp.root, "import-1.gba"))
        val s = sevenZ("b.7z", "FireRed.gba" to rom).copyTo(File(tmp.root, "import-2.gba"))
        assertEquals(RomArchive.Format.ZIP, RomArchive.sniff(z))
        assertEquals(RomArchive.Format.SEVEN_Z, RomArchive.sniff(s))
        assertNull(RomArchive.sniff(plain()))
        // A copy named .gba still extracts once told its format.
        val out = File(tmp.root, "unpacked.gba")
        assertNotNull(RomArchive.extract(s, out, RomArchive.sniff(s)))
        assertArrayEquals(rom, out.readBytes())
    }

    @Test fun playableExtractsOnceUnderTheRomName() {
        val cache = tmp.newFolder("cache")
        val archive = zip("Pokemon FireRed (USA).zip", "Pokemon - FireRed Version (USA).gba" to rom)
        val first = RomArchive.playable(archive, cache)!!
        assertEquals("Pokemon - FireRed Version (USA).gba", first.name)
        assertArrayEquals(rom, first.readBytes())
        val stamp = first.lastModified()
        Thread.sleep(20)
        assertEquals(first, RomArchive.playable(archive, cache))
        assertEquals(stamp, first.lastModified())   // reused, not rewritten

        // The archive changes: a fresh extraction, and the stale one goes.
        val changed = rom.copyOf().also { it[0x1000] = (it[0x1000] + 1).toByte() }
        archive.delete()
        zip("Pokemon FireRed (USA).zip", "Pokemon - FireRed Version (USA).gba" to changed).setLastModified(System.currentTimeMillis() + 5000)
        val second = RomArchive.playable(archive, cache)!!
        assertNotEquals(first.parentFile, second.parentFile)
        assertFalse(first.exists())
        assertArrayEquals(changed, second.readBytes())
    }

    @Test fun savesGoByTheRomInside() {
        val pack = zip("Pack.zip", "roms/FireRed.gba" to rom)
        assertEquals(listOf("FireRed", "Pack"), RomArchive.saveNames(pack))
        assertEquals("FireRed", RomArchive.baseName(pack))
        val doubled = sevenZ("FireRed.gba.7z", "FireRed.gba" to rom)
        assertEquals(listOf("FireRed", "FireRed.gba"), RomArchive.saveNames(doubled))
        assertEquals(listOf("FireRed"), RomArchive.saveNames(plain()))

        val saves = tmp.newFolder("saves")
        assertEquals(File(saves, "FireRed.sav"), SavesLocation.resolve(saves, pack))   // new save: RetroArch's name
        val old = File(saves, "Pack.srm").apply { writeBytes(ByteArray(0x20000)) }
        assertEquals(old, SavesLocation.resolve(saves, pack))   // one already under the archive's name is kept
        val ra = File(saves, "FireRed.srm").apply { writeBytes(ByteArray(0x20000)) }
        assertEquals(ra, SavesLocation.resolve(saves, pack))
    }

    @Test fun playableKeepsOnlyTheLastFew() {
        val cache = tmp.newFolder("cache")
        val outs = (1..5).map { i ->
            RomArchive.playable(zip("Game$i.zip", "Game$i.gba" to rom), cache)!!.also { it.parentFile!!.setLastModified(1000L * i) }
        }
        val kept = File(cache, "rom-archives").listFiles()!!.size
        assertEquals(3, kept)
        assertTrue(outs.last().exists())
    }
}
