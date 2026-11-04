package render

import com.pokedaisy.app.ExportCore
import com.pokedaisy.app.MgbaCore
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import com.pokedaisy.app.companion.data.*
import com.pokedaisy.app.companion.ui.OptionColors
import com.pokedaisy.app.companion.ui.PartyPalette
import com.pokedaisy.app.companion.ui.PartySlotColors
import com.pokedaisy.app.companion.ui.partyPaletteFor
import com.pokedaisy.app.companion.ui.partySlotStyleFor
import com.pokedaisy.app.companion.ui.theme.APP_THEMES
import com.pokedaisy.app.companion.ui.theme.AppColors
import com.pokedaisy.app.companion.ui.theme.DarkMode
import com.pokedaisy.app.companion.ui.theme.SEASONAL_THEMES
import java.io.File
import java.security.MessageDigest
import java.util.zip.CRC32
import org.json.JSONArray
import org.json.JSONObject

/**
 * The 3DS port's game data, from the app itself: every ROM under a folder goes through the app's own detection
 * (TelemetrySampler, with MgbaCore answering from the file: -PgameExport), and what it picks - the config, the name
 * tables - is written as JSON, one file a table set, for 3ds/tools/gen_games.py. So the C port never re-implements the
 * app's choices.
 */
private fun fields(value: Any?, depth: Int = 0): Any? = when (value) {
    null -> JSONObject.NULL
    is Boolean, is Number, is String -> value
    is Char -> value.toString()
    is Enum<*> -> value.name
    is IntRange -> JSONArray(listOf(value.first, value.last))
    is LongArray -> JSONArray(value.toList())
    is IntArray -> JSONArray(value.toList())
    is ByteArray -> JSONArray(value.map { it.toInt() and 0xFF })
    is Iterable<*> -> JSONArray(value.map { fields(it, depth + 1) })
    is Map<*, *> -> JSONObject().also { o -> value.forEach { (k, v) -> o.put(k.toString(), fields(v, depth + 1)) } }
    else -> {
        if (depth > 6) JSONObject.NULL
        else {
            val o = JSONObject()
            for (f in value.javaClass.declaredFields) {
                if (java.lang.reflect.Modifier.isStatic(f.modifiers) || f.name.contains("$")) continue
                f.isAccessible = true
                runCatching { o.put(f.name, fields(f.get(value), depth + 1)) }
            }
            o
        }
    }
}

private fun sha1(bytes: ByteArray) = MessageDigest.getInstance("SHA-1").digest(bytes).joinToString("") { "%02x".format(it) }

// The tables the data module keeps internal.
private fun internalTable(file: String, getter: String): IntArray? =
    Class.forName("com.pokedaisy.app.companion.data.$file").methods.first { it.name.startsWith(getter) }.invoke(null) as IntArray?

// withNativeGender's table: each game's gBaseStats ratios, by its own species ids.
private fun genderRatios(): IntArray? = when (activeGame) {
    GameKind.FIRERED -> runningFireRedHack?.genderRatios ?: genderRatiosFireRed
    GameKind.EMERALD, GameKind.RECHARGED_YELLOW -> genderRatiosFireRed
    GameKind.UNBOUND -> genderRatiosUnbound
    GameKind.RADICAL_RED -> genderRatiosRadicalRed
    GameKind.ODYSSEY -> genderRatiosOdyssey
    GameKind.AMETHYST -> if (amethystV141) genderRatiosAmethystV141 else genderRatiosAmethyst
    GameKind.HEART_AND_SOUL -> genderRatiosHns
    GameKind.LAZARUS -> genderRatiosLazarus
    GameKind.SOULGOLD -> genderRatiosSoulGold
    GameKind.EMERALD_SEAGLASS -> genderRatiosSeaglass
    GameKind.GLAZED -> genderRatiosGlazed
    GameKind.IMPERIUM -> genderRatiosImperium
    GameKind.QUETZAL -> genderRatiosQuetzal
    GameKind.ROWE -> genderRatiosRowe
    GameKind.ORANGE_ISLANDS -> genderRatiosOrangeIslands
    GameKind.SWORD_SHIELD -> genderRatiosSwordShield
    GameKind.GS_CHRONICLES -> genderRatiosGsChronicles
    GameKind.MARIOMON -> if (mariomonV13) genderRatiosMariomonV13 else genderRatiosMariomon
    GameKind.ELITE_REDUX -> internalTable("EliteReduxGenKt", "getGenderRatiosEliteRedux")
    GameKind.TEAM_ROCKET -> if (teamRocketV3) genderRatiosTeamRocketV3 else internalTable("TeamRocketGenKt", "getGenderRatiosTeamRocket")
    GameKind.WISHES -> genderRatiosWishes
    GameKind.MONOMYTH -> internalTable("MonomythGenKt", "getGenderRatiosMonomyth")
    GameKind.DREAMSTONE -> genderRatiosDreamstone
    else -> null
}

