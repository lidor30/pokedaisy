package com.pokedaisy.app.companion

import androidx.compose.ui.geometry.Rect
import com.pokedaisy.app.companion.i18n.tk
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.min

// Controller skins in Delta's format (`.deltaskin`: a zip with an info.json and its PDF / PNG pictures), the ones players
// download from deltastyles.com or delta-skins.github.io. What they are and how a skin is laid on the screen; the files
// themselves are DeltaSkinStore's, the drawing companion.ui.TouchControls'.
//
// info.json, as Delta reads it: { name, identifier, gameTypeIdentifier, debug, representations: { <device: iphone / ipad>:
// { <display: standard / edgeToEdge / splitView>: { <portrait / landscape>: { assets: { resizable | small / medium /
// large }, items: [ { inputs: [names] | { up, down, left, right }, frame: {x, y, width, height}, extendedEdges } ],
// screens: [ { inputFrame, outputFrame } ] (or the older gameScreenFrame), mappingSize: {width, height},
// extendedEdges: {top, bottom, left, right}, translucent } } } } }. Every frame is in the mapping size's points, top left.

/** The systems a skin can be for, by the end of its gameTypeIdentifier (`com.rileytestut.delta.game.gba`). */
enum class SkinSystem(val label: String, val key: String) {
    GBA(tk("GBA"), "gba"),
    /** Game Boy and Game Boy Color games, from a `gbc` skin. */
    GB(tk("GAME BOY"), "gbc"),
    DS(tk("DS"), "ds");

    companion object {
        fun of(gameType: String): SkinSystem? = when (gameType.substringAfterLast('.').lowercase()) {
            "gba" -> GBA
            "gbc", "gb" -> GB
            "ds", "nds" -> DS
            else -> null
        }
    }
}

/** A rectangle in a skin's mapping points. */
data class SkinRect(val x: Float, val y: Float, val w: Float, val h: Float) {
    val right get() = x + w
    val bottom get() = y + h
}

/** How far a button's touch area reaches past its frame, in points. */
data class SkinEdges(val top: Float = 0f, val bottom: Float = 0f, val left: Float = 0f, val right: Float = 0f)

/**
 * One touch area of a skin: [buttons] held while it's touched (A and B together for an "ab" item), [dirs] fixed
 * directions (an item for "up" alone), or [dpad] = a directional pad whose directions come from where it's touched.
 */
data class SkinItem(
    val frame: SkinRect,
    val edges: SkinEdges,
    val buttons: Set<TouchButton> = emptySet(),
    val dirs: Int = 0,
    val dpad: Boolean = false,
)

/** One way of holding one device: its picture, its touch areas, where the game goes ([screen], null = not set). */
data class SkinRepresentation(
    val device: String,
    val display: String,
    val portrait: Boolean,
    val mappingW: Float,
    val mappingH: Float,
    /** The picture's file in the skin's folder: a PDF ("resizable") or the largest PNG. */
    val asset: String?,
    val items: List<SkinItem>,
    val screen: SkinRect?,
    /** Delta's "translucent": OPACITY applies; otherwise the picture is drawn solid round the game. */
    val translucent: Boolean,
) {
    val aspect get() = mappingW / mappingH
}

