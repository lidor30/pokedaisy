package com.pokedaisy.app.companion.data

import com.pokedaisy.app.companion.data.save.Gen3BoxMon
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.Random

/**
 * The JVM half of scripts/verify_shiny.py (skipped unless it ran): SHINY ODDS on every retail version, from headless dumps.
 * For each line of build/shiny/runs.tsv (key, game code, revision, ROM path):
 *
 *  - `made/` (a wild PIKACHU just made by setwildbattle, no battle yet): finds where the game really put it (its player's
 *    OT ID), runs [ShinyOdds.Roller] there with the config the Poller would pick and writes the rewrite as `pokes.txt`.
 *  - `shiny/` and `plain/` (the battle started with and without those pokes): the shiny battle's foe reads shiny in
 *    gBattleMons, the plain one's doesn't, and the ROM gives PIKACHU a shiny icon palette.
 *
 * Writes build/shiny/jvm.tsv (key, the target's gEnemyParty, where the foe was, rewritten, battle shiny, plain shiny,
 * shiny icon) for the script's report.
 */
class ShinyVerifyTest {
    private val dir = File("../build/shiny")
    // The script's --probes, else its defaults.
    private val PROBES: List<Int> by lazy {
        File(dir, "probes.txt").takeIf { it.isFile }?.readText()?.trim()?.split(",")?.mapNotNull { it.toIntOrNull() } ?: DEFAULT_PROBES
    }