private fun tables(): JSONObject = JSONObject().apply {
    // In the game's own casing, as the app draws them.
    put("species", JSONObject().also { o -> activeSpeciesNames.keys.forEach { o.put(it.toString(), speciesName(it)) } })
    put("moves", JSONObject().also { o -> activeMoveData.forEach { (k, v) -> o.put(k.toString(), JSONArray(listOf(lookupMove(k).name, v.type, v.power))) } })
    put("items", JSONObject().also { o -> activeItemNames.keys.forEach { o.put(it.toString(), itemName(it)) } })
    put("gender", fields(genderRatios()))
    put("types", JSONObject().also { o -> activeSpeciesTypeData.forEach { (k, v) -> o.put(k.toString(), JSONArray(listOf(v.type1, v.type2))) } })
    put("chart", fields(activeTypeEffectiveness))
    put("typeNames", fields(activeTypeNames))
    put("mapsecs", JSONObject().also { o -> activeMapSecData.forEach { (k, v) -> o.put(k.toString(), JSONArray(listOf(v.name, v.region, v.x, v.y, v.w, v.h))) } })
}

// A Game Boy game's Gen1Config / Gen2Config, which the sampler keeps to itself, with what the readers need beside it.
private fun gbConfig(sampler: TelemetrySampler, sha1: String): JSONObject? {
    fun held(name: String): Any? = TelemetrySampler::class.java.getDeclaredField(name).apply { isAccessible = true }.get(sampler)
    val cfg = held("gen1Cfg") ?: held("gen2Cfg") ?: return null
    val o = JSONObject()
    for (f in cfg.javaClass.declaredFields) {
        if (java.lang.reflect.Modifier.isStatic(f.modifiers) || f.name.contains("$") || f.name == "pokedex") continue
        f.isAccessible = true
        o.put(f.name, fields(f.get(cfg)))
    }
    if (cfg is Gen1Config) {
        o.put("gen", 1)
        val charset = when (cfg.language) { 'D', 'F' -> Gen1Charset.DF; 'I', 'S' -> Gen1Charset.IS; else -> Gen1Charset.EN }
        o.put("chars", JSONArray((0..255).map { charset.chars[it] ?: "" }))
        cfg.pokedex?.gen1?.let { o.put("dexOwned", it.owned).put("dexSeen", it.seen) }
    } else if (cfg is Gen2Config) {
        o.put("gen", 2)
        @Suppress("UNCHECKED_CAST")
        val chars = Gen2Art.javaClass.methods.first { it.name.startsWith("chars") }.invoke(Gen2Art, cfg.polished) as Map<Int, Char>
        o.put("chars", JSONArray((0..255).map { chars[it]?.toString() ?: "" }))
        if (cfg.polished) {
            o.put("forms", fields(polishedVariantForms))
            o.put("cosmeticForms", 0x38) // Gen2Art.POLISHED_NUM_COSMETIC_FORMS, private there
        }
    }
    GbField.forGame(activeGame)?.let { f ->
        if (f === GbField.Crystal) {
            o.put("field", JSONObject().put("crystal", true))
        } else {
            val j = JSONObject()
            for (x in f.javaClass.declaredFields + f.javaClass.superclass.declaredFields) {
                if (java.lang.reflect.Modifier.isStatic(x.modifiers) || x.name.contains("$")) continue
                x.isAccessible = true
                j.put(x.name, fields(x.get(f)))
            }
            o.put("field", j)
        }
    }
    gbDexTables(cfg, sha1)?.let { t ->
        val d = JSONObject().put("count", t.nationalCount).put("metric", t.metric).put("suffix", t.categorySuffix)
            .put("decimal", t.decimalPoint.toString()).put("unitSpace", t.unitSpace)
        t.gen1?.let { g ->
            d.put("gen1", fields(g)).put("chars", JSONArray((0..255).map { g.charset.chars[it] ?: "" }))
        }
        t.gen2?.let { g ->
            d.put("gen2", fields(g))
            @Suppress("UNCHECKED_CAST")
            val chars = Gen2Dex.javaClass.getDeclaredField("CHARS").apply { isAccessible = true }.get(Gen2Dex) as Map<Int, String>
            d.put("chars", JSONArray((0..255).map { chars[it] ?: "" }))
            if (g.polished != null) {
                d.put("polishedSpecies", t.speciesCount - 1)
                val text = Class.forName("com.pokedaisy.app.companion.data.Gen2PolishedCrystalGenKt").methods.first { it.name.startsWith("getPolishedTextChars") }
                d.put("polishedChars", JSONArray((text.invoke(null) as Array<*>).toList()))
            }
        }
        o.put("dex", d)
    }
    // Where the cart keeps its art: the art objects' tables, by this ROM's hash.
    val art = (if (cfg is Gen1Config) Gen1Art else Gen2Art).let { a ->
        @Suppress("UNCHECKED_CAST")
        val games = a.javaClass.getDeclaredField("GAMES").apply { isAccessible = true }.get(a) as List<Any>
        games.firstOrNull { t ->
            val f = t.javaClass.declaredFields.first { it.name.startsWith("sha1") }.apply { isAccessible = true }.get(t)
            f == sha1 || (f as? Set<*>)?.contains(sha1) == true
        }
    }
    if (art != null) {
        val a = JSONObject()
        for (f in art.javaClass.declaredFields) {
            if (java.lang.reflect.Modifier.isStatic(f.modifiers) || f.name.contains("$") || f.name == "chars" || f.name == "charset") continue
            f.isAccessible = true
            a.put(f.name, fields(f.get(art)))
        }
        o.put("art", a)
    }
    return o
}

