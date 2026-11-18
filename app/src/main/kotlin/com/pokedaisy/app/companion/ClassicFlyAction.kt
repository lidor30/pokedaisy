package com.pokedaisy.app.companion

import com.pokedaisy.app.companion.data.*
import com.pokedaisy.app.game.GameSession

/** The game's own FLY, shared by Android and iOS: gates, fade, then its cut-in/warp on a later frame. */
class ClassicFlyAction(
    private val items: FieldItemUse,
    private val support: () -> FieldItemSupport?,
    private val session: () -> GameSession?,
    private val handedOver: () -> Unit = {},
) : FlyAction {
    override fun canFly() = support()?.let { it.canFly && it.fns.classicFly } == true

    override fun targets(done: (FlyTargets) -> Unit) =
        items.onGame(FlyTargets(FlyResult.FAILED), done) { fields, calls, busy ->
            if (fields.canFly) flyTargets(fields.fns, busy, calls) else FlyTargets(FlyResult.FAILED)
        }

    override fun fly(mapsec: Int, targets: FlyTargets, done: (FlyResult) -> Unit) {
        val game = session() ?: return done(FlyResult.FAILED)
        items.onGame(FlyResult.FAILED, { result ->
            if (result == FlyResult.OK) handedOver()
            done(result)
        }) { fields, calls, busy ->
            if (session() !== game || !fields.canFly || !fields.fns.classicFly) return@onGame FlyResult.FAILED
            val started = flyStart(fields.fns, mapsec, targets, busy, calls)
            Logs.i("fly to $mapsec: $started")
            if (started == FlyResult.OK) {
                game.requestField(false, FLY_FADE_FRAMES) { next, leftField ->
                    val ok = flyFinish(fields.fns, mapsec, targets, next, leftField)
                    Logs.i("fly to $mapsec: ${when {
                        ok -> "flying"
                        leftField -> "the game left the field during the fade, not taken"
                        else -> "failed, field handed back"
                    }}")
                }
            }
            started
        }
    }
}