    // scripts/verify_shiny.py's HACKS: the hacks on vanilla's struct, by key.
    private val hacks = mapOf(
        "KAIZO" to NATIVE_EMERALD_KAIZO, "LEGACY" to NATIVE_EMERALD_LEGACY,
        "RE_V225" to NATIVE_RECHARGED_EMERALD_V2_2_5, "RE_V228" to NATIVE_RECHARGED_EMERALD_V2_2_8,
        "RE_REBALANCED" to NATIVE_RECHARGED_EMERALD_REBALANCED_V2_2_8, "GLAZED" to NATIVE_GLAZED,
        "ULTRA_VIOLET" to NATIVE_ULTRA_VIOLET, "ORANGE_ISLANDS" to NATIVE_ORANGE_ISLANDS, "ROCKET_EDITION" to NATIVE_ROCKET_EDITION,
        "ASH_GRAY" to NATIVE_ASH_GRAY, "ASH_GRAY_FORK" to NATIVE_ASH_GRAY,
        "GHOST_GREY_104" to NATIVE_GHOST_GREY, "GHOST_GREY_102" to NATIVE_GHOST_GREY, "GHOST_GREY_101" to NATIVE_GHOST_GREY_V1_0_1,
        "RY_V180" to NATIVE_RECHARGED_YELLOW_V1_8_0, "RY_V190" to NATIVE_RECHARGED_YELLOW_V1_9_0,
        "RY_V194" to NATIVE_RECHARGED_YELLOW_V1_9_4, "RY_V197" to NATIVE_RECHARGED_YELLOW_V1_9_7,
        "CELIA" to NATIVE_CELIA,
        "UNBOUND" to NATIVE_UNBOUND_WITH_DEX, "UNBOUND_FR" to NATIVE_UNBOUND_FR, "GAIA" to NATIVE_GAIA_V3_2,
        "RADICAL_RED" to NATIVE_RADICAL_RED_V4_1, "ODYSSEY" to NATIVE_ODYSSEY, "AMETHYST" to NATIVE_AMETHYST,
        "AMETHYST_V141" to NATIVE_AMETHYST_V1_4_1, "SWSH" to NATIVE_SWORD_SHIELD, "SWSH_CASUAL" to NATIVE_SWORD_SHIELD,
        "SWSH_PERFORMANCE" to NATIVE_SWORD_SHIELD, "SWSH_CASUAL_PERFORMANCE" to NATIVE_SWORD_SHIELD,
        "GS_CHRONICLES" to NATIVE_GS_CHRONICLES,
        "LAZARUS" to NATIVE_LAZARUS, "SEAGLASS" to NATIVE_EMERALD_SEAGLASS, "TMT2" to NATIVE_TMT2, "IMPERIUM" to NATIVE_IMPERIUM,
        "MARIOMON_V1_5_2" to NATIVE_MARIOMON_V1_5_2, "MARIOMON_V1_5_1" to NATIVE_MARIOMON_V1_5_1,
        "MARIOMON_V1_3" to NATIVE_MARIOMON_V1_3, "MARIOMON_V1_2_2" to NATIVE_MARIOMON_V1_2_2,
        "MARIOMON_V1_2_1" to NATIVE_MARIOMON_V1_2_1,
        "SOULGOLD" to NATIVE_SOULGOLD, "SOULGOLD_V12" to NATIVE_SOULGOLD_V1_2, "SOULGOLD_V12B" to NATIVE_SOULGOLD_V1_2B,
        "SOULGOLD_V12C" to NATIVE_SOULGOLD_V1_2C, "SOULGOLD_V121" to NATIVE_SOULGOLD_V1_2_1, "SOULGOLD_V121B" to NATIVE_SOULGOLD_V1_2_1,
        "QUETZAL" to NATIVE_QUETZAL, "QUETZAL_ES" to NATIVE_QUETZAL_ES,
        "QUETZAL_V1" to NATIVE_QUETZAL_V1, "QUETZAL_ES_V1" to NATIVE_QUETZAL_ES_V1, "QUETZAL_084" to NATIVE_QUETZAL_V084,
        "QUETZAL_ES_084" to NATIVE_QUETZAL_V084_ES, "QUETZAL_MOEMON_084" to NATIVE_QUETZAL_MOEMON_V084,
        "QUETZAL_MOEMON_ES_084" to NATIVE_QUETZAL_MOEMON_V084_ES, "ROWE" to NATIVE_ROWE,
        "ELITE_REDUX" to NATIVE_ELITE_REDUX_V2_65_4_1B, "ELITE_REDUX_V2653" to NATIVE_ELITE_REDUX_V2_65_3B,
        "TEAM_ROCKET" to NATIVE_TEAM_ROCKET,
    )
    // pokeemerald-expansion's: National Dex species ids.
    private val national = setOf(
        "LAZARUS", "SEAGLASS", "TMT2", "IMPERIUM", "MARIOMON_V1_5_2", "MARIOMON_V1_5_1", "MARIOMON_V1_3", "MARIOMON_V1_2_2",
        "MARIOMON_V1_2_1", "SOULGOLD", "SOULGOLD_V12", "SOULGOLD_V12B", "SOULGOLD_V12C", "SOULGOLD_V121", "SOULGOLD_V121B", "QUETZAL", "QUETZAL_ES", "QUETZAL_V1",
        "QUETZAL_ES_V1", "QUETZAL_084", "QUETZAL_ES_084", "QUETZAL_MOEMON_084", "QUETZAL_MOEMON_ES_084", "ROWE", "ELITE_REDUX",
        "ELITE_REDUX_V2653", "TEAM_ROCKET",
    )
    // The CFRU ones: plaintext boxes.
    private val plaintext = setOf(
        "UNBOUND", "UNBOUND_FR", "GAIA", "RADICAL_RED", "ODYSSEY", "AMETHYST", "AMETHYST_V141",
        "SWSH", "SWSH_CASUAL", "SWSH_PERFORMANCE", "SWSH_CASUAL_PERFORMANCE", "GS_CHRONICLES",
    )

    private fun configFor(key: String, code: String, rev: Int): NativeConfig? = hacks[key] ?: when ("$code$rev") {
        "BPRE0" -> NATIVE_FIRERED_REV0
        "BPRE1" -> NATIVE_FIRERED_REV1
        "BPEE0" -> NATIVE_EMERALD_RETAIL
        else -> TelemetrySampler.otherRetailConfig(code, rev)
    }