// The POKéDEX tables of a Game Boy game's config: Polished Crystal's are per build, the Poller's pick.
private fun gbDexTables(cfg: Any, sha1: String): PokedexTables? = when (cfg) {
    is Gen1Config -> cfg.pokedex
    is Gen2Config -> if (cfg.polished) polishedCrystalPokedex(sha1) else cfg.pokedex
    else -> null
}

// Every dex page of a Game Boy cart as the app reads it, for 3ds/test/gb_check.c to hold the C port against.
private fun gbDexCheck(out: File, cfg: Any, sha1: String, crc: Long, rom: ByteArray) {
    val t = gbDexTables(cfg, sha1) ?: return
    Gen1Dex.rom = rom
    val pages = JSONArray()
    for (dex in 1..t.nationalCount) {
        val e = (t.gen1?.let { Gen1Dex.entry(it, dex) } ?: t.gen2?.let { Gen2Dex.entry(it, dex) }) ?: continue
        val px = t.gen1?.let { Gen1Dex.frontPixels(it, dex) } ?: t.gen2?.let { Gen2Dex.frontPixels(it, e.species) }
        var hash = 0
        px?.forEach { hash = hash * 31 + it }
        pages.put(JSONArray(listOf(dex, e.species, e.category, e.heightDm, e.weightHg, e.description, e.type1, e.type2,
            JSONArray(e.baseStats), e.heightIn ?: -1, e.weightLbs10 ?: -1, formatDexHeight(t, e), formatDexWeight(t, e),
            if (px == null) 0 else 1, hash.toLong() and 0xFFFFFFFFL)))
    }
    File(out, "gbcheck").mkdirs()
    File(out, "gbcheck/dex-%08x.json".format(crc)).writeText(pages.toString())
}

