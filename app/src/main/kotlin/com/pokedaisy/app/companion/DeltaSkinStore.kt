package com.pokedaisy.app.companion

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.util.zip.ZipInputStream

/** An imported skin: its info, and the folder its pictures are in. [id] is the folder's name. */
data class InstalledSkin(val id: String, val skin: DeltaSkin, val dir: File) {
    fun file(name: String): File? = DeltaSkinStore.safeName(name)?.let { File(dir, it) }?.takeIf { it.isFile }
}

/**
 * The imported Delta skins, one folder each under [root] (the app's `files/skins/`), holding the skin's info.json and
 * pictures as they came in the `.deltaskin`. A file the player picks is never trusted: only entries with plain names
 * are written (no `..`, no absolute paths), each and all together within size limits, and the skin must read as one
 * PokeDaisy can play before anything is kept. Importing a skin with the same identifier again replaces it.
 */
class DeltaSkinStore(val root: File) {

    /** What an import found: the skins kept, and why the others weren't. */
    data class ImportResult(val added: List<InstalledSkin>, val refused: List<String>)

    fun list(): List<InstalledSkin> {
        val dirs = root.listFiles { f -> f.isDirectory && !f.name.startsWith(".") } ?: return emptyList()
        return dirs.mapNotNull { load(it.name) }.sortedBy { it.skin.name.lowercase() }
    }

    fun load(id: String): InstalledSkin? {
        val dir = File(root, safeName(id) ?: return null)
        val info = File(dir, INFO)
        if (!info.isFile) return null
        cache[id]?.takeIf { it.first == info.lastModified() }?.let { return it.second }
        val skin = runCatching { DeltaSkin.parse(info.readText()) }.getOrNull() ?: return null
        return InstalledSkin(id, skin, dir).also { cache[id] = info.lastModified() to it }
    }

    fun delete(id: String): Boolean {
        val dir = File(root, safeName(id) ?: return false)
        cache.remove(id)
        return dir.isDirectory && dir.deleteRecursively()
    }

    /** Imports a `.deltaskin` (a zip), or a zip holding several. */
    fun import(input: InputStream): ImportResult {
        val files = readZip(input)
        val nested = files.filterKeys { it.endsWith(".deltaskin", ignoreCase = true) }
        if (nested.isNotEmpty() && INFO !in files) {
            val added = ArrayList<InstalledSkin>()
            val refused = ArrayList<String>()
            for ((name, bytes) in nested) {
                runCatching { readZip(ByteArrayInputStream(bytes)) }
                    .mapCatching { install(it) }
                    .onSuccess { added += it }
                    .onFailure { refused += "${name.substringAfterLast('/')}: ${it.message}" }
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
        val skin = DeltaSkin.parse(String(files.getValue(INFO), Charsets.UTF_8))
        val system = skin.system ?: throw IllegalArgumentException("a skin for another system (${skin.gameType.substringAfterLast('.')})")
        if (system == SkinSystem.DS) throw IllegalArgumentException("DS skins aren't supported yet")
        val missing = skin.representations.mapNotNull { it.asset }.distinct().filter { a -> safeName(a)?.let { it !in files } ?: true }
        if (missing.isNotEmpty()) throw IllegalArgumentException("its pictures are missing (${missing.first()})")
        val id = folderFor(skin)
        root.mkdirs()
        val temp = File(root, ".import-$id")
        temp.deleteRecursively()
        temp.mkdirs()
        try {
            for ((name, bytes) in files) {
                val safe = safeName(name) ?: continue
                val out = File(temp, safe)
                out.parentFile?.mkdirs()
                out.writeBytes(bytes)
            }
            val dir = File(root, id)
            dir.deleteRecursively()
            if (!temp.renameTo(dir)) throw IllegalArgumentException("it couldn't be saved")
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
        private val cache = java.util.concurrent.ConcurrentHashMap<String, Pair<Long, InstalledSkin>>()

        /** [name] as a relative path with no way out of the skin's folder, or null. */
        fun safeName(name: String): String? {
            val n = name.replace('\\', '/').trimStart('/')
            if (n.isEmpty() || n.endsWith('/')) return null
            val parts = n.split('/')
            if (parts.any { it.isEmpty() || it == "." || it == ".." || it.startsWith(".") }) return null
            if (parts.first().contains(':')) return null
            return parts.joinToString(File.separator)
        }

        /** Every file in a zip by its path, within the limits (else IllegalArgumentException). macOS's `__MACOSX`
         * leftovers are skipped. */
        fun readZip(input: InputStream): Map<String, ByteArray> {
            val out = LinkedHashMap<String, ByteArray>()
            var total = 0L
            var entries = 0
            ZipInputStream(input).use { zip ->
                while (true) {
                    val e = zip.nextEntry ?: break
                    if (++entries > MAX_ENTRIES) throw IllegalArgumentException("too many files in it")
                    val name = e.name.replace('\\', '/')
                    if (e.isDirectory || name.startsWith("__MACOSX/") || name.substringAfterLast('/').startsWith(".")) continue
                    val buf = ByteArrayOutputStream()
                    val chunk = ByteArray(64 * 1024)
                    var size = 0L
                    while (true) {
                        val n = zip.read(chunk)
                        if (n < 0) break
                        size += n
                        total += n
                        if (size > MAX_ENTRY || total > MAX_TOTAL) throw IllegalArgumentException("too big")
                        buf.write(chunk, 0, n)
                    }
                    out[name] = buf.toByteArray()
                }
            }
            if (out.isEmpty()) throw IllegalArgumentException("not a zip file")
            return out
        }
    }
}
