package com.pokedaisy.app.ios

import com.pokedaisy.app.GameInfo
import com.pokedaisy.app.GameInfoDetails
import com.pokedaisy.app.GameTitleCatalog
import com.pokedaisy.app.companion.data.BlockedGames
import com.pokedaisy.app.companion.data.RomIdentityReader
import com.pokedaisy.app.companion.data.StateFiles
import com.pokedaisy.app.companion.data.romCrc32
import com.pokedaisy.app.companion.i18n.tr
import com.pokedaisy.app.game.RomPicker
import okio.FileSystem
import okio.Path.Companion.toPath
import okio.SYSTEM

/** Read the imported cartridge and its saves without opening a core. Called off the UI thread. */
internal fun readIosGameInfo(path: String, title: String, recent: Int): GameInfo {
    val rom = path.toPath()
    val save = RomPicker.savePathFor(path).toPath()
    val reader = RomIdentityReader.forPath(rom)
    val metadata = FileSystem.SYSTEM.metadataOrNull(rom)
    val gameBoy = reader.isGameBoy()
    val headerTitle = if (gameBoy) reader.gameBoyTitle() else reader.headerTitle()?.takeIf { it.isNotBlank() }
    val code = if (gameBoy) null else reader.gameCode()?.takeIf { it.all { c -> c in '0'..'9' || c in 'A'..'Z' } }
    val sha1 = reader.sha1()
    val crc = runCatching { romCrc32(rom).toString(16).padStart(8, '0') }.getOrNull()
    val blocked = BlockedGames.forSha1(sha1) ?: BlockedGames.forCrc(crc)
    val states = runCatching { RomPicker.statesDir.toPath() / StateFiles.romKey(rom) }.getOrNull()
    val game = GameInfoDetails.baseGame(code) ?: sha1?.let { GameTitleCatalog.BY_SHA1[it] } ?: headerTitle
    val backups = iosSaveBackups(save)
    val date = FoundationDates::shortDateTime

    val gameRows = buildList {
        add(tr("NAME") to title)
        add(tr("GAME") to (game ?: tr("Unknown")))
        if (blocked != null) add(tr("SECOND SCREEN") to tr("Removed at its developer's request"))
        if (recent > 0) add(tr("LAST PLAYED") to tr("#{0} in recent games", recent))
    }
    return GameInfo(
        title = title,
        sections = listOf(
            GameInfo.Section(tr("GAME"), gameRows),
            GameInfoDetails.rom(
                rom.name, "PokeDaisy/roms", tr("Imported"), metadata?.size?.let(GameInfoDetails::sizeLabel),
                metadata?.lastModifiedAtMillis?.let(date), headerTitle, code,
                if (gameBoy) reader.gameBoyRevision() else reader.revision(), crc, sha1,
            ),
            GameInfoDetails.save(save, backups, date, showChip = !gameBoy),
            GameInfoDetails.states(states, date),
        ),
        blocked = blocked,
    )
}