    private class Ram(src: MemoryReader) : MemoryReader {
        val ewram: ByteArray = src.readCoreMemory(0x02000000L, 0x40000)
        val iwram: ByteArray = src.readCoreMemory(0x03000000L, 0x8000)
        private fun at(addr: Long) = if (addr >= 0x03000000L) iwram to (addr - 0x03000000L).toInt() else ewram to (addr - 0x02000000L).toInt()
        override fun readCoreMemory(addr: Long, size: Int): ByteArray { val (b, o) = at(addr); return b.copyOfRange(o, o + size) }
        fun write(addr: Long, bytes: ByteArray): Boolean { val (b, o) = at(addr); bytes.copyInto(b, o); return true }
    }

    private fun u32(b: ByteArray, o: Int) =
        (b[o].toLong() and 0xFF) or ((b[o + 1].toLong() and 0xFF) shl 8) or ((b[o + 2].toLong() and 0xFF) shl 16) or ((b[o + 3].toLong() and 0xFF) shl 24)

    /** Every readable [species] record with [ot] as its OT ID, in EWRAM and IWRAM (4-byte aligned). */
    private fun foes(ram: Ram, ot: Long, t: ShinyOdds.Target, species: Int): List<Long> {
        val plain = t.plaintext
        val mask = t.speciesMask
        val found = mutableListOf<Long>()
        for ((base, b) in listOf(0x02000000L to ram.ewram, 0x03000000L to ram.iwram)) {
            var o = 0
            while (o + Gen3BoxMon.SIZE <= b.size) {
                if (u32(b, o + 4) == ot && u32(b, o) != 0L) {
                    if (plain) { if (o + t.plainSpecies + 2 <= b.size && u16(b, o + t.plainSpecies) and mask == species) found += base + o }
                    else { val mon = Gen3BoxMon.read(b, o); if (mon.checksumOk && mon.species and mask == species) found += base + o }
                }
                o += 4
            }
        }
        return found
    }

