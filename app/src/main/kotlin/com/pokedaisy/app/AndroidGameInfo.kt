package com.pokedaisy.app

import android.content.Context
import com.pokedaisy.app.GameInfo.Section
import com.pokedaisy.app.GameInfo.GamePick
import com.pokedaisy.app.achievements.RetroAchievements
import com.pokedaisy.app.companion.i18n.tr
import java.io.File
import java.text.DateFormat
import java.util.Date
import okio.Path.Companion.toOkioPath

/** Library INFO's Android file, cover, support and achievements adapter. */
object AndroidGameInfo {
    /** Blocking (reads headers; with [hashes], the whole ROM - CRC32, SHA1, the
     * companion check) - off the UI thread. Without [hashes] it's quick, for a
     * first look while those run. */
    fun read(context: Context, prefs: Prefs, rom: File, hashes: Boolean): GameInfo {
        val pending = "…"
        val name = GameTitles.label(context, prefs, rom)
        val code = RomIdentity.gameCode(rom)
        val sha1 = if (hashes) RomIdentity.sha1(rom) else null
        val crc = if (hashes) runCatching { SaveStates.crc32(rom) }.getOrNull() else null
        val game = sha1?.let { GameTitles.BY_SHA1[it] } ?: GameInfoDetails.baseGame(code)
        val linked = RomFolder.isLinked(prefs, rom)
        val blocked = com.pokedaisy.app.companion.data.BlockedGames.forSha1(sha1)
        val supported = if (hashes) CompanionSupport.isSupported(rom) else null
        val bestEffort = if (supported == false && blocked == null) com.pokedaisy.app.companion.data.BestEffortStore.load(sha1) else null
        val bestEffortAs = bestEffort?.let { com.pokedaisy.app.companion.data.BestEffort.candidate(it.id)?.title }

        val gameRows = buildList {
            add(tr("NAME") to name)
            add(tr("GAME") to (game ?: tr("Unknown")))
            add(tr("SECOND SCREEN") to when {
                !hashes -> pending
                blocked != null -> tr("Removed at its developer's request")
                supported == true -> tr("Supported")
                bestEffort != null && bestEffort.full -> tr("Supported (best effort, as {0})", bestEffortAs ?: "?")
                bestEffort != null -> tr("Partly (best effort, as {0})", bestEffortAs ?: "?")
                else -> tr("Not supported")
            })
            if (rom.absolutePath in prefs.recentRomPaths()) {
                add(tr("LAST PLAYED") to tr("#{0} in recent games", prefs.recentRomPaths().indexOf(rom.absolutePath) + 1))
            }
            add(tr("COVER") to when {
                prefs.romCoverManual(rom) -> tr("Picked by you")
                !coverFile(context, rom).isFile -> tr("None")
                prefs.romCoverSource(rom) == CoverArtSync.Source.SCREENSCRAPER.name -> tr("From ScreenScraper")
                prefs.romCoverSource(rom) == CoverArtSync.Source.RETROACHIEVEMENTS.name -> tr("From RetroAchievements")
                else -> tr("From SteamGridDB")
            })
        }

        val romSection = GameInfoDetails.rom(
            rom.name, rom.parent ?: "?", if (linked) tr("Your ROMs folder (played in place)")
            else prefs.romSourcePath(rom)?.let { tr("Imported from {0}", it) } ?: tr("Imported"),
            sizeLabel(rom.length()), dateLabel(rom.lastModified()), RomIdentity.headerTitle(rom),
            code ?: "?", RomIdentity.revision(rom), crc, sha1,
        )
        val save = SavesLocation.saveFor(context, prefs, rom)
        val savesDir = save.parentFile ?: SavesLocation.dir(context, prefs)
        val backups = GameSaves.backups(savesDir.toOkioPath(), save.nameWithoutExtension)
        val saveSection = GameInfoDetails.save(save.toOkioPath(), backups, ::dateLabel, ::sizeLabel)
        val states = crc?.let { SaveStates(context.getExternalFilesDir(null) ?: context.filesDir, it).files.directory }

        return GameInfo(
            name,
            listOf(
                Section(tr("GAME"), gameRows),
                romSection,
                saveSection,
                GameInfoDetails.states(states, ::dateLabel),
                Section("RetroAchievements", achievementRows(rom, hashes)),
            ),
            supportUrl = if (supported == false && bestEffort?.full != true && blocked == null) RomSupportRequest.url(rom, sha1) else null,
            bestEffortSha1 = sha1.takeIf { bestEffort != null },
            gamePick = sha1?.takeIf { supported == false && blocked == null && code != null && com.pokedaisy.app.companion.data.BestEffort.canTry(code) }
                ?.let { GamePick(it, code!!, bestEffort?.id) },
            blocked = blocked,
        )
    }

    /** The ROM's set (looked up by its MD5) and the signed-in player's unlocks in it. */
    private fun achievementRows(rom: File, hashes: Boolean): List<Pair<String, String>> = buildList {
        val ra = RetroAchievements
        if (!ra.available || !hashes) {
            add(tr("SET") to if (!ra.available) tr("Unavailable") else "…")
            return@buildList
        }
        // A DS cart's hash is RA's own DS one (header + code + icon), not the file's MD5.
        val md5 = RomIdentity.raHash(rom)
        val id = md5?.let { ra.lookupGameId(it) }
        add(
            tr("SET") to when (id) {
                null -> tr("Couldn't reach RetroAchievements")
                0 -> tr("None for this ROM")
                else -> tr("Game #{0}", id)
            },
        )
        if (id != null && id > 0) {
            add(
                tr("UNLOCKED") to when {
                    !ra.accountSaved -> tr("Sign in (Settings > RetroAchievements) to see yours")
                    else -> ra.progress(RomIdentity.raConsole(rom))?.let { all ->
                        val p = all[id]
                        when {
                            p == null -> tr("None yet")
                            p.total > 0 -> tr("{0} of {1}", p.unlocked, p.total) +
                                (if (p.unlockedHardcore > 0) " · " + tr("{0} hardcore", p.unlockedHardcore) else "")
                            else -> "${p.unlocked}"
                        }
                    } ?: tr("Couldn't load your progress")
                },
            )
        }
        md5?.let { add((if (RomIdentity.isNds(rom)) tr("RA HASH") else "MD5") to it) }
    }

    private fun coverFile(context: Context, rom: File) =
        File(File(context.getExternalFilesDir(null) ?: context.filesDir, "covers"), "${rom.name}.png")

    fun sizeLabel(bytes: Long): String = when {
        bytes >= 1 shl 20 -> "%.1f MB".format(bytes / 1048576.0)
        bytes >= 1 shl 10 -> "%.1f KB".format(bytes / 1024.0)
        else -> "$bytes B"
    }

    fun dateLabel(ms: Long): String =
        DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(ms))
}