// What HERE's world gives for every map and clock of a Game Boy guide, and each team, for the same check.
private fun gbGuideCheck(out: File, id: GuideId) {
    val g = gameGuide(id, false) ?: return
    val w = g.world ?: return
    val gen1 = id == GuideId.YELLOW || id == GuideId.RED || id == GuideId.BLUE
    val check = JSONArray()
    fun rows(l: List<EncounterSlot>) = JSONArray(l.map { JSONArray(listOf(it.species, it.minLevel, it.maxLevel, it.percent)) })
    for (tod in 0..3) for (badges in listOf(0, 8, 16)) {
        val flags = ByteArray(0x200)
        val banks = listOf(SaveBank(0x10000, 0, 16) to byteArrayOf(((1 shl (badges.coerceAtMost(8))) - 1).toByte(), if (badges == 16) -1 else 0))
        val p = SaveProgress(flags, ByteArray(0), flagBanks = banks,
            varBanks = listOf(SaveBank(0x10000, 0, 1) to byteArrayOf(tod.toByte(), 0)))
        val maps = if (gen1) (0..255).map { 0 to it } else (0..255).flatMap { a -> (0..255).map { a to it } }
        for ((group, num) in maps) {
            val e = w.encounters(group, num, p)
            val lists = listOf(e.grass, e.water, e.rockSmash, e.oldRod, e.goodRod, e.superRod, e.headbutt, e.headbuttRare)
            if (lists.all { it.isEmpty() }) continue
            check.put(JSONArray(listOf(tod, badges, group, num, JSONArray(lists.map { rows(it) }))))
        }
    }
    File(out, "gbcheck").mkdirs()
    File(out, "gbcheck/wild-$id.json").writeText(check.toString())
}

private fun argb(c: Color?): Long = c?.toArgb()?.toLong()?.and(0xFFFFFFFFL) ?: -1L
private fun layers(l: List<Pair<Color, Int>>) = JSONArray(l.map { JSONArray(listOf(argb(it.first), it.second)) })

// OptionColors as it stands: every colour of the windows.
private fun look(): JSONObject = JSONObject().apply {
    val o = OptionColors
    put("titleLayers", layers(o.titleLayers)); put("listLayers", layers(o.listLayers)); put("chipLayers", layers(o.chipLayers))
    put("fieldMoveLayers", layers(o.fieldMoveLayers))
    for ((name, c) in listOf(
        "titleFill" to o.titleFill, "titleText" to o.titleText, "titleShadow" to o.titleShadow, "listFill" to o.listFill,
        "rowSelected" to o.rowSelected, "label" to o.label, "labelShadow" to o.labelShadow, "value" to o.value,
        "valueShadow" to o.valueShadow, "success" to o.success, "fieldMove" to o.fieldMove, "fieldMoveShadow" to o.fieldMoveShadow,
        "featureFill" to o.featureFill, "featureText" to o.featureText, "featureShadow" to o.featureShadow, "muted" to o.muted,
        "mutedShadow" to o.mutedShadow, "frameDark" to o.frameDark, "frameLight" to o.frameLight, "onBackdrop" to o.onBackdrop,
        "onBackdropShadow" to o.onBackdropShadow, "tabSelectedFill" to o.tabSelectedFill, "tabIdleFill" to o.tabIdleFill,
        "groupTitleFill" to o.groupTitleFill, "groupTitleText" to o.groupTitleText, "groupTitleShadow" to o.groupTitleShadow,
        "divider" to o.divider, "badgeText" to o.badgeText, "newBadge" to o.newBadge, "favorite" to o.favorite,
        "imageWell" to o.imageWell, "hintBar" to o.hintBar, "hintShadow" to o.hintShadow,
    )) put(name, argb(c))
    put("scrim", o.backdropScrim.toDouble())
}

