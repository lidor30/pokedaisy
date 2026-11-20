package com.pokedaisy.app.ios

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.UIKitInteropProperties
import androidx.compose.ui.viewinterop.UIKitView
import com.pokedaisy.app.LinkSavePaths
import com.pokedaisy.app.companion.LinkPlayFlow
import com.pokedaisy.app.companion.LinkPlayFlow.Phase
import com.pokedaisy.app.companion.TouchButton
import com.pokedaisy.app.companion.data.StateFiles
import com.pokedaisy.app.companion.data.StateResume
import com.pokedaisy.app.companion.data.romCrc32
import com.pokedaisy.app.companion.i18n.tk
import com.pokedaisy.app.companion.i18n.tr
import com.pokedaisy.app.companion.ui.*
import com.pokedaisy.app.companion.ui.theme.AppColors
import com.pokedaisy.app.gameAudioGain
import com.pokedaisy.app.game.LinkGameSession
import com.pokedaisy.app.game.RomPicker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath

@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, kotlinx.cinterop.ExperimentalForeignApi::class)
@Composable
internal fun IosLinkPlay(
    games: List<String>,
    initial: String?,
    label: (String) -> String,
    onClose: () -> Unit,
    onSaves: () -> Unit,
) {
    val play = remember(initial) { LinkPlayFlow<String>().apply { initial?.let(setup::seed) } }
    val setup = play.setup
    val scope = rememberCoroutineScope()
    val m = rememberGbaTextMetrics()
    var session by remember { mutableStateOf<LinkGameSession?>(null) }
    var focused by remember { mutableIntStateOf(0) }
    var fast by remember { mutableStateOf(false) }

    fun start() {
        val picked = play.beginStart() ?: return
        scope.launch {
            val prepared = withContext(Dispatchers.Default) { runCatching { IosLinkSaves.prepare(picked) } }
            val saves = prepared.getOrElse {
                play.startFailed(it.message ?: tr("Can't read that file"))
                return@launch
            }
            focused = 0
            fast = false
            val game = LinkGameSession(
                picked, saves.paths.map(Path::toString), gameAudioGain(iosPreferences.gameVolume, iosPreferences.gameMuted),
                onOpen = { opened ->
                    if (opened) play.opened()
                    else play.startFailed { session?.close() }
                },
                onFocus = { focused = it },
                onFault = { play.fault() },
                finishSaves = saves::finish,
                onClosed = { finished ->
                    session = null
                    val exit = play.closed(finished)
                    onSaves()
                    if (exit) onClose()
                },
            )
            session = game
            game.start()
        }
    }

    DisposableEffect(setup) {
        onDispose { setup.clearPicker(); session?.close() }
    }
    CompanionBackHandler { play.back(onClose) }
    LaunchedEffect(play.confirmingLeave, play.notice, session) { session?.blockInput(play.confirmingLeave || play.notice != null) }
    when (play.phase) {
        Phase.SETUP, Phase.STARTING -> LinkSetupScreen(
            setup, m, play.phase == Phase.STARTING, label, { it },
            onPick = { slot ->
                setup.begin(slot)?.let {
                    val cartridges = games.sortedBy { path -> label(path).lowercase() }
                    if (setup.ready(it, cartridges) && cartridges.isEmpty()) play.notice = tr("No GBA games in the library yet")
                }
            },
            onStart = ::start, onClose = onClose,
        )
        Phase.PLAYING -> session?.let { game ->
            val picked = setup.slots
            val shown = IosTouchPreferences.visible && !play.confirmingLeave && play.notice == null
            Column(Modifier.fillMaxSize().background(Color.Black)) {
                BoxWithConstraints(Modifier.fillMaxWidth().weight(1f)) {
                    IosLinkGrid(picked.size, wide = maxWidth >= maxHeight) { slot ->
                        val isFocused = slot == focused
                        val description = tr("GAME {0}", slot + 1) + " " + label(picked[slot])
                        Box(Modifier.fillMaxSize().clickable { game.focus(slot) }.semantics {
                            contentDescription = description
                            selected = isFocused
                        }, contentAlignment = Alignment.Center) {
                            Box(Modifier.aspectRatio(1.5f).then(
                                if (isFocused) Modifier.border(2.dp, AppColors.focusRing) else Modifier),
                            ) {
                                UIKitView(factory = { game.picture(slot) }, modifier = Modifier.fillMaxSize(),
                                    properties = UIKitInteropProperties(interactionMode = null))
                                Box(Modifier.fillMaxSize().background(
                                    if (isFocused) Color.Transparent else Color.Black.copy(alpha = 0.45f)))
                            }
                        }
                    }
                    LinkPlayChrome(m, fast = fast, onFast = { fast = !fast; game.fast = fast },
                        requestLeave = play::requestLeave)
                    if (play.confirmingLeave) LinkLeaveConfirmation(m, onLeave = { play.leave(game::close) }, onDismiss = play::dismissLeave)
                }
                if (shown) {
                    BoxWithConstraints(Modifier.fillMaxWidth()) {
                        val style = IosTouchPreferences.drawingStyle
                            .forButtons(setOf(TouchButton.TURBO_A, TouchButton.TURBO_B))
                        val touch = remember(game) {
                            TouchInput(onKeys = game::touch, onPress = {
                                val current = IosTouchPreferences.style
                                if (current.vibrate) IosTouchPreferences.feel(current.vibrationStrength)
                            })
                        }
                        DisposableEffect(game) { onDispose { game.touch(0, 0) } }
                        val compact = maxWidth < 480.dp
                        TouchControls(style, portrait = compact, touch,
                            modifier = Modifier.fillMaxWidth().height(if (compact) 185.dp else 128.dp))
                    }
                }
            }
        }
        Phase.CLOSING -> LinkPlayMessage(tr("Finishing the games' saves…"), m)
    }
    play.notice?.let {
        OptionConfirm(tk("LINK PLAY"), it, tk("OK"), m,
            onConfirm = { play.notice = null }, onDismiss = { play.notice = null }, cancelLabel = null)
    }
}

