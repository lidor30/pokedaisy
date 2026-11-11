package com.pokedaisy.app.companion.data

/** MAKEOVER > RIDE ON: what the player rides when they get on their bike. [label] stays English; the row translates it. */
enum class RideVehicle(val id: Int, val label: String, internal val art: RideVehiclesGen.Art?) {
    BIKE(0, "BIKE", null),
    MOTORCYCLE(1, "MOTORCYCLE", RideVehiclesGen.MOTORCYCLE),
    SCOOTER(2, "SCOOTER", RideVehiclesGen.SCOOTER),
    ;

    companion object {
        fun of(id: Int) = entries.firstOrNull { it.id == id } ?: BIKE
    }
}

/**
 * RIDE ON: the player's bike swapped for one of our vehicles ([RideVehicle], scripts/gen_ride_vehicles.py's art), as part
 * of PLAYER LOOK's ROM patch ([PlayerLook.Patch]): the hero's bike sheets are rewritten in place, frame by frame. Each frame
 * keeps the rider's upper body from the hero's standing frame facing that way (rows up to the view's cut - the frames as
 * PLAYER LOOK left them, so a worn hero rides too) and the vehicle goes round and under it, its colours taken to the
 * nearest of the hero's overworld palette (the palette slot is the hero's, shared with everything they wear). The pedalling
 * frames get the standing ones' rider: nobody pedals a motorcycle. FireRed / LeafGreen's bike sheet; Emerald's (and Ruby /
 * Sapphire's) Mach and Acro bike sheets, the Acro bike's wheelie and hop frames by the way they face.
 */
object RideOn {
    /** The bike frames' views: 0 south, 1 north, 2 west (the sheets' standing frames 0, 1, 2), by frame. */
    private val NINE = listOf(0, 1, 2, 0, 0, 1, 1, 2, 2)

    /** The Acro bike's frames past the first nine, by the Mach bike frame each matches (PlayerLook.sheet's own pick). */
    private val ACRO = NINE + listOf(0, 3, 0, 4, 1, 5, 1, 6, 2, 7, 2, 8, 3, 4, 5, 6, 7, 8).map { NINE[it] }

    private fun parts(engine: LookEngine) = when (engine) {
        LookEngine.FIRERED -> listOf(LookPart.FR_BIKE)
        else -> listOf(LookPart.EM_MACH_BIKE, LookPart.EM_ACRO_BIKE)
    }

    /**
     * The writes putting [vehicle] into [me]'s bike sheets in [scan]'s ROM, over [under] (the look's writes, which they
     * follow: the sheets as worn) with the hero's overworld palette [pal] (16 RGB555 colours). Empty for the bike.
     */
    fun writes(scan: PlayerLook.Scan, me: LookChar, under: List<Pair<Int, ByteArray>>, pal: ByteArray, vehicle: RideVehicle): List<Pair<Int, ByteArray>> {
        val art = vehicle.art ?: return emptyList()
        val colours = art.palette.map { nearest(pal, it) }
        val out = ArrayList<Pair<Int, ByteArray>>()
        for (part in parts(me.engine)) {
            val order = if (part == LookPart.EM_ACRO_BIKE) ACRO else NINE
            for (at in scan.hitsOf(me, part)) {
                val size = order.size * part.frameBytes
                if (at + size > scan.rom.size) continue
                val sheet = scan.rom.copyOfRange(at, at + size)
                for ((off, data) in under) {
                    val s = maxOf(off, at)
                    val e = minOf(off + data.size, at + size)
                    if (s < e) data.copyInto(sheet, s - at, s - off, e - off)
                }
                val standing = (0..2).map { decode(sheet, it) }
                val made = ByteArray(size)
                for ((k, view) in order.withIndex()) encode(compose(standing[view], art, view, colours), made, k)
                out += at to made
            }
        }
        return out
    }

    /** One frame: the vehicle's body, the rider's upper body over it, their leg, then the vehicle's front parts. */
    internal fun compose(rider: IntArray, art: RideVehiclesGen.Art, view: Int, colours: List<Int>): IntArray {
        val v = art.views[view]
        val cut = art.cut[view]
        val out = IntArray(32 * 32)
        for (i in 0 until 32 * 32) {
            val b = v[i].toInt() and 0xFF
            val c = b and 0x3F
            val y = i / 32
            out[i] = when {
                c != 0 && b and 0xC0 != 0 -> colours[c]           // FRONT or the leg
                y <= cut && rider[i] != 0 -> rider[i]
                c != 0 -> colours[c]
                else -> 0
            }
        }
        return out
    }

    /** Frame [k] of a 4x4-tile sheet as 32 x 32 palette indices. */
    internal fun decode(sheet: ByteArray, k: Int): IntArray {
        val px = IntArray(32 * 32)
        val base = k * 512
        for (t in 0 until 16) for (i in 0 until 32) {
            val v = sheet[base + t * 32 + i].toInt() and 0xFF
            val x = (t % 4) * 8 + (i % 4) * 2
            val y = (t / 4) * 8 + i / 4
            px[y * 32 + x] = v and 15
            px[y * 32 + x + 1] = v shr 4
        }
        return px
    }

    private fun encode(px: IntArray, sheet: ByteArray, k: Int) {
        val base = k * 512
        for (t in 0 until 16) for (i in 0 until 32) {
            val x = (t % 4) * 8 + (i % 4) * 2
            val y = (t / 4) * 8 + i / 4
            sheet[base + t * 32 + i] = (px[y * 32 + x] or (px[y * 32 + x + 1] shl 4)).toByte()
        }
    }

    /** The palette index (1-15) whose colour is closest to [rgb] (0xRRGGBB), weighted as the eye sees. */
    internal fun nearest(pal: ByteArray, rgb: Int): Int {
        if (rgb == 0) return 0
        val r = (rgb shr 16) and 0xFF; val g = (rgb shr 8) and 0xFF; val b = rgb and 0xFF
        var best = 1
        var bestD = Int.MAX_VALUE
        for (i in 1..15) {
            val c = (pal[i * 2].toInt() and 0xFF) or ((pal[i * 2 + 1].toInt() and 0xFF) shl 8)
            val pr = (c and 31) shl 3; val pg = ((c shr 5) and 31) shl 3; val pb = ((c shr 10) and 31) shl 3
            val d = (pr - r) * (pr - r) * 3 + (pg - g) * (pg - g) * 4 + (pb - b) * (pb - b) * 2
            if (d < bestD) { bestD = d; best = i }
        }
        return best
    }
}