// Every theme, light and dark; then the Game Boy games' own windows.
private fun exportLooks(out: File) {
    val root = JSONObject()
    val themes = JSONArray()
    OptionColors.inGame = true
    activeGame = GameKind.FIRERED
    for (t in APP_THEMES + SEASONAL_THEMES) {
        AppColors.applyTheme(t)
        val e = JSONObject().put("id", t.id).put("label", t.label).put("seasonal", t.season != null).put("rainbow", t.rainbow)
            .put("wash", argb(t.gameWash))
        DarkMode.override = false
        e.put("light", look())
        DarkMode.override = true
        e.put("dark", look())
        themes.put(e)
    }
    root.put("themes", themes)
    AppColors.applyTheme(APP_THEMES.first { it.id == com.pokedaisy.app.companion.ui.theme.DAISY_THEME_ID })
    val gb = JSONObject()
    for (k in listOf(GameKind.YELLOW, GameKind.RED, GameKind.PURE_RED, GameKind.CRYSTAL)) {
        activeGame = k
        DarkMode.override = false
        val light = look()
        DarkMode.override = true
        gb.put(k.name, JSONObject().put("light", light).put("dark", look()))
    }
    root.put("gb", gb)
    File(out, "looks.json").writeText(root.toString())
}

private fun slotColors(c: PartySlotColors) = JSONArray(listOf(argb(c.outline), argb(c.light), argb(c.fill), argb(c.shade), argb(c.band),
    argb(c.text), argb(c.textShadow), argb(c.stripe)))

private fun partyPalette(p: PartyPalette) = JSONObject().apply {
    put("normal", slotColors(p.normal)); put("selected", slotColors(p.selected)); put("fainted", slotColors(p.fainted))
    put("selectedFainted", slotColors(p.selectedFainted))
    for ((n, c) in listOf("text" to p.text, "textShadow" to p.textShadow, "male" to p.male, "maleShadow" to p.maleShadow,
        "female" to p.female, "femaleShadow" to p.femaleShadow, "hpFrame" to p.hpFrame, "hpInner" to p.hpInner,
        "hpLabel" to p.hpLabel, "hpLabelShade" to p.hpLabelShade)) put(n, argb(c))
    fun bar(b: com.pokedaisy.app.companion.ui.HpBarColors) = JSONArray(listOf(argb(b.top), argb(b.main)))
    put("hpGreen", bar(p.hpGreen)); put("hpYellow", bar(p.hpYellow)); put("hpRed", bar(p.hpRed)); put("hpEmpty", bar(p.hpEmpty))
    put("ball", JSONObject().also { o -> p.ball.forEach { (k, v) -> o.put(k.toString(), argb(v)) } })
    put("emptyAlpha", p.emptyAlpha.toDouble())
    put("empty", p.empty?.let { slotColors(it) } ?: JSONObject.NULL)
    put("chamfer", p.chamfer)
}

// A private data class of Colors, by reflection: an unboxed Color is a long, its sRGB ARGB in the top half.
private fun colorsOf(value: Any?): Any? = when (value) {
    null -> JSONObject.NULL
    is Long -> (value ushr 32) and 0xFFFFFFFFL
    is Color -> argb(value)
    is Int, is Boolean, is String -> value
    is Pair<*, *> -> JSONArray(listOf(colorsOf(value.first), colorsOf(value.second)))
    is List<*> -> JSONArray(value.map { colorsOf(it) })
    else -> JSONObject.NULL
}

private fun bagPalette(kind: GameKind): JSONObject {
    val file = Class.forName("com.pokedaisy.app.companion.ui.ItemsScreenKt")
    val m = file.declaredMethods.first { it.name == "bagPaletteFor" && it.parameterCount == 2 }.apply { isAccessible = true }
    val p = m.invoke(null, kind, 0)
    val o = JSONObject()
    for (f in p.javaClass.declaredFields) {
        if (java.lang.reflect.Modifier.isStatic(f.modifiers) || f.name.contains("$")) continue
        f.isAccessible = true
        o.put(f.name, colorsOf(f.get(p)))
    }
    return o
}

private fun backdrop(kind: GameKind): Any {
    val file = Class.forName("com.pokedaisy.app.companion.ui.theme.ThemeKt")
    val m = file.declaredMethods.first { it.name == "backdropColors" }.apply { isAccessible = true }
    return (m.invoke(null, kind) as IntArray?)?.let { c -> JSONArray(c.map { it.toLong() and 0xFFFFFFFFL }) } ?: JSONObject.NULL
}

