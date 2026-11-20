package com.pokedaisy.app.companion.data

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.zip.CRC32
import okio.Path.Companion.toOkioPath

/**
 * PLAYER LOOK ([PlayerLook]): every hero found whole in each retail ROM, and a patch for every hero in every game -
 * written to app/build/player-look/ for mgba_dump's `romblocks` (how the looks were played headless). The frame
 * helpers are checked without a ROM. Skips a ROM that isn't here.
 */
class PlayerLookTest {
    private val roms = mapOf(
        "firered" to "Pokemon - FireRed Version (USA, Europe) (Rev 1).gba",
        "firered_rev0" to "Pokemon - FireRed Version (USA).gba",
        "leafgreen" to "Pokemon - LeafGreen Version (USA, Europe) (Rev 1).gba",
        "emerald" to "Pokemon - Emerald Version (USA, Europe).gba",
        "ruby" to "Pokemon - Ruby Version (USA, Europe) (Rev 1).gba",
        "ruby_rev2" to "Pokemon - Ruby Version (USA, Europe) (Rev 2).gba",
        "sapphire" to "Pokemon - Sapphire Version (USA, Europe) (Rev 1).gba",
    )

    /** Hacks whose own heroes the other games wear (never dressed up themselves). */
    private val sources = mapOf(
        "heart_and_soul" to ("Pokemon Heart and Soul (v2.0.6).gba" to setOf(LookChar.GOLD, LookChar.KRIS)),
    )

    /** The hacks in [PlayerLook.HACKS] and the heroes each has. */
    private val hacks = mapOf(
        "kaizo" to ("Pokemon Emerald Kaizo (v2.0).gba" to setOf(LookChar.BRENDAN, LookChar.MAY)),
        "legacy" to ("Pokemon Emerald Legacy (v1.1.4).gba" to setOf(LookChar.BRENDAN, LookChar.MAY)),
        "imperium" to ("Pokemon Emerald Imperium (v1.3.1).gba" to setOf(LookChar.BRENDAN, LookChar.MAY)),
        "ultra_violet" to ("Pokemon Ultra Violet (v1.22).gba" to setOf(LookChar.RED, LookChar.LEAF)),
    )

    @Test
    fun widenAndNarrowAreInverse() {
        val f = ByteArray(256) { it.toByte() }
        assertArrayEquals(f, PlayerLook.narrow(PlayerLook.widen(f)))
        assertEquals(512, PlayerLook.widen(f).size)
    }

    @Test
    fun literalLzDecodes() {
        val data = ByteArray(2048) { (it * 7).toByte() }
        val stream = PlayerLook.lzLiteral(data)
        assertEquals(0, stream.size % 4)
        assertArrayEquals(data, RomArt.lz77(stream, 0, data.size))
    }

    @Test
    fun mergeKeepsTheLaterWriteAndTheFilesCrc() {
        val rom = ByteArray(64) { it.toByte() }
        val blocks = PlayerLook.merge(rom, listOf(10 to ByteArray(8) { 1 }, 14 to ByteArray(8) { 2 }, 40 to ByteArray(4) { 3 }))
        assertEquals(2, blocks.size)
        assertEquals(10, blocks[0].off)
        assertArrayEquals(ByteArray(4) { 1 } + ByteArray(8) { 2 }, blocks[0].data)
        assertEquals(CRC32().apply { update(rom, 10, 12) }.value.toInt(), blocks[0].crc)
    }

