package com.pokedaisy.app

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import com.pokedaisy.app.companion.i18n.L10n
import com.pokedaisy.app.companion.i18n.tr
import java.io.File
import java.io.InputStream
import java.io.RandomAccessFile

/**
 * The entry point for frontends (Cocoon, iiSU, ES-DE, Daijisho, ...): they
 * start this activity explicitly with the ROM, and the game opens straight
 * away - no library screen in between, and leaving the game goes back to the
 * frontend.
 *
 * The ROM can come as the intent's data (`content://` or `file://`, the usual
 * VIEW launch) or as a string extra holding a path or URI ([ROM_EXTRAS]).
 * A ROM whose real path we can read is played where it is (a `.zip` / `.7z`
 * too - [RomArchive]); anything else is copied into the library once (an
 * archive unpacked) and reused while it's unchanged. Unlike the
 * library's import there's no "add anyway?" confirm: an unsupported ROM still
 * plays, with the companion's own "not supported" notice.
 *
 * No UI of its own (translucent theme); it finishes as soon as the game is
 * started.
 *
 * It's exported (frontends start it by component), so any app can send it a
 * path: it never reads this app's own files or provider, copies at most
 * [RomIntake.maxCopyBytes], and plays only what has a cart header ([RomIdentity.looksLikeRom]).
 */
class LaunchActivity : Activity() {

