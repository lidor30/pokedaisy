@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.pokedaisy.app.ios

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.pokedaisy.app.companion.TouchButton
import com.pokedaisy.app.companion.TouchControlsStyle
import com.pokedaisy.app.companion.TouchShow
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSOperationQueue
import platform.GameController.GCController
import platform.GameController.GCControllerDidConnectNotification
import platform.GameController.GCControllerDidDisconnectNotification
import platform.UIKit.UIImpactFeedbackGenerator
import platform.UIKit.UIImpactFeedbackStyle.UIImpactFeedbackStyleLight
import platform.UIKit.UIImpactFeedbackStyle.UIImpactFeedbackStyleMedium
import platform.UIKit.UIImpactFeedbackStyle.UIImpactFeedbackStyleHeavy
import platform.darwin.NSObjectProtocol

/** Only native storage, controller discovery and haptics. The settings page and editor are shared Compose UI. */
internal object IosTouchPreferences {
    private var current by mutableStateOf(iosPreferences.touchControlsStyle)
    var revision by mutableIntStateOf(0)
        private set
    var style: TouchControlsStyle
        get() = current
        set(value) {
            iosPreferences.touchControlsStyle = value
            current = value
            revision++
            changed()
        }

    /** Upright, the companion right under the game (Android's UNDER GAME, the default) or along the bottom. */
    var underGame by mutableStateOf(iosPreferences.portraitCompanionUnderGame)
        private set
    fun setUnderGame(on: Boolean) {
        iosPreferences.portraitCompanionUnderGame = on
        underGame = on
        revision++
    }

    var changed: () -> Unit = {}
    private var controllerConnected by mutableStateOf(GCController.controllers().isNotEmpty())
    val visible: Boolean get() = when (style.show) {
        TouchShow.AUTO -> !controllerConnected
        TouchShow.ALWAYS -> true
        TouchShow.NEVER -> false
    }
    private val observers = mutableListOf<NSObjectProtocol>()
    fun install() {
        if (observers.isNotEmpty()) return
        val center = NSNotificationCenter.defaultCenter
        for (name in listOf(GCControllerDidConnectNotification, GCControllerDidDisconnectNotification)) {
            observers += center.addObserverForName(name, null, NSOperationQueue.mainQueue) { _ ->
                controllerConnected = GCController.controllers().isNotEmpty()
                changed()
            }
        }
    }

    val buttons = TouchButton.entries.toSet() - TouchButton.ZOOM
    val drawingStyle: TouchControlsStyle get() = style.forButtons(buttons)

    fun feel(strength: Int) {
        val style = when (strength) {
            1 -> UIImpactFeedbackStyleLight
            2 -> UIImpactFeedbackStyleMedium
            3 -> UIImpactFeedbackStyleHeavy
            else -> return
        }
        UIImpactFeedbackGenerator(style).impactOccurred()
    }
}