// buildGameBackground's picks (Theme.kt) by name: the art behind the party, whether it's one cell tiled.
private fun backdropArt(kind: GameKind): Any? = when {
    kind.isGameBoyGame || (kind == GameKind.FIRERED && runningGhostGrey) -> null
    kind == GameKind.FIRERED && runningLetsGo -> JSONArray(listOf(RomArt.LETS_GO_BACKDROP, true))
    kind == GameKind.SWORD_SHIELD -> JSONArray(listOf(RomArt.SWSH_BACKDROP, true))
    backdrop(kind) != JSONObject.NULL -> JSONArray(listOf("partybg/firered.png", false))
    kind == GameKind.TEAM_ROCKET -> JSONArray(listOf("partybg/firered.png", false))
    kind == GameKind.EMERALD -> JSONArray(listOf(if (runningEmeraldHack != null) RomArt.RE_BACKDROP else "partybg/emerald.png", false))
    kind == GameKind.HEART_AND_SOUL -> JSONArray(listOf("partybg/hns.png", false))
    kind == GameKind.ELITE_REDUX -> JSONArray(listOf("partybg/emerald.png", false))
    kind in setOf(GameKind.UNBOUND, GameKind.RADICAL_RED, GameKind.ODYSSEY, GameKind.AMETHYST, GameKind.GS_CHRONICLES) ->
        JSONArray(listOf(RomArt.CFRU_BACKDROP, true))
    else -> JSONArray(listOf("partybg/firered.png", false))
}

private val seenGuides = HashSet<GuideId>()
private val seenGuideChecks = HashSet<GuideId>()

private fun have(h: Have?): JSONArray = when (h) {
    is Have.Flag -> JSONArray(listOf(1, h.flag))
    is Have.Item -> JSONArray(listOf(2, h.item))
    is Have.Caught -> JSONArray(listOf(3) + h.national.take(3))
    null -> JSONArray(listOf(0))
}

// The hand-written pages, the boss order and the area data of a GuideId, once each.
private fun exportGuide(out: File, id: GuideId) {
    if (!seenGuides.add(id)) return
    val g = gameGuide(id, false)
    val o = JSONObject()
    o.put("verified", g?.verified ?: false)
    val empty = SaveProgress(ByteArray(0x200), ByteArray(0x400))
    o.put("pages", JSONArray((g?.pages ?: emptyList()).map { p ->
        JSONObject().put("title", p.title).put("sections", JSONArray(p.sections.map { s ->
            JSONObject().put("heading", s.heading).put("note", s.note ?: JSONObject.NULL).put("entries", JSONArray(s.entries.map { e ->
                JSONObject().put("title", e.title).put("answer", e.answer).put("hint", e.hint ?: JSONObject.NULL)
                    .put("have", have(e.have)).put("areas", JSONArray(e.areas.map { areaKey(it) }))
            }))
        }))
    }))
    o.put("bosses", JSONArray((g?.bosses ?: emptyList()).map { b ->
        val trainer = runCatching { b.teams(empty).first().second }.getOrDefault(0)
        JSONObject().put("title", b.title).put("where", b.where).put("done", b.done).put("trainer", trainer)
    }))
    val areas = JSONArray()
    allAreaThings(id).toSortedMap().forEach { (sec, things) ->
        things.forEach { t ->
            areas.put(JSONArray(listOf(sec, t.kind.name, t.id, t.flag, t.qty, t.wants, t.where)))
        }
    }
    o.put("areas", areas)
    File(out, "guide-$id.json").writeText(o.toString())
}

