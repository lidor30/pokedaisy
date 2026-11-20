package com.pokedaisy.app

import com.pokedaisy.app.GameInfo.Section
import com.pokedaisy.app.companion.data.StateFiles
import com.pokedaisy.app.companion.data.isDirectory
import com.pokedaisy.app.companion.data.isFile
import com.pokedaisy.app.companion.data.lastModified
import com.pokedaisy.app.companion.data.length
import com.pokedaisy.app.companion.i18n.tr
import okio.Path

/** Section text shared by library hosts. Each host supplies its real storage paths and date format. */
object GameInfoDetails {
    private const val PENDING = "…"

    fun rom(
        name: String, folder: String, source: String, size: String?, modified: String?,
        title: String?, code: String?, revision: Int?, crc32: String?, sha1: String?,
    ) = Section("ROM", buildList {
        add(tr("FILE") to name)
        add(tr("FOLDER") to folder)
        add(tr("FROM") to source)
        size?.let { add(tr("SIZE") to it) }
        modified?.let { add(tr("MODIFIED") to it) }
        title?.let { add(tr("HEADER TITLE") to it) }
        code?.let { add(tr("GAME CODE") to it) }
        revision?.let { add(tr("REVISION") to it.toString()) }
        add("CRC32" to (crc32 ?: PENDING))
        add("SHA1" to (sha1 ?: PENDING))
    })

    fun save(
        path: Path, backups: List<Path>, formatDate: (Long) -> String,
        formatSize: (Long) -> String = ::sizeLabel, showChip: Boolean = true,
    ) = Section(tr("SAVE FILE"), buildList {
        if (path.isFile) {
            add(tr("FILE") to path.toString())
            val size = path.length
            val chip = if (showChip) GameSaves.kind(size) else null
            add(tr("SIZE") to (formatSize(size) + (chip?.let { " · $it" } ?: "")))
            add(tr("MODIFIED") to formatDate(path.lastModified))
        } else {
            add(tr("FILE") to tr("None yet - made by the game's first save"))
            add(tr("WILL BE") to path.toString())
        }
        add(tr("BACKUPS") to (backups.firstOrNull()?.let { tr("{0} · newest {1}", backups.size, it.name) } ?: tr("None")))
    })

    fun states(dir: Path?, formatDate: (Long) -> String) = Section(tr("SAVE STATES"), buildList {
        if (dir == null) {
            add(tr("SLOTS") to PENDING)
            return@buildList
        }
        val files = dir.takeIf { it.isDirectory }?.let { StateFiles(it) }
        val slots = (0..9).filter { files?.exists(it) == true }
        add(tr("SLOTS") to tr("{0} of {1} used", slots.size, 10))
        if (files != null) {
            slots.maxByOrNull { files.stateFile(it).lastModified }?.let {
                add(tr("NEWEST") to tr("Slot {0} · {1}", it, formatDate(files.stateFile(it).lastModified)))
            }
        }
        val resumable = files?.latestResumeSource()
        add(tr("RESUME") to when {
            files?.freshBootFile?.isFile == true -> tr("Starts fresh from the save next time")
            resumable != null -> tr("Picks up where you left off ({0})", formatDate(resumable.lastModified))
            else -> tr("None")
        })
    })

    fun sizeLabel(bytes: Long): String {
        val (unit, suffix) = when {
            bytes >= 1 shl 20 -> 1048576L to "MB"
            bytes >= 1 shl 10 -> 1024L to "KB"
            else -> return "$bytes B"
        }
        val tenths = (bytes / unit) * 10 + ((bytes % unit) * 10 + unit / 2) / unit
        return "${tenths / 10}.${tenths % 10} $suffix"
    }

    fun baseGame(code: String?): String? = when (code) {
        "BPRE", "PUVV" -> "Pokémon FireRed" // PUVV: Ultra Violet
        "BPGE" -> "Pokémon LeafGreen"
        "BPEE", "MONE" -> "Pokémon Emerald" // MONE: Monomyth (its own pokeemerald build)
        "BPES" -> "Pokémon Edición Esmeralda"
        "BPED" -> "Pokémon Smaragd-Edition"
        "BPEF" -> "Pokémon Version Émeraude"
        "BPEI" -> "Pokémon Versione Smeraldo"
        "BPEJ" -> "ポケットモンスター エメラルド"
        "AXVE" -> "Pokémon Ruby"
        "AXPE" -> "Pokémon Sapphire"
        else -> code?.let { com.pokedaisy.app.companion.data.RETAIL_PORT_CODE_TITLES[it] }
    }

}
