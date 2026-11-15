package com.pokedaisy.app.companion.ui

import com.pokedaisy.app.companion.data.LockedMap
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import kotlin.math.roundToInt
import androidx.compose.ui.unit.IntOffset
import androidx.compose.foundation.layout.offset
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import com.pokedaisy.app.companion.data.DecompIconSource
import com.pokedaisy.app.companion.data.RomArt
import com.pokedaisy.app.companion.data.UnboundIconSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okio.Path.Companion.toOkioPath

/**
 * Art images by path, decoded once: what [RomArt] rebuilt from a ROM the
 * player has run (party menus, backdrops, region maps - no game art is
 * bundled), else an asset of that name. A path missing now is retried once
 * [RomArt.updates] moves - key composables on [rememberArtGeneration].
 */
internal object GameArt {
    // A map can't hold a missing path as null, so those are kept apart (with the RomArt generation they missed in).
    private val cache = LockedMap<String, Bitmap>()
    private val missing = LockedMap<String, Int>()

    /** [path] if it's already decoded (no disk read). */
    fun peek(path: String): Bitmap? = cache[path]

    fun get(context: Context, path: String): Bitmap? {
        cache[path]?.let { return it }
        val gen = RomArt.updates.value
        if (missing[path] == gen) return null
        val bmp = try {
            RomArt.file(context.filesDir?.toOkioPath(), path)?.let { f -> BitmapFactory.decodeFile(f.toString()) }
                ?: context.assets.open(path).use { BitmapFactory.decodeStream(it) }
        } catch (e: Exception) {
            null
        }
        if (bmp == null) {
            missing[path] = gen
            return null
        }
        cache[path] = bmp
        return bmp
    }
}

/** The data code's [RomArt.Image] as an Android Bitmap, made once per image: the icon and dex caches hand out the
 * same Image each time, so a list scrolling back doesn't copy its pixels again. */
fun RomArt.Image.bitmap(): Bitmap = synchronized(androidBitmaps) {
    androidBitmaps.getOrPut(this) { Bitmap.createBitmap(argb, width, height, Bitmap.Config.ARGB_8888) }
}

private val androidBitmaps = java.util.WeakHashMap<RomArt.Image, Bitmap>()

/** Changes when [RomArt] adds art, so `remember`s keyed on it load again. */
@Composable
fun rememberArtGeneration(): Int = RomArt.updates.collectAsState().value

/**
 * Species icon sheets are 32x64: two 32x32 animation frames stacked
 * vertically (see docs/telemetry.md). Crop to just the first frame, matching
 * the web UI's background-position trick.
 */
internal fun cropToFirstFrame(bitmap: Bitmap): Bitmap {
    val frameHeight = bitmap.width // icon sheets are square-frame, height = 2x width
    if (bitmap.height <= frameHeight) return bitmap
    return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, frameHeight)
}

/** The full 32x64 two-frame icon sheet for [asset], or null (not loaded yet / unknown). */
private suspend fun loadMonIconSheet(context: Context, asset: String?): Bitmap? {
    val id = asset?.substringAfterLast('/')?.substringBefore('.')?.toIntOrNull()
    return when {
        asset == null -> null
        // Unbound icons aren't bundled - pull them live from the game.
        asset.startsWith("pokemon-unbound/") -> withContext(Dispatchers.IO) {
            id?.let { UnboundIconSource.get(it)?.bitmap() }
        }
        // Native configs that know gMonIconTable: decode
        // straight from the ROM (see IconTables.monPresent - gated
        // independently of item icons since some hacks relocate only one).
        id != null && DecompIconSource.tables.monPresent -> withContext(Dispatchers.IO) {
            ((if ("/shiny/" in asset) DecompIconSource.getShiny(id) else null) ?: DecompIconSource.get(id))?.bitmap() ?: GameArt.get(context, asset)
        }
        else -> withContext(Dispatchers.IO) { GameArt.get(context, asset) }
    }
}

/** The icon sheet if [DecompIconSource] / [UnboundIconSource] already has it decoded. */
internal fun peekMonIcon(asset: String?): Bitmap? {
    if (asset == null) return null
    GameArt.peek(asset)?.let { return it }   // rom-art sheets (a Game Boy game's own icons)
    val id = asset.substringAfterLast('/').substringBefore('.').toIntOrNull() ?: return null
    return when {
        asset.startsWith("pokemon-unbound/") -> UnboundIconSource.peek(id)?.bitmap()
        "/shiny/" in asset -> DecompIconSource.peekShiny(id)?.bitmap()
        else -> DecompIconSource.peek(id)?.bitmap()
    }
}

