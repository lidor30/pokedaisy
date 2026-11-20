package com.pokedaisy.app.ios

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.pokedaisy.app.Hotkeys
import com.pokedaisy.app.companion.FeatureTips
import com.pokedaisy.app.companion.FieldHudPart
import com.pokedaisy.app.companion.FlyAction
import com.pokedaisy.app.companion.GameRuntime
import com.pokedaisy.app.companion.ItemUse
import com.pokedaisy.app.companion.TouchButton
import com.pokedaisy.app.companion.data.SnapshotView
import com.pokedaisy.app.companion.ui.FeatureTipHost
import com.pokedaisy.app.companion.ui.FlyStyleAsk
import com.pokedaisy.app.companion.ui.GameQuickActions
import com.pokedaisy.app.companion.GameTipFrame
import com.pokedaisy.app.companion.GameTipSession
import com.pokedaisy.app.companion.controllerTipShortcut
import com.pokedaisy.app.game.GameViewController
import platform.GameController.GCController
import kotlin.time.TimeSource

internal class IosGameTips(
    game: GameViewController,
    runtime: GameRuntime,
    private val settings: IosCompanionSettings,
    private val screen: IosGameScreen,
    snapshot: () -> SnapshotView,
    items: () -> ItemUse?,
    fly: () -> FlyAction?,
    quick: GameQuickActions,
) {
    private val tips = GameTipSession(
        settings = settings,
        snapshot = snapshot,
        items = items,
        fly = fly,
        openQuickMenu = { screen.triggerQuickMenu(true) },
        quickAction = quick::run,
        notice = screen::showQuickNotice,
        frame = {
            val playing = game.playingForward
            val blocked = screen.tipBlocked || FlyStyleAsk.showing
            GameTipFrame(
                snapshot = snapshot(),
                settledOnField = playing && runtime.settledOnField,
                blocked = blocked,
                dismissForOverlay = blocked,
                moments = runtime.tipMoments.takeIf { playing },
                mapOpen = playing && runtime.session.mapOpen,
            )
        },
        expanded = { screen.expanded },
        shortcut = ::shortcut,
        touchShortcut = { action -> when (action) {
            Hotkeys.Action.ZOOM -> touchHas(TouchButton.ZOOM)
            Hotkeys.Action.QUICK_MENU -> touchHas(TouchButton.QUICK_MENU)
            else -> false
        } },
        quickMenuHasActions = { settings.quickMenuActions.any(quick::offered) },
        hudAvailable = {
            settings.fieldHud && !screen.portrait && FieldHudPart.PARTY !in settings.fieldHudStyle.hidden &&
                snapshot().party.isNotEmpty()
        },
        tryOnlyWhenCalm = true,
        cycleZoom = settings::cycleZoom,
        openSummary = { summaryRequest++ },
    )

    var summaryRequest by mutableIntStateOf(0)
        private set

    private fun touchHas(button: TouchButton) = IosTouchPreferences.visible && button in IosTouchPreferences.drawingStyle.extras

    private fun shortcut(action: Hotkeys.Action): String? {
        if (!settings.hotkeysEnabled || GCController.controllers().isEmpty()) return null
        return controllerTipShortcut(settings.hotkeyBindings()[action].orEmpty())
    }

    @Composable
    fun Clock() {
        val scheduler = FeatureTips.scheduler ?: return
        DisposableEffect(tips) {
            tips.attach()
            onDispose(tips::detach)
        }
        LaunchedEffect(tips, scheduler) {
            val start = TimeSource.Monotonic.markNow()
            tips.run(nowMillis = { start.elapsedNow().inWholeMilliseconds })
        }
    }

    @Composable
    fun Card() {
        BoxWithConstraints {
            val top = with(LocalDensity.current) {
                maxOf(screen.hudClear().first.toFloat(), screen.hudFrame()?.top ?: 0f).toDp()
            }
            FeatureTipHost(active = true, roomy = false, modifier = Modifier.padding(top = top + 8.dp),
                cardModifier = Modifier.width(maxWidth * if (maxWidth < 600.dp) 0.94f else 0.54f), maxWidth = maxWidth,
                keys = tips::keys, onTry = tips::tryIt)
        }
    }
}
