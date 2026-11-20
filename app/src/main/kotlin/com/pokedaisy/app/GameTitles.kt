package com.pokedaisy.app

import android.content.Context
import com.pokedaisy.app.companion.data.RomCategory
import com.pokedaisy.app.companion.data.TelemetrySampler
import java.io.File
import okio.source

/**
 * The name a game goes by in the library ("Pokémon FireRed") instead of its ROM
 * file's ("Pokemon - FireRed Version (USA, Europe) (Rev 1)") - the file name stays
 * in the list row's second line and INFO. A ROM is named by its whole-file SHA1
 * (an archive by the ROM inside, [RomIdentity]): retail by the pret decomps'
 * `*.sha1`, hacks by the hashes [com.pokedaisy.app.companion.data.TelemetrySampler]
 * detects them with. Anything else (hacks it doesn't know, other games) keeps
 * its file name, tidied by [tidy].
 *
 * Hashing is up to 32 MB per ROM (128 MB a DS cart), so [identify] runs off the UI thread and caches
 * each file's answer by path + size + modified time (`rom-titles.tsv`); [label]
 * only reads that cache. Order: the player's own RENAME, then this, then the file.
 */
object GameTitles {

    val BY_SHA1 get() = GameTitleCatalog.BY_SHA1
    internal val NDS_SHA1S get() = GameTitleCatalog.NDS_SHA1S

    fun isRetail(sha1: String): Boolean = GameTitleCatalog.isRetail(sha1)

    /** What the library shows for [rom]: the player's RENAME, else the file's name (GAME NAMES) or [defaultLabel]. Cheap. */
    fun label(context: Context, prefs: Prefs, rom: File): String =
        GameTitleCatalog.label(
            fileName = { RomArchive.baseName(rom) },
            rename = prefs.romDisplayName(rom),
            preferFileName = { prefs.libraryFileNames },
            recognized = { title(context, rom) },
        )

    /** [rom]'s name with no RENAME: the game's ([title]) if known, else the tidied file name. */
    fun defaultLabel(context: Context, rom: File): String =
        GameTitleCatalog.label(fileName = { RomArchive.baseName(rom) }, recognized = { title(context, rom) })

    /** The game's name, if [identify] has already recognised this exact file. Cheap. */
    fun title(context: Context, rom: File): String? =
        cache(context)[rom.absolutePath]?.takeIf { it.matches(rom) }?.title?.let(GameTitleCatalog::shown)

    /** [rom]'s name as retail randomized by Universal Pokémon Randomizer ZX ([RandomizedRetail]), or null. Blocking. */
    internal fun randomizedTitle(rom: File, code: String?): String? {
        if (code != "BPRE" && code != "BPEE") return null
        val size = RomArchive.romSize(rom)
        val hit = com.pokedaisy.app.companion.data.RandomizedRetail.matchStream(code, RomIdentity.revision(rom), size) { RomArchive.open(rom).source() }
        return hit?.let { GameTitleCatalog.randomizedTitle(it.retailSha1) }
    }

    /** What kind of game [rom] is, if [identify] has looked at this exact file. Cheap. */
    fun category(context: Context, rom: File): RomCategory? =
        cache(context)[rom.absolutePath]?.takeIf { it.matches(rom) }?.category

    /** Which console [rom] runs on (GB / GBC / GBA / NDS, never HACK: a hack is its base game's), if [identify] has
     * looked at this exact file. Cheap. The library's quick views filter on it. */
    fun platform(context: Context, rom: File): RomCategory? =
        cache(context)[rom.absolutePath]?.takeIf { it.matches(rom) }?.platform

    /** A 3DS cart image (NCSD header), never hashed: they're gigabytes. */
    private fun is3ds(rom: File) =
        rom.extension.lowercase() in com.pokedaisy.app.n3ds.N3dsCart.EXTENSIONS && com.pokedaisy.app.n3ds.N3dsCart.isCart(rom)

    /** [rom]'s console from its header: [categoryOf] without the hack check. */
    internal fun platformOf(rom: File, sha1: () -> String?): RomCategory = when {
        is3ds(rom) -> RomCategory.N3DS
        RomIdentity.isNds(rom) -> RomCategory.NDS
        RomIdentity.isGameBoy(rom) -> gameBoyPlatform(rom, sha1)
        else -> RomCategory.GBA
    }