    @Test fun `every retail version gets its shiny foe`() {
        val tsv = File(dir, "runs.tsv")
        assumeTrue("run scripts/verify_shiny.py", tsv.isFile)
        val out = StringBuilder()
        for (line in tsv.readLines().filter { it.isNotBlank() }) {
            val (key, code, revText, romPath) = line.split("\t")
            val rev = revText.toInt()
            val cfg = configFor(key, code, rev)
            val row = mutableListOf(key)
            if (cfg == null) { out.append("$key\tno config\n"); continue }
            activeGame = if (code.startsWith("BPR") || code.startsWith("BPG")) GameKind.FIRERED else GameKind.EMERALD
            romLanguage = cfg.language
            romGameCode = cfg.gameCode
            resetNativeBagCache()
            val cfrPlain = key in plaintext
            val target = ShinyOdds.targetFor(cfg.copy(shinyBelow = 8), fireRedFamily = code.startsWith("BPR") || code.startsWith("BPG"), plaintext = cfrPlain,
                national = key in national)
            row += target?.let { "%08X".format(it.enemyParty) } ?: "none"
            val plain = target?.plaintext == true
            // The foe: the scripted PIKACHU, or whatever a walked encounter met (the record in the foe slot).
            var species = PIKACHU

            val made = File(dir, "$key/made")
            if (File(made, "ewram.bin").isFile && target != null) {
                val ram = Ram(FixtureMemoryReader.fromDir(made))
                val save2 = if (target.saveBlock2Static) target.saveBlock2Ptr else u32(ram.readCoreMemory(target.saveBlock2Ptr, 4), 0)
                val ot = u32(ram.readCoreMemory(save2 + target.trainerIdOff, 4), 0)
                val first = ram.readCoreMemory(target.enemyParty, target.stride)
                val inSlot = if (plain) u16(first, target.plainSpecies) else Gen3BoxMon.read(first, 0).takeIf { it.checksumOk }?.species ?: 0
                if (inSlot != 0) species = inSlot and target.speciesMask
                row += foes(ram, ot, target, species).joinToString(",") { "%08X".format(it) }.ifEmpty { "none" }
                val pokes = StringBuilder()
                val roller = ShinyOdds.Roller(target, Random(1))
                val writer = { a: Long, bytes: ByteArray ->
                    if (bytes.size % 4 != 0) for (i in bytes.indices) pokes.append("poke8 0x%08X 0x%02X\n".format(a + i, bytes[i].toInt() and 0xFF))
                    else for (i in bytes.indices step 4) pokes.append("poke32 0x%08X 0x%08X\n".format(a + i, u32(bytes, i)))
                    ram.write(a, bytes)
                }
                // Two frames, as on the device: the first notes the new record, the second (unchanged) decides.
                val n = roller.tick(1, ram::readCoreMemory, writer) + roller.tick(1, ram::readCoreMemory, writer)
                File(dir, "$key/pokes.txt").writeText(pokes.toString())
                row += if (n > 0) "rewritten" else "not rewritten"
                // The probes: the same foe at chosen shiny values, to see which the game itself draws as shiny.
                val made2 = Ram(FixtureMemoryReader.fromDir(made))
                val record = made2.readCoreMemory(target.enemyParty, if (plain) 8 else Gen3BoxMon.SIZE)
                val mon = if (plain) null else Gen3BoxMon.read(record, 0)
                if (mon == null || mon.checksumOk) for (v in PROBES) {
                    // Exactly shiny value v: the low half kept, the high half set (nature and gender don't matter here).
                    val pid = u32(record, 0)
                    val ot = u32(record, 4)
                    val lo = pid and 0xFFFF
                    val tsv = (ot ushr 16) xor (ot and 0xFFFF)
                    val p = ((tsv xor lo xor v.toLong()) shl 16) or lo
                    val bytes = if (plain) ByteArray(4) { (p ushr (8 * it)).toByte() } else mon!!.withPersonality(p)
                    File(dir, "$key/probe$v.txt").writeText((bytes.indices step 4).joinToString("") {
                        "poke32 0x%08X 0x%08X\n".format(target.enemyParty + it, u32(bytes, it))
                    })
                }
            } else row += listOf("-", "-")

            val rom = RomFileReader.load(romPath)
            fun foeShiny(run: String): String {
                val d = File(dir, "$key/$run")
                if (!File(d, "ewram.bin").isFile || rom == null) return "-"
                val t = readNativeTelemetry(rom.withRam(FixtureMemoryReader.fromDir(d)), cfg.copy(shinyBelow = 8))
                val foe = t.battleMons[BATTLE_POS_OPPONENT_LEFT]
                return if (foe.species != species) "foe ${foe.species}" else if (foe.shiny) "shiny" else "plain"
            }
            row += foeShiny("shiny")
            row += foeShiny("plain")
            // What the game drew: PIKACHU's shiny or normal battle colours in the OBJ palettes (vdump's pal.bin), found by
            // their table entries' tags in the ROM (25 / 525) - exact where a frame comparison isn't (animated backdrops).
            val pals = File(romPath).takeIf { it.isFile }?.let { romPalettes(it.readBytes(), species) }
            fun obj(run: String) = File(dir, "$key/$run/pal.bin").takeIf { it.isFile }?.readBytes()?.copyOfRange(0x200, 0x400)
            fun slot(b: ByteArray, i: Int) = b.copyOfRange(i * 32 + 2, i * 32 + 32)   // colour 0 is transparent
            val shinyObj = obj("shiny")
            val plainObj = obj("plain")
            // The colour sets only one of the two battles has (the foe's, wherever its slot landed - a rebuild's palettes
            // aren't always the table's, and its slots can be handed out in another order).
            fun sets(b: ByteArray?) = b?.let { o -> (0 until 16).map { slot(o, it).toList() } }.orEmpty()
            val onlyShiny = sets(shinyObj).filter { it !in sets(plainObj) }
            val onlyPlain = sets(plainObj).filter { it !in sets(shinyObj) }
            fun drawn(run: String): String {
                val o = obj(run) ?: return "-"
                fun has(pal: IntArray) = (0 until 16).any { sl -> (1 until 16).all { c -> u16(o, sl * 32 + c * 2) and 0x7FFF == pal[c] and 0x7FFF } }
                if (pals != null && has(pals.second)) return "shiny"
                if (pals != null && has(pals.first)) return "plain"
                if (onlyShiny.isEmpty() && onlyPlain.isEmpty()) return if (run == "plain" || run == "shiny") "same" else "?"
                val here = sets(o)
                val asShiny = onlyShiny.any { it in here }
                val asPlain = onlyPlain.any { it in here }
                return when { asShiny && !asPlain -> "shiny"; asPlain && !asShiny -> "plain"; else -> "?" }
            }
            row += "${drawn("shiny")}/${drawn("plain")}"
            // The shiny icon: PIKACHU's palette recoloured from the ROM's battle palettes.
            row += if (rom == null) "-" else {
                val t = cfg.copy(shinyBelow = 8).iconTables
                val rd = { a: Long, n: Int -> rom.readCoreMemory(a, n) }
                val idx = rd(t.monIconPaletteIndices + PIKACHU, 1)[0].toInt() and 0xFF
                val pal = rd(u32(rd(t.monIconPaletteTable + idx * 8L, 4), 0), 32)
                if (shinyIconPalette(t, PIKACHU, pal, rd)?.contentEquals(pal) == false) "icon" else "no icon"
            }
            // Each probe battle's foe: its shiny value as gBattleMons has it (the script compares its frame with plain's).
            row += PROBES.joinToString(",") { v ->
                val d = File(dir, "$key/probe$v")
                if (!File(d, "ewram.bin").isFile || rom == null) "-" else {
                    val raw = FixtureMemoryReader.fromDir(d).readCoreMemory(cfg.battleMons, 4 * cfg.battleMonLayout.size)
                    val o = cfg.battleMonLayout.size   // battler 1: the foe in a single battle
                    val l = cfg.battleMonLayout
                    if (l.personality < 0) "?" else "$v:${shinyValue(u32(raw, o + l.personality), u32(raw, o + l.otId))}:${drawn("probe$v")}"
                }
            }
            out.append(row.joinToString("\t")).append('\n')
        }
        File(dir, "jvm.tsv").writeText(out.toString())
    }

