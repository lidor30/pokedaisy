package com.pokedaisy.app

import com.pokedaisy.app.companion.data.RETAIL_DUMPS
import com.pokedaisy.app.companion.data.RETAIL_PORT_CODE_TITLES
import com.pokedaisy.app.companion.data.TelemetrySampler
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The website's compatibility data (`website/src/data/compat.json`), built from the tables
 * [CompanionSupport.isSupported] and the Poller's detect() use, so the site's ROM checker
 * (`website/src/lib/compat.js`, the same rules in JavaScript) can't drift from the app, plus
 * README.md's feature matrix (the site's game list), so the two pages say the same.
 * Fails when the checked-in file is stale; regenerate it with
 * `UPDATE_SITE_DATA=1 ./gradlew :app:testDebugUnitTest --tests '*SiteDataExportTest'`.
 */
class SiteDataExportTest {
    private val root = generateSequence(File("").absoluteFile) { it.parentFile }
        .first { File(it, "settings.gradle.kts").isFile }
    private val out = File(root, "website/src/data/compat.json")

    /** English and the other Emeralds have no generated port, so no entry in [RETAIL_PORT_CODE_TITLES]. */
    private val codeTitles = RETAIL_PORT_CODE_TITLES + mapOf(
        "BPRE" to "Pokémon FireRed",
        "BPEE" to "Pokémon Emerald",
        "BPGE" to "Pokémon LeafGreen",
        "BPES" to "Pokémon Edición Esmeralda",
        "BPED" to "Pokémon Smaragd-Edition",
        "BPEF" to "Pokémon Version Émeraude",
        "BPEI" to "Pokémon Versione Smeraldo",
        "BPEJ" to "ポケットモンスター エメラルド",
    )

    /** The DS carts' titles by game code ([TelemetrySampler.NDS_GAMES]). */
    private val dsTitles = mapOf("CPUE" to "Pokémon Platinum", "IPKE" to "Pokémon HeartGold", "IPKD" to "Pokémon Goldene Edition HeartGold", "IPGD" to "Pokémon Silberne Edition SoulSilver", "IPGE" to "Pokémon SoulSilver", "IRBO" to "Pokémon Black")

    /** The base game of each hack that has a game code of its own ([TelemetrySampler.HACK_GAME_CODES]). */
    private val hackCodeBases = mapOf(TelemetrySampler.ULTRA_VIOLET_CODE to "BPRE")

    /** `LAZARUS_V2_0_SHA1` -> v2.0, `EMERALD_ROGUE_V2_2_1_EX_SHA1` -> v2.2.1-EX, `SOULGOLD_V1_2B_SHA1` -> v1.2b,
     * `QUETZAL_V9_0_ALPHA_ES_SHA1` -> v9.0-ALPHA-ES, `MARIOMON_V1_5_2_Anniversary_SHA1` -> v1.5.2-Anniversary (a tag
     * keeps its own case); a build with no version of its own is `*_UNVERSIONED_SHA1` -> "". */
    private val hackVersions: Map<String, String> by lazy {
        val name = Regex("""_V(\d+(?:_\d+)*)([A-Z]?)((?:_[A-Z][A-Za-z]*)*)_SHA1$""")
        TelemetrySampler::class.java.declaredFields
            .filter { java.lang.reflect.Modifier.isStatic(it.modifiers) && it.type == String::class.java }
            .mapNotNull { f ->
                f.isAccessible = true
                if (f.name.endsWith("_UNVERSIONED_SHA1")) return@mapNotNull f.get(null) as String to ""
                val m = name.find(f.name) ?: return@mapNotNull null
                val (digits, letter, tags) = m.destructured
                f.get(null) as String to buildString {
                    append("v").append(digits.replace('_', '.')).append(letter.lowercase())
                    tags.split('_').filter { it.isNotEmpty() }.forEach { append("-").append(it) }
                }
            }.toMap()
    }

    /** README's "Supported games" table (the hand-checked feature matrix): its columns, then each row's
     * game, version and cells (`yes` ✅ / `part` ◐ / `no` —). */
    private fun readmeMatrix(): Pair<List<String>, List<List<String>>> {
        val lines = File(root, "README.md").readLines()
        val from = lines.indexOfFirst { it.trim() == "## Supported games" }
        val table = lines.drop(from + 1).takeWhile { !it.startsWith("## ") }
            .filter { it.trimStart().startsWith("|") }
            .map { row -> row.trim().removePrefix("|").removeSuffix("|").split("|").map { it.trim() } }
        assertTrue("no Supported games table in README.md", table.size > 2)
        val cell = mapOf("✅" to "yes", "◐" to "part", "—" to "no")
        val rows = table.drop(2).map { r -> r.take(2) + r.drop(2).map { c -> cell[c] ?: error("README matrix: unknown cell '$c' in $r") } }
        return table[0].drop(2) to rows
    }

