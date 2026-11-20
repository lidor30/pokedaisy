package com.pokedaisy.app.ios

import com.pokedaisy.app.companion.data.RomIdentityReader
import com.pokedaisy.app.companion.data.StateFiles
import com.pokedaisy.app.companion.data.isFile
import com.pokedaisy.app.companion.data.length
import com.pokedaisy.app.companion.data.save.Gen3SaveEditor
import com.pokedaisy.app.companion.data.save.Gen3SaveReader
import com.pokedaisy.app.companion.data.save.RomMonIcons
import com.pokedaisy.app.companion.data.save.SPECIES_EGG
import com.pokedaisy.app.companion.data.save.TradeSide
import com.pokedaisy.app.companion.i18n.tr
import com.pokedaisy.app.companion.ui.MonIconLoader
import com.pokedaisy.app.companion.ui.imageBitmap
import com.pokedaisy.app.game.RomPicker
import okio.Path
import okio.Path.Companion.toPath

internal sealed interface IosGen3Save {
    class Ready(
        val path: Path, val stateKey: String, val gameCode: String,
        val source: Gen3SaveReader.Result.Ready, val icons: MonIconLoader?,
    ) : IosGen3Save {
        fun tradeSide(title: String) = TradeSide(title, Gen3SaveEditor(source.save), gameCode)
    }
    class Failed(val message: String) : IosGen3Save
}

internal fun editableIosGames(paths: List<String>): List<String> = paths.filter {
    val rom = it.toPath()
    RomPicker.savePathFor(it).toPath().isFile &&
        Gen3SaveReader.formatFor(RomIdentityReader.forPath(rom)) { rom.length } != null
}.distinctBy { RomPicker.savePathFor(it) }

/** Use the raw files that iOS plays from. Both library tools share Gen 3 eligibility and sector checks with Android. */
internal fun readIosGen3Save(path: String, title: String): IosGen3Save {
    if (!recoverIosSaveTrades()) return IosGen3Save.Failed(iosSaveTradeRecoveryError())
    return runCatching {
        val rom = path.toPath()
        val reader = RomIdentityReader.forPath(rom)
        val format = Gen3SaveReader.formatFor(reader) { rom.length }
            ?: return IosGen3Save.Failed(tr("The save editor can't read this game's save format."))
        val battery = RomPicker.savePathFor(path).toPath(normalize = true)
        when (val result = Gen3SaveReader.open(battery, format)) {
            is Gen3SaveReader.Result.Failed -> IosGen3Save.Failed(result.message)
            is Gen3SaveReader.Result.Ready -> {
                val code = reader.gameCode().orEmpty()
                val icons = RomMonIcons.forRom(rom, reader.sha1(), code, reader.revision())?.let { source ->
                    MonIconLoader { species, egg -> source.get(if (egg) SPECIES_EGG else species)?.imageBitmap() }
                }
                IosGen3Save.Ready(battery, StateFiles.romKey(rom), code, result, icons)
            }
        }
    }.getOrElse { IosGen3Save.Failed(tr("Can't read the save {0}", title)) }
}
