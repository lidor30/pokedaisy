package com.pokedaisy.app.companion.data

import okio.Path

/**
 * SPRITE PACK (the library's TOOLS): the makeover's graphics - PLAYER LOOK's heroes, TEXT LOOK's boxes and fonts, the
 * FOLLOWER's Pokémon - as one file a player loads, so a look works without the other game's ROM in the library.
 *
 * Nothing is bundled and nothing is downloaded: a pack is made from ROMs by whoever has them (`make sprite-pack`,
 * ui-preview's `SpritePackTool`), and the app takes only graphics it already knows. Each file in a pack is the same bytes a
 * scan of a ROM leaves in `rom-art/look/` ([LookArt], [TextArt], [MonArt]), at the same path, and is accepted only where
 * its size and CRC32 are the fingerprint's ([LookBlob], [TextBlob]) or the table's ([MonArtSigs]). So a pack can't put a
 * byte into the running game that a scan of a known ROM wouldn't have put there; everything else in it is ignored.
 *
 * The file is a zip (read by the app, written by the tool: this module has no zip code) holding [MANIFEST] - its first line
 * [MAGIC] and the format, then `made <date>` and a `path size crc` line per file - and the files. The manifest marks it as
 * a pack and lets a damaged file be told apart from one that isn't ours; the files are checked on their own anyway.
 */
object SpritePack {
    const val MANIFEST = "pokedaisy-sprites.txt"
    const val MAGIC = "pokedaisy-sprite-pack"
    const val FORMAT = 1

    /** A pack's limits, checked while it's read (the reader's caps): far over what every known graphic adds up to
     * (~12 MB: 4 sources x 386 Pokémon x 7 KB, the heroes and the text looks). */
    const val MAX_FILES = 4000
    const val MAX_FILE_BYTES = 64 * 1024
    const val MAX_TOTAL_BYTES = 48L * 1024 * 1024

    /** What a pack file is, by its path (as [LookArt]'s folder lays it out). */
    sealed interface Item {
        val path: String
        class Hero(val char: LookChar, val part: LookPart) : Item {
            override val path get() = "${char.name.lowercase()}/${part.name.lowercase()}.bin"
        }
        class Text(val style: LookEngine, val part: TextPart) : Item {
            override val path get() = "text/${style.name.lowercase()}/${part.name.lowercase()}.bin"
        }
        class Mon(val source: MonArt.Source, val national: Int) : Item {
            override val path get() = "mon/${source.tag}/$national.bin"
        }
    }

    /** [path]'s item, or null where it's no graphic of ours. */
    fun itemOf(path: String): Item? {
        val p = path.trimStart('/').split('/')
        if (p.any { it.isEmpty() || it == "." || it == ".." }) return null
        val file = p.last().takeIf { it.endsWith(".bin") }?.removeSuffix(".bin") ?: return null
        return when {
            p.size == 3 && p[0] == "text" -> {
                val style = TextLook.STYLES.firstOrNull { it.name.lowercase() == p[1] } ?: return null
                val part = TextPart.entries.firstOrNull { it.name.lowercase() == file } ?: return null
                Item.Text(style, part)
            }
            p.size == 3 && p[0] == "mon" -> {
                val source = MonArt.Source.entries.firstOrNull { it.tag == p[1] } ?: return null
                val n = file.toIntOrNull()?.takeIf { it in 1..MonArt.LAST && it.toString() == file } ?: return null
                Item.Mon(source, n)
            }
            p.size == 2 -> {
                val char = LookChar.entries.firstOrNull { it.name.lowercase() == p[0] } ?: return null
                val part = LookPart.entries.firstOrNull { it.name.lowercase() == file } ?: return null
                PlayerLook.blob(char, part) ?: return null
                Item.Hero(char, part)
            }
            else -> null
        }
    }

    /** Whether [bytes] are exactly the graphic [item] names. */
    fun matches(item: Item, bytes: ByteArray): Boolean = when (item) {
        is Item.Hero -> PlayerLook.blob(item.char, item.part)?.let { bytes.size == it.size && crc32(bytes) == it.crc } == true
        is Item.Text -> TextLook.blob(item.style, item.part)?.let { bytes.size == it.size && crc32(bytes) == it.crc } == true
        is Item.Mon -> bytes.size == MonArt.SIZE && MonArtSigs.knows(item.source, item.national, crc32(bytes))
    }