    private fun generate(): String {
        val retail = TelemetrySampler.OTHER_RETAIL_CODES.sorted().mapNotNull { code ->
            val revs = (0..3).filter { TelemetrySampler.otherRetailConfig(code, it) != null }
            if (revs.isEmpty()) null else Triple(code, revs, codeTitles.getValue(code))
        }
        val hackSha1s = TelemetrySampler.SUPPORTED_HACK_SHA1S
        // A game whose README row is all "—" plays but has no companion yet (R.O.W.E.): the site lists it
        // as in progress, not as supported, and leaves it out of the matrix.
        val (columns, allRows) = readmeMatrix()
        val inProgressGames = allRows.filter { r -> r.drop(2).all { it == "no" } }.map { it[0] }.toSet()
        val rows = allRows.filter { it[0] !in inProgressGames }
        val (inProgress, hacks) = hackSha1s.map { Triple(it, GameTitles.BY_SHA1.getValue(it), hackVersions.getValue(it)) }
            .sortedWith(compareBy({ it.second }, { it.third }))
            .partition { it.second in inProgressGames }
        val gameBoy = if (TelemetrySampler.GAME_BOY_SUPPORT) TelemetrySampler.SUPPORTED_GB_SHA1S.sorted() else emptyList()
        val ds = if (TelemetrySampler.NDS_SUPPORT) TelemetrySampler.NDS_GAMES.toSortedMap().toList() else emptyList()
        // DS carts go by their header's code and revision (`ds`), as the app takes them, not by hash.
        val titles = GameTitles.BY_SHA1.filterKeys { it !in hackSha1s && it !in gameBoy && it !in GameTitles.NDS_SHA1S }.toSortedMap()

        return buildString {
            append("{\n")
            append("  \"_generated\": ${q("by app/src/test/kotlin/com/pokedaisy/app/SiteDataExportTest.kt - don't edit by hand")},\n")
            append("  \"maxBaseSize\": 16777216,\n")
            append("  \"baseCodes\": {${listOf("BPRE", "BPEE").joinToString(", ") { "${q(it)}: ${q(codeTitles.getValue(it))}" }}},\n")
            // Hacks under a game code of their own (Ultra Violet's PUVV): their base game's title.
            append("  \"hackCodes\": {${TelemetrySampler.HACK_GAME_CODES.sorted().joinToString(", ") { "${q(it)}: ${q(codeTitles.getValue(hackCodeBases.getValue(it)))}" }}},\n")
            append("  \"retail\": [\n")
            append(retail.joinToString(",\n") { (code, revs, title) ->
                "    {\"code\": ${q(code)}, \"revs\": [${revs.joinToString(", ")}], \"title\": ${q(title)}}"
            })
            append("\n  ],\n")
            for ((key, list) in listOf("hacks" to hacks, "inProgress" to inProgress)) {
                append("  ${q(key)}: [\n")
                append(list.joinToString(",\n") { (sha1, title, version) ->
                    "    {\"sha1\": ${q(sha1)}, \"title\": ${q(title)}, \"version\": ${q(version)}}"
                })
                append("\n  ],\n")
            }
            // Retail FireRed / Emerald dumps with the end trimmed / save code patched (RetailDumps): read as retail.
            append("  \"retailDumps\": [\n")
            append(RETAIL_DUMPS.joinToString(",\n") { d ->
                "    {\"code\": ${q(d.code)}, \"rev\": ${d.rev}, \"end\": ${d.end}, \"ranges\": [" +
                    d.ranges.joinToString(", ") { "[${it.first}, ${it.last + 1}]" } +
                    "], \"sha1\": ${q(d.sha1)}, \"retailSha1\": ${q(d.retailSha1)}}"
            })
            append("\n  ],\n")
            append("  \"gameBoy\": [\n")
            append(gameBoy.joinToString(",\n") { "    {\"sha1\": ${q(it)}, \"title\": ${q(GameTitles.BY_SHA1.getValue(it))}}" })
            append("\n  ],\n")
            append("  \"ds\": [\n")
            append(ds.joinToString(",\n") { (code, rev) -> "    {\"code\": ${q(code)}, \"rev\": $rev, \"title\": ${q(dsTitles.getValue(code))}}" })
            append("\n  ],\n")
            append("  \"titles\": {\n")
            append(titles.entries.joinToString(",\n") { (sha1, title) -> "    ${q(sha1)}: ${q(title)}" })
            append("\n  },\n")
            append("  \"matrix\": {\n")
            append("    \"columns\": [${columns.joinToString(", ") { q(it) }}],\n")
            append("    \"rows\": [\n")
            append(rows.joinToString(",\n") { r ->
                "      {\"game\": ${q(r[0])}, \"version\": ${q(r[1])}, \"cells\": [${r.drop(2).joinToString(", ") { q(it) }}]}"
            })
            append("\n    ]\n  }\n}\n")
        }
    }

    private fun q(s: String) = buildString {
        append('"')
        for (c in s) when {
            c == '"' -> append("\\\"")
            c == '\\' -> append("\\\\")
            c < ' ' -> append("\\u%04x".format(c.code))
            else -> append(c)
        }
        append('"')
    }

    @Test
    fun `every supported hack has a version`() {
        val missing = TelemetrySampler.SUPPORTED_HACK_SHA1S - hackVersions.keys
        assertTrue("no *_V<version>_SHA1 constant for $missing", missing.isEmpty())
        assertEquals("v2.2.1-EX", hackVersions[TelemetrySampler.EMERALD_ROGUE_V2_2_1_EX_SHA1])
        assertEquals("v1.2b", hackVersions[TelemetrySampler.SOULGOLD_V1_2B_SHA1])
    }

    @Test
    fun `every DS cart has a title`() {
        assertEquals(TelemetrySampler.NDS_GAMES.keys, dsTitles.keys)
    }

    @Test
    fun `every hack game code has a base game`() {
        assertEquals(TelemetrySampler.HACK_GAME_CODES, hackCodeBases.keys)
    }

    @Test
    fun `website compat json is up to date`() {
        val json = generate()
        if (System.getenv("UPDATE_SITE_DATA") == "1") {
            out.parentFile.mkdirs()
            out.writeText(json)
            return
        }
        assertTrue("${out.path} is missing - run with UPDATE_SITE_DATA=1", out.isFile)
        assertEquals(
            "${out.path} is stale: UPDATE_SITE_DATA=1 ./gradlew :app:testDebugUnitTest --tests '*SiteDataExportTest'",
            json, out.readText(),
        )
    }
}