    private fun u16(b: ByteArray, o: Int) = (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8)

    /** [species]' normal and shiny battle palettes from [rom]: gMonPaletteTable's entries {LZ77 palette, tag} with tags
     * species, +1, +2 in a row, and gMonShinyPaletteTable's with species + 500 on. Null if either isn't found. */
    private fun romPalettes(rom: ByteArray, species: Int): Pair<IntArray, IntArray>? {
        fun find(tag: Int): IntArray? {
            var o = 0
            while (o + 24 <= rom.size) {
                if (u16(rom, o + 4) == tag && u16(rom, o + 12) == tag + 1 && u16(rom, o + 20) == tag + 2) {
                    val ptr = u32(rom, o)
                    if (ptr in 0x08000000L until 0x08000000L + rom.size) {
                        val at = (ptr - 0x08000000L).toInt()
                        val raw = runCatching { Gfx.lz77(rom.copyOfRange(at, minOf(rom.size, at + 128))) }.getOrNull()
                        if (raw != null && raw.size >= 32) return IntArray(16) { u16(raw, it * 2) }
                    }
                }
                o += 4
            }
            return null
        }
        return (find(species) ?: return null) to (find(species + 500) ?: return null)
    }

    private companion object {
        const val PIKACHU = 25
        val DEFAULT_PROBES = listOf(8, 15, 16, 31, 32, 63, 64, 127, 128, 255, 256)
    }
}
