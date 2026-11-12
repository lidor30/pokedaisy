package com.pokedaisy.app

import android.content.Context
import com.pokedaisy.app.companion.data.TelemetrySampler
import java.io.File

/**
 * The player's linked ROMs folders ([Prefs.romsFolders]): every ROM in them that the
 * second screen supports ([CompanionSupport]) joins the library and plays from
 * where it is - nothing is copied. Checking a ROM can mean hashing 32 MB, so each
 * file's verdict is cached by path + size + modified time and a rescan (every
 * time the library opens) only looks at new or changed files. ROMs packed in
 * `.zip` / `.7z` archives count too ([RomArchive]).
 */
object RomFolder {

    // A getter: ROM_EXTENSIONS follows the DS / Game Boy switches, which LibraryActivity sets after this object may load.
    private val EXTENSIONS get() = RomArchive.ROM_EXTENSIONS + RomArchive.EXTENSIONS
    private const val MAX_ROM_BYTES = 32L shl 20
    /** A DS cart: 512 MB at most (Platinum and HeartGold are 128, Black 256). */
    private const val MAX_DS_ROM_BYTES = 512L shl 20
    /** The folder itself plus a few levels: a frontend's tree nests (`ROMs/gba/Pokemon/Retail/`, GitHub #233). */
    private const val MAX_DEPTH = 6

    /** A DS cart or an archive (which may hold one: GitHub #315, zipped DS games went missing) up to a DS cart's size. */
    private fun maxBytes(f: File): Long =
        if (f.name.endsWith(".nds", ignoreCase = true) || RomArchive.isArchive(f)) MAX_DS_ROM_BYTES else MAX_ROM_BYTES

    /** [partial]: read through a PARTIAL best-effort match ([CompanionSupport.Verdict.PARTIAL]) - listed, tagged. */
    /** [tryable]: unsupported but a Gen 3 Pokémon base game - listed, tagged NOT SUPPORTED, so TRY BEST EFFORT is reachable. */
    private class Entry(
        val size: Long, val modified: Long, val supported: Boolean,
        val partial: Boolean = false, val matched: Boolean = false, val tryable: Boolean = false,
        /** Written before TRYABLE existed ("0"): checked again once. */
        val legacy: Boolean = false,
        /** A game on [com.pokedaisy.app.companion.data.BlockedGames]' list: its BLOCKED tag (written "B" while it plays
         * anyway, "X" while it's kept off). */
        val blocked: Boolean = false,
    ) {
        val listed get() = supported || partial || tryable
    }

    private fun entryOf(size: Long, modified: Long, f: File): Entry {
        val e = verdictEntry(size, modified, f)
        val blocked = runCatching { CompanionSupport.listed(f) }.getOrNull() != null
        return if (blocked) Entry(size, modified, e.supported, blocked = true) else e
    }

    private fun verdictEntry(size: Long, modified: Long, f: File): Entry =
        when (runCatching { CompanionSupport.verdict(f) }.getOrDefault(CompanionSupport.Verdict.UNSUPPORTED)) {
            CompanionSupport.Verdict.SUPPORTED -> Entry(size, modified, true)
            CompanionSupport.Verdict.MATCHED -> Entry(size, modified, true, matched = true)
            CompanionSupport.Verdict.PARTIAL -> Entry(size, modified, false, partial = true)
            CompanionSupport.Verdict.TRYABLE -> Entry(size, modified, false, tryable = true)
            CompanionSupport.Verdict.UNSUPPORTED -> Entry(size, modified, false)
        }

    /** The library: imported ROMs (`files/roms/`), then the linked folders' supported
     * ones from the last scan - minus hidden ones ([Prefs.hiddenRoms]) and any whose
     * file name an imported ROM already has (covers and names are keyed by file
     * name). Cheap: reads the cache, never a ROM. */
    fun libraryRoms(context: Context, prefs: Prefs): List<File> {
        val hidden = prefs.hiddenRoms
        val imported = importedRoms(context).filter { it.absolutePath !in hidden }
        // Only a listed copy stands in for the linked file: a hidden one used to hide that file too.
        val names = imported.mapTo(HashSet()) { it.name }
        val linked = found(context, prefs).filter { it.name !in names }
        return (imported + linked)
            .sortedWith(byLabel(context, prefs))
    }

