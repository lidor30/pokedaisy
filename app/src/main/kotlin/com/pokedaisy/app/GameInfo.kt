package com.pokedaisy.app

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.pokedaisy.app.companion.i18n.tk
import com.pokedaisy.app.companion.i18n.tr
import com.pokedaisy.app.companion.ui.OptionSelector
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.pokedaisy.app.companion.ui.GbaText
import com.pokedaisy.app.companion.ui.GbaTextMetrics
import com.pokedaisy.app.companion.ui.OptionButton
import com.pokedaisy.app.companion.ui.OptionColors
import com.pokedaisy.app.companion.ui.OptionListWindow
import com.pokedaisy.app.companion.ui.OptionOverlay
import com.pokedaisy.app.companion.ui.OptionTitleWindow
import com.pokedaisy.app.companion.ui.drawRowDivider
import androidx.compose.ui.draw.drawBehind
import com.pokedaisy.app.achievements.RetroAchievements
import java.io.File
import java.text.DateFormat
import java.util.Date

/** The library menu's INFO: everything known about a game, its ROM and its saves, by section. */
class GameInfo(
    val title: String,
    val sections: List<Section>,
    /** A ROM the companion can't read: ASK FOR SUPPORT's request form ([RomSupportRequest]). */
    val supportUrl: String? = null,
    /** The ROM's SHA-1 when a best-effort match is kept for it: FORGET MATCH. */
    val bestEffortSha1: String? = null,
    /** A ROM of a Gen 3 base game the app doesn't know: PICK A GAME (WHICH GAME IS THIS?). */
    val gamePick: GamePick? = null,
    /** A game its makers asked PokéDaisy not to read ([com.pokedaisy.app.companion.data.BlockedGames]): its button. */
    val blocked: com.pokedaisy.app.companion.data.BlockedGame? = null,
) {
    class Section(val title: String, val rows: List<Pair<String, String>>)

    /** The ROM ([sha1], header [gameCode]) and the game it's read as now ([current], a candidate id). */
    class GamePick(val sha1: String, val gameCode: String, val current: String?)

    companion object {
        /** Blocking (reads headers; with [hashes], the whole ROM - CRC32, SHA1, the
         * companion check) - off the UI thread. Without [hashes] it's quick, for a
         * first look while those run. */
        fun read(context: Context, prefs: Prefs, rom: File, hashes: Boolean): GameInfo {
            val pending = "…"
            val name = GameTitles.label(context, prefs, rom)
            val code = RomIdentity.gameCode(rom)
            val sha1 = if (hashes) RomIdentity.sha1(rom) else null
            val crc = if (hashes) runCatching { SaveStates.crc32(rom) }.getOrNull() else null
            val game = sha1?.let { GameTitles.BY_SHA1[it] } ?: baseGame(code)
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

            val romRows = buildList {
                add(tr("FILE") to rom.name)
                add(tr("FOLDER") to (rom.parent ?: "?"))
                add(tr("FROM") to if (linked) tr("Your ROMs folder (played in place)") else prefs.romSourcePath(rom)?.let { tr("Imported from {0}", it) } ?: tr("Imported"))
                add(tr("SIZE") to sizeLabel(rom.length()))
                add(tr("MODIFIED") to dateLabel(rom.lastModified()))
                RomIdentity.headerTitle(rom)?.let { add(tr("HEADER TITLE") to it) }
                add(tr("GAME CODE") to (code ?: "?"))
                RomIdentity.revision(rom)?.let { add(tr("REVISION") to it.toString()) }
                add("CRC32" to (crc ?: pending))
                add("SHA1" to (sha1 ?: pending))
            }

            val save = SavesLocation.saveFor(context, prefs, rom)
            val savesDir = save.parentFile ?: SavesLocation.dir(context, prefs)
            val backups = GameSaves.backups(savesDir, save.nameWithoutExtension)
            val saveRows = buildList {
                if (save.isFile) {
                    add(tr("FILE") to save.absolutePath)
                    add(tr("SIZE") to (sizeLabel(save.length()) + (GameSaves.kind(save.length())?.let { " · $it" } ?: "")))
                    add(tr("MODIFIED") to dateLabel(save.lastModified()))
                } else {
                    add(tr("FILE") to tr("None yet - made by the game's first save"))
                    add(tr("WILL BE") to save.absolutePath)
                }
                add(tr("BACKUPS") to if (backups.isEmpty()) tr("None") else tr("{0} · newest {1}", backups.size, backups.first().name))
            }

            val stateRows = buildList {
                if (crc == null) {
                    add(tr("SLOTS") to pending)
                } else {
                    val st = SaveStates(context.getExternalFilesDir(null) ?: context.filesDir, crc)
                    val slots = st.allSlots().filter { it.present }
                    add(tr("SLOTS") to tr("{0} of {1} used", slots.size, SaveStates.SLOT_MAX - SaveStates.SLOT_MIN + 1))
                    slots.maxByOrNull { it.savedAt }?.let { add(tr("NEWEST") to tr("Slot {0} · {1}", it.slot, dateLabel(it.savedAt))) }
                    add(tr("RESUME") to when {
                        st.freshBootFile.exists() -> tr("Starts fresh from the save next time")
                        st.resumeFile.isFile -> tr("Picks up where you left off ({0})", dateLabel(st.resumeFile.lastModified()))
                        else -> tr("None")
                    })
                }
            }

            return GameInfo(
                name,
                listOf(
                    Section(tr("GAME"), gameRows),
                    Section("ROM", romRows),
                    Section(tr("SAVE FILE"), saveRows),
                    Section(tr("SAVE STATES"), stateRows),
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

        private fun baseGame(code: String?): String? = when (code) {
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
}

/** PICK A GAME: let best effort decide / NONE OF THESE (else a candidate id). */
const val GAME_AUTO = "AUTO"
const val GAME_NONE = "-"

/** [info] over the library: a title window, its sections in one scrolling list window, CLOSE. */
@Composable
fun GameInfoDialog(
    info: GameInfo, m: GbaTextMetrics, small: GbaTextMetrics, onDismiss: () -> Unit, onOpenUrl: (String) -> Unit = {},
    onForgetBestEffort: (String) -> Unit = {},
    /** PICK A GAME's answer: a candidate id, [GAME_AUTO] (best effort decides) or [GAME_NONE] (NONE OF THESE). */
    onPickGame: (GameInfo.GamePick, String) -> Unit = { _, _ -> },
) {
    var picking by remember { mutableStateOf(false) }
    OptionOverlay(onDismiss, Modifier.widthIn(max = 1100.dp).fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            OptionTitleWindow(tr("INFO"), m, trailing = info.title, onBack = onDismiss)
            Spacer(Modifier.height(m.u * 4))
            OptionListWindow(m, Modifier.fillMaxWidth().weight(1f)) {
                // Two columns (GAME, ROM | SAVE FILE, SAVE STATES): the landscape screen shows it all at once.
                Row(
                    Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = m.u * 4),
                    horizontalArrangement = Arrangement.spacedBy(m.u * 12),
                ) {
                    val half = (info.sections.size + 1) / 2
                    listOf(info.sections.take(half), info.sections.drop(half)).forEach { column ->
                        Column(Modifier.weight(1f)) {
                            column.forEachIndexed { i, section -> InfoSection(section, i == 0, m, small) }
                        }
                    }
                }
            }
            Spacer(Modifier.height(m.u * 4))
            Row(Modifier.fillMaxWidth()) {
                info.supportUrl?.let { url ->
                    // Not read by the companion: a game request with the file's name and SHA-1 (never the ROM).
                    OptionButton(tr("ASK FOR SUPPORT"), m, onClick = { onOpenUrl(url) })
                }
                info.blocked?.action?.let { a ->
                    // A blocked game's own link (its makers' Discord): where to ask about it, not us.
                    OptionButton(tr(a.label), m, onClick = { onOpenUrl(a.url) })
                }
                info.gamePick?.let {
                    // The game it's a version of, picked here rather than on the companion.
                    Spacer(Modifier.width(m.u * 4))
                    OptionButton(tk("PICK A GAME"), m, onClick = { picking = true })
                }
                info.bestEffortSha1?.let { sha1 ->
                    // Back to NOT SUPPORTED: the companion asks again next time.
                    Spacer(Modifier.width(m.u * 4))
                    OptionButton(tr("FORGET MATCH"), m, onClick = { onForgetBestEffort(sha1) })
                }
                Spacer(Modifier.weight(1f))
                OptionButton(tr("CLOSE"), m, emphasis = true, modifier = Modifier.widthIn(min = 160.dp), onClick = onDismiss)
            }
        }
    }
    info.gamePick?.takeIf { picking }?.let { gp ->
        // AUTO, then every config of its base game, one row per title (two builds can share one), then NONE OF THESE.
        val games = com.pokedaisy.app.companion.data.BestEffort.candidatesFor(gp.gameCode).distinctBy { it.title }
        val current = gp.current?.let { id -> games.firstOrNull { it.id == id }?.id ?: games.firstOrNull { c -> c.title == com.pokedaisy.app.companion.data.BestEffort.candidate(id)?.title }?.id } ?: GAME_AUTO
        // The one in use right under AUTO, in view without scrolling.
        val options = listOf(GAME_AUTO) + listOfNotNull(current.takeIf { it != GAME_AUTO }) + games.map { it.id }.filter { it != current } + GAME_NONE
        OptionSelector(
            tk("WHICH GAME IS THIS?"), options, current,
            { id -> when (id) { GAME_AUTO -> tr("AUTO"); GAME_NONE -> tr("NONE OF THESE"); else -> games.first { it.id == id }.title } }, m,
            onPick = { picking = false; onPickGame(gp, it) },
            onDismiss = { picking = false },
        )
    }
}

@Composable
internal fun InfoSection(section: GameInfo.Section, first: Boolean, m: GbaTextMetrics, small: GbaTextMetrics) {
    GbaText(
        section.title, OptionColors.value, OptionColors.valueShadow, m,
        Modifier.padding(top = if (first) m.u * 2 else m.u * 8, bottom = m.u * 2),
    )
    section.rows.forEachIndexed { j, (label, value) ->
        Row(
            Modifier.fillMaxWidth()
                .then(
                    if (j < section.rows.lastIndex) {
                        Modifier.drawBehind { drawRowDivider(OptionColors.divider, m.u.toPx(), 0f) }
                    } else Modifier,
                )
                .padding(vertical = small.u * 3),
            horizontalArrangement = Arrangement.spacedBy(m.u * 6),
        ) {
            GbaText(label, OptionColors.muted, OptionColors.mutedShadow, small, Modifier.weight(0.34f))
            GbaText(value, OptionColors.label, OptionColors.labelShadow, small, Modifier.weight(0.66f), maxLines = 3)
        }
    }
}