    @Test
    fun everyHeroInEveryGame() {
        val retail = roms.mapNotNull { (key, name) -> File(RETAIL_ROM_DIR + name).takeIf { it.isFile }?.let { key to PlayerLook.scan(it.readBytes()) } }.toMap()
        if (retail.isEmpty()) return
        val art = HashMap<LookChar, Map<LookPart, ByteArray>>()
        for ((key, scan) in retail) {
            val engine = when {
                key == "emerald" -> LookEngine.EMERALD
                key.startsWith("ruby") || key.startsWith("sapphire") -> LookEngine.RUBY
                else -> LookEngine.FIRERED
            }
            val chars = LookChar.entries.filter { it.engine == engine }
            assertEquals(key, chars.toSet(), scan.chars)
            chars.forEach { c -> art[c] = LookBlob.entries.filter { it.char == c }.associate { it.part to scan.data(c, it.part)!! } }
        }
        for ((key, rom) in sources) {
            val f = File(RETAIL_ROM_DIR + rom.first).takeIf { it.isFile } ?: continue
            val scan = PlayerLook.scan(f.readBytes())
            assertEquals(key, rom.second, scan.chars)
            scan.chars.forEach { c -> art[c] = LookBlob.entries.filter { it.char == c }.associate { it.part to scan.data(c, it.part)!! } }
        }
        val scans = retail + hacks.mapNotNull { (key, rom) ->
            val f = File(RETAIL_ROM_DIR + rom.first).takeIf { it.isFile } ?: return@mapNotNull null
            key to PlayerLook.scan(f.readBytes()).also { assertEquals(key, rom.second, it.chars) }
        }
        val out = File("build/player-look").apply { mkdirs() }
        for ((key, scan) in scans) {
            for (me in scan.chars) for (look in LookChar.entries.filter { it != me && it in art }) {
                val patch = PlayerLook.build(scan, me, look, art[look]!!, art)
                assertNotNull("$key: $me as $look", patch)
                patch!!
                // Every block lies on the ROM, none overlap, each CRC is the file's.
                var end = 0
                for (b in patch.blocks.sortedBy { it.off }) {
                    assertTrue("$key $me as $look: overlap at ${b.off}", b.off >= end)
                    end = b.off + b.data.size
                    assertTrue(end <= scan.rom.size)
                    assertEquals(CRC32().apply { update(scan.rom, b.off, b.data.size) }.value.toInt(), b.crc)
                }
                // The worn overworld palette goes in where the object-event table points, the shared colours kept.
                val worn = PlayerLook.wear(me, look, art[look]!!) { scan.data(me, it) }!!
                val pal = worn.pal
                if (me.engine != look.engine) {
                    val own = scan.data(me, LookPart.OW_PAL)!!
                    LOOK_SHARED_COLOURS[me.engine]!!.forEach { i -> assertEquals(own[i * 2], pal[i * 2]); assertEquals(own[i * 2 + 1], pal[i * 2 + 1]) }
                }
                assertTrue("$key $me as $look: palette", patch.blocks.any { b -> (0..b.data.size - 32).any { b.data.copyOfRange(it, it + 32).contentEquals(pal) } })
                File(out, "$key-${me.name.lowercase()}-as-${look.name.lowercase()}.txt").writeText(patch.toText())
                // RIVAL LOOK: the other gender's hero wears the look's counterpart too, in the same blocks.
                if (PlayerLook.hasRival(me.engine) && me.counterpart in scan.chars) {
                    val both = PlayerLook.build(scan, me, look, art[look]!!, art, rival = true)!!
                    var e = 0
                    for (b in both.blocks.sortedBy { it.off }) {
                        assertTrue("$key $me as $look + rival: overlap at ${b.off}", b.off >= e)
                        e = b.off + b.data.size
                        assertEquals(CRC32().apply { update(scan.rom, b.off, b.data.size) }.value.toInt(), b.crc)
                    }
                    val rivalPal = PlayerLook.wear(me.counterpart, look.counterpart, art[look.counterpart]!!) { scan.data(me.counterpart, it) }!!.pal
                    assertTrue("$key $me as $look: rival palette", both.blocks.any { b -> (0..b.data.size - 32).any { b.data.copyOfRange(it, it + 32).contentEquals(rivalPal) } })
                    assertTrue("$key $me as $look: player palette kept", both.blocks.any { b -> (0..b.data.size - 32).any { b.data.copyOfRange(it, it + 32).contentEquals(pal) } })
                    File(out, "$key-${me.name.lowercase()}-as-${look.name.lowercase()}-rival.txt").writeText(both.toText())
                }
            }
            // Back to the game's own: no blocks, every worn palette back to the hero's.
            for (me in scan.chars) {
                val back = PlayerLook.settle(scan, me, null, art)
                assertTrue("$key $me: own palettes back", back.isNotEmpty() && back.all { p -> p.from.none { it.contentEquals(p.to) } })
                File(out, "$key-${me.name.lowercase()}-own.txt").writeText(PlayerLook.Patch(emptyList(), back).toText())
            }
        }
    }

