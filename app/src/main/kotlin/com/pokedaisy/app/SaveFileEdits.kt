package com.pokedaisy.app

import android.content.Context
import com.pokedaisy.app.companion.data.save.Gen3SaveReader
import com.pokedaisy.app.companion.i18n.tr
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import okio.Path
import okio.Path.Companion.toOkioPath

/**
 * Writing an edited save back (the library's PC BOXES page). Only while the game is closed - a
 * running core holds its own copy of the save and would write it back over the edit - and never
 * without a backup that holds the save as it was:
 *
 * 1. the file must still be exactly what the editor read (RetroArch or a sync may have changed it);
 * 2. a [SaveBackups] copy of it must exist (taken now, or the newest one already is it);
 * 3. the edit goes to a temp file beside it, synced, then moved over the save in one step;
 * 4. the save is read back and compared; if it differs the backup is put back;
 * 5. the game boots from the save next time instead of resuming ([SaveStates.freshBootFile]):
 *    a resume state holds the old save and would write it back.
 *
 * The bytes themselves come from [com.pokedaisy.app.companion.data.save.Gen3SaveFile.withBlocks],
 * which already proved they read back as the edit in a new slot with the old save left as the
 * game's fallback.
 */
object SaveFileEdits {

    sealed class Result {
        class Written(val save: File, val backup: File) : Result()
        class Failed(val reason: String) : Result()
    }

    /** The save format the editor reads [rom]'s save in, or null if it has none (hacks too big to be in-place edits, GB, Japanese). */
    fun formatFor(rom: File) = Gen3SaveReader.formatFor(RomIdentity.reader(rom)) { RomArchive.romSize(rom) }

    /** A DS cart whose save the editor reads ([com.pokedaisy.app.companion.data.save.Gen4SaveFile]) with tables from the
     * cart: HeartGold / SoulSilver in any language (IPK / IPG), Platinum USA rev 1 (CPUE, Renegade Platinum too), English
     * Diamond / Pearl (ADAE / APAE). Hacks that
     * changed the tables (Origin HeartGold) are turned away when the cart is read. */
    fun isDsEditable(rom: File): Boolean {
        if (!com.pokedaisy.app.companion.data.TelemetrySampler.NDS_SUPPORT || !RomIdentity.isNds(rom)) return false
        val code = RomIdentity.ndsGameCode(rom) ?: return false
        return code.take(3) in setOf("IPK", "IPG") || code == "CPUE" && RomIdentity.ndsRevision(rom) == 1 || code == "ADAE" || code == "APAE"
    }

    /** Black / White or Black 2 / White 2 (any language): POKé TRANSFER's receiving game. */
    fun isGen5(rom: File): Boolean {
        if (!com.pokedaisy.app.companion.data.TelemetrySampler.NDS_SUPPORT || !RomIdentity.isNds(rom)) return false
        val code = RomIdentity.ndsGameCode(rom) ?: return false
        return code.take(3) in setOf("IRB", "IRA", "IRE", "IRD")
    }

    /** Writes [edited] over [rom]'s save, which must still be [expected]. Blocking: off the UI thread. */
    fun commit(context: Context, prefs: Prefs, rom: File, expected: ByteArray, edited: ByteArray): Result =
        runCatching {
            val save = SavesLocation.saveFor(context, prefs, rom).canonicalFile.toOkioPath()
            transactions(context).write(SaveEditFiles.Edit(save, expected, edited)) {
                SaveStates(context.getExternalFilesDir(null) ?: context.filesDir, SaveStates.crc32(rom)).files
            }.androidResult()
        }.getOrElse { Result.Failed(tr("Can't write the save")) }

    /** Trade, Pal Park and Poké Transfer all require both saves to commit together. Blocking. */
    fun commitBoth(context: Context, prefs: Prefs, a: Edit, b: Edit): Result = runCatching {
        fun side(edit: Edit): SaveTradeJournal.Side {
            val save = SavesLocation.saveFor(context, prefs, edit.rom).canonicalFile.toOkioPath()
            return SaveTradeJournal.Side(SaveEditFiles.Edit(save, edit.expected, edit.edited), stateKey(context, edit.rom))
        }
        transactions(context).trade(side(a), side(b)).androidResult()
    }.getOrElse { Result.Failed(tr("Can't write the save")) }

    fun recover(context: Context): Boolean = transactions(context).recover()

    private fun stateKey(context: Context, rom: File): String {
        val storage = if (context.getExternalFilesDir(null) == null) "internal" else "external"
        return "$storage:${SaveStates.crc32(rom)}"
    }

    private fun transactions(context: Context) = ClosedSaveEdits(
        files = files { SaveBackups.snapshot(it, force = true) },
        journal = SaveTradeJournal(context.filesDir.toOkioPath() / "save-trade.pending", ::sync),
        accept = { side ->
            // This app-private journal records the absolute location chosen by SavesLocation at
            // commit time. A per-game override, an extra search folder, or a DS cart's folder can
            // be anywhere on writable storage. Use the original location if settings change.
            val path = side.edit.path
            path.isAbsolute && path.parent != null &&
                path.name.substringAfterLast('.', "").lowercase() in setOf("sav", "srm") &&
                File(path.toString()).canonicalFile.toOkioPath() == path &&
                stateKeyPattern.matches(side.stateKey)
        },
        states = { key ->
            val root = if (key.startsWith("external:")) checkNotNull(context.getExternalFilesDir(null)) else context.filesDir
            SaveStates(root, key.substringAfter(':')).files
        },
    )

    private val stateKeyPattern = Regex("(external|internal):[0-9a-f]{8}")

    /** One save's part of [commitBoth]: the file as read and as edited. */
    class Edit(val rom: File, val expected: ByteArray, val edited: ByteArray)

    internal fun writeBoth(saveA: File, a: Edit, saveB: File, b: Edit, backup: (File) -> File?): Result =
        files(backup).writeBoth(
            SaveEditFiles.Edit(saveA.absoluteFile.toOkioPath(), a.expected, a.edited),
            SaveEditFiles.Edit(saveB.absoluteFile.toOkioPath(), b.expected, b.edited),
        ).androidResult()

    /** [commit]'s file work, apart from Android for the tests. [backup] takes a copy and returns it, or null if it didn't. */
    internal fun write(save: File, expected: ByteArray, edited: ByteArray, backup: (File) -> File?): Result =
        files(backup).write(SaveEditFiles.Edit(save.absoluteFile.toOkioPath(), expected, edited)).androidResult()

    private fun files(backup: (File) -> File?) = SaveEditFiles(
        backup = { path ->
            val file = File(path.toString())
            (backup(file) ?: SaveBackups.list(file).firstOrNull())?.toOkioPath()
        },
        writeSynced = ::sync,
        publish = { tmp, save ->
            val source = File(tmp.toString()).toPath()
            val target = File(save.toString()).toPath()
            try {
                Files.move(source, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
                Files.move(source, target, StandardCopyOption.REPLACE_EXISTING)
            }
        },
    )

    private fun sync(path: Path, bytes: ByteArray) {
        FileOutputStream(File(path.toString())).use {
            it.write(bytes)
            it.fd.sync()
        }
    }

    private fun SaveEditFiles.Result.androidResult(): Result = when (this) {
        is SaveEditFiles.Result.Written -> Result.Written(File(save.toString()), File(backup.toString()))
        is SaveEditFiles.Result.Failed -> Result.Failed(reason)
    }
}