    /** The app that sent the launch (its package, from the referrer Android hands us), for crash reports: which
     * frontend - Cocoon, ES-DE, iiSU... - a crash came from. */
    private var sender: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        sender = runCatching { referrer?.takeIf { it.scheme == "android-app" }?.host }.getOrNull()
        // Its toasts in the player's language (no ROM yet: AUTO = the device's).
        L10n.apply(Prefs(this).appLanguage, null)
        val uri = romUri(intent)
        if (uri == null) {
            Toast.makeText(this, tr("No ROM was passed to PokeDaisy"), Toast.LENGTH_LONG).show()
            close()
            return
        }
        // Copying a 32 MB ROM takes a moment, so off the UI thread. The content://
        // read grant lasts while this activity is alive, so it finishes only after.
        Thread({
            val rom = try {
                resolve(uri)
            } catch (t: Throwable) {
                Log.e("pokedaisy", "frontend launch: couldn't open $uri", t)
                null
            }
            // Its CRC (and an archive's unpacking) here, not on the game window's UI thread (RomCrcs).
            if (rom != null) RomCrcs.prepare(this, rom)
            runOnUiThread {
                if (rom != null) play(rom) else cantRead(uri)
                close()
            }
        }, "pokedaisy-launch").apply { isDaemon = true; start() }
    }

    /** The file to play for [uri]: the ROM itself when we can read its path, else a library copy. */
    private fun resolve(uri: Uri): File? {
        if (RomIntake.isOwn(this, uri)) return null
        RomUris.originalPath(this, uri)?.takeIf { it.startsWith("/") }?.let { path ->
            val f = File(path)
            if (RomIntake.isPrivate(this, f)) return null
            if (readable(f)) return f.takeIf { RomIdentity.looksLikeRom(it) }
        }
        if (uri.scheme == "file") return null   // no path access, and no provider to copy through
        return copyIntoLibrary(uri)?.let { out ->
            if (RomIdentity.looksLikeRom(out)) out else null.also { out.delete() }
        }
    }

    /**
     * Copies [uri] into the library under its own name (replacing a same-named
     * ROM, as the library's import does) - unless the copy already there has
     * the same bytes, so a frontend launching the same game again doesn't
     * rewrite 32 MB every time. An archive's ROM is unpacked under the
     * archive's name (`Emerald.zip` -> `Emerald.gba`).
     */
    private fun copyIntoLibrary(uri: Uri): File? {
        val romsDir = File(getExternalFilesDir(null), "roms").apply { mkdirs() }
        var name = RomUris.sanitizeFileName(RomUris.displayName(this, uri) ?: "imported-${System.currentTimeMillis()}.gba")
        if (name.substringAfterLast('.', "").lowercase() in RomArchive.EXTENSIONS) return unpackIntoLibrary(uri, romsDir, name)
        if (name.substringAfterLast('.', "").lowercase() !in ROM_EXT) name += ".gba"
        val out = File(romsDir, name)
        if (out.isFile && sameBytes(uri, out)) return out

        runOnUiThread { Toast.makeText(this, tr("Copying {0}…", name), Toast.LENGTH_SHORT).show() }
        // Next to roms/ (same volume, so the swap below is a rename).
        val importDir = File(getExternalFilesDir(null), "import").apply { mkdirs() }
        val tmp = File(importDir, "launch-${System.currentTimeMillis()}.gba")
        try {
            contentResolver.openInputStream(uri)?.use { input -> tmp.outputStream().use { RomIntake.copyCapped(input, it) } }
        } catch (t: Throwable) {
            tmp.delete()
            throw t
        }
        if (tmp.length() <= 0) {
            tmp.delete()
            return null
        }
        if (!tmp.renameTo(out)) {
            runCatching { tmp.copyTo(out, overwrite = true) }
            tmp.delete()
        }
        if (out.length() <= 0) return null
        added(out, uri)
        return out
    }

    /** [copyIntoLibrary] for a `.zip` / `.7z` [archiveName]: its ROM goes into [romsDir], unpacked. */
    private fun unpackIntoLibrary(uri: Uri, romsDir: File, archiveName: String): File? {
        val importDir = File(getExternalFilesDir(null), "import").apply { mkdirs() }
        val packed = File(importDir, "launch-${System.currentTimeMillis()}.${archiveName.substringAfterLast('.')}")
        val unpacked = File(importDir, "${packed.name}.rom")
        runOnUiThread { Toast.makeText(this, tr("Unpacking {0}…", archiveName), Toast.LENGTH_SHORT).show() }
        try {
            contentResolver.openInputStream(uri)?.use { input -> packed.outputStream().use { RomIntake.copyCapped(input, it) } }
            val entry = RomArchive.extract(packed, unpacked, RomArchive.sniff(packed)) ?: return null
            val out = File(romsDir, "${archiveName.substringBeforeLast('.')}.${entry.extension}")
            if (out.isFile && out.length() == unpacked.length() && unpacked.inputStream().use { a -> out.inputStream().use { b -> sameStream(a, b) } }) {
                return out
            }
            out.delete()
            if (!unpacked.renameTo(out)) unpacked.copyTo(out, overwrite = true)
            if (out.length() <= 0) return null
            added(out, uri)
            return out
        } finally {
            packed.delete()
            unpacked.delete()
        }
    }

    /** A ROM just copied into the library: where it came from, and its cover. */
    private fun added(out: File, uri: Uri) {
        val prefs = Prefs(this)
        prefs.setRomSourcePath(out, RomUris.originalPath(this, uri))
        // The library's cover, as its import fetches one (network, so its own thread).
        // RetroAchievements answers for games with no SteamGridDB match; idempotent.
        com.pokedaisy.app.achievements.RetroAchievements.init(this)
        val cover = File(File(getExternalFilesDir(null), "covers").apply { mkdirs() }, "${out.name}.png")
        Thread({
            runCatching { CoverArtSync.fetchOne(out, cover, prefs) }
        }, "pokedaisy-launch-cover").apply { isDaemon = true; start() }
    }

    /** Whether [uri] holds exactly [file]'s bytes (reading is far cheaper than rewriting). */
    private fun sameBytes(uri: Uri, file: File): Boolean = runCatching {
        if (file.length() <= 0) return false
        val a = contentResolver.openInputStream(uri) ?: return false
        a.use { file.inputStream().use { b -> sameStream(a, b) } }
    }.getOrDefault(false)

    private fun sameStream(a: InputStream, b: InputStream): Boolean {
        val bufA = ByteArray(1 shl 16)
        val bufB = ByteArray(1 shl 16)
        while (true) {
            val n = readFull(a, bufA)
            if (readFull(b, bufB) != n) return false
            // A short last chunk: clear both tails so stale bytes don't count.
            java.util.Arrays.fill(bufA, n, bufA.size, 0)
            java.util.Arrays.fill(bufB, n, bufB.size, 0)
            if (!bufA.contentEquals(bufB)) return false
            if (n < bufA.size) return true
        }
    }

    private fun readFull(s: InputStream, buf: ByteArray): Int {
        var off = 0
        while (off < buf.size) {
            val r = s.read(buf, off, buf.size - off)
            if (r < 0) break
            off += r
        }
        return off
    }

    private fun play(rom: File) {
        val prefs = Prefs(this)
        prefs.lastRomPath = rom.absolutePath
        prefs.pushRecentRom(rom.absolutePath)
        startActivity(
            Intent(this, PokeDaisyActivity::class.java)
                .putExtra(LibraryActivity.EXTRA_ROM, rom.absolutePath)
                .putExtra(PokeDaisyActivity.EXTRA_FROM_FRONTEND, true)
                .putExtra(LibraryActivity.EXTRA_LAUNCHED_FROM, "frontend: ${sender ?: "unknown"}")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            // A frontend on the bottom screen: the game still goes to the top one.
            Screens.mainDisplayLaunch(this),
        )
    }

    /** A `file://` path we may not read is All files access on Android 11+, so open its page. */
    private fun cantRead(uri: Uri) {
        val path = RomUris.originalPath(this, uri) ?: uri.toString()
        val needsAllFiles = uri.scheme == "file" &&
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !Environment.isExternalStorageManager()
        if (needsAllFiles) {
            Toast.makeText(this, tr("PokeDaisy can't read {0} - allow All files access, then launch again", path), Toast.LENGTH_LONG).show()
            runCatching {
                startActivity(
                    Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:$packageName"))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }
        } else {
            Toast.makeText(this, tr("PokeDaisy can't read {0}", path), Toast.LENGTH_LONG).show()
        }
    }

    private fun close() {
        finish()
        @Suppress("DEPRECATION") overridePendingTransition(0, 0)
    }

    private fun readable(f: File): Boolean =
        f.isFile && runCatching { RandomAccessFile(f, "r").use { true } }.getOrDefault(false)

    companion object {
        private val ROM_EXT = RomArchive.ROM_EXTENSIONS
        /** Extras frontends put a ROM path / URI in: ours, RetroArch's (`ROM`), and the usual spellings. */
        private val ROM_EXTRAS = listOf("rom", "ROM", "path", "PATH", "file", "FILE", "uri", "URI")

        /** [intent]'s ROM: its data, else the first [ROM_EXTRAS] extra that's set. */
        fun romUri(intent: Intent?): Uri? {
            intent ?: return null
            intent.data?.let { return it }
            // Another app's extras can hold classes we can't unparcel; that's just "no ROM".
            val extras = runCatching { intent.extras }.getOrNull() ?: return null
            for (key in ROM_EXTRAS) {
                @Suppress("DEPRECATION")
                when (val v = runCatching { extras.get(key) }.getOrNull()) {
                    is Uri -> return v
                    is String -> if (v.isNotBlank()) return if (v.startsWith("/")) Uri.fromFile(File(v)) else Uri.parse(v)
                }
            }
            return null
        }
    }
}