data class DeltaSkin(
    val name: String,
    val identifier: String,
    val gameType: String,
    val representations: List<SkinRepresentation>,
) {
    val system: SkinSystem? get() = SkinSystem.of(gameType)
    val hasPortrait get() = representations.any { it.portrait }
    val hasLandscape get() = representations.any { !it.portrait }

    /** The representation for a [w]x[h] area held [portrait]: the one whose shape is closest; null = the skin has none
     * for that way of holding. */
    fun pick(w: Float, h: Float, portrait: Boolean): SkinRepresentation? {
        val want = if (h > 0f) w / h else 1f
        return representations.filter { it.portrait == portrait && it.mappingW > 0f && it.mappingH > 0f }
            .minByOrNull { abs(ln(it.aspect / want)) }
    }

    companion object {
        /** Delta's input names, ours for each. Directions are bits ([TouchDirs]). */
        private val BUTTONS = mapOf(
            "a" to TouchButton.A, "b" to TouchButton.B, "x" to TouchButton.X, "y" to TouchButton.Y,
            "l" to TouchButton.L, "r" to TouchButton.R, "start" to TouchButton.START, "select" to TouchButton.SELECT,
            "menu" to TouchButton.QUICK_MENU,
            "fastforward" to TouchButton.FF, "togglefastforward" to TouchButton.FF,
            "quicksave" to TouchButton.SAVE_STATE, "quickload" to TouchButton.LOAD_STATE,
        )

        private fun dir(name: String): Int = when (name.lowercase()) {
            "up", "analogstickup" -> TouchDirs.UP
            "down", "analogstickdown" -> TouchDirs.DOWN
            "left", "analogstickleft" -> TouchDirs.LEFT
            "right", "analogstickright" -> TouchDirs.RIGHT
            else -> 0
        }

        /** Reads info.json; throws IllegalArgumentException (its message for the player) when it isn't a skin. */
        fun parse(json: String): DeltaSkin {
            val root = runCatching { LENIENT.parseToJsonElement(json.trimStart('﻿')) as JsonObject }.getOrElse { throw IllegalArgumentException("info.json can't be read") }
            val reps = ArrayList<SkinRepresentation>()
            val devices = root.obj("representations") ?: throw IllegalArgumentException("info.json has no representations")
            for (device in devices.keys) {
                val displays = devices.obj(device) ?: continue
                for (display in displays.keys) {
                    val ways = displays.obj(display) ?: continue
                    for (way in ways.keys) {
                        val portrait = when (way.lowercase()) { "portrait" -> true; "landscape" -> false; else -> continue }
                        val o = ways.obj(way) ?: continue
                        representation(device.lowercase(), display, portrait, o)?.let { reps += it }
                    }
                }
            }
            if (reps.isEmpty()) throw IllegalArgumentException("info.json has no buttons")
            return DeltaSkin(
                name = root.str("name").ifBlank { root.str("identifier", "Skin") },
                identifier = root.str("identifier").ifBlank { root.str("name") },
                gameType = root.str("gameTypeIdentifier"),
                representations = reps,
            )
        }

        private fun rect(o: JsonObject?): SkinRect? {
            o ?: return null
            val w = o.num("width", Double.NaN).toFloat()
            val h = o.num("height", Double.NaN).toFloat()
            if (w.isNaN() || h.isNaN() || w <= 0f || h <= 0f) return null
            return SkinRect(o.num("x", 0.0).toFloat(), o.num("y", 0.0).toFloat(), w, h)
        }

        private fun edges(o: JsonObject?, base: SkinEdges): SkinEdges {
            o ?: return base
            fun side(k: String, d: Float) = o.num(k, d.toDouble()).toFloat()
            return SkinEdges(side("top", base.top), side("bottom", base.bottom), side("left", base.left), side("right", base.right))
        }

        private fun representation(device: String, display: String, portrait: Boolean, o: JsonObject): SkinRepresentation? {
            val mapping = o.obj("mappingSize") ?: return null
            val mw = mapping.num("width", 0.0).toFloat()
            val mh = mapping.num("height", 0.0).toFloat()
            if (mw <= 0f || mh <= 0f) return null
            val assets = o.obj("assets")
            val asset = assets?.let { a ->
                listOf("resizable", "large", "medium", "small").firstNotNullOfOrNull { k -> a.str(k).takeIf { it.isNotBlank() } }
            }
            val baseEdges = edges(o.obj("extendedEdges"), SkinEdges())
            val items = ArrayList<SkinItem>()
            val list = o["items"] as? JsonArray ?: JsonArray(emptyList())
            for (element in list) {
                val it = element as? JsonObject ?: continue
                val frame = rect(it.obj("frame")) ?: continue
                val e = edges(it.obj("extendedEdges"), baseEdges)
                val inputs = it["inputs"]
                val item = when (inputs) {
                    is JsonObject -> {
                        // { up, down, left, right }: a D-pad (or a thumbstick), read as one. The DS's touch screen
                        // ({ x, y }) isn't a button.
                        val keys = inputs.keys.map { k -> k.lowercase() }.toSet()
                        if (keys.containsAll(listOf("up", "down", "left", "right"))) SkinItem(frame, e, dpad = true) else null
                    }
                    is JsonArray -> {
                        val names = inputs.map { k -> k.text().lowercase() }
                        val buttons = names.mapNotNull { BUTTONS[it] }.toSet()
                        val dirs = names.fold(0) { acc, n -> acc or dir(n) }
                        if (buttons.isEmpty() && dirs == 0) null else SkinItem(frame, e, buttons, dirs)
                    }
                    is JsonPrimitive -> inputs.text().let { name ->
                        when {
                            name.equals("dpad", true) -> SkinItem(frame, e, dpad = true)
                            BUTTONS[name.lowercase()] != null -> SkinItem(frame, e, setOf(BUTTONS.getValue(name.lowercase())))
                            dir(name) != 0 -> SkinItem(frame, e, dirs = dir(name))
                            else -> null
                        }
                    }
                    else -> null
                }
                if (item != null) items += item
            }
            val screens = o["screens"] as? JsonArray
            val screen = screens?.firstNotNullOfOrNull { k -> rect((k as? JsonObject)?.obj("outputFrame")) }
                ?: rect(o.obj("gameScreenFrame"))
            if (items.isEmpty()) return null
            return SkinRepresentation(device, display, portrait, mw, mh, asset, items, screen, o.str("translucent").equals("true", ignoreCase = true))
        }
    }
}

/** The D-pad's direction bits (mGBA's, like TouchGeometry's KEY_*). */
object TouchDirs {
    const val RIGHT = 1 shl 4
    const val LEFT = 1 shl 5
    const val UP = 1 shl 6
    const val DOWN = 1 shl 7
}

/**
 * A skin's representation laid on a [w]x[h] px area. Held sideways the whole picture fills the area (stretched when its
 * shape is within a few percent of it, else as big as fits, centred) and [game] is where its screen puts the game.
 * Upright the game and the companion keep their places, so only the part of the picture under its screen - the
 * buttons - is shown, as wide as fits ([clip]); [game] is null then.
 */