    /** A pack's [MANIFEST]: [MAGIC] + [FORMAT], the date, then a line per file. */
    fun manifest(files: Map<String, ByteArray>, made: String): String = buildString {
        append("$MAGIC $FORMAT\n")
        append("made $made\n")
        append("# path size crc32 - see SpritePack.kt in the PokeDaisy source\n")
        files.entries.sortedBy { it.key }.forEach { (path, b) -> append("$path ${b.size} ${crc32(b).toString(16).padStart(8, '0')}\n") }
    }

    /** Every graphic kept under [filesDir] ([LookArt.dir]) that a pack may carry, by path: what the tool packs. */
    fun collect(filesDir: Path?): Map<String, ByteArray> {
        val root = LookArt.dir(filesDir)
        val out = LinkedHashMap<String, ByteArray>()
        fun visit(dir: Path, rel: String) {
            for (f in dir.listFiles().sortedBy { it.name }) {
                val path = if (rel.isEmpty()) f.name else "$rel/${f.name}"
                if (f.isFile) {
                    val item = itemOf(path) ?: continue
                    val b = f.readBytes()
                    if (matches(item, b)) out[path] = b
                } else visit(f, path)
            }
        }
        visit(root, "")
        return out
    }

    /** What loading a pack did. [heroes] / [textStyles] / [mons] are what's there now that wasn't before; [kept] the
     * files already there; [refused] files that aren't a graphic of ours (a newer pack's, or changed). */
    class Result(
        val made: String?, val heroes: List<LookChar>, val textStyles: List<LookEngine>, val mons: Int,
        val added: Int, val kept: Int, val refused: Int,
    ) {
        val nothingNew get() = added == 0
    }

    /** Why a file isn't a pack (the window's words, English: translated where drawn). */
    class NotAPack(message: String) : Exception(message)

    /**
     * Loads [files] (a pack's, path -> bytes) under [filesDir]: each graphic it carries that isn't kept yet, then the
     * region map heads of the heroes that are now whole (as a scan would, [LookArt.keep]). Never overwrites a kept file.
     * Throws [NotAPack] without its manifest.
     */
    fun install(filesDir: Path?, files: Map<String, ByteArray>): Result {
        val manifest = files[MANIFEST]?.decodeToString()?.lines() ?: throw NotAPack("This file isn't a sprite pack.")
        val head = manifest.firstOrNull()?.trim()?.split(' ')
        if (head?.getOrNull(0) != MAGIC) throw NotAPack("This file isn't a sprite pack.")
        val format = head.getOrNull(1)?.toIntOrNull() ?: 0
        if (format > FORMAT) throw NotAPack("This sprite pack needs a newer PokeDaisy.")
        val made = manifest.firstOrNull { it.startsWith("made ") }?.removePrefix("made ")?.trim()?.take(32)

        val heroesBefore = LookArt.available(filesDir)
        val textBefore = TextLook.STYLES.filter { TextArt.load(filesDir, it) != null }.toSet()
        val monsBefore = MonArt.available(filesDir)
        val root = LookArt.dir(filesDir)
        var added = 0
        var kept = 0
        var refused = 0
        for ((path, bytes) in files) {
            if (path == MANIFEST || !path.endsWith(".bin")) continue
            val item = itemOf(path)
            if (item == null || !matches(item, bytes)) { refused++; continue }
            val f = root / item.path
            if (f.isFile && f.length == bytes.size.toLong()) { kept++; continue }
            f.parent?.mkdirs()
            f.writeAtomically(bytes)
            added++
        }
        if (added > 0) {
            MonArt.forget()
            LookChar.entries.filter { LookArt.has(filesDir, it) }.forEach { LookArt.keepHead(filesDir, it) }
            RomArt.announce()
        }
        return Result(
            made = made,
            heroes = LookArt.available(filesDir).filter { it !in heroesBefore },
            textStyles = TextLook.STYLES.filter { it !in textBefore && TextArt.load(filesDir, it) != null },
            mons = (MonArt.available(filesDir) - monsBefore).size,
            added = added, kept = kept, refused = refused,
        )
    }
}
