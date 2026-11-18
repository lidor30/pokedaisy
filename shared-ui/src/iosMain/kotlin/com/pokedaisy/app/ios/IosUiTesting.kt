package com.pokedaisy.app.ios

import androidx.compose.runtime.ExperimentalComposeApi
import androidx.compose.ui.platform.AccessibilitySyncOptions
import androidx.compose.ui.uikit.ComposeUIViewControllerConfiguration
import platform.Foundation.NSProcessInfo

/** Compose 1.7 otherwise exposes semantics only while accessibility services run. Opt in for XCUITest, not gameplay. */
@OptIn(ExperimentalComposeApi::class)
internal fun ComposeUIViewControllerConfiguration.configureUiAutomation() {
    if (NSProcessInfo.processInfo.environment["POKEDAISY_UI_TEST"] == "1") {
        accessibilitySyncOptions = AccessibilitySyncOptions.Always(debugLogger = null)
    }
}
