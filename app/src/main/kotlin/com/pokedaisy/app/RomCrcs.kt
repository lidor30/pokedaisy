package com.pokedaisy.app

import android.content.Context
import java.io.File

/**
 * A game's CRC32, the key of its save states, caches and per-game settings ([SaveStates]), kept by path + size +
 * modified time in `filesDir/rom-crcs.tsv` ([RomPatcher.CrcCache], shared with PATCH A ROM). Working it out reads the
 * whole ROM: a 512 MB DS cart from an SD card took over 5 s, and opening Black 2 the first time froze the game's
 * window until Android closed the app. So a launch [prepare]s the ROM off the UI thread first, and the game's own
 * [PokeDaisyActivity.loadRom] finds it ready ([of] still works it out where nothing did).
 */
object RomCrcs {
    private var cache: RomPatcher.CrcCache? = null

    @Synchronized
    fun cache(context: Context): RomPatcher.CrcCache =
        cache ?: RomPatcher.CrcCache(File(context.applicationContext.filesDir, "rom-crcs.tsv")).also { cache = it }

    /** [rom]'s CRC32 if it's known (an archive's: the ROM inside). */
    fun cached(context: Context, rom: File): Long? = cache(context).get(rom)

    /**
     * [rom]'s CRC32, worked out and kept when it isn't known. Blocking: off the UI thread where it may not be. For an
     * archive, [unpacked] (its ROM already extracted, [RomArchive.playable]) is read instead of the archive: the same
     * bytes, without decompressing it a second time - a .7z's LZMA2 asks for its whole dictionary (128 MB) and ran a
     * phone out of memory on the UI thread (Crashlytics, 1.4.0).
     */
    fun of(context: Context, rom: File, unpacked: File? = null): Long = cached(context, rom)
        ?: RomPatcher.crcOf(unpacked?.takeIf { it != rom && it.isFile } ?: rom).also { cache(context).put(rom, it) }

    /** Whether opening [rom] would read or unpack it whole first: [prepare] it off the UI thread. */
    fun needsPrepare(context: Context, rom: File): Boolean = RomArchive.isArchive(rom) || cached(context, rom) == null

    /** Off the UI thread, before a launch: an archive unpacked ([RomArchive.playable]) and the CRC known. */
    fun prepare(context: Context, rom: File) {
        val unpacked = runCatching { RomArchive.playable(rom, context.cacheDir) }.getOrNull()
        runCatching { of(context, rom, unpacked) }
    }

    /** A ROM big enough that [prepare] takes a noticeable moment (a DS cart): say so while it runs. */
    fun isBig(rom: File): Boolean = rom.length() > 64L * 1024 * 1024
}