/** Both animation frames (for callers that animate the icon, e.g. FrPartySlot). */
@Composable
fun rememberMonIconSheet(asset: String?): Bitmap? {
    val context = LocalContext.current
    val gen = rememberIconGeneration()
    // An icon already decoded shows on the first frame (no blank slot while the load below runs).
    var bitmap by remember(asset) { mutableStateOf(peekMonIcon(asset)) }
    LaunchedEffect(asset, gen) { loadMonIconSheet(context, asset)?.let { bitmap = it } }
    return bitmap
}

/** Changes when live icons may load (see [DecompIconSource.generation]): icon loads key on it. */
@Composable
private fun rememberIconGeneration(): Int = DecompIconSource.generation.collectAsState().value

@Composable
fun SpeciesIcon(asset: String?, size: Dp, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    // Starts from an already-decoded icon, so a list scrolling back doesn't flash blank.
    var bitmap by remember(asset) { mutableStateOf(peekMonIcon(asset)?.let { cropToFirstFrame(it) }) }
    // Again when the icons change (a launch's saved companion data shows before the core is up).
    val gen = rememberIconGeneration()
    val firstGen = remember(asset) { gen }
    LaunchedEffect(asset, gen) {
        if (bitmap == null || gen != firstGen) loadMonIconSheet(context, asset)?.let { bitmap = cropToFirstFrame(it) }
    }
    Box(modifier = modifier.size(size)) {
        bitmap?.let {
            // Nearest-neighbour: a pixel-art sprite, often scaled up.
            Image(bitmap = it.asImageBitmap(), contentDescription = null, modifier = Modifier.size(size), filterQuality = FilterQuality.None)
        }
    }
}

@Composable
fun ItemIcon(
    asset: String?, size: Dp, modifier: Modifier = Modifier,
    /** The picture's own pixels centred in [size], not its canvas (a DS item icon sits off its 32 x 32's middle). */
    centerArt: Boolean = false,
) {
    val context = LocalContext.current
    // Starts from an already-decoded icon, like SpeciesIcon.
    var bitmap by remember(asset) {
        val id = asset?.takeUnless { it.startsWith("items-unbound/") }?.substringAfterLast('/')?.substringBefore('.')?.toIntOrNull()
        mutableStateOf(id?.let { DecompIconSource.peekItem(it)?.bitmap() })
    }
    val gen = rememberIconGeneration()
    LaunchedEffect(asset, gen) {
        val id = asset?.substringAfterLast('/')?.substringBefore('.')?.toIntOrNull()
        val loaded = when {
            asset == null -> null
            // Unbound item icons aren't bundled - pull them live from the game.
            asset.startsWith("items-unbound/") -> withContext(Dispatchers.IO) {
                id?.let { UnboundIconSource.getItem(it)?.bitmap() }
            }
            id != null && DecompIconSource.tables.itemPresent -> withContext(Dispatchers.IO) {
                DecompIconSource.getItem(id)?.bitmap() ?: GameArt.get(context, asset)
            }
            else -> withContext(Dispatchers.IO) {
                GameArt.get(context, asset)
            }
        }
        loaded?.let { bitmap = it }
    }
    Box(modifier = modifier.size(size)) {
        bitmap?.let {
            val shift = if (centerArt) artCentreShift(it) else null
            Image(
                bitmap = it.asImageBitmap(), contentDescription = null,
                filterQuality = if (centerArt) FilterQuality.None else androidx.compose.ui.graphics.drawscope.DrawScope.DefaultFilterQuality,
                modifier = Modifier.size(size).then(
                    if (shift == null) Modifier
                    else Modifier.offset { IntOffset((shift.first * size.toPx() / it.width).roundToInt(), (shift.second * size.toPx() / it.height).roundToInt()) },
                ),
            )
        }
    }
}

/** How far [bitmap]'s opaque pixels' middle is from its own, in its pixels (x, y), to move it back by; cached. */
private val artShifts = java.util.Collections.synchronizedMap(java.util.WeakHashMap<Bitmap, Pair<Float, Float>>())

private fun artCentreShift(bitmap: Bitmap): Pair<Float, Float> = artShifts.getOrPut(bitmap) {
    val w = bitmap.width
    val h = bitmap.height
    val px = IntArray(w * h).also { bitmap.getPixels(it, 0, w, 0, 0, w, h) }
    var l = w; var t = h; var r = -1; var b = -1
    for (y in 0 until h) for (x in 0 until w) if (px[y * w + x] ushr 24 != 0) {
        if (x < l) l = x
        if (x > r) r = x
        if (y < t) t = y
        if (y > b) b = y
    }
    if (r < 0) 0f to 0f else (w / 2f - (l + r + 1) / 2f) to (h / 2f - (t + b + 1) / 2f)
}

@Composable
fun regionMapBitmap(assetName: String?): Bitmap? {
    val context = LocalContext.current
    val gen = rememberArtGeneration()
    // Decoded once and cached (GameArt), so loading in composition is cheap.
    return remember(assetName, gen) { assetName?.let { GameArt.get(context, "regionmap/$it.png") } }
}