    /** TEXT LOOK ([TextLook]): FireRed's box and font in Emerald (and its hacks), Emerald's in FireRed / LeafGreen
     * (and Ultra Violet) - box and font both on the English carts - written beside the looks' patches. */
    @Test
    fun textLooks() {
        val keys = mapOf(
            "firered" to (roms["firered"]!! to LookEngine.FIRERED), "leafgreen" to (roms["leafgreen"]!! to LookEngine.FIRERED),
            "emerald" to (roms["emerald"]!! to LookEngine.EMERALD), "kaizo" to (hacks["kaizo"]!!.first to LookEngine.EMERALD),
            "legacy" to (hacks["legacy"]!!.first to LookEngine.EMERALD), "ultra_violet" to (hacks["ultra_violet"]!!.first to LookEngine.FIRERED),
        )
        val scans = keys.mapNotNull { (key, v) ->
            File(RETAIL_ROM_DIR + v.first).takeIf { it.isFile }?.let { key to (PlayerLook.scan(it.readBytes(), TextBlob.entries) to v.second) }
        }.toMap()
        val art = HashMap<LookEngine, Map<TextPart, ByteArray>>()
        for ((key, sv) in scans) {
            val (scan, style) = sv
            // Every part of the game's own, the font four times in FireRed.
            for (part in TextPart.entries) assertTrue("$key $part", scan.hits[TextLook.blob(style, part)!!].orEmpty().isNotEmpty())
            if (key == "firered") assertEquals(4, scan.hits[TextLook.blob(style, TextPart.FONT)!!]!!.size)
            art.getOrPut(style) { TextPart.entries.associateWith { p -> TextLook.blob(style, p)!!.let { b -> Fingerprints.decode(scan.rom, scan.hits[b]!![0], b)!! } } }
        }
        val out = File("build/player-look").apply { mkdirs() }
        for ((key, sv) in scans) {
            val (scan, host) = sv
            val other = TextLook.STYLES.first { it != host }
            val src = art[other] ?: continue
            val blocks = TextLook.build(scan, host, other, src)
            // The box, its palette and the font all go in: FireRed's four fonts and widths, Emerald's one.
            val fonts = if (host == LookEngine.FIRERED) 4 else 1
            val written = blocks.sumOf { it.data.size }
            assertTrue("$key: ${blocks.size} blocks, $written bytes", written >= fonts * (32768 + 512) + 32)
            for (b in blocks) assertEquals(CRC32().apply { update(scan.rom, b.off, b.data.size) }.value.toInt(), b.crc)
            // Emerald (and Kaizo, Legacy) draw FireRed's box with the frame code put in the ROM's last 2 KB.
            if (host == LookEngine.EMERALD) assertTrue("$key: frame code", blocks.any { it.off >= scan.rom.size - 0x800 })
            File(out, "$key-text-${other.name.lowercase()}.txt").writeText(PlayerLook.Patch(blocks, emptyList()).toText())
        }
    }

