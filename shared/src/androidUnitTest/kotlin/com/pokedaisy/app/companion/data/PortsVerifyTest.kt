package com.pokedaisy.app.companion.data

import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * scripts/verify_ports.py's JVM half: every ported ROM's headless dumps read
 * with its RETAIL_PORTS config, against its English ROM's read with English's
 * config (the same save, the same boot). Skipped unless build/ports/verify.tsv
 * exists; writes build/ports/report.md and fails if any check did.
 */
class PortsVerifyTest {
    private val dir = File("../build/ports")

    // English FireRed's storage pointer is the Poller's (its config is the hacks' base too).
    private val english = mapOf(
        "NATIVE_FIRERED_REV0" to NATIVE_FIRERED_REV0.copy(pokemonStoragePtr = NATIVE_FIRERED_REV0.saveBlock2Ptr + 4),
        "NATIVE_FIRERED_REV1" to NATIVE_FIRERED_REV1.copy(pokemonStoragePtr = NATIVE_FIRERED_REV1.saveBlock2Ptr + 4),
        "NATIVE_LEAFGREEN_REV0" to NATIVE_LEAFGREEN_REV0, "NATIVE_LEAFGREEN_REV1" to NATIVE_LEAFGREEN_REV1,
        "NATIVE_RUBY" to NATIVE_RUBY, "NATIVE_SAPPHIRE" to NATIVE_SAPPHIRE,
    )

    private val guides = mapOf(
        "GUIDE_TABLES_FIRERED_REV1" to GUIDE_TABLES_FIRERED_REV1, "GUIDE_TABLES_LEAFGREEN_REV0" to GUIDE_TABLES_LEAFGREEN_REV0,
        "GUIDE_TABLES_LEAFGREEN_REV1" to GUIDE_TABLES_LEAFGREEN_REV1, "GUIDE_TABLES_RUBY" to GUIDE_TABLES_RUBY,
        "GUIDE_TABLES_SAPPHIRE" to GUIDE_TABLES_SAPPHIRE,
    )

    // The dex tables each guide config's ROM holds (the same ROM: verify.tsv's tables path).
    private val enDexOf = mapOf(
        "GUIDE_TABLES_FIRERED_REV1" to POKEDEX_FIRERED_REV1, "GUIDE_TABLES_LEAFGREEN_REV0" to POKEDEX_LEAFGREEN_REV0,
        "GUIDE_TABLES_LEAFGREEN_REV1" to POKEDEX_LEAFGREEN_REV1, "GUIDE_TABLES_RUBY" to POKEDEX_RUBY,
        "GUIDE_TABLES_SAPPHIRE" to POKEDEX_SAPPHIRE,
    )

    private fun kindOf(code: String) = if (code.startsWith("BPR") || code.startsWith("BPG")) GameKind.FIRERED else GameKind.EMERALD

    private fun select(code: String, cfg: NativeConfig) {
        activeGame = kindOf(code)
        romLanguage = cfg.language
        romGameCode = cfg.gameCode
        resetNativeBagCache()
    }

    private fun peeks(key: String): List<Long> =
        File(dir, "dumps/$key/peeks.txt").takeIf { it.isFile }?.readLines().orEmpty()
            .mapNotNull { Regex("^[0-9a-f]{8}: ([0-9a-f]{8})$").find(it.trim())?.groupValues?.get(1)?.toLong(16) }

    private fun read(key: String, run: String, rom: RomFileReader, cfg: NativeConfig) =
        readNativeTelemetry(rom.withRam(FixtureMemoryReader.fromDir(File(dir, "dumps/$key/$run"))), cfg)

