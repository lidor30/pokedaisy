package com.pokedaisy.app

import android.content.Context
import com.pokedaisy.app.companion.data.save.Gen3SaveFormat
import com.pokedaisy.app.companion.i18n.tr
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption

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
    fun formatFor(rom: File): Gen3SaveFormat? {
        if (RomIdentity.isGameBoy(rom)) return null
        if (RomArchive.romSize(rom) > 0x1000000L) return null
        val code = RomIdentity.gameCode(rom)
        // 16 MB builds under a retail header whose save isn't retail's: read as retail, they come out as other species at
        // the wrong levels, and an edit would be written at retail's offsets. Only a full 16 MB ROM can be one.
        if ((code == "BPEE" || code == "BPRE") && RomArchive.romSize(rom) == 0x1000000L && RomIdentity.sha1(rom) in NOT_RETAIL_SAVES) return null
        return Gen3SaveFormat.forGameCode(code)
    }

    /**
     * The 16 MB builds the Poller knows whose save isn't laid out as their header's retail game's:
     * - Emerald Legacy: a 120-slot ITEMS pocket moves everything after it in SaveBlock1.
     * - Emerald Seaglass: pokeemerald-expansion's records (National Dex species ids, experience in 21 bits with a nickname's
     *   11th letter above it, its own shiny odds; GitHub #1167: COLLECTION showed its Pokémon at level 100 and none shiny).
     * - Recharged Emerald / Recharged Yellow: SaveBlock1s of their own past the bag.
     * - Clover: FireRed's save under an Emerald header.
     */
    private val NOT_RETAIL_SAVES = with(com.pokedaisy.app.companion.data.TelemetrySampler) {
        setOf(
            EMERALD_LEGACY_V1_1_4_SHA1, EMERALD_SEAGLASS_V3_0_SHA1, CLOVER_V1_3_3_SHA1,
            RECHARGED_EMERALD_V2_2_5_SHA1, RECHARGED_EMERALD_V2_2_8_SHA1, RECHARGED_EMERALD_REBALANCED_V2_2_8_SHA1,
            RECHARGED_YELLOW_V1_8_0_SHA1, RECHARGED_YELLOW_V1_9_0_SHA1, RECHARGED_YELLOW_V1_9_4_SHA1, RECHARGED_YELLOW_V1_9_7_SHA1,
        )
    }

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
    fun commit(context: Context, prefs: Prefs, rom: File, expected: ByteArray, edited: ByteArray): Result {
        val save = SavesLocation.saveFor(context, prefs, rom)
        val result = write(save, expected, edited) { SaveBackups.snapshot(it, force = true) }
        if (result is Result.Written) runCatching {
            SaveStates(context.getExternalFilesDir(null) ?: context.filesDir, SaveStates.crc32(rom)).freshBootFile.createNewFile()
        }
        return result
    }

    /**
     * A trade: [a]'s and [b]'s saves written together. [b] first; if [a] then fails, [b] is put back as it was (through the
     * same checked write), so neither save ends up with a Pokémon twice or not at all. Blocking.
     */
    fun commitBoth(context: Context, prefs: Prefs, a: Edit, b: Edit): Result {
        val saveA = SavesLocation.saveFor(context, prefs, a.rom)
        val saveB = SavesLocation.saveFor(context, prefs, b.rom)
        val result = writeBoth(saveA, a, saveB, b) { SaveBackups.snapshot(it, force = true) }
        if (result is Result.Written) for (rom in listOf(a.rom, b.rom)) runCatching {
            SaveStates(context.getExternalFilesDir(null) ?: context.filesDir, SaveStates.crc32(rom)).freshBootFile.createNewFile()
        }
        return result
    }

    /** One save's part of [commitBoth]: the file as read and as edited. */
    class Edit(val rom: File, val expected: ByteArray, val edited: ByteArray)

    internal fun writeBoth(saveA: File, a: Edit, saveB: File, b: Edit, backup: (File) -> File?): Result {
        if (saveA.absoluteFile == saveB.absoluteFile) return Result.Failed(tr("Both games use the same save file"))
        val first = write(saveB, b.expected, b.edited, backup)
        if (first !is Result.Written) return first
        val second = write(saveA, a.expected, a.edited, backup)
        if (second !is Result.Written) {
            // Put B back; if even that fails, its backup (first.backup) holds it.
            write(saveB, b.edited, b.expected, backup)
            return second
        }
        return second
    }

    /** [commit]'s file work, apart from Android for the tests. [backup] takes a copy and returns it, or null if it didn't. */
    internal fun write(save: File, expected: ByteArray, edited: ByteArray, backup: (File) -> File?): Result {
        val dir = save.parentFile ?: return Result.Failed(tr("Can't write the save to {0}", save.path))
        val current = runCatching { save.readBytes() }.getOrNull()
            ?: return Result.Failed(tr("Can't read the save {0}", save.path))
        if (!current.contentEquals(expected)) return Result.Failed(tr("The save changed since it was opened - open it again"))

        // A copy that holds the save as it is now: a new one, or the newest one already is it.
        val copy = backup(save) ?: SaveBackups.list(save).firstOrNull()
        if (copy == null || !runCatching { copy.readBytes().contentEquals(current) }.getOrDefault(false)) {
            return Result.Failed(tr("Can't back up the save in {0} - nothing was changed", dir.absolutePath))
        }

        val tmp = File(dir, "${save.name}.edit.tmp")
        val moved = runCatching {
            FileOutputStream(tmp).use { it.write(edited); it.fd.sync() }
            try {
                Files.move(tmp.toPath(), save.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            } catch (_: Exception) {
                Files.move(tmp.toPath(), save.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        }.isSuccess
        if (!moved) {
            tmp.delete()
            return Result.Failed(tr("Can't write the save to {0}", dir.absolutePath))
        }
        if (!runCatching { save.readBytes().contentEquals(edited) }.getOrDefault(false)) {
            runCatching { copy.copyTo(save, overwrite = true) }
            return Result.Failed(tr("The save didn't read back as written - the backup was put back"))
        }
        return Result.Written(save, copy)
    }
}
