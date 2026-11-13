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
 * RIDE ON: the player's bike swapped for one of our vehicles ([RideVehicle], scripts/gen_ride_vehicles.py's art), in two
 * parts. The rider: PLAYER LOOK's ROM patch ([PlayerLook.Patch]) rewrites the hero's bike sheets in place, each frame the
 * rider alone - their upper body from the hero's standing frame facing that way (rows up to the view's cut, the frames as
 * PLAYER LOOK left them, so a worn hero rides too), their leg in the hero's own colours, clear where the vehicle's front
 * parts go. The pedalling frames get the standing ones: nobody pedals a motorcycle. The vehicle: a sprite of its own right
 * behind the rider ([sprite], pk_wide.c's ride), in its own 15 colours - the hero's 16 had no greys for a tyre. FireRed /
 * LeafGreen's bike sheet; Emerald's (and Ruby / Sapphire's) Mach and Acro bike sheets, the Acro bike's wheelie and hop
 * frames by the way they face.
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
        val leg = nearest(pal, LEG)
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
                for ((k, view) in order.withIndex()) encode(compose(standing[view], art, view, leg), made, k)
                out += at to made
            }
        }
        return out
    }

    /** One frame of the rider alone: their leg (in [leg], the hero's colour nearest the art's), their upper body where the
     * vehicle has no front part, nothing else (the vehicle's sprite shows there). */
    internal fun compose(rider: IntArray, art: RideVehiclesGen.Art, view: Int, leg: Int): IntArray {
        val v = art.views[view]
        val cut = art.cut[view]
        val out = IntArray(32 * 32)
        for (i in 0 until 32 * 32) {   // the rider's frame: the views' first 32 rows
            val b = v[i].toInt() and 0xFF
            out[i] = when {
                b and 0x80 != 0 -> leg
                b and 0x40 != 0 && b and 0x0F != 0 -> 0
                i / 32 <= cut -> rider[i]
                else -> 0
            }
        }
        return out
    }

    /** The vehicle as pk_wide.c's ride takes it ([BYTES], pk_wide.h's PK_RIDE_BYTES): its views (south, north, west) one
     * after another in OBJ tile order, 32 x 64 each (the rider's frame on top, the wheels reaching below it); then its
     * exhaust smoke, [SMOKE_FRAMES] 16 x 8 frames of two tiles; then where the smoke goes in each view (x, y signed bytes,
     * -128 none) and two spare bytes. And its palette (16 RGB555 colours). */
    class Sprite(val frames: ByteArray, val pal: ByteArray, val width: Int) {
        fun sameAs(o: Sprite?) = o != null && width == o.width && frames.contentEquals(o.frames) && pal.contentEquals(o.pal)
    }

    /** [vehicle]'s sprite; null for the bike. */
    const val SMOKE_FRAMES = 12
    const val BYTES = 3 * 1024 + SMOKE_FRAMES * 64 + 8

    fun sprite(vehicle: RideVehicle): Sprite? {
        val art = vehicle.art ?: return null
        val frames = ByteArray(BYTES)
        for (view in 0..2) {
            val v = art.views[view]
            val px = IntArray(32 * 64) { i -> if (i < v.size && v[i].toInt() and 0x80 == 0) v[i].toInt() and 0x0F else 0 }
            // 4 x 8 tiles, row by row (OBJ 1D mapping).
            for (t in 0 until 32) for (i in 0 until 32) {
                val x = (t % 4) * 8 + (i % 4) * 2
                val y = (t / 4) * 8 + i / 4
                frames[view * 1024 + t * 32 + i] = (px[y * 32 + x] or (px[y * 32 + x + 1] shl 4)).toByte()
            }
        }
        // The smoke: each 16 x 8 frame two tiles, left then right.
        val smoke = 3 * 1024
        for (f in 0 until minOf(SMOKE_FRAMES, art.smoke.size / 128)) for (t in 0 until 2) for (i in 0 until 32) {
            val x = t * 8 + (i % 4) * 2
            val y = i / 4
            val a = art.smoke[f * 128 + y * 16 + x].toInt() and 15
            val b = art.smoke[f * 128 + y * 16 + x + 1].toInt() and 15
            frames[smoke + f * 64 + t * 32 + i] = (a or (b shl 4)).toByte()
        }
        for (k in 0 until 6) frames[smoke + SMOKE_FRAMES * 64 + k] = art.exhaust.getOrElse(k) { -128 }.toByte()
        val pal = ByteArray(32)
        for (i in 1 until art.palette.size.coerceAtMost(16)) {
            val c = art.palette[i]
            val v = ((c shr 19) and 31) or (((c shr 11) and 31) shl 5) or (((c shr 3) and 31) shl 10)
            pal[i * 2] = v.toByte(); pal[i * 2 + 1] = (v shr 8).toByte()
        }
        return Sprite(frames, pal, art.width)
    }

    /** The art's leg colour (dark navy trousers), taken to the hero's palette. */
    private const val LEG = 0x39397B

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