    /** Each hack's following Pokémon ([MonArt.Source]) kept from its ROM: how many of 1-386 (the big ones cropped where
     * their drawing fits 32x32, the rest left out), and with all of them kept each species comes from the first source. */
    @Test
    fun followerSources() {
        val roms = mapOf(
            MonArt.Source.HEART_AND_SOUL to ("Pokemon Heart and Soul (v2.0.6).gba" to 386 - 5),
            MonArt.Source.ROWE to ("Pokemon R.O.W.E. (v2.1.9.1 Experimental).gba" to 386 - 7),
            MonArt.Source.RECHARGED_YELLOW to ("Pokemon Recharged Yellow (v1.9.7).gba" to 386),
            MonArt.Source.SEAGLASS to ("Pokemon Emerald Seaglass (v3.0).gba" to 386),
        )
        val dir = java.nio.file.Files.createTempDirectory("mons").toFile()
        val has = HashMap<MonArt.Source, Set<Int>>()
        for ((source, rom) in roms) {
            val f = File(RETAIL_ROM_DIR + rom.first).takeIf { it.isFile } ?: continue
            val one = java.nio.file.Files.createTempDirectory("mon").toFile()
            val kept = MonArt.keep(one.toOkioPath(), f.readBytes())
            println("follower sources: $source kept $kept")
            assertTrue("$source: kept $kept of ${rom.second}", kept >= rom.second)
            assertEquals(setOf(source), MonArt.sources(one.toOkioPath()))
            has[source] = MonArt.available(one.toOkioPath())
            for (n in listOf(1, 25, 64, 151, 258, 386)) {
                val m = MonArt.load(one.toOkioPath(), n) ?: continue
                assertEquals(source, m.source)
                // A drawn sprite in a real palette: its south frame isn't empty, colour 0 is the see-through one.
                assertTrue("$source $n frame", m.frames.copyOf(512).any { it != 0.toByte() })
                assertTrue("$source $n pictures", m.front.any { it != 0.toByte() } && m.back.any { it != 0.toByte() })
            }
            one.deleteRecursively()
            MonArt.keep(dir.toOkioPath(), f.readBytes())
        }
        // Several kept: each Pokémon from the first source (in Source's order) that has it.
        for (n in listOf(25, 151, 249, 384)) MonArt.load(dir.toOkioPath(), n)?.let { m -> assertEquals("$n", MonArt.Source.entries.first { n in has[it].orEmpty() }, m.source) }
        dir.deleteRecursively()
    }

    /** PLAYER LOOK's Pokémon ([MonArt], [PlayerLook.buildMon]): every species 1-386 but the big legends that don't fit
     * 32x32 kept from Heart and Soul, and a patch for each hero of every retail game as two of them - written beside the others. */
    @Test
    fun pokemonLooks() {
        val hns = File(RETAIL_ROM_DIR + sources["heart_and_soul"]!!.first).takeIf { it.isFile } ?: return
        val dir = java.nio.file.Files.createTempDirectory("mon").toFile()
        val kept = MonArt.keep(dir.toOkioPath(), hns.readBytes())
        assertTrue("kept $kept", kept >= MonArt.LAST - 5)
        val out = File("build/player-look").apply { mkdirs() }
        for ((key, name) in roms) {
            val f = File(RETAIL_ROM_DIR + name).takeIf { it.isFile } ?: continue
            val scan = PlayerLook.scan(f.readBytes())
            for (me in scan.chars) for (n in listOf(25, 64, 258)) {
                val patch = PlayerLook.buildMon(scan, me, MonArt.load(dir.toOkioPath(), n)!!)
                assertNotNull("$key $me as $n", patch)
                var end = 0
                for (b in patch!!.blocks.sortedBy { it.off }) {
                    assertTrue("$key $me as $n: overlap", b.off >= end)
                    end = b.off + b.data.size
                    assertEquals(CRC32().apply { update(scan.rom, b.off, b.data.size) }.value.toInt(), b.crc)
                }
                File(out, "$key-${me.name.lowercase()}-as-mon$n.txt").writeText(patch.toText())
            }
        }
        dir.deleteRecursively()
    }
}
