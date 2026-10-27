package com.pokedaisy.app.companion.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/**
 * What the co-op battle code shows over the game, in the HUD's view: the partner's join prompt, the host's wait for an
 * answer, the link-drop wait and short notices. The battle itself is the game's own link battle, never drawn here. The glue
 * (CoopBattles) fills it from the emu thread (Compose state tolerates that) and takes the answers back through the callbacks.
 * Nothing here talks to the game.
 */
class CoopOverlayState {
    var join by mutableStateOf<CoopJoinPromptState?>(null)
    /** (partner's name, seconds left) while the host waits for an answer. */
    private var hostWaitState by mutableStateOf<Pair<String, Int>?>(null)
    private var hostWaitSince = 0L
    internal var cancelGraceNanos = CANCEL_GRACE_NANOS
    var hostWait: Pair<String, Int>?
        get() = hostWaitState
        set(v) { if (v != null && hostWaitState == null) hostWaitSince = System.nanoTime(); hostWaitState = v }
    /** (partner's name, seconds left) while the link is down in a battle. */
    var linkWait by mutableStateOf<Pair<String, Int>?>(null)
    var notice by mutableStateOf<String?>(null)
    private var noticeToken by mutableStateOf(0)
    /** The game's select sound (set by the overlay): a cursor step, A, B. */
    var onSound: () -> Unit = {}

    var onJoin: () -> Unit = {}
    var onDecline: () -> Unit = {}
    var onCancelAsk: () -> Unit = {}

    /** Something is up that takes the game's buttons. */
    val takesKeys: Boolean get() = join != null || hostWait != null

    fun showNotice(text: String) { notice = text; noticeToken++ }

    /** A game button while [takesKeys] (UI thread): the prompt's or the wait's. True = used. */
    fun key(k: MenuKey): Boolean {
        join?.let { j ->
            when (k) {
                MenuKey.UP, MenuKey.DOWN -> j.cursorJoin = !j.cursorJoin
                MenuKey.A -> if (j.cursorJoin) onJoin() else onDecline()
                MenuKey.B -> onDecline()
                else -> {}
            }
            return true
        }
        if (hostWait != null) {
            // not A: the host is often mashing it through the battle intro; and not a B held or tapped as the wait opens (running
            // away, auto-run): the device run's offers were all withdrawn 0.4 s after they went out
            if (k == MenuKey.B && System.nanoTime() - hostWaitSince >= cancelGraceNanos) onCancelAsk()
            return true
        }
        return false
    }

    internal val noticeKey: Int get() = noticeToken

    companion object { const val CANCEL_GRACE_NANOS = 2_000_000_000L }
}

/** The overlays, over the whole game view; each sits in its own part of the picture and leaves the rest to the game. */
@Composable
fun CoopBattleOverlays(state: CoopOverlayState) {
    val click = LocalClickSound.current
    androidx.compose.runtime.SideEffect { state.onSound = click }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        state.join?.let { j ->
            Box(Modifier.align(Alignment.Center).fillMaxWidth(0.6f).fillMaxHeight(0.7f)) {
                CoopJoinPrompt(j, onJoin = state.onJoin, onDecline = state.onDecline)
            }
        }
        state.hostWait?.let { (name, left) ->
            Box(Modifier.align(Alignment.Center).fillMaxWidth(0.6f).fillMaxHeight(0.5f)) {
                CoopHostWaiting(name, left, onCancel = state.onCancelAsk)
            }
        }
        state.linkWait?.let { (name, left) ->
            Box(Modifier.align(Alignment.Center).fillMaxWidth(0.6f).fillMaxHeight(0.5f)) { CoopLinkWait(name, left) }
        }
        state.notice?.let { text ->
            Box(Modifier.align(Alignment.TopCenter).fillMaxWidth(0.7f).fillMaxHeight(0.3f).padding(top = 8.dp)) { CoopNotice(text) }
            LaunchedEffect(state.noticeKey) {
                delay(NOTICE_MS)
                state.notice = null
            }
        }
    }
}

private const val NOTICE_MS = 4_000L
