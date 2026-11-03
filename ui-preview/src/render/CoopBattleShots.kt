@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package render

import androidx.compose.runtime.Composable
import com.pokedaisy.app.companion.ui.CoopBattleOverlays
import com.pokedaisy.app.companion.ui.CoopOverlayState
import com.pokedaisy.app.companion.ui.CoopHostWaiting
import com.pokedaisy.app.companion.ui.CoopJoinPrompt
import com.pokedaisy.app.companion.ui.CoopJoinPromptState
import com.pokedaisy.app.companion.ui.CoopLinkWait
import com.pokedaisy.app.companion.ui.CoopNotice

/** The Emerald co-op battle screens: the join prompt, the waits and a notice (the battle itself is the game's own). */
fun coopBattleShots(w: Int, h: Int, d: Float, tw: Int = 1920, th: Int = 1080, td: Float = 2.5f): List<Shot> {
    return listOf(
        Shot("coop-battle-prompt", w, h, d, {
            val s = CoopJoinPromptState().apply { hostName = "MAYA"; kind = 1; secondsLeft = 27 }
            val c: @Composable () -> Unit = { CoopJoinPrompt(s, {}, {}) }
            c
        }),
        Shot("coop-battle-prompt-trainers", w, h, d, {
            val s = CoopJoinPromptState().apply { hostName = "MAYA"; kind = 2; secondsLeft = 9; cursorJoin = false }
            val c: @Composable () -> Unit = { CoopJoinPrompt(s, {}, {}) }
            c
        }),
        Shot("coop-battle-waiting", w, h, d, { val c: @Composable () -> Unit = { CoopHostWaiting("MAYA", 24) {} }; c }),
        Shot("coop-battle-link", w, h, d, { val c: @Composable () -> Unit = { CoopLinkWait("MAYA", 41) }; c }),
        Shot("coop-battle-notice", w, h, d, {
            val c: @Composable () -> Unit = { CoopNotice("YOUR PARTNER LEFT. THE BATTLE WAS UNDONE.") }
            c
        }),
    ) + overGame(tw, th, td)
}

/** The same screens as the game shows them: [CoopBattleOverlays] in the HUD's view over the (stand-in) game, top screen. */
private fun overGame(w: Int, h: Int, d: Float): List<Shot> {
    fun over(build: CoopOverlayState.() -> Unit): () -> (@Composable () -> Unit) = {
        val state = CoopOverlayState().apply(build)
        val c: @Composable () -> Unit = { PreviewGame { CoopBattleOverlays(state) } }
        c
    }
    return listOf(
        Shot("coop-battle-prompt-over-game", w, h, d, over { join = CoopJoinPromptState().apply { hostName = "MAYA"; kind = 1; secondsLeft = 27 } }),
        Shot("coop-battle-waiting-over-game", w, h, d, over { hostWait = "MAYA" to 24 }),
        Shot("coop-battle-link-over-game", w, h, d, over { linkWait = "MAYA" to 41 }),
        Shot("coop-battle-notice-over-game", w, h, d, over { showNotice("YOUR PARTNER LEFT. THE BATTLE WAS UNDONE.") }),
    )
}