    /** The hidden ROMs that are still there to show again (imported or in a linked folder). */
    fun hiddenRoms(context: Context, prefs: Prefs): List<File> =
        prefs.hiddenRoms.map(::File).filter { it.isFile }.sortedWith(byLabel(context, prefs))

    /** Alphabetical by the library's names, accents aside ("Pokemon" next to "Pokémon"). */
    private fun byLabel(context: Context, prefs: Prefs): Comparator<File> {
        val collator = java.text.Collator.getInstance().apply { strength = java.text.Collator.PRIMARY }
        return compareBy(collator) { GameTitles.label(context, prefs, it) }
    }

    private fun importedRoms(context: Context): List<File> =
        File(context.getExternalFilesDir(null), "roms").listFiles { f ->
            f.isFile && f.name.substringAfterLast('.', "").lowercase() in EXTENSIONS
        }?.toList().orEmpty()

    /** Supported, not hidden, still-present ROMs of the linked folders (or of [folder] alone), per the last scan. */
    fun found(context: Context, prefs: Prefs, folder: String? = null): List<File> {
        val roots = folder?.let(::listOf) ?: prefs.romsFolders
        if (roots.isEmpty()) return emptyList()
        val hidden = prefs.hiddenRoms
        return load(context).filter { (path, e) -> e.listed && roots.any { under(path, it) } && path !in hidden }
            .map { File(it.key) }.filter { it.isFile }
    }

    /** Every ROM file the app knows, whatever its verdict: imported ones and the linked folders' from the last scan,
     * hidden ones too - where PATCH A ROM looks for a patch's base ROM ([RomPatcher.findBase]). Cheap, the cache only. */
    fun allRomFiles(context: Context, prefs: Prefs): List<File> {
        val roots = prefs.romsFolders
        val linked = load(context).keys.filter { path -> roots.any { under(path, it) } }.map(::File)
        return (importedRoms(context) + linked + prefs.hiddenRoms.map(::File)).filter { it.isFile }.distinctBy { it.absolutePath }
    }

    /** Whether [rom] came from a linked folder (the player's own file: HIDE only, no DELETE). */
    fun isLinked(prefs: Prefs, rom: File): Boolean =
        prefs.romsFolders.any { under(rom.absolutePath, it) }

    private fun under(path: String, root: String) = path.startsWith("$root/")

    /** The linked folder [path] is already part of (itself, or one it sits in), else null: linking it again
     * would only look through the same files twice. */
    fun coveredBy(folders: List<String>, path: String): String? {
        val p = File(path).absolutePath
        return folders.firstOrNull { p == it || under(p, it) }
    }

    /** [folders] with [path] linked too (not [coveredBy] one of them): the folders inside it go, it covers them now. */
    fun withFolder(folders: List<String>, path: String): List<String> {
        val p = File(path).absolutePath
        return folders.filterNot { under(it, p) } + p
    }

    /** What a [scan] saw: the supported ROMs it found that the previous one didn't
     * have, how many ROM files it looked at and how many of those are supported;
     * [unreadable] are the folders it couldn't read at all, [readable] is false while there's one. */
    class ScanResult(val added: List<File>, val files: Int, val supported: Int, val unreadable: List<String> = emptyList()) {
        val readable get() = unreadable.isEmpty()
    }

