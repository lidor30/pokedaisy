package com.pokedaisy.app.n3ds

import android.content.Context
import android.util.Log
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Where a 3DS game's save lives and what has to be there before the game boots.
 *
 * Azahar keeps a cart's save as the save archive's files (Pokemon X / Y: one file, `main`) in its emulated SD card, under
 * [root]: `sdmc/Nintendo 3DS/<id0>/<id1>/title/<high>/<low>/data/00000001/`, with the archive's format in
 * `00000001.metadata` beside the folder. **Without that file the game is told its save isn't formatted and formats it
 * again at boot, deleting the save** (seen headless: docs/3DS_GAMES_PLAN.md). So before every boot [prepare] writes the
 * format of a game it knows, refuses to boot a save of a game it doesn't, and copies the save aside first.
 */
object N3dsSaves {
    private const val TAG = "pokedaisy/n3ds"
    private const val ZEROS = "00000000000000000000000000000000"
    private const val KEEP_BACKUPS = 10

    /** A save archive's format as the game makes it (FS:FormatSaveData), Azahar's `ArchiveFormatInfo`. */
    class Format(val totalSize: Int, val directories: Int, val files: Int, val duplicate: Boolean)

    /** Formats seen from each game making its own new save. */
    private val FORMATS = mapOf(
        N3dsCart.POKEMON_X_USA to Format(0x40000, 1, 1, true),
        N3dsCart.POKEMON_Y_USA to Format(0x40000, 1, 1, true),
    )

    /** Azahar's user folder: its SD card, NAND, config and shader caches. On the shared storage, so a save can be put
     * in or copied out over USB. */
    fun root(context: Context): File = File(context.getExternalFilesDir(null) ?: context.filesDir, "n3ds")

    fun saveFolder(context: Context, titleId: Long): File {
        val high = String.format(Locale.US, "%08x", (titleId ushr 32).toInt())
        val low = String.format(Locale.US, "%08x", titleId.toInt())
        return File(root(context), "sdmc/Nintendo 3DS/$ZEROS/$ZEROS/title/$high/$low/data/00000001")
    }

    private fun metadata(folder: File) = File(folder.parentFile, "00000001.metadata")

    /** What [prepare] found: OK to boot, or why not. */
    sealed class Ready {
        object Ok : Ready()
        class Refused(val reason: String) : Ready()
    }

    /**
     * Before a boot: a save without its format file gets the game's format written (or, for a game whose format isn't
     * known, the boot is refused: it would format the save away), and a save there is copied to `backups/` first.
     */
    fun prepare(context: Context, titleId: Long): Ready {
        val folder = saveFolder(context, titleId)
        val files = folder.listFiles()?.filter { it.isFile }.orEmpty()
        if (files.isEmpty()) return Ready.Ok // a new game: the game formats its own save
        if (!metadata(folder).isFile) {
            val format = FORMATS[titleId]
                ?: return Ready.Refused("This game's save has no format file, and PokeDaisy doesn't know the game's own.")
            writeFormat(metadata(folder), format)
            Log.i(TAG, "wrote the save format for ${java.lang.Long.toHexString(titleId)}")
        }
        backup(context, titleId, files)
        return Ready.Ok
    }

    private fun writeFormat(file: File, f: Format) {
        val b = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN)
        b.putInt(f.totalSize).putInt(f.directories).putInt(f.files).put(if (f.duplicate) 1 else 0)
        file.writeBytes(b.array())
    }

    /** The save's files, as they are before this boot, into `backups/<title id>/<time>/`; the newest [KEEP_BACKUPS]
     * that differ from each other are kept. */
    private fun backup(context: Context, titleId: Long, files: List<File>) {
        val dir = File(root(context), "backups/" + java.lang.Long.toHexString(titleId))
        val previous = dir.listFiles()?.filter { it.isDirectory }?.maxByOrNull { it.name }
        if (previous != null && files.all { f -> File(previous, f.name).let { it.isFile && it.readBytes().contentEquals(f.readBytes()) } }) return
        val to = File(dir, SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date()))
        to.mkdirs()
        files.forEach { it.copyTo(File(to, it.name), overwrite = true) }
        dir.listFiles()?.filter { it.isDirectory }?.sortedByDescending { it.name }?.drop(KEEP_BACKUPS)?.forEach { it.deleteRecursively() }
    }
}