fun main(args: Array<String>) {
    val out = File(args[1]).also { it.mkdirs() }
    val games = JSONArray()
    val seenTables = HashMap<String, String>()
    val roms = File(args[0]).walkTopDown().filter { it.isFile && it.extension.lowercase() in setOf("gba", "gb", "gbc") }.sortedBy { it.path }.toList()
    for (rom in roms) {
        val bytes = rom.readBytes()
        ExportCore.rom = bytes
        ExportCore.platform = if (rom.extension.lowercase() == "gba") MgbaCore.PLATFORM_GBA else MgbaCore.PLATFORM_GB
        activeNativeConfig = null
        romGameCode = ""
        romLanguage = 'E'
        fireRedHack = null
        emeraldHack = null
        randomizedRetail = null
        val sampler = TelemetrySampler()
        val view = runCatching { sampler.sample(InProcessReader) }
        val code = String(bytes, 0xAC, 4, Charsets.ISO_8859_1)
        val rev = bytes[0xBC].toInt() and 0xFF
        // English Unbound reads through a fallback config (the sampler's nativeCfg stays null).
        val cfg = activeNativeConfig ?: NATIVE_UNBOUND_WITH_DEX.takeIf { activeGame == GameKind.UNBOUND }
        val entry = JSONObject()
        entry.put("file", rom.name)
        entry.put("sha1", sha1(bytes))
        entry.put("crc32", CRC32().also { it.update(bytes) }.value)
        entry.put("size", bytes.size)
        entry.put("code", code)
        entry.put("rev", rev)
        entry.put("kind", activeGame.name)
        entry.put("title", com.pokedaisy.app.GameTitles.BY_SHA1[entry.getString("sha1")] ?: activeGame.displayName())
        entry.put("language", romLanguage.toString())
        entry.put("unsupported", view.getOrNull()?.unsupported ?: true)
        entry.put("fireRedHack", runningFireRedHack != null)
        entry.put("regionImages", JSONArray(activeRegionMapImages.toList()))
        entry.put("readsRomRegionMap", readsRomRegionMap)
        entry.put("letsGo", runningLetsGo)
        entry.put("error", view.exceptionOrNull()?.toString() ?: JSONObject.NULL)
        entry.put("config", if (cfg != null) fields(cfg) else JSONObject.NULL)
        // Which field-item table is this build's: the first candidate whose VarGet is where it says (a build's own CRCs).
        val items = fieldItemsCandidatesForGame(activeGame, code, rev, cfg?.fieldItems).firstOrNull { t ->
            val at = (t.varGet and 0x01FFFFFEL).toInt()
            at + 32 <= bytes.size && t.codeCrcs.isNotEmpty() &&
                CRC32().also { it.update(bytes, at, 32) }.value == t.codeCrcs[0]
        }
        entry.put("fieldItems", fields(items))
        entry.put("boxSwap", fields(boxSwapFor(items, cfg)))
        val gb = gbConfig(sampler, entry.getString("sha1"))
        if (gb != null) {
            fun held(name: String): Any? = TelemetrySampler::class.java.getDeclaredField(name).apply { isAccessible = true }.get(sampler)
            gbDexCheck(out, (held("gen1Cfg") ?: held("gen2Cfg"))!!, entry.getString("sha1"), entry.getLong("crc32"), bytes)
        }
        OptionColors.inGame = true
        for ((name, night) in listOf("light" to false, "dark" to true)) {
            DarkMode.override = night
            entry.put("party-$name", partyPalette(partyPaletteFor(activeGame)))
            entry.put("bag-$name", bagPalette(activeGame))
        }
        entry.put("backdrop", backdrop(activeGame))
        entry.put("backdropArt", backdropArt(activeGame) ?: JSONObject.NULL)
        entry.put("partyStyle", fields(partySlotStyleFor(activeGame)))
        entry.put("gb", gb ?: JSONObject.NULL)
        if (cfg != null || gb != null) {
            val text = tables().toString()
            val id = sha1(text.toByteArray()).take(12)
            if (id !in seenTables) {
                File(out, "tables-$id.json").writeText(text)
                seenTables[id] = rom.name
            }
            entry.put("tables", id)
            (cfg?.guideTables?.guide ?: if (gb != null) guideId(activeGame, null) else null)?.let { g ->
                entry.put("guide", g.name)
                exportGuide(out, g)
                if (gb != null && seenGuideChecks.add(g)) gbGuideCheck(out, g)
            }
        }
        games.put(entry)
        println("${rom.name}: ${activeGame.name} ${if (cfg != null) "config" else "no config"}")
    }
    exportLooks(out)
    File(out, "games.json").writeText(games.toString(1))
    println("${games.length()} ROMs, ${seenTables.size} table sets")
}