    /**
     * GB or GBC. The header's 0x143 says Color-only (0xC0: Crystal) or not at all (0x00: Red / Blue), but a dual-mode cart
     * (0x80) can be either: Yellow is a Game Boy game with colours added, Gold / Silver are Color games that also run on
     * the Game Boy (GitHub #271), and Polished Crystal sets 0x80 but only runs on a Color (#237). Known carts go by
     * [sha1] ([COLOR_DUAL]); any other dual-mode cart by its file's extension, as the dump sets name them (No-Intro's
     * "(GB Compatible).gbc" vs "(CGB+SGB Enhanced).gb").
     */
    private fun gameBoyPlatform(rom: File, sha1: () -> String?): RomCategory {
        val flag = RomArchive.readAt(rom, 0x143L, 1)?.firstOrNull()?.toInt()?.and(0xFF)
        return when {
            flag == 0xC0 -> RomCategory.GBC
            flag != 0x80 -> RomCategory.GB
            sha1().let { it in COLOR_DUAL || it !in TelemetrySampler.SUPPORTED_GB_SHA1S && romExtension(rom) == "gbc" } ->
                RomCategory.GBC
            else -> RomCategory.GB
        }
    }

    /** [rom]'s extension, or its ROM's inside an archive. */
    private fun romExtension(rom: File): String =
        (if (RomArchive.isArchive(rom)) RomArchive.romEntry(rom)?.name?.substringAfterLast('/') else rom.name)
            ?.substringAfterLast('.', "")?.lowercase().orEmpty()

    /** Color games with a dual-mode header (0x80) that the companion knows. Yellow says the same but is a GB game. */
    private val COLOR_DUAL: Set<String>
        get() = TelemetrySampler.POLISHED_CRYSTAL_SHA1S + TelemetrySampler.GOLD_SHA1 + TelemetrySampler.SILVER_SHA1 +
            TelemetrySampler.GOLD_DE_SHA1 + TelemetrySampler.SILVER_DE_SHA1

    /** [rom]'s [RomCategory] (reads its header; [sha1] when [identify] hashed it). A Pokémon cart hashed but known as
     * neither retail nor a hack is a hack too (an unsupported one); a cart nobody hashes is its platform's. */
    private fun categoryOf(rom: File, sha1: String?, hashed: Boolean, randomized: Boolean = false): RomCategory = when {
        is3ds(rom) -> RomCategory.N3DS
        RomIdentity.isNds(rom) -> RomCategory.NDS
        sha1 != null && GameTitleCatalog.knownHack(sha1) -> RomCategory.HACK
        // A randomized retail cart is still the retail game.
        randomized -> RomCategory.GBA
        hashed && sha1 != null && !isRetail(sha1) -> RomCategory.HACK
        RomIdentity.isGameBoy(rom) -> gameBoyPlatform(rom) { sha1 }
        else -> RomCategory.GBA
    }

    /**
     * Recognises every ROM in [roms] not already cached (or changed since). Blocking
     * (hashes) - never on the UI thread. True when a new answer was cached, i.e. a
     * name may have changed and the list should redraw.
     *
     * The hashing (up to 512 MB a DS cart) runs outside the cache's lock, which the UI thread's
     * [label] / [category] / [platform] take: a pass holds it only to copy the cache and to put
     * each answer in. Passes run one at a time ([passLock]), so no file is hashed twice and the
     * cache file has one writer.
     */
    fun identify(context: Context, roms: List<File>): Boolean = synchronized(passLock) {
        val snapshot = synchronized(this) { HashMap(cache(context)) }
        var changed = false
        for (rom in roms) {
            val known = snapshot[rom.absolutePath]?.takeIf { it.matches(rom) && it.category != null }
            if (!rom.isFile || known?.platform != null) continue
            // Named before platforms were kept: only the header to read, no hash.
            val entry = if (known != null) {
                Entry(known.size, known.modified, known.title, known.category, platformOf(rom) { RomIdentity.sha1(rom) })
            } else {
                val nds = RomIdentity.isNds(rom)
                val worthHashing = if (nds) GameTitleCatalog.knowsDsCode(RomIdentity.ndsGameCode(rom))
                    else GameTitleCatalog.knowsGbaCode(RomIdentity.gameCode(rom)) || RomIdentity.isGameBoy(rom)
                val sha1 = if (worthHashing) RomIdentity.sha1(rom) else null
                val randomized = if (sha1 != null && sha1 !in BY_SHA1 && !nds) randomizedTitle(rom, RomIdentity.gameCode(rom)) else null
                val title = sha1?.let { BY_SHA1[it] } ?: randomized
                Entry(rom.length(), rom.lastModified(), title, categoryOf(rom, sha1, worthHashing, randomized != null), platformOf(rom) { sha1 })
            }
            synchronized(this) { cache(context)[rom.absolutePath] = entry }
            changed = true
        }
        // Forget files that are gone, so the cache doesn't grow forever.
        val gone = snapshot.keys.filter { !File(it).isFile }
        val pruned = gone.isNotEmpty() && synchronized(this) { cache(context).keys.removeAll(gone.toSet()) }
        if (changed || pruned) save(context, synchronized(this) { HashMap(cache(context)) })
        changed
    }