    /**
     * Walks the linked folders and checks every new or changed ROM file. Blocking
     * (may hash several 32 MB files) - never on the UI thread. [onProgress] gets
     * (checked, total) as it goes. A folder that can't be read (no All files
     * access, SD card out) keeps its games from the scan before.
     */
    @Synchronized
    fun scan(context: Context, prefs: Prefs, onProgress: (Int, Int) -> Unit = { _, _ -> }): ScanResult {
        val folders = prefs.romsFolders
        if (folders.isEmpty()) return ScanResult(emptyList(), 0, 0)
        val (roots, unreadable) = folders.map(::File).partition { it.listFiles() != null }
        val unread = unreadable.map { it.path }
        if (roots.isEmpty()) return ScanResult(emptyList(), 0, 0, unread)
        val candidates = roots.flatMap { root ->
            root.walkTopDown().maxDepth(MAX_DEPTH)
                .onEnter { it == root || !it.name.startsWith(".") }
                .filter { f ->
                    f.isFile && f.name.substringAfterLast('.', "").lowercase() in EXTENSIONS &&
                        f.length() in 0x200L..maxBytes(f)
                }
                .toList()
        }.distinctBy { it.absolutePath }
        val old = load(context)
        val fresh = LinkedHashMap<String, Entry>()
        candidates.forEachIndexed { i, f ->
            val size = f.length()
            val modified = f.lastModified()
            val cached = old[f.absolutePath]?.takeIf { it.size == size && it.modified == modified && !it.legacy }
            fresh[f.absolutePath] = cached
                ?: entryOf(size, modified, f)
            onProgress(i + 1, candidates.size)
        }
        // Imported ROMs' verdicts ([checkImported]) live in the same cache, and so do an unreadable folder's.
        for ((path, e) in old) if (roots.none { under(path, it.path) }) fresh.putIfAbsent(path, e)
        save(context, fresh)
        return ScanResult(
            added = fresh.filter { (path, e) -> e.listed && old[path]?.listed != true }.map { File(it.key) },
            files = fresh.size,
            supported = fresh.values.count { it.supported },
            unreadable = unread,
        )
    }

    /**
     * Checks imported ROMs (`files/roms/`, the ones added with "add anyway?" included) into the
     * verdict cache, for the library's NOT SUPPORTED tag ([unsupported]). Blocking; only new or
     * changed files are read.
     */
    @Synchronized
    fun checkImported(context: Context) {
        val old = load(context)
        val fresh = LinkedHashMap(old)
        var changed = false
        for (f in importedRoms(context)) {
            val size = f.length()
            val modified = f.lastModified()
            if (old[f.absolutePath]?.let { it.size == size && it.modified == modified && !it.legacy } == true) continue
            fresh[f.absolutePath] = entryOf(size, modified, f)
            changed = true
        }
        if (changed) save(context, fresh)
    }

    /** Paths the cache knows the second screen can't read - cheap, never reads a ROM. */
    fun unsupported(context: Context): Set<String> =
        load(context).filterValues { !it.supported && !it.partial }.keys

    /** Paths of games on [com.pokedaisy.app.companion.data.BlockedGames]' list - the BLOCKED tag (over NOT SUPPORTED). */
    fun blocked(context: Context): Set<String> =
        load(context).filterValues { it.blocked }.keys

    /** Paths read through a PARTIAL best-effort match - the PARTIALLY SUPPORTED tag. */
    fun partial(context: Context): Set<String> =
        load(context).filterValues { it.partial }.keys

    /**
     * A best-effort match was kept or forgotten ([com.pokedaisy.app.companion.data.BestEffortStore]):
     * drop the cached verdicts that could change (all but SUPPORTED), so the next scan reads them again.
     */
    @Synchronized
    fun forgetUnsupported(context: Context) {
        val old = load(context)
        val kept = old.filterValues { it.supported && !it.matched }
        if (kept.size != old.size) save(context, kept)
    }

    /** Where best effort keeps its matches, and the verdicts to drop when one changes. Each activity's onCreate. */
    fun useBestEffortStore(context: Context) {
        val app = context.applicationContext
        com.pokedaisy.app.companion.data.BestEffortStore.dir = app.filesDir
        com.pokedaisy.app.companion.data.BestEffortStore.onChanged = { forgetUnsupported(app) }
    }

