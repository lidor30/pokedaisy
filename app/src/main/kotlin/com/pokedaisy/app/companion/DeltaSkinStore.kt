package com.pokedaisy.app.companion

import com.pokedaisy.app.companion.data.LockedMap
import com.pokedaisy.app.companion.data.deleteRecursively
import com.pokedaisy.app.companion.data.isDirectory
import com.pokedaisy.app.companion.data.isFile
import com.pokedaisy.app.companion.data.lastModified
import com.pokedaisy.app.companion.data.listFiles
import com.pokedaisy.app.companion.data.mkdirs
import com.pokedaisy.app.companion.data.moveTo
import com.pokedaisy.app.companion.data.readText
import com.pokedaisy.app.companion.data.writeBytes
import okio.Buffer
import okio.FileSystem
import okio.IOException
import okio.Path
import okio.Path.Companion.toPath
import okio.SYSTEM
import okio.buffer
import okio.openZip

/** An imported skin: its info, and the folder its pictures are in. [id] is the folder's name. */
data class InstalledSkin(val id: String, val skin: DeltaSkin, val dir: Path) {
    fun file(name: String): Path? = DeltaSkinStore.safeName(name)?.let { dir / it }?.takeIf { it.isFile }
}

/**
 * The imported Delta skins, one folder each under [root] (the app's `files/skins/`), holding the skin's info.json and
 * pictures as they came in the `.deltaskin`. A file the player picks is never trusted: only entries with plain names
 * are written (no `..`, no absolute paths), each and all together within size limits, and the skin must read as one
 * PokeDaisy can play before anything is kept. Importing a skin with the same identifier again replaces it.
 */
class DeltaSkinStore(val root: Path) {

    /** What an import found: the skins kept, and why the others weren't. */
    data class ImportResult(val added: List<InstalledSkin>, val refused: List<String>)

    fun list(): List<InstalledSkin> {
        val dirs = root.listFiles().filter { it.isDirectory && !it.name.startsWith(".") }
        return dirs.mapNotNull { load(it.name) }.sortedBy { it.skin.name.lowercase() }
    }

    fun load(id: String): InstalledSkin? {
        val dir = root / (safeName(id) ?: return null)
        val info = dir / INFO
        if (!info.isFile) return null
        cache[id]?.takeIf { it.first == info.lastModified }?.let { return it.second }
        val skin = runCatching { DeltaSkin.parse(info.readText()) }.getOrNull() ?: return null
        return InstalledSkin(id, skin, dir).also { cache[id] = info.lastModified to it }
    }

    fun delete(id: String): Boolean {
        val dir = root / (safeName(id) ?: return false)
        cache.remove(id)
        if (!dir.isDirectory) return false
        return runCatching { dir.deleteRecursively() }.isSuccess
    }

    /** Imports the `.deltaskin` (a zip) at [zip], or a zip holding several. The platform copies a picked file there. */
    fun import(zip: Path): ImportResult {
        val files = runCatching { readZip(zip) }.getOrElse { return ImportResult(emptyList(), listOf(it.message ?: "not a zip file")) }
        val nested = files.filterKeys { it.endsWith(".deltaskin", ignoreCase = true) }
        if (nested.isNotEmpty() && INFO !in files) {
            val added = ArrayList<InstalledSkin>()
            val refused = ArrayList<String>()
            root.mkdirs()
            val temp = root / ".nested.deltaskin"
            try {
                for ((name, bytes) in nested) {
                    runCatching { temp.writeBytes(bytes); readZip(temp) }
                        .mapCatching { install(it) }
                        .onSuccess { added += it }
                        .onFailure { refused += "${name.substringAfterLast('/')}: ${it.message}" }
                }
            } finally {
                FileSystem.SYSTEM.delete(temp, mustExist = false)
            }
            return ImportResult(added, refused)
        }
        return runCatching { ImportResult(listOf(install(files)), emptyList()) }
            .getOrElse { ImportResult(emptyList(), listOf(it.message ?: "not a skin")) }
    }

