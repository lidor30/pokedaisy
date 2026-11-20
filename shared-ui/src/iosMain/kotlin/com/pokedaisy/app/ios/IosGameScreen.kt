package com.pokedaisy.app.ios

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.graphicsLayer
import com.pokedaisy.app.CompanionShaders
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.unit.dp
import com.pokedaisy.app.companion.TouchStage
import com.pokedaisy.app.companion.i18n.tr
import com.pokedaisy.app.companion.ui.*
import com.pokedaisy.app.core.PkApiCore
import com.pokedaisy.app.game.GameViewController
import kotlin.time.DurationUnit
import kotlin.time.TimeSource
import kotlin.math.roundToInt

/** iOS storage, controller and input-ownership adapter. Screen assembly and every panel gesture are shared. */
internal class IosGameScreen(
    private val game: GameViewController?,
    private val touch: IosTouchControls,
    private val gameBoy: Boolean,
) {
    private val prefs = iosPreferences
    private val repeatClock = TimeSource.Monotonic.markNow()
    private fun menuTime() = repeatClock.elapsedNow().toDouble(DurationUnit.SECONDS)
    val back = CompanionBack()
    private val overlayBack = CompanionBack()
    private val overlayKeys = CompanionKeys()
    enum class Overlay { FIELD_HUD, BATTLE, BATTLE_RETRY, COOP }
    var coopKey: (MenuKey) -> Boolean = { false }
    private var coopTouchKeys = 0
    private val capturedOverlays = mutableSetOf<Overlay>()
    private val overlayCaptured get() = capturedOverlays.isNotEmpty()
    val tipBlocked get() = quickMenu.open || state.side.windowOpen || overlayCaptured || rewindUnavailable ||
        quickNotice != null || game?.keyCapture?.active == true || game?.keyboardInputCaptured == true
    private var closed = false
    fun captureOverlay(owner: Overlay, on: Boolean) {
        if (closed) return
        val changed = if (on) capturedOverlays.add(owner) else capturedOverlays.remove(owner)
        if (changed) {
            if (owner == Overlay.COOP) {
                coopTouchKeys = 0
                if (on) quickMenu.close()
            }
            captureButtons()
            syncInputCapture()
        }
    }

    private fun coopTouch(buttons: Int): Boolean {
        if (Overlay.COOP !in capturedOverlays) return false
        val pressed = buttons and coopTouchKeys.inv()
        coopTouchKeys = buttons
        BUTTONS.firstOrNull { (bit, _) -> pressed and bit != 0 }?.let { (_, key) -> coopKey(key) }
        return true
    }

    val keys = CompanionKeys()
    private val textInputFocus = TextInputFocus { game?.keyboardInputCaptured = it }
    private var releaseFocus: () -> Unit = {}
    private var previousButtons = 0
    private var menuSuppressed = 0
    private var nextRepeat = 0.0
    private var portraitGripHeight = 0
    private var gameStatusBarHeight by mutableIntStateOf(0)
    private var density = 1f
    private var pictureAspect by mutableStateOf(prefs.gameAspect)
    private val gameOverlays = GameOverlays(IosOverlays.store).apply { key = prefs.overlay }
    private var expandedSupported by mutableStateOf(game?.wideSupported == true)
    val expanded get() = pictureAspect == com.pokedaisy.app.companion.GameAspect.WIDE && expandedSupported
    private val stretched get() = pictureAspect == com.pokedaisy.app.companion.GameAspect.STRETCH
    private var companionShaders by mutableStateOf(readCompanionShaders())
    private var companionCell by mutableStateOf(0f)
    private fun readCompanionShaders() = CompanionShaders.settings(
        prefs.gbaColors, prefs.screenFilter, prefs.gridStrength, prefs.companionShaders)
    fun refreshPicture() {
        companionShaders = readCompanionShaders()
        pictureAspect = prefs.gameAspect
        gameOverlays.key = prefs.overlay
        expandedSupported = game?.wideSupported == true
        game?.expanded = expanded
        game?.stretch = stretched
        game?.pictureEffects = com.pokedaisy.app.companion.MetalScreenEffects.picture(prefs.screenFilter, prefs.gridStrength, prefs.gbaColors, prefs.unlitScreen)
            .copy(smoothing = IosSmoothing.program(prefs.smoothing))
        game?.camera?.let { it.lookAround = prefs.lookAround; it.zoomOnText = prefs.expandZoomOnText }
    }
    private val aspect = if (gameBoy) 160f / 144 else 1.5f
    private var frames by mutableStateOf<GameScreenFrames?>(null)
    private var rewindUnavailable by mutableStateOf(false)
    private var itemNotice by mutableStateOf<ItemNotice?>(null)
    private var itemNoticeMillis = ITEM_NOTICE_MILLIS
    private var quickNotice by mutableStateOf<String?>(null)
    val quickMenu = QuickMenuState().also { it.onClose = ::syncInputCapture }
    var canOpenQuickMenu: () -> Boolean = { false }
    fun showItemNotice(item: Int, message: String, millis: Long = ITEM_NOTICE_MILLIS, ok: Boolean = true) {
        itemNoticeMillis = millis
        itemNotice = ItemNotice(item, message, ok)
    }
    fun showQuickNotice(message: String) {
        captureButtons()
        quickNotice = message
        syncInputCapture()
    }
    private fun captureButtons() {
        menuSuppressed = game?.controllerButtons ?: 0
        previousButtons = 0
        nextRepeat = menuTime() + .35
    }
    private fun syncInputCapture() {
        game?.overlayMenuInputCaptured = !closed && overlayCaptured
        game?.quickMenuInputCaptured = !closed && quickMenu.open
        game?.companionInputCaptured = !closed && (quickMenu.open || state.side.windowOpen || overlayCaptured || rewindUnavailable || quickNotice != null)
        touch.releaseKeys()
    }
    fun triggerQuickMenu(pressed: Boolean) {
        if (!pressed) return
        if (!quickMenu.open && (!canOpenQuickMenu() || state.side.windowOpen || rewindUnavailable ||
                quickNotice != null || game?.keyCapture?.active == true || game?.keyboardInputCaptured == true ||
                game?.uiPaused == true)) return
        quickMenu.trigger(true)
        if (quickMenu.open) {
            com.pokedaisy.app.companion.FeatureTips.used(com.pokedaisy.app.companion.TipId.QUICK_MENU)
            quickMenu.labels = prefs.quickMenuLabels
            quickMenu.ring = prefs.quickMenuStyle == com.pokedaisy.app.companion.QuickMenuStyle.RING
            captureButtons()
            syncInputCapture()
        }
    }
    fun showRewindUnavailable() {
        quickMenu.close()
        captureButtons()
        rewindUnavailable = true
        syncInputCapture()
    }
    val state: GameScreenState = GameScreenState(prefs,
        windowChanged = { on ->
            if (on) quickMenu.close()
            captureButtons()
            syncInputCapture()
        },
        companionHidden = { releaseFocus() },
    )
    val portrait get() = state.portrait.enabled
    var window: Boolean
        get() = state.side.window
        set(on) { prefs.companionWindow = on; state.setSide(state.side.copy(window = on)) }

    init {
        game?.onPictureChanged = ::refreshPicture
        game?.onGamePixel = { companionCell = CompanionShaders.cellSize(it, density) }
        refreshPicture()
        game?.onCompanionButtons = ::controllerInput
        game?.onCompanionAim = quickMenu::aim
        touch.quickMenu = quickMenu
        touch.triggerQuickMenu = ::triggerQuickMenu
        touch.coopGameKeys = ::coopTouch
        touch.previewStage = ::previewStage
        touch.beforeEdit = { quickMenu.close(); if (state.side.windowOpen) state.hide() }
    }

    @Composable
    fun Content(gameContent: @Composable () -> Unit, companion: @Composable () -> Unit,
                gameOverlay: @Composable () -> Unit = {}, statusBar: (@Composable () -> Unit)? = null) {
        val focus = LocalFocusManager.current
        DisposableEffect(focus) {
            releaseFocus = { focus.clearFocus(force = true) }
            onDispose { releaseFocus = {} }
        }
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val upright = maxHeight >= maxWidth
            density = LocalDensity.current.density
            portraitGripHeight = with(LocalDensity.current) { (rememberGbaTextMetrics().u.toPx() * 24).roundToInt() }
            val effect = IosCompanionEffects.effect(companionShaders,
                companionCell.takeIf { it > 0f } ?: CompanionShaders.cellSize(0f, density))
            val chrome = Modifier.graphicsLayer { renderEffect = effect }
            val compactText = minOf(maxWidth, maxHeight) >= 600.dp
            val visible = IosTouchPreferences.visible
            val barHeight = if (statusBar == null) 0 else with(LocalDensity.current) { statusBarHeight().roundToPx() }
            val strip = if (visible && IosTouchPreferences.underGame)
                TouchGeometry.naturalHeight(IosTouchPreferences.style, constraints.maxWidth.toFloat(), density, gameBoy) else 0
            SideEffect {
                gameStatusBarHeight = barHeight
                game?.stretch = stretched || skinViewport(constraints.maxWidth, constraints.maxHeight, upright) != null ||
                    (!upright && !expanded && gameOverlays.key.isNotEmpty())
                state.setSide(state.side.withEnabled(!upright))
                state.setPortrait(state.portrait.copy(enabled = upright, underGame = IosTouchPreferences.underGame, padStrip = strip, expand = expanded))
            }
            GameScreen(state, gameHeight = { (it / aspect).roundToInt() + barHeight },
                game = {
                    BoxWithConstraints(Modifier.fillMaxSize()) {
                        val w = constraints.maxWidth; val h = constraints.maxHeight
                        gameOverlays.Prepare(w, h, IosOverlays.revision)
                        val border = if (!upright && !expanded && skinViewport(w, h, upright) == null)
                            gameOverlays.placement(w, h, gameBoy, stretched) else null
                        GamePicture(aspect, upright, Modifier.fillMaxSize(), fill = expanded || stretched,
                            viewport = { pw, ph -> pictureViewport(pw, ph, upright) }, topStripHeight = barHeight,
                            topStrip = statusBar?.let { bar -> { Box(chrome) { bar() } } }, content = gameContent)
                        if (border != null) gameOverlays.Artwork(w, h, gameBoy, stretched, Modifier.fillMaxSize())
                    }
                },
                controls = { if (visible) touch.Controls(gameBoy, upright) },
                gameOverlay = { CompositionLocalProvider(LocalCompanionBack provides overlayBack, LocalCompanionKeys provides overlayKeys,
                    LocalTextInputFocus provides textInputFocus) {
                        Box(chrome, propagateMinConstraints = true) { gameOverlay() }
                    } },
                companion = {
                    CompositionLocalProvider(LocalCompanionTopInset provides (frames?.portrait?.topInset(state.gripHeight) ?: 0),
                        LocalCompanionBack provides back, LocalCompanionKeys provides keys, LocalTextInputFocus provides textInputFocus) {
                        // The same host/call site in either orientation keeps tab, scroll and navigation state.
                        Box(chrome, propagateMinConstraints = true) {
                            SidePanelHost(window = !upright && state.side.isWindow, onClose = state::hide, keys = keys,
                                compactText = compactText, content = companion)
                        }
                    }
                },
                pictureOverlay = {
                    Box(Modifier.fillMaxSize()) {
                        ItemNoticeHost(itemNotice, rememberGbaTextMetrics(),
                            Modifier.align(Alignment.TopCenter).padding(8.dp), millis = itemNoticeMillis) { itemNotice = null }
                    }
                    game?.keyCapture?.let { KeyCapturePopup(it) }
                    if (rewindUnavailable) {
                        val dismiss = {
                            rewindUnavailable = false
                            syncInputCapture()
                        }
                        OptionConfirm(tr("REWIND"), tr("Rewind is off - turn it on in SETTINGS"), tr("OK"),
                            rememberGbaTextMetrics(), onConfirm = dismiss, onDismiss = dismiss, cancelLabel = null)
                    }
                    quickNotice?.let { message ->
                        val dismiss = { quickNotice = null; syncInputCapture() }
                        OptionConfirm(tr("QUICK MENU"), message, tr("OK"), rememberGbaTextMetrics(),
                            onConfirm = dismiss, onDismiss = dismiss, cancelLabel = null)
                    }
                },
                dismissSideOnGameTap = { !visible }, chromeModifier = chrome,
                onFrames = { measured, _ -> frames = measured; touch.stage = stage(measured, visible) })
        }
    }

    /** The editor closes a modal window first; hidden controls use a correctly sized visible stand-in. */
    fun previewStage(otherOrientation: Boolean = false): TouchStage? {
        if (state.width <= 0 || state.height <= 0) return null
        val w = if (otherOrientation) state.height else state.width
        val h = if (otherOrientation) state.width else state.height
        val upright = h >= w
        val strip = if (IosTouchPreferences.underGame)
            TouchGeometry.naturalHeight(IosTouchPreferences.style, w.toFloat(), density, gameBoy) else 0
        val p = state.portrait.copy(enabled = upright, padStrip = strip, underGame = IosTouchPreferences.underGame)
        val side = state.side.withEnabled(!upright)
        val measured = gameScreenFrames(w, h, (w / aspect).roundToInt() + gameStatusBarHeight, if (upright) portraitGripHeight else 0, side, p)
        return stage(measured, true, w, h, upright)
    }

    private fun stage(f: GameScreenFrames, visible: Boolean, w: Int = state.width, h: Int = state.height,
                      upright: Boolean = state.portrait.enabled): TouchStage {
        val picture = pictureFrame(f, upright).translate(f.game.left, f.game.top)
        val area = if (visible) f.controls else Rect(0f, h.toFloat(), w.toFloat(), h.toFloat())
        val companion = f.companion.takeIf { f.companionShown && (upright || !state.side.isWindow) }
        return TouchStage(w.toFloat(), h.toFloat(), density, upright, picture, area, companion, aspect, gameBoy)
    }

    fun hudFrame(): Rect? = frames?.let { f ->
        stage(f, IosTouchPreferences.visible).game.translate(-f.game.left, -f.game.top)
    }

    private fun skinViewport(w: Int, h: Int, upright: Boolean, controlsWidth: Float? = null): Rect? {
        if (upright || !IosTouchPreferences.visible) return null
        val skin = IosTouchSkins.selected(gameBoy) ?: return null
        val side = state.side.arrange(state.width)
        val controlsW = controlsWidth ?: (w + side.gameRightMargin - side.controlsRightMargin).toFloat()
        return skinFit(skin, controlsW, h.toFloat(), portrait = false)?.game
    }

    private fun pictureViewport(w: Int, h: Int, upright: Boolean, controlsWidth: Float? = null): Rect? {
        skinViewport(w, h, upright, controlsWidth)?.let { return it }
        if (upright || expanded) return null
        return gameOverlays.picture(w, h, gameBoy, stretched)
    }

    private fun pictureFrame(f: GameScreenFrames, upright: Boolean): Rect {
        val viewport = pictureViewport(f.game.width.toInt(), f.game.height.toInt(), upright, f.controls.width)
        return fittedGamePicture(f.game.width, f.game.height, aspect, upright, fill = expanded || stretched,
            viewport = viewport, topStripHeight = gameStatusBarHeight)
    }

    fun coreFrame(): Rect? = frames?.let { f ->
        val picture = pictureFrame(f, state.portrait.enabled)
        if (expanded) fittedGamePicture(picture.width, picture.height, aspect).translate(picture.left, picture.top)
        else picture
    }

    fun hudClear(): Pair<Int, Int> {
        val f = frames ?: return 0 to 0
        val p = touch.placed?.takeIf { IosTouchPreferences.visible } ?: return 0 to 0
        val picture = stage(f, true).game
        return TouchGeometry.hudClear(p, picture.left - f.controls.left, picture.right - f.controls.left)
    }

    fun hudAvoid(): List<Rect> = frames?.let { f ->
        if (!IosTouchPreferences.visible) emptyList() else touch.placed?.items.orEmpty().map {
            Rect(it.left, it.top, it.right, it.bottom).translate(f.controls.left - f.game.left, f.controls.top - f.game.top)
        }
    }.orEmpty()

    private fun controllerInput(buttons: Int) {
        if (!state.side.windowOpen && !quickMenu.open && !overlayCaptured && !rewindUnavailable && quickNotice == null) return
        menuSuppressed = menuSuppressed and buttons
        val current = buttons and menuSuppressed.inv()
        val pressed = current and previousButtons.inv()
        val now = menuTime()
        val repeated = if (now >= nextRepeat) current and DIRECTIONS else 0
        if (pressed != 0) nextRepeat = now + .35 else if (repeated != 0) nextRepeat = now + .1
        previousButtons = current
        val menuVisit = quickMenu.visit
        for ((bit, key) in BUTTONS) {
            if (quickMenu.visit != menuVisit) break // A selection/back cannot feed another key into its new owner.
            if (!state.side.windowOpen && !quickMenu.open && !overlayCaptured && !rewindUnavailable && quickNotice == null) break
            when {
                pressed and bit != 0 -> menuKey(key, false)
                repeated and bit != 0 -> menuKey(key, true)
            }
            // Dismissal must not send a simultaneous A/START into a newly revealed owner before recomposition.
            if (pressed and bit != 0 && (key == MenuKey.B || key == MenuKey.START)) break
        }
    }
    private fun menuKey(key: MenuKey, repeat: Boolean) {
        if (rewindUnavailable || quickNotice != null) {
            if (!repeat && key in listOf(MenuKey.A, MenuKey.B, MenuKey.START)) {
                rewindUnavailable = false; quickNotice = null; syncInputCapture()
            }
            return
        }
        if (Overlay.COOP in capturedOverlays && coopKey(key)) return
        if (quickMenu.open) { quickMenu.key(key); return }
        if (overlayCaptured) {
            if (key == MenuKey.B || key == MenuKey.START) {
                if (!repeat) overlayBack.back()
            } else overlayKeys.key(key, repeat)
            return
        }
        when (key) {
            MenuKey.B -> if (!repeat && !back.back()) state.hide()
            MenuKey.START -> if (!repeat) state.hide()
            else -> keys.key(key, repeat)
        }
    }
    fun toggleCompanion() {
        if (portrait) state.togglePortrait()
        else if (state.side.shown) state.hide() else state.show()
    }

    fun close() {
        closed = true
        capturedOverlays.clear()
        canOpenQuickMenu = { false }
        quickMenu.close()
        textInputFocus.clear()
        state.setSide(state.side.withEnabled(false))
        game?.onGamePixel = null
        game?.onCompanionButtons = {}
        game?.onCompanionAim = { _, _ -> false }
        game?.overlayMenuInputCaptured = false
        game?.quickMenuInputCaptured = false
        game?.companionInputCaptured = false
        touch.previewStage = null
        touch.beforeEdit = {}
        touch.quickMenu = null
        touch.triggerQuickMenu = {}
        touch.coopGameKeys = { false }
        releaseFocus()
    }
    private companion object {
        const val DIRECTIONS = PkApiCore.KEY_UP or PkApiCore.KEY_DOWN or PkApiCore.KEY_LEFT or PkApiCore.KEY_RIGHT
        val BUTTONS = listOf(PkApiCore.KEY_B to MenuKey.B, PkApiCore.KEY_START to MenuKey.START,
            PkApiCore.KEY_L to MenuKey.L, PkApiCore.KEY_R to MenuKey.R, PkApiCore.KEY_UP to MenuKey.UP,
            PkApiCore.KEY_DOWN to MenuKey.DOWN, PkApiCore.KEY_LEFT to MenuKey.LEFT, PkApiCore.KEY_RIGHT to MenuKey.RIGHT,
            PkApiCore.KEY_A to MenuKey.A, PkApiCore.KEY_SELECT to MenuKey.SELECT)
    }
}