    /** [forget] on a thread of its own: a running [scan] holds the lock while it hashes ROMs, and REMOVE waiting for it
     * on the UI thread froze Settings until Android called it not responding (Crashlytics, 1.3.3). The folder's games
     * leave the library at once anyway: they're listed by the linked folders. */
    fun forgetLater(context: Context, folder: String) {
        val app = context.applicationContext
        Thread({ runCatching { forget(app, folder) } }, "pokedaisy-rom-forget").start()
    }

    /** Forgets [folder]'s cached verdicts (it was unlinked): linked again, it is read from scratch. Blocking. */
    @Synchronized
    fun forget(context: Context, folder: String) {
        val old = load(context)
        val kept = old.filterKeys { !under(it, folder) }
        if (kept.size != old.size) save(context, kept)
    }

    private fun cacheFile(context: Context) = File(context.filesDir, "rom-folder-scan.tsv")

    /** Which games the companion supported when the cache was written ([CompanionSupport]'s sets, the retail dumps it
     * takes as retail and the DS carts by code and revision: a ROM it turned away before is checked again - a Black cart
     * linked before Black was supported stayed out of the library). */
    private val SUPPORTED = "#supported\t" + Integer.toHexString(
        (TelemetrySampler.SUPPORTED_HACK_SHA1S.sorted() + TelemetrySampler.SUPPORTED_GB_SHA1S.sorted() +
            com.pokedaisy.app.companion.data.RETAIL_DUMPS.map { it.sha1 } +
            TelemetrySampler.NDS_GAMES.map { (code, rev) -> "$code:$rev" }.sorted() +
            com.pokedaisy.app.companion.data.OriginHeartGold.V4_0_3_EN_RC8_SHA1).hashCode(),
    )

    /** Which games were blocked when the cache was written ([com.pokedaisy.app.companion.data.BlockedGames]): a newly
     * blocked game may sit in it as SUPPORTED, so on a change every answer is read again (once, after that update). */
    private val BLOCKED get() = "#blocked\t" + com.pokedaisy.app.companion.data.BlockedGames.stamp

    private fun load(context: Context): Map<String, Entry> {
        val f = cacheFile(context)
        if (!f.isFile) return emptyMap()
        return runCatching {
            val lines = f.readLines()
            // Written before a game got its companion (an app update): only SUPPORTED answers still stand.
            val stale = SUPPORTED !in lines
            val blockedLine = BLOCKED
            if (blockedLine !in lines) return@runCatching emptyMap()
            lines.mapNotNull { line ->
                val p = line.split('\t')
                if (p.size != 4) return@mapNotNull null
                val size = p[1].toLongOrNull() ?: return@mapNotNull null
                val modified = p[2].toLongOrNull() ?: return@mapNotNull null
                if (stale && p[3] != "1") return@mapNotNull null
                p[0] to Entry(
                    size, modified, p[3] == "1" || p[3] == "M" || p[3] == "B", partial = p[3] == "P", matched = p[3] == "M",
                    tryable = p[3] == "T", legacy = p[3] == "0", blocked = p[3] == "B" || p[3] == "X",
                )
            }.toMap()
        }.getOrDefault(emptyMap())
    }

    private fun save(context: Context, entries: Map<String, Entry>) {
        val f = cacheFile(context)
        val tmp = File(f.parentFile, "${f.name}.tmp")
        runCatching {
            tmp.writeText(SUPPORTED + "\n" + BLOCKED + "\n" + entries.entries.joinToString("") { (path, e) ->
                "$path\t${e.size}\t${e.modified}\t${if (e.blocked) (if (e.supported) "B" else "X") else if (e.matched) "M" else if (e.supported) "1" else if (e.partial) "P" else if (e.tryable) "T" else "U"}\n"
            })
            tmp.renameTo(f)
        }
    }
}
