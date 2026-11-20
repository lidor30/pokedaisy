package com.pokedaisy.app.companion

import com.pokedaisy.app.companion.data.FlyResult
import com.pokedaisy.app.companion.data.FlyTargets

/** Routes the common Game Boy and GBA actions. Hosts supply their window and tip effects. */
class GameFlyAction(
    private val gameBoy: () -> GameBoyFieldActions?,
    private val classic: FlyAction,
    private val gbaAvailable: () -> Boolean,
    private val flight: () -> GameFlight?,
    private val beforeGbaFly: () -> Unit = {},
    private val flyUsed: () -> Unit,
) : FlyAction {
    override fun canFly() = gameBoy()?.available == true || gbaAvailable()

    override fun targets(done: (FlyTargets) -> Unit) {
        val gb = gameBoy()
        if (gb?.available == true) gb.targets(done) else classic.targets(done)
    }

    override fun fly(mapsec: Int, targets: FlyTargets, done: (FlyResult) -> Unit) {
        val completed: (FlyResult) -> Unit = { result ->
            if (result == FlyResult.OK) flyUsed()
            done(result)
        }
        val gb = gameBoy()
        if (gb?.available == true) return gb.fly(mapsec, targets, completed)
        if (!gbaAvailable()) return done(FlyResult.FAILED)
        beforeGbaFly()
        val gbaFlight = flight()
        if (gbaFlight == null) classic.fly(mapsec, targets, completed)
        else gbaFlight.flyTo(mapsec, targets, classic, completed)
    }
}