    private fun install(raw: Map<String, ByteArray>): InstalledSkin {
        // info.json at the top, or inside one folder (a skin zipped with its folder): the files are taken from there.
        val infoPath = raw.keys.filter { it.substringAfterLast('/') == INFO }.minByOrNull { it.count { c -> c == '/' } }
            ?: throw IllegalArgumentException("no info.json in it")
        val prefix = infoPath.removeSuffix(INFO)
        val files = raw.filterKeys { it.startsWith(prefix) }.mapKeys { it.key.removePrefix(prefix) }
        val skin = DeltaSkin.parse(files.getValue(INFO).decodeToString())
        val system = skin.system ?: throw IllegalArgumentException("a skin for another system (${skin.gameType.substringAfterLast('.')})")
        if (system == SkinSystem.DS) throw IllegalArgumentException("DS skins aren't supported yet")
        val missing = skin.representations.mapNotNull { it.asset }.distinct().filter { a -> safeName(a)?.let { it !in files } ?: true }
        if (missing.isNotEmpty()) throw IllegalArgumentException("its pictures are missing (${missing.first()})")
        val id = folderFor(skin)
        root.mkdirs()
        val temp = root / ".import-$id"
        temp.deleteRecursively()
        temp.mkdirs()
        try {
            for ((name, bytes) in files) {
                val safe = safeName(name) ?: continue
                (temp / safe).writeBytes(bytes)
            }
            val dir = root / id
            dir.deleteRecursively()
            runCatching { temp.moveTo(dir) }.onFailure { throw IllegalArgumentException("it couldn't be saved") }
            cache.remove(id)
            return load(id) ?: throw IllegalArgumentException("it couldn't be read back")
        } finally {
            temp.deleteRecursively()
        }
    }

    /** The folder for [skin]: its identifier, made safe; the same identifier lands in the same folder (an update). */
    private fun folderFor(skin: DeltaSkin): String {
        val base = skin.identifier.ifBlank { skin.name }.lowercase()
            .map { if (it.isLetterOrDigit() || it == '.' || it == '-' || it == '_') it else '_' }.joinToString("")
            .trim('.').take(80)
        return base.ifBlank { "skin" }
    }

    companion object {
        const val INFO = "info.json"
        /** A skin's files together: Delta's own are a few MB; big PNG sets stay well under this. */
        const val MAX_TOTAL = 64L * 1024 * 1024
        const val MAX_ENTRY = 40L * 1024 * 1024
        const val MAX_ENTRIES = 256

        /** Every skin store's parsed skins, by folder: list() runs on every visit to the settings page. */
        private val cache = LockedMap<String, Pair<Long, InstalledSkin>>()

        /** [name] as a relative path with no way out of the skin's folder, or null. */
        fun safeName(name: String): String? {
            val n = name.replace('\\', '/').trimStart('/')
            if (n.isEmpty() || n.endsWith('/')) return null
            val parts = n.split('/')
            if (parts.any { it.isEmpty() || it == "." || it == ".." || it.startsWith(".") }) return null
            if (parts.first().contains(':')) return null
            return parts.joinToString("/")
        }

        /** Every file in the zip at [zip] by its path, within the limits (else IllegalArgumentException). macOS's
         * `__MACOSX` leftovers are skipped. Read through okio's zip file system: each entry's size is checked against
         * what is really read, never trusted from the zip's directory. */
        fun readZip(zip: Path): Map<String, ByteArray> {
            val fs = try {
                FileSystem.SYSTEM.openZip(zip)
            } catch (e: IOException) {
                throw IllegalArgumentException("not a zip file")
            }
            val out = LinkedHashMap<String, ByteArray>()
            var total = 0L
            var entries = 0
            for (entry in fs.listRecursively("/".toPath())) {
                if (++entries > MAX_ENTRIES) throw IllegalArgumentException("too many files in it")
                val name = entry.toString().removePrefix("/")
                if (name.startsWith("__MACOSX/") || entry.name.startsWith(".")) continue
                if (fs.metadataOrNull(entry)?.isRegularFile != true) continue
                val buf = Buffer()
                fs.source(entry).buffer().use { src ->
                    var size = 0L
                    while (true) {
                        val n = src.read(buf, 64L * 1024)
                        if (n < 0) break
                        size += n
                        total += n
                        if (size > MAX_ENTRY || total > MAX_TOTAL) throw IllegalArgumentException("too big")
                    }
                }
                out[name] = buf.readByteArray()
            }
            if (out.isEmpty()) throw IllegalArgumentException("not a zip file")
            return out
        }
    }
}