@Composable
private fun IosLinkGrid(count: Int, wide: Boolean, tile: @Composable (Int) -> Unit) {
    Layout(content = { repeat(count) { tile(it) } }, modifier = Modifier.fillMaxSize()) { tiles, max ->
        val (columns, rows) = linkGridShape(count, wide)
        val width = max.maxWidth / columns
        val height = max.maxHeight / rows
        val places = tiles.map { it.measure(Constraints.fixed(width, height)) }
        layout(max.maxWidth, max.maxHeight) {
            places.forEachIndexed { slot, picture ->
                picture.place((slot % columns) * width, (slot / columns) * height)
            }
        }
    }
}

/** Back up each actual battery destination and retire only resumes invalidated by a changed primary save. */
private class IosLinkSaves private constructor(
    val paths: List<Path>,
    private val originals: List<Original>,
) {
    private data class Original(val rom: String, val save: Path, val crc: Long?)

    fun finish() {
        originals.filter { stamp(it.save) != it.crc }.forEach {
            val state = iosStateFiles(it.rom.toPath())
            StateResume.startFresh(state)
        }
    }

    companion object {
        fun prepare(roms: List<String>): IosLinkSaves {
            val prepared = LinkSavePaths.prepare(
                roms, recover = ::recoverIosSaveTrades,
                saveFor = { RomPicker.savePathFor(it).toPath(normalize = true) },
                prepareBattery = {
                    StateFiles.backupBattery(it)
                    null
                },
            )
            return when (prepared) {
                is LinkSavePaths.Preparation.Failed -> error(prepared.reason)
                is LinkSavePaths.Preparation.Ready -> {
                    val previous = prepared.primarySlots.map { slot ->
                        val save = prepared.paths[slot]
                        Original(roms[slot], save, stamp(save))
                    }
                    IosLinkSaves(prepared.paths, previous)
                }
            }
        }

        private fun stamp(path: Path): Long? =
            FileSystem.SYSTEM.metadataOrNull(path)?.size?.takeIf { it > 0 }?.let { romCrc32(path) }
    }
}
