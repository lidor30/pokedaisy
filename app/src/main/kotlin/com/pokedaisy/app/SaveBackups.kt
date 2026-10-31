package com.pokedaisy.app

import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * A copy of a game's save taken every time the game starts, before the emulator opens
 * it: `<saves>/pokedaisy-backups/<game>.backup-<yyyyMMdd-HHmmss>.<ext>`. Beside the
 * save (not in the app's private folder) so a file manager reaches it, it outlives an
 * uninstall, and LOAD SAVE can pick it. A copy is only made when the save differs from
 * the newest one, and the newest [Settings.keep] per game are kept.
 *
 * Settings > FOLDERS > SAVE BACKUPS ([settings], from [Prefs]): the start's backups can be turned off, kept
 * fewer or more, and gathered in one folder of the player's ([Settings.folder]) instead of beside each save.
 * The save editor's backups ([SaveFileEdits]) are taken whatever the switch says (`force`): it never writes
 * a save without one.
 */
object SaveBackups {

    const val DIR_NAME = "pokedaisy-backups"
    const val KEEP = 10

    /** KEEP PER GAME's choices. */
    val KEEP_CHOICES = listOf(1, 3, 5, 10, 20, 50)

    class Settings(
        /** Off: a game's start takes no backup. */
        val enabled: Boolean = true,
        /** Every game's backups in this folder; null = a [DIR_NAME] folder beside each save. */
        val folder: File? = null,
        val keep: Int = KEEP,
    ) {
        companion object {
            fun from(prefs: Prefs) = Settings(prefs.saveBackups, prefs.saveBackupsDir?.let(::File), prefs.saveBackupsKeep)
        }
    }

    /** The player's choices: set at app start and by Settings when they change. */
    @Volatile var settings = Settings()

    /** Where [save]'s new backups go. */
    fun dir(save: File, settings: Settings = this.settings): File = settings.folder ?: besideSave(save)

    private fun besideSave(save: File) = File(save.parentFile, DIR_NAME)

    /** [save]'s backups, newest first: in the backup folder and beside the save (where they went before a folder
     * was picked, or after it was dropped), so RESTORE BACKUP still finds the older ones. */
    fun list(save: File, settings: Settings = this.settings): List<File> =
        listOfNotNull(besideSave(save), settings.folder).distinctBy { it.absolutePath }
            .flatMap { inDir(it, save) }.sortedByDescending { it.name }

    private fun inDir(dir: File, save: File): List<File> =
        dir.listFiles { f -> f.isFile && f.name.startsWith("${save.nameWithoutExtension}.backup-") }
            ?.sortedByDescending { it.name }.orEmpty()

    /** Backs [save] up if it has data the newest backup doesn't. Never throws: a
     * folder we can't write mustn't keep the game from starting. [force]: even with backups off. */
    fun snapshot(
        save: File, now: Long = System.currentTimeMillis(), settings: Settings = this.settings, force: Boolean = false,
    ): File? = runCatching {
        if (!settings.enabled && !force) return null
        if (!save.isFile || save.length() == 0L || save.length() > GameSaves.MAX_BYTES) return null
        val bytes = save.readBytes()
        val dir = dir(save, settings)
        val existing = inDir(dir, save)
        val keep = settings.keep.coerceAtLeast(1)
        if (existing.firstOrNull()?.let { sameSave(it.readBytes(), bytes) } == true) {
            existing.drop(keep).forEach { it.delete() }   // KEEP PER GAME lowered since
            return null
        }
        dir.mkdirs()
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date(now))
        val out = File(dir, "${save.nameWithoutExtension}.backup-$stamp.${save.extension}")
        val tmp = File(dir, "${out.name}.tmp")
        tmp.writeBytes(bytes)
        if (!tmp.renameTo(out)) { tmp.delete(); return null }
        (listOf(out) + existing.filter { it != out }).drop(keep).forEach { it.delete() }
        out
    }.onFailure { Log.w("pokedaisy", "save backup failed: $it") }.getOrNull()

    /** Same save data, ignoring mGBA's 16-byte RTC footer (every GBA save chip's
     * size is a multiple of 512), which changes with the clock alone. */
    internal fun sameSave(a: ByteArray, b: ByteArray): Boolean {
        val n = a.size - a.size % 512
        if (n == 0 || n != b.size - b.size % 512) return a.contentEquals(b)
        for (i in 0 until n) if (a[i] != b[i]) return false
        return true
    }
}