    /** Held for a whole [identify] pass (never by the UI thread's lookups). */
    private val passLock = Any()

    fun tidy(name: String): String = GameTitleCatalog.tidy(name)

    private class Entry(
        val size: Long, val modified: Long, val title: String?, val category: RomCategory?,
        val platform: RomCategory? = category?.takeIf { it != RomCategory.HACK },
    ) {
        fun matches(rom: File) = size == rom.length() && modified == rom.lastModified()
    }

    private var loaded: MutableMap<String, Entry>? = null

    /** Read the file again (ui-preview writes one per shot). */
    @Synchronized internal fun reload() { loaded = null }

    @Synchronized
    private fun cache(context: Context): MutableMap<String, Entry> = loaded ?: load(context).also { loaded = it }

    private fun cacheFile(context: Context) = File(context.filesDir, "rom-titles.tsv")

    /** Which games [BY_SHA1] knew when the cache was written: a file found nameless then may have a name now. */
    /** Randomized retail carts are named too ([randomizedTitle]): a cache from before asks its nameless files again. */
    private const val RANDOMIZED_KNOWN = "\tupr-4.6.1"
    private val KNOWN = "#known\t" + Integer.toHexString(BY_SHA1.keys.sorted().hashCode()) + RANDOMIZED_KNOWN

    /** How [RomCategory] was decided when the cache was written: a cache from before asks every file again. */
    private const val CATEGORIES = "#categories\t5"
    /** The ones before: only their GB games are asked again (Polished Crystal is GBC since 3, Gold / Silver since 4). */
    private val CATEGORIES_GB_STALE = setOf("#categories\t2", "#categories\t3")
    /** And 3DS carts (their own category since 5: before, read as GBA). */
    private const val CATEGORIES_3DS_STALE = "#categories\t4"

    private fun load(context: Context): MutableMap<String, Entry> {
        val map = java.util.concurrent.ConcurrentHashMap<String, Entry>()
        val f = cacheFile(context)
        if (!f.isFile) return map
        runCatching {
            var stale = true
            var categoriesStale = true
            var gbStale = false
            var n3dsStale = false
            f.forEachLine { line ->
                if (line == KNOWN) stale = false
                if (line == CATEGORIES) categoriesStale = false
                if (line in CATEGORIES_GB_STALE) { categoriesStale = false; gbStale = true }
                if (line == CATEGORIES_3DS_STALE) { categoriesStale = false; n3dsStale = true }
                val p = line.split('\t')
                // A 4-column line is from before categories: no category, so identify() reads it again. A 5-column
                // one from before platforms: a hack's is read again (the others' platform is their category).
                if (p.size !in 4..6) return@forEachLine
                val size = p[1].toLongOrNull() ?: return@forEachLine
                val modified = p[2].toLongOrNull() ?: return@forEachLine
                val category = p.getOrNull(4)?.let { c -> RomCategory.entries.firstOrNull { it.name == c } }
                val platform = p.getOrNull(5)?.let { c -> RomCategory.entries.firstOrNull { it.name == c } }
                map[p[0]] = if (platform != null) Entry(size, modified, p[3].ifEmpty { null }, category, platform)
                    else Entry(size, modified, p[3].ifEmpty { null }, category)
            }
            // Written before a game was added (an app update): ask the nameless files again.
            if (stale) map.values.removeAll { it.title == null }
            // Categories decided another way (Yellow was GBC once): every file's is asked again.
            if (categoriesStale) map.replaceAll { _, e -> Entry(e.size, e.modified, e.title, null, null) }
            // A GB game from before these Color games were known: identify() reads it again (a retail one's category
            // is its platform, so both go).
            else if (n3dsStale) map.replaceAll { path, e ->
                if (path.substringAfterLast('.').lowercase() in com.pokedaisy.app.n3ds.N3dsCart.EXTENSIONS) Entry(e.size, e.modified, e.title, null, null) else e
            }
            else if (gbStale) map.replaceAll { _, e ->
                if (e.platform == RomCategory.GB) Entry(e.size, e.modified, e.title, e.category.takeIf { it == RomCategory.HACK }, null) else e
            }
        }
        return map
    }

    private fun save(context: Context, entries: Map<String, Entry>) {
        val f = cacheFile(context)
        val tmp = File(f.parentFile, "${f.name}.tmp")
        runCatching {
            tmp.writeText(KNOWN + "\n" + CATEGORIES + "\n" + entries.entries.joinToString("") { (path, e) ->
                "$path\t${e.size}\t${e.modified}\t${e.title.orEmpty()}\t${e.category?.name.orEmpty()}\t${e.platform?.name.orEmpty()}\n"
            })
            tmp.renameTo(f)
        }
    }
}