class SkinFit(val rep: SkinRepresentation, val w: Float, val h: Float, cropToControls: Boolean) {
    /** Where the whole picture goes (px, may reach past the area when cropped). */
    val image: Rect
    /** The part of the area that shows the picture. */
    val clip: Rect
    /** Where the game goes (px), or null for the usual place. */
    val game: Rect?
    private val sx: Float
    private val sy: Float

    init {
        val screen = rep.screen
        // The part of the picture shown: all of it, or (upright) what's under the screen.
        // Upright: from just above the topmost button (never above the screen's bottom), so the buttons come out as big
        // as the space allows.
        val buttonsTop = rep.items.minOfOrNull { it.frame.y - it.edges.top }?.coerceAtLeast(0f) ?: 0f
        val top = if (cropToControls && screen != null && screen.bottom < rep.mappingH * 0.8f) maxOf(screen.bottom, buttonsTop) else 0f
        val partH = rep.mappingH - top
        val kx = w / rep.mappingW
        val ky = h / partH
        val close = maxOf(kx, ky) / min(kx, ky) <= STRETCH_WITHIN
        if (close) { sx = kx; sy = ky } else { sx = min(kx, ky); sy = sx }
        val shownW = rep.mappingW * sx
        val shownH = partH * sy
        val left = (w - shownW) / 2
        // Upright the buttons go to the bottom of their room, sideways the picture's middle to the area's.
        val shownTop = if (cropToControls) h - shownH else (h - shownH) / 2
        image = Rect(left, shownTop - top * sy, left + shownW, shownTop - top * sy + rep.mappingH * sy)
        clip = Rect(left, shownTop, left + shownW, shownTop + shownH)
        game = if (cropToControls || screen == null) null else toPx(screen)
    }

    fun toPx(r: SkinRect) = Rect(image.left + r.x * sx, image.top + r.y * sy, image.left + r.right * sx, image.top + r.bottom * sy)

    /** Each item's frame and touch area (frame + its extended edges), in px; items outside [clip] left out. */
    val items: List<Placed> = rep.items.mapNotNull { item ->
        val f = toPx(item.frame)
        if (f.bottom <= clip.top + 1 || f.top >= clip.bottom - 1) return@mapNotNull null
        val e = item.edges
        Placed(item, f, Rect(f.left - e.left * sx, f.top - e.top * sy, f.right + e.right * sx, f.bottom + e.bottom * sy))
    }

    class Placed(val item: SkinItem, val frame: Rect, val touch: Rect)

    /** What a finger at ([x], [y]) holds: buttons as bits of TouchButton ordinals, and D-pad direction bits. */
    fun touch(x: Float, y: Float, eightWay: Boolean): Pair<Int, Int> {
        var buttons = 0
        var dirs = 0
        for (p in items) {
            if (x < p.touch.left || x > p.touch.right || y < p.touch.top || y > p.touch.bottom) continue
            for (b in p.item.buttons) buttons = buttons or (1 shl b.ordinal)
            dirs = dirs or p.item.dirs
            if (p.item.dpad) dirs = dirs or dpadDirs(p.frame, x, y, eightWay)
        }
        return buttons to dirs
    }

    companion object {
        /** Stretch the picture to fill when the area's shape is this close to it (else keep its shape). */
        const val STRETCH_WITHIN = 1.08f

        /** A D-pad's directions as Delta reads them: its frame split in a 3x3 grid, the middle cell none. 4-WAY keeps the
         * farther axis only. */
        fun dpadDirs(frame: Rect, x: Float, y: Float, eightWay: Boolean): Int {
            val nx = (x - frame.center.x) / (frame.width / 2)
            val ny = (y - frame.center.y) / (frame.height / 2)
            val third = 1f / 3f
            var bits = 0
            val horiz = when { nx < -third -> TouchDirs.LEFT; nx > third -> TouchDirs.RIGHT; else -> 0 }
            val vert = when { ny < -third -> TouchDirs.UP; ny > third -> TouchDirs.DOWN; else -> 0 }
            if (eightWay) bits = horiz or vert
            else if (horiz != 0 || vert != 0) bits = if (abs(nx) > abs(ny)) (horiz.takeIf { it != 0 } ?: vert) else (vert.takeIf { it != 0 } ?: horiz)
            return bits
        }
    }
}

// info.json read the way org.json did before (the app's first parser): lenient about trailing commas and comments, a
// missing or null value is the default, numbers and strings read either way.
@OptIn(ExperimentalSerializationApi::class)
private val LENIENT = Json { isLenient = true; allowTrailingComma = true; allowComments = true }

private fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject

/** A string or number's text ("" for anything else), as org.json's optString. */
private fun kotlinx.serialization.json.JsonElement?.text(default: String = ""): String =
    (this as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content ?: default

private fun JsonObject.str(key: String, default: String = ""): String = this[key].text(default)

/** A number (or a string holding one), else [default], as org.json's optDouble. */
private fun JsonObject.num(key: String, default: Double): Double = this[key].text().toDoubleOrNull() ?: default