    @Test fun `every port reads like english`() {
        val tsv = File(dir, "verify.tsv")
        assumeTrue("run scripts/verify_ports.py", tsv.isFile)
        val rows = StringBuilder("| ROM | key | party | bag | money | place | boxes | dex | guide | trainer | battle | handlers | names |\n" +
            "|---|---|---|---|---|---|---|---|---|---|---|---|---|\n")
        var failures = 0
        tsv.readLines().filter { it.isNotBlank() }.forEach { line ->
            val f = line.split("\t")
            val (key, path, enKey, enPath, enName) = f
            val probeStr = f[5]
            val file = f[6]
            // The English tables the guide / dex were mapped from (FireRed rev 0's are rev 1's).
            val tablesRom = RomFileReader.load(f[7])!!
            val enGuide = guides.getValue(f[8])
            val code = key.dropLast(1)
            val cfg = RETAIL_PORTS.getValue(key)()
            val en = english.getValue(enName)
            val rom = RomFileReader.load(path)!!
            val enRom = RomFileReader.load(enPath)!!
            val probe = probeStr.toInt()
            val checks = LinkedHashMap<String, String>()
            fun check(name: String, ok: Boolean, detail: String = "") { checks[name] = if (ok) "✅" else "❌ $detail" }
            runCatching {
                // "-": booted with its own save (Japanese FireRed / LeafGreen can't read the English
                // ones), nothing to compare with - checked absolutely below instead.
                val own = enKey == "-"
                select(code, en)
                PokedexSource.reader = enRom
                val e = if (own) null else read("EN_$enKey", "field", enRom, en)
                val eBattle = if (own) null else read("EN_$enKey", "battle", enRom, en)
                PokedexSource.reader = tablesRom
                val eParty = GuideRomSource.party(enGuide, probe)?.map { it.species to it.level }
                // Every national number's species, height and weight, from the tables the port's were mapped from.
                val enDex = enDexOf.getValue(f[8])
                val eDex = (1..enDex.nationalCount).map { n ->
                    val sp = PokedexSource.speciesFor(enDex, n)
                    Triple(sp, PokedexSource.entry(enDex, n)?.heightDm, PokedexSource.entry(enDex, n)?.weightHg)
                }
                PokedexSource.reader = enRom
                select(code, cfg)
                PokedexSource.reader = rom
                val t = read(key, "field", rom, cfg)
                if (e != null) {
                    check("party", t.party.isNotEmpty() && t.party.map { Triple(it.species, it.level, it.hp) } == e.party.map { Triple(it.species, it.level, it.hp) },
                        "${t.party.map { it.species to it.level }} vs ${e.party.map { it.species to it.level }}")
                    check("bag", t.items.map { it.itemId to it.quantity }.toSet() == e.items.map { it.itemId to it.quantity }.toSet() && t.items.isNotEmpty(),
                        "${t.items.size} vs ${e.items.size}")
                    check("money", t.money == e.money, "${t.money} vs ${e.money}")
                    check("place", t.regionMapSectionId == e.regionMapSectionId, "${t.regionMapSectionId} vs ${e.regionMapSectionId}")
                    // BOXES: every slot the same Pokémon (species, personality, level), the same box names and wallpapers
                    // (names but in Japanese, whose text table reads the English save's bytes as other letters).
                    fun boxes(b: PcBoxes?) = b?.boxes?.map { box ->
                        Triple(if (cfg.language == 'J') "" else box.name, box.wallpaper, box.mons.map { m -> m?.let { Triple(it.species, it.personality, it.level) } })
                    }
                    val tBoxes = boxes(t.pcBoxes)
                    check("boxes", tBoxes != null && tBoxes == boxes(e.pcBoxes) && tBoxes.any { b -> b.third.any { it != null } },
                        "${t.pcBoxes?.boxes?.sumOf { b -> b.mons.count { it != null } }} vs ${e.pcBoxes?.boxes?.sumOf { b -> b.mons.count { it != null } }}")
                } else {
                    // Absolutely: real Pokémon (the box data's checksum held - decodePartyMon drops the rest),
                    // a bag of real items with sane counts, money in range, a named place.
                    check("party", t.party.isNotEmpty() && t.party.all { it.species in 1..411 && it.level in 1..100 && it.maxHp > 0 && it.hp <= it.maxHp },
                        "${t.party.map { it.species to it.level }}")
                    check("bag", t.items.isNotEmpty() && t.items.all { it.itemId in 1..376 && it.quantity in 1..999 }, "${t.items.take(5).map { it.itemId to it.quantity }}")
                    check("money", (t.money ?: -1L) in 0L..999_999L, "${t.money}")
                    check("place", lookupLocation(t.regionMapSectionId).let { it.mapSecName.isNotBlank() && it.regionMapAsset != null },
                        "${t.regionMapSectionId}")
                    // BOXES: 14 boxes of real Pokémon (decodeBoxMon drops a record whose checksum fails), some of them filled.
                    val mons = t.pcBoxes?.boxes?.flatMap { it.mons }?.filterNotNull()
                    check("boxes", t.pcBoxes?.boxes?.size == PC_BOXES && !mons.isNullOrEmpty() && mons.all { it.species in 1..412 && it.level in 1..100 },
                        "${mons?.size}")
                }
                val dex = cfg.pokedex!!
                val tDex = (1..dex.nationalCount).map { n ->
                    Triple(PokedexSource.speciesFor(dex, n), PokedexSource.entry(dex, n)?.heightDm, PokedexSource.entry(dex, n)?.weightHg)
                }
                val dexDiff = tDex.indices.filter { tDex[it] != eDex[it] }
                check("dex", pokedexMatchesRom(rom, dex) && dexDiff.isEmpty(), "national ${dexDiff.take(5).map { it + 1 }} differ")
                check("guide", cfg.guideTables?.let { guideTablesMatchRom(rom, it) } == true)
                check("trainer", GuideRomSource.party(cfg.guideTables!!, probe)?.map { it.species to it.level } == eParty)
                val b = read(key, "battle", rom, cfg)
                val me = b.battleMons[BATTLE_POS_PLAYER_LEFT]
                val foe = b.battleMons[BATTLE_POS_OPPONENT_LEFT]
                // Own save: the battle's lead is the party's first Pokémon that can fight.
                val eMe = eBattle?.battleMons?.get(BATTLE_POS_PLAYER_LEFT)?.let { BattleMonView(it.species, it.level) }
                    ?: t.party.first { it.hp > 0 && !it.isEgg }.let { BattleMonView(it.species, it.level) }
                check("battle", b.inBattle && me.species == eMe.species && me.level == eMe.level && foe.species == 25 && foe.level == 5,
                    "${b.inBattle} ${me.species}/${me.level} vs ${eMe.species}/${eMe.level}, foe ${foe.species}/${foe.level}")
                if (cfg.hasBattleInputAddrs) {
                    val p = peeks(key)
                    val want = listOf(cfg.handleInputChooseAction, cfg.handleInputChooseMove, cfg.completeWhenChoseItem, cfg.waitForMonSelection)
                    check("handlers", p.size >= 4 && p.take(4).map { it and 1L.inv() } == want.map { it and 1L.inv() } &&
                        (cfg.partyMenu == 0L || p.getOrNull(4)?.let { it != 0L } == true),
                        p.joinToString { "%08X".format(it) } + " want " + want.joinToString { "%08X".format(it) })
                } else checks["handlers"] = "—"
                val names = t.party.map { speciesName(it.species) } + t.items.map { itemName(it.itemId) } +
                    t.party.flatMap { m -> m.moves.filter { it != 0 }.map { lookupMove(it).name } } +
                    listOf(lookupLocation(t.regionMapSectionId).mapSecName)
                check("names", names.none { '#' in it || it.isBlank() }, names.filter { '#' in it || it.isBlank() }.take(3).toString())
                checks["sample"] = "${speciesName(t.party.first().species)} · ${itemName(t.items.first().itemId)} · ${lookupLocation(t.regionMapSectionId).mapSecName}"
            }.onFailure { checks["error"] = "❌ ${it::class.simpleName}: ${it.message}" }
            if (checks.values.any { it.startsWith("❌") }) failures++
            val cols = listOf("party", "bag", "money", "place", "boxes", "dex", "guide", "trainer", "battle", "handlers")
            rows.append("| $file | $key | " + cols.joinToString(" | ") { checks[it] ?: "?" } + " | ${checks["sample"] ?: checks["error"] ?: ""} |\n")
        }
        File(dir, "report.md").writeText("# Ported ROMs\n\n$failures failing\n\n$rows")
        assertTrue("$failures ports failed - see build/ports/report.md", failures == 0)
    }
}

/** What the battle check compares: a battler's species and level. */
private data class BattleMonView(val species: Int, val level: Int)
