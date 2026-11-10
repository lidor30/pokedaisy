package com.pokedaisy.app.companion.ui

import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.FocusRequester
import com.pokedaisy.app.companion.i18n.tk
import com.pokedaisy.app.companion.i18n.tr
import android.graphics.Bitmap
import androidx.compose.animation.core.animateFloat
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import com.pokedaisy.app.companion.ui.theme.gbaFocusRing
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.runtime.withFrameNanos
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pokedaisy.app.companion.data.GameKind
import com.pokedaisy.app.companion.data.isGameBoyGame
import com.pokedaisy.app.companion.data.MapTiles
import com.pokedaisy.app.companion.data.NdsArt
import com.pokedaisy.app.companion.data.PlayerMapTile
import com.pokedaisy.app.companion.data.RegionMapModel
import com.pokedaisy.app.companion.data.SnapshotView
import com.pokedaisy.app.companion.data.activeGame
import com.pokedaisy.app.companion.data.activeRegionMap
import com.pokedaisy.app.companion.data.flySpotName
import com.pokedaisy.app.companion.data.gameCase
import com.pokedaisy.app.companion.data.isDsGame
import com.pokedaisy.app.companion.data.isGen5
import com.pokedaisy.app.companion.data.isHgss
import kotlin.math.floor

/**
 * The game's region map, stretched to the whole tab (cropped to the map
 * itself by [regionMapContentBounds]) in a rounded frame, drawn the way the
 * game draws its own: the player's head on the tile the game puts it, the
 * blinking cursor, and the place name in the game's own label - FireRed's
 * dark strip top-left, Emerald's window bottom-right ([MapLabelStyle]).
 *
 * Tapping the map moves the cursor there and names the place under it (the
 * game's own cursor grid, [RegionMapModel.pick]); PLACES lists every town,
 * route and place to jump to; the region button flips between a game's maps
 * (Kanto / the Sevii Islands); ME (or tapping the head) goes back to the player.
 */
@Composable
fun MapScreen(
    snapshot: SnapshotView,
    modifier: Modifier = Modifier,
    /** FLY (Fly.kt); null = no button. */
    fly: com.pokedaisy.app.companion.FlyAction? = null,
    /** FLY's destinations open first - for screenshot tests, which can't tap. */
    initialFly: com.pokedaisy.app.companion.data.FlyTargets? = null,
    /** Never stretched, not even a little (the HUD's MAP popup): the map at its own shape, centred. */
    keepShape: Boolean = false,
) {
    val gen = rememberArtGeneration()
    // RomRegionMap lands a hack's own map in the background and announces it as new art.
    val model = remember(snapshot.game, gen) { activeRegionMap() }
    Box(modifier = modifier.fillMaxSize()) {
        val playerRegion = model?.sections?.get(snapshot.regionMapSectionId)?.region?.takeIf { it in model.images.indices }
        var selection by remember(model) { mutableStateOf<MapSelection?>(null) }
        // The tile the D-pad moves the cursor from (the companion's window): the last one tapped or moved to; null = the
        // player's (or the picked place's first).
        var cursorTile by remember(model) { mutableStateOf<Pair<Int, Int>?>(null) }
        var shownOverride by remember(model) { mutableStateOf<Int?>(null) }
        var placesOpen by remember { mutableStateOf(false) }
        // FLY: its destinations once asked for, or what kept it from starting.
        var flyTargets by remember { mutableStateOf(initialFly) }
        var flyNote by remember { mutableStateOf<String?>(null) }
        // The button shows where the ROM has FLY and someone in the party knows it.
        val canFly = fly?.canFly() == true && snapshot.party.any { !it.isEgg && it.moves.any { mv -> mv.id == FLY_MOVE } }
        val openFly: (() -> Unit)? = fly?.takeIf { canFly }?.let { f ->
            {
                f.targets { t ->
                    if (t.result == com.pokedaisy.app.companion.data.FlyResult.OK) flyTargets = t else flyNote = flyMessage(t.result)
                }
            }
        }
        // FREE FLIGHT's TAKE OFF (LAND while up), under FLY: the companion window goes, the game is the player's to steer.
        val takeOff: (() -> Unit)? = fly?.takeIf { canFly && it.canFreeFlight() }?.let { f ->
            { f.toggleFlight { r -> if (r != null) flyNote = flyMessage(r) } }
        }
        val flyingNow = fly?.flying() == true
        // Back to the player when they land somewhere else after FLY, or cross to another of the map's regions (the
        // Sevii ferry): a tile picked or a region flipped to before would keep the map away from where they are now.
        var flewFrom by remember { mutableStateOf<Int?>(null) }
        LaunchedEffect(playerRegion) { selection = null; cursorTile = null; shownOverride = null }
        LaunchedEffect(snapshot.regionMapSectionId) {
            if (flewFrom != null && snapshot.regionMapSectionId != flewFrom) { selection = null; cursorTile = null; shownOverride = null; flewFrom = null }
        }
        val shown = selection?.region ?: shownOverride ?: playerRegion ?: 0
        val bitmap = regionMapBitmap(model?.images?.getOrNull(shown))
        // No early return out of Box's inline content: it corrupts the slot table (seen on Lazarus).
        if (model == null || bitmap == null) {
            NoRegionMap(snapshot)
        } else {
            val style = MapLabelStyle.of(snapshot.game)
            val playerTile = if (playerRegion == shown) {
                // A DS game's reader works out the block itself (the Town Map's own rule: indoors, the door went in by).
                snapshot.regionMapTile
                    ?: PlayerMapTile.of(model, snapshot.regionMapSectionId, snapshot.x, snapshot.y, snapshot.mapWidth, snapshot.mapHeight, snapshot.mapType)
            } else null
            // A map of places with points (Black's): the player's place is the one at their cell (a house's or a cave's zone
            // goes by its town's), and the head and the cursor sit on the place's own point, not a cell's middle.
            val playerSection = if (model.points.isNotEmpty() && playerTile != null) {
                model.pick(shown, playerTile.first, playerTile.second)?.mapsec?.takeIf { it >= 0 } ?: snapshot.regionMapSectionId
            } else snapshot.regionMapSectionId
            val headPoint = if (playerTile != null) model.points[playerSection] else null
            // Sprites the game draws over its map (Platinum's towns): grey until first arrived at, then the town's colour.
            // Every mark's two pictures are loaded each time (a fixed number of loads), then the one that applies kept.
            val visited = snapshot.townsVisited ?: 0L
            val marks = model.marks.mapNotNull { mk ->
                val plain = regionMapBitmap(mk.asset)
                val coloured = regionMapBitmap(mk.visitedAsset)
                val on = (visited shr mk.visitedFlag) and 1L != 0L
                if (mk.hiddenUntilVisited && !on) null else (if (on) coloured else plain)?.let { it to IntOffset(mk.x - HEAD / 2, mk.y - HEAD / 2) }
            }
            val sel = selection
            val (name, dungeon) = when {
                sel != null -> sel.name to sel.dungeon
                playerRegion == null || playerRegion == shown -> snapshot.location.mapSecName to null
                else -> "" to null
            }
            // The map fills the tab while that stretches it only a little (the Thor's
            // bottom screen: ~16% taller), else keeps its own shape, centred (a wide screen).
            val crop = remember(bitmap) { regionMapContentBounds(bitmap) }
            // The D-pad between the map and its buttons (the companion's window): they sit inside the map's own box,
            // so neither finds the other by itself.
            val mapFocus = remember { FocusRequester() }
            val buttonsFocus = remember { FocusRequester() }
            val bottomRow = snapshot.game in BOTTOM_ROW_MAPS
            if (snapshot.game.isHgss) PokegearMapView(
                bitmap = bitmap,
                style = style,
                head = regionMapBitmap(if (snapshot.playerGender == 1) com.pokedaisy.app.companion.data.PokegearMap.HEAD_F else com.pokedaisy.app.companion.data.PokegearMap.HEAD_M),
                playerTile = playerTile,
                cursorSprite = run {
                    val small = regionMapBitmap(com.pokedaisy.app.companion.data.PokegearMap.cursor(0))
                    val big = regionMapBitmap(com.pokedaisy.app.companion.data.PokegearMap.cursor(1))
                    if (small != null && big != null) small to big else null
                },
                tile = model.tile,
                cell = cursorTile ?: sel?.tiles?.firstOrNull()?.let { it.x to it.y },
                label = gameCase(name),
                onCell = { tx, ty ->
                    cursorTile = tx to ty
                    selection = if (playerTile != null && tx to ty == playerTile) null else model.select(shown, tx, ty)
                },
                self = mapFocus,
            ) { zoomed, toggleZoom ->
                val m = rememberGbaTextMetrics(1f)
                OptionButton(tr("PLACES"), m, onClick = { placesOpen = true })
                if (openFly != null) FlyButton(openFly)
                if (takeOff != null) TakeOffButton(takeOff, flyingNow)
                if (selection != null) OptionButton(tr("ME"), m, onClick = { selection = null; cursorTile = null; shownOverride = null }, emphasis = true)
                Spacer(Modifier.weight(1f))
                // The game's X: in, and back out.
                OptionButton(
                    null, rememberGbaTextMetrics(), onClick = toggleZoom, contentDescription = tr("ZOOM"),
                    pixelIcon = if (zoomed) PixelIcons.zoomOut else PixelIcons.zoomIn,
                )
            } else BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                val mapAspect = crop.width.toFloat() / crop.height
                val boxAspect = maxWidth / maxHeight
                val fitted = when {
                    !keepShape && maxOf(boxAspect / mapAspect, mapAspect / boxAspect) <= MAX_MAP_STRETCH -> Modifier.fillMaxSize()
                    boxAspect > mapAspect -> Modifier.size(maxHeight * mapAspect, maxHeight)
                    else -> Modifier.size(maxWidth, maxWidth / mapAspect)
                }
                Box(fitted) {
                    RegionMap(
                        bitmap = bitmap,
                        style = style,
                        head = regionMapBitmap(
                            if (snapshot.game.isGen5) NdsArt.gen5Player(snapshot.game)
                            else if (snapshot.game.isHgss) (if (snapshot.playerGender == 1) com.pokedaisy.app.companion.data.PokegearMap.HEAD_F else com.pokedaisy.app.companion.data.PokegearMap.HEAD_M)
                            else if (snapshot.game.isDsGame) (if (snapshot.playerGender == 1) NdsArt.PLATINUM_HEAD_F else NdsArt.PLATINUM_HEAD_M)
                            else style.headAsset(snapshot.playerGender, snapshot.game),
                        ),
                        headTile = playerTile,
                        cursor = sel?.tiles ?: if (playerRegion == shown) model.tilesOf(playerSection) else emptyList(),
                        label = gameCase(name),
                        dungeon = dungeon?.let(::gameCase),
                        onTap = { tx, ty ->
                            cursorTile = tx to ty
                            selection = if (playerTile != null && tx to ty == playerTile) null else model.select(shown, tx, ty)
                        },
                        cursorFrom = cursorTile ?: sel?.tiles?.firstOrNull()?.let { it.x to it.y } ?: playerTile,
                        self = mapFocus, buttons = buttonsFocus, buttonsBottom = bottomRow,
                        tile = model.tile, originX = model.originX, originY = model.originY, marks = marks,
                        cursorSprite = if (snapshot.game.isHgss) {
                            val small = regionMapBitmap(com.pokedaisy.app.companion.data.PokegearMap.cursor(0))
                            val big = regionMapBitmap(com.pokedaisy.app.companion.data.PokegearMap.cursor(1))
                            if (small != null && big != null) small to big else null
                        } else if (snapshot.game.isGen5) {
                            val small = regionMapBitmap(NdsArt.gen5Cursor(snapshot.game, 0))
                            val big = regionMapBitmap(NdsArt.gen5Cursor(snapshot.game, 1))
                            if (small != null && big != null) small to big else null
                        } else null,
                        headAt = headPoint?.let { (x, y) -> IntOffset(x, y) },
                        cursorAt = (if (sel != null) model.points[sel.mapsec] else if (playerRegion == shown) model.points[playerSection] else null)
                            ?.let { (x, y) -> IntOffset(x, y) },
                    )
                    MapButtons(
                        bottomRow = bottomRow,
                        map = mapFocus, group = buttonsFocus,
                        regionLabel = if (model.images.size > 1) regionName(model.images, (shown + 1) % model.images.size) else null,
                        awayFromPlayer = selection != null || (playerRegion != null && shown != playerRegion),
                        onPlaces = { placesOpen = true },
                        onFly = openFly,
                        onTakeOff = takeOff, flying = flyingNow,
                        onRegion = { selection = null; cursorTile = null; shownOverride = (shown + 1) % model.images.size },
                        onMe = { selection = null; cursorTile = null; shownOverride = null },
                    )
                }
            }
            flyTargets?.let { t ->
                FlyList(
                    model, t, flyer = snapshot.party.getOrNull(t.flyerSlot)?.name,
                    onPick = { mapsec ->
                        fly?.fly(mapsec, t) { result ->
                            flyTargets = null
                            if (result == com.pokedaisy.app.companion.data.FlyResult.OK) flewFrom = snapshot.regionMapSectionId
                            if (result != com.pokedaisy.app.companion.data.FlyResult.OK) flyNote = flyMessage(result)
                        }
                    },
                    onDismiss = { flyTargets = null },
                )
            }
            flyNote?.let { note ->
                OptionConfirm(tk("FLY"), note, tk("OK"), rememberGbaTextMetrics(), onConfirm = { flyNote = null }, onDismiss = { flyNote = null }, cancelLabel = null)
            }
            if (placesOpen) {
                PlacesList(
                    model,
                    onPick = { id ->
                        placesOpen = false
                        cursorTile = null
                        selection = model.sections[id]?.let { MapSelection(it.region, model.tilesOf(id), it.name, null, id) }
                    },
                    onMe = { placesOpen = false; selection = null; cursorTile = null; shownOverride = null },
                    onDismiss = { placesOpen = false },
                )
            }
        }
    }
}

/** Where the cursor is when it's not on the player: an image, the tiles it frames, and the label's text. */
private data class MapSelection(val region: Int, val tiles: List<MapTiles>, val name: String, val dungeon: String?, val mapsec: Int = -1)

/** What tile ([tx], [ty]) of image [region] is: its section's tiles (a dungeon: just that tile) and names; blank on empty map. */
private fun RegionMapModel.select(region: Int, tx: Int, ty: Int): MapSelection {
    val pick = pick(region, tx, ty) ?: return MapSelection(region, listOf(MapTiles(tx, ty, 1, 1)), "", null)
    val main = pick.mapsec.takeIf { it >= 0 } ?: pick.dungeon
    val dungeon = sections[pick.dungeon]?.name?.takeIf { pick.mapsec >= 0 && pick.dungeon >= 0 }
    val tiles = if (pick.dungeon >= 0) listOf(MapTiles(tx, ty, 1, 1)) else tilesOf(main).ifEmpty { listOf(MapTiles(tx, ty, 1, 1)) }
    return MapSelection(region, tiles, sections[main]?.name.orEmpty(), dungeon, main)
}

/**
 * Each game family's own region-map label, sampled from the game (headless
 * mGBA, the Town Map / wall map); sizes in map pixels.
 */
internal enum class MapLabelStyle(val width: Int, val height: Int) {
    /** FireRed: a strip that darkens the map (GBA blend, ~10/16) from the map's top-left corner, white text, grey shadow. */
    FIRERED(120, 16),

    /** Emerald: the standard framed window, white, in the full-screen map's bottom-right corner. */
    EMERALD(110, 30),

    /** Gen 1 (Yellow): the name in black on a white strip over the town map's top row. */
    GEN1(128, 8);

    /** The map cursor's corners: white on the GBA maps, black on Gen 1's light town map. */
    val cursorColor: Color get() = if (this == GEN1) Color(0xFF181818) else Color.White

    /** The player's 16x16 head on the map (regionmap/player_*.png, from the ROM by RomArt). */
    fun headAsset(gender: Int, game: GameKind? = null): String? = when (this) {
        // PLAYER LOOK: another game's hero worn, whose head the game's own map shows now.
        FIRERED, EMERALD -> com.pokedaisy.app.companion.data.PlayerLook.worn.value?.headArt ?: ownHeadAsset(gender, game)
        else -> ownHeadAsset(gender, game)
    }

    private fun ownHeadAsset(gender: Int, game: GameKind? = null): String? = when (this) {
        FIRERED -> if (gender == 1) "player_leaf" else "player_red"
        // Super Mariomon drew MARIO in Brendan's slot.
        // Recharged Emerald redrew both heads for its map.
        // Dreamstone Mysteries' player picks one of five looks at the start: no head of ours fits, so none is drawn.
        // Wishes of Tomorrow's JOKAIM map keeps Emerald's Brendan / May heads (by gender, checked headless).
        // Team Rocket Edition 2026's player is a Rocket recruit of the player's choosing: no head of ours fits either.
        // Monomyth's ATHANTO MAP has one head of its own for either gender.
        EMERALD -> if (game == GameKind.DREAMSTONE || game == GameKind.TEAM_ROCKET) null
            else if (game == GameKind.MONOMYTH) "player_monomyth"
            else if (com.pokedaisy.app.companion.data.runningRechargedEmerald) { if (gender == 1) "player_may_re" else "player_brendan_re" }
            else if (gender == 1) "player_may" else if (game == GameKind.MARIOMON) "player_mario" else "player_brendan"
        // Red's standing sprite (Gen1Art), Crystal's CHRIS / KRIS or Gold / Silver's boy (Gen2Art), from the ROM.
        // Crystal Clear's player is the player's own (model, skin, colours): none drawn.
        // Polished Crystal's: the look picked at the start (wPlayerGender: CHRIS, KRIS or CRYS), as its Pokégear draws it.
        GEN1 -> if (game == GameKind.CRYSTAL_CLEAR || game == GameKind.PRISM) null   // Prism's too (six models)
            else if (game == GameKind.POLISHED_CRYSTAL) com.pokedaisy.app.companion.data.Gen2Art.POLISHED_PLAYERS.getOrNull(gender)
            else if (game == GameKind.GOLD || game == GameKind.SILVER) com.pokedaisy.app.companion.data.Gen2Art.GOLD_SILVER_PLAYER
            else if (game == GameKind.RED || game == GameKind.BLUE) "player_red_blue"
            else if (game == GameKind.PURE_RED || game == GameKind.PURE_BLUE) "player_${com.pokedaisy.app.companion.data.Gen1Art.pureTownMapName(game)}"
            else if (game != GameKind.CRYSTAL && game != GameKind.CRYSTAL_LEGACY) "player_yellow"
            else if (gender == 1) com.pokedaisy.app.companion.data.Gen2Art.CRYSTAL_PLAYER_F
            else com.pokedaisy.app.companion.data.Gen2Art.CRYSTAL_PLAYER
    }

    companion object {
        private val EMERALD_FAMILY = setOf(
            GameKind.EMERALD, GameKind.EMERALD_SEAGLASS, GameKind.TMT2, GameKind.ROWE,
            GameKind.EMERALD_ROGUE, GameKind.HEART_AND_SOUL, GameKind.LAZARUS, GameKind.SOULGOLD,
            GameKind.GLAZED, GameKind.IMPERIUM, GameKind.MARIOMON, GameKind.WISHES,
            // Dreamstone Mysteries' TOWN MAP: its place name in a framed window bottom right, like Emerald's.
            GameKind.DREAMSTONE,
            // Team Rocket Edition: Emerald's region_map.c screen (its 2026 rebuild's seven maps).
            GameKind.TEAM_ROCKET,
            // Monomyth's ATHANTO MAP too.
            GameKind.MONOMYTH,
        )

        fun of(game: GameKind?) = when (game) {
            in EMERALD_FAMILY -> EMERALD
            // Crystal's POKéGEAR map: the same black name on light.
            else -> if (game.isGameBoyGame) GEN1 else FIRERED
        }
    }
}

/** A name for region-map image [i] on the region button: FireRed's own maps by name, others numbered. */
private fun regionName(images: Array<String>, i: Int): String = when (images[i]) {
    "kanto" -> "KANTO"
    "sevii123" -> "SEVII 1-3"
    "sevii45" -> "SEVII 4-5"
    "sevii67" -> "SEVII 6-7"
    "crystal_johto" -> "JOHTO"
    "crystal_kanto" -> "KANTO"
    "crystal_clear_johto", "gold_silver_johto" -> "JOHTO"
    "crystal_clear_kanto", "gold_silver_kanto" -> "KANTO"
    "prism_naljo" -> "NALJO"
    "prism_rijon" -> "RIJON"
    "prism_johto" -> "JOHTO"
    "prism_kanto" -> "KANTO"
    "prism_sevii" -> "SEVII"
    "prism_tunod" -> "TUNOD"
    "polished_crystal_johto" -> "JOHTO"
    "polished_crystal_kanto" -> "KANTO"
    "polished_crystal_orange" -> "ISLANDS"   // the game's own label on that map
    "quetzal_kanto" -> "KANTO"
    "quetzal_sevii123" -> "SEVII 1-3"
    "quetzal_sevii45" -> "SEVII 4-5"
    "quetzal_sevii67" -> "SEVII 6-7"
    "hoenn" -> "HOENN"   // Quetzal's fifth map: Emerald's, which alone never needs a name
    "quetzal_johto" -> "JOHTO"
    "tr3_kanto" -> "KANTO"
    "tr3_sevii123" -> "SEVII 1-3"
    "tr3_sevii45" -> "SEVII 4-5"
    "tr3_sevii67" -> "SEVII 6-7"
    "tr3_johto" -> "JOHTO"
    "tr3_hoenn" -> "HOENN"
    "tr3_sinnoh" -> "SINNOH"
    else -> tr("MAP {0}", i + 1)
}

/** The map image in its rounded frame with the head, the cursor and the label over it; [onTap] gets the tapped tile.
 * In the companion's window it's a D-pad stop (where the tab starts, ringed while focused): the D-pad moves the cursor a
 * tile from [cursorFrom] (through [onTap], naming each place like the game's Town Map), and off the map's edge the
 * focus goes on to the buttons. */
@Composable
private fun RegionMap(
    bitmap: Bitmap,
    style: MapLabelStyle,
    head: Bitmap?,
    headTile: Pair<Int, Int>?,
    cursor: List<MapTiles>,
    label: String,
    dungeon: String?,
    onTap: (Int, Int) -> Unit,
    /** The tile a D-pad move starts from; null = the map's middle. */
    cursorFrom: Pair<Int, Int>? = null,
    /** The map's own focus, and its buttons' ([buttonsBottom]: along the bottom, else top right): off the map's edge on
     * their side, the D-pad goes to them. */
    self: FocusRequester = remember { FocusRequester() },
    buttons: FocusRequester? = null,
    buttonsBottom: Boolean = false,
    /** The grid: tile (tx, ty) covers map pixels from ([originX] + tx * [tile], [originY] + ty * [tile]). */
    tile: Int = TILE,
    originX: Int = 0,
    originY: Int = 0,
    /** Sprites over the map, each at its top-left map pixel (Platinum's towns). */
    marks: List<Pair<Bitmap, IntOffset>> = emptyList(),
    /** The game's own cursor sprite (HeartGold / SoulSilver's: small, big), centred on [cursorFrom]'s tile instead of
     * FireRed's corners around [cursor]; it pulses on the same beat. */
    cursorSprite: Pair<Bitmap, Bitmap>? = null,
    /** The map pixel the head is centred on, at its own size (Black's places' points); null = [headTile]'s middle. */
    headAt: IntOffset? = null,
    /** The map pixel [cursorSprite] is centred on; null = [cursorFrom]'s tile's middle. */
    cursorAt: IntOffset? = null,
) {
    // FireRed's region-map cursor (graphics/region_map/cursor.png) swaps
    // between its two frames every 20 game frames. Read only while drawing,
    // so a swap redraws the map without recomposing it.
    val bigCursor = rememberBlink(CURSOR_FRAME_MS, enabled = CompanionTweaks[CompanionTweaks.Tweak.MAP_CURSOR_BLINK])

    val image = remember(bitmap) { bitmap.asImageBitmap() }
    val headImage = remember(head) { head?.asImageBitmap() }
    val markImages = remember(marks) { marks.map { (b, at) -> b.asImageBitmap() to at } }
    val cursorImages = remember(cursorSprite) { cursorSprite?.let { (a, b) -> a.asImageBitmap() to b.asImageBitmap() } }
    // FireRed's region-map screens carry the game's own black border (and
    // a white inner strip) around the map; draw only the map itself.
    val crop = remember(bitmap) { regionMapContentBounds(bitmap) }
    val shape = PixelRoundedShape(12.dp)
    val sound = LocalClickSound.current
    val tap by rememberUpdatedState(onTap)
    val from by rememberUpdatedState(cursorFrom)
    val keys = remember { MutableInteractionSource() }
    val focused by keys.collectIsFocusedAsState()
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .focusStart(true)
            .onPreviewKeyEvent { e ->
                if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                val (dx, dy) = when (e.key) {
                    Key.DirectionLeft -> -1 to 0
                    Key.DirectionRight -> 1 to 0
                    Key.DirectionUp -> 0 to -1
                    Key.DirectionDown -> 0 to 1
                    else -> return@onPreviewKeyEvent false
                }
                // The map's tiles (its border cropped off); off them, the key moves the focus out instead.
                val (cols, rows) = Math.floorDiv(crop.left - originX, tile)..Math.floorDiv(crop.right - 1 - originX, tile) to
                    Math.floorDiv(crop.top - originY, tile)..Math.floorDiv(crop.bottom - 1 - originY, tile)
                val (x, y) = from ?: ((cols.first + cols.last) / 2 to (rows.first + rows.last) / 2)
                val tx = x + dx
                val ty = y + dy
                if (tx !in cols || ty !in rows) return@onPreviewKeyEvent false
                tap(tx, ty)
                true
            }
            .gbaFocusRing(focused, shape)
            .focusRequester(self)
            .focusProperties {
                if (buttons != null) {
                    if (buttonsBottom) { down = buttons; left = buttons } else { up = buttons; right = buttons }
                }
            }
            .focusable(interactionSource = keys)
            .pixelClip(shape)
            .drawWithContent {
                drawContent()
                val px = (MAP_FRAME / 5).toPx().coerceAtLeast(1f)
                drawLayeredFrame(
                    // The list window's inner white band; dark mode's title window slate.
                    listOf(OptionColors.frameDark to 2 * px, OptionColors.frameLight to px, (if (OptionColors.dark) OptionColors.titleFill else Color.White) to 2 * px),
                    radius = 12.dp.toPx(),
                )
            },
    ) {
        val density = LocalDensity.current
        // Screen pixels per map pixel: the map is stretched to its box (MapScreen sizes it).
        val sx = with(density) { maxWidth.toPx() } / crop.width
        val sy = with(density) { maxHeight.toPx() } / crop.height
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(crop, sx, sy) {
                    detectTapGestures { p ->
                        sound()
                        tap(floor((crop.left + p.x / sx - originX) / tile).toInt(), floor((crop.top + p.y / sy - originY) / tile).toInt())
                    }
                },
        ) {
            drawImage(
                image,
                srcOffset = IntOffset(crop.left, crop.top),
                srcSize = IntSize(crop.width, crop.height),
                dstSize = IntSize(size.width.toInt(), size.height.toInt()),
                filterQuality = FilterQuality.None,
            )
            for ((mark, at) in markImages) {
                drawImage(
                    mark,
                    dstOffset = IntOffset(((at.x - crop.left) * sx).toInt(), ((at.y - crop.top) * sy).toInt()),
                    dstSize = IntSize((mark.width * sx).toInt(), (mark.height * sy).toInt()),
                    filterQuality = FilterQuality.None,
                )
            }
            // The game's own cursor sprite under the head (Black's ring: the player's figure stands over it); FireRed's
            // corners over it.
            val spriteAt = cursorFrom.takeIf { cursor.isNotEmpty() }
            if (cursorImages != null && spriteAt != null) {
                val img = if (bigCursor.value) cursorImages.second else cursorImages.first
                val cx = (cursorAt?.x ?: (originX + spriteAt.first * tile + tile / 2)) - img.width / 2 - crop.left
                val cy = (cursorAt?.y ?: (originY + spriteAt.second * tile + tile / 2)) - img.height / 2 - crop.top
                drawImage(
                    img,
                    dstOffset = IntOffset((cx * sx).toInt(), (cy * sy).toInt()),
                    dstSize = IntSize((img.width * sx).toInt(), (img.height * sy).toInt()),
                    filterQuality = FilterQuality.None,
                )
            }
            if (headImage != null && headTile != null && headAt != null) {
                // Centred on the place's point, at its own size (Black's figure and arrow).
                drawImage(
                    headImage,
                    dstOffset = IntOffset(((headAt.x - headImage.width / 2 - crop.left) * sx).toInt(), ((headAt.y - headImage.height / 2 - crop.top) * sy).toInt()),
                    dstSize = IntSize((headImage.width * sx).toInt(), (headImage.height * sy).toInt()),
                    filterQuality = FilterQuality.None,
                )
            } else if (headImage != null && headTile != null) {
                // Centred on its tile, as the game's sprite is.
                val hx = originX + headTile.first * tile + tile / 2 - HEAD / 2 - crop.left
                val hy = originY + headTile.second * tile + tile / 2 - HEAD / 2 - crop.top
                drawImage(
                    headImage,
                    dstOffset = IntOffset((hx * sx).toInt(), (hy * sy).toInt()),
                    dstSize = IntSize((HEAD * sx).toInt(), (HEAD * sy).toInt()),
                    filterQuality = FilterQuality.None,
                )
            }
            if (cursorImages == null || cursorFrom == null) for (t in cursor) {
                drawMapCursor(
                    left = originX + t.x * tile - crop.left,
                    top = originY + t.y * tile - crop.top,
                    right = originX + (t.x + t.w) * tile - crop.left,
                    bottom = originY + (t.y + t.h) * tile - crop.top,
                    sx = sx,
                    sy = sy,
                    big = bigCursor.value,
                    color = style.cursorColor,
                )
            }
        }
        MapLabel(style, label, dungeon, crop, sx, sy)
    }
}

/**
 * The place name in [style]'s label, scaled with the map: one font pixel per
 * whole screen pixels per map pixel, so the pixel font stays crisp. FireRed
 * puts a dungeon's name (Mt. Moon on Route 4) in a second strip under it.
 */
@Composable
private fun MapLabel(style: MapLabelStyle, label: String, dungeon: String?, crop: IntRect, sx: Float, sy: Float) {
    val density = LocalDensity.current
    val k = floor(minOf(sx, sy)).toInt().coerceAtLeast(1)
    // The name a screen pixel a font pixel smaller than the map's own where the map is drawn big (the user's call: the game's
    // size read large on the companion); Gen 1's letters fill their row, so they keep the map's.
    val t = if (style != MapLabelStyle.GEN1 && k >= 3) k - 1 else k
    val m = remember(k, t, density) {
        with(density) { GbaTextMetrics(k, k.toDp(), (16f * t).toSp(), t.toFloat(), (16f * t).toDp(), (20f * t).toDp()) }
    }
    @Composable
    fun box(row: Int, text: String) {
        with(density) {
            val w = style.width * sx
            val h = style.height * sy
            val (x, y) = when (style) {
                MapLabelStyle.FIRERED -> 0f to row * h
                // The tab's frame covers the map's top edge: the row starts inside it.
                MapLabelStyle.GEN1 -> 0f to MAP_FRAME.toPx()
                // The game's window ends 1px from the screen's edge; the tab's frame
                // covers that edge, so it ends 1px inside the frame instead.
                MapLabelStyle.EMERALD -> {
                    val frame = MAP_FRAME.toPx()
                    (crop.width * sx - frame - sx - w) to (crop.height * sy - frame - sy - h)
                }
            }
            Box(
                contentAlignment = if (style == MapLabelStyle.GEN1) Alignment.TopStart else Alignment.CenterStart,
                modifier = Modifier
                    .offset { IntOffset(x.toInt(), y.toInt()) }
                    .size(w.toDp(), h.toDp())
                    .drawBehind {
                        when (style) {
                            MapLabelStyle.FIRERED -> drawRect(FR_STRIP)
                            // The game's white label window; its text is titleText, so dark mode takes the title window's fill.
                            MapLabelStyle.GEN1 -> drawRect(OptionColors.titleFill)
                            MapLabelStyle.EMERALD -> drawLayeredBox(OptionColors.listLayers.map { (c, lw) -> c to lw * sy }, OptionColors.titleFill, radius = sy)
                        }
                    }
                    // FireRed's text sits 2px in, but the tab's frame covers the map's edge here.
                    .padding(start = ((if (style == MapLabelStyle.EMERALD) 7 else 4) * sx).toDp()),
            ) {
                when (style) {
                    MapLabelStyle.FIRERED -> GbaText(text, Color.White, FR_SHADOW, m)
                    // Its letters fill the 8px row (glyph rows 0-6 sit 6 font pixels under the
                    // line's top), wider than the row's line box: measured unbounded, pulled up.
                    MapLabelStyle.GEN1 -> GbaText(
                        text, OptionColors.titleText, Color.Transparent, m,
                        Modifier.wrapContentHeight(Alignment.Top, unbounded = true).offset { IntOffset(0, -6 * k + (sy / 2).toInt()) },
                    )
                    MapLabelStyle.EMERALD -> GbaText(text, OptionColors.titleText, OptionColors.titleShadow, m)
                }
            }
        }
    }
    // FireRed drops its strip over empty map; Emerald's window stays, blank.
    if (label.isNotEmpty() || style == MapLabelStyle.EMERALD) box(0, label)
    if (dungeon != null && style == MapLabelStyle.FIRERED) box(1, dungeon)
}

/** Maps whose top right is land and routes, with open sea along the bottom left instead (left of their label window):
 * Heart and Soul's Johto (its north-east routes run into the corner) and R.O.W.E.'s (its Sevii title strip and islands). */
private val BOTTOM_ROW_MAPS = setOf(GameKind.HEART_AND_SOUL, GameKind.ROWE)

/** PLACES / region, FLY under them and ME, top right on every map. Emerald-style maps had them bottom left, across from
 * their bottom-right label - over the west of Hoenn's land, where the player looks; the top right is open sea there too.
 * [bottomRow] ([BOTTOM_ROW_MAPS]): one row along the bottom left instead, low enough to stay on the sea. */
@Composable
private fun BoxScope.MapButtons(
    bottomRow: Boolean,
    /** The map's focus and this group's (the D-pad, the companion's window): back to the map from the buttons. */
    map: FocusRequester,
    group: FocusRequester,
    regionLabel: String?,
    awayFromPlayer: Boolean,
    onPlaces: () -> Unit,
    onRegion: () -> Unit,
    onMe: () -> Unit,
    /** FLY's button; null = none. */
    onFly: (() -> Unit)? = null,
    /** FREE FLIGHT's TAKE OFF / LAND, under FLY; null = none. */
    onTakeOff: (() -> Unit)? = null,
    flying: Boolean = false,
) {
    val m = rememberGbaTextMetrics(1f)
    // Toward the map from a button: UP from the bottom row; LEFT from the top right (but between a row's own buttons),
    // DOWN from its lowest.
    fun toMap(up: Boolean = false, left: Boolean = false, down: Boolean = false) = Modifier.focusProperties {
        if (up) this.up = map
        if (left) this.left = map
        if (down) this.down = map
    }
    if (bottomRow) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(m.u * 4),
            verticalAlignment = Alignment.Bottom,
            modifier = Modifier.align(Alignment.BottomStart).padding(m.u * 8).focusRequester(group),
        ) {
            if (regionLabel != null) OptionButton(regionLabel, m, onClick = onRegion, modifier = toMap(up = true))
            OptionButton(tr("PLACES"), m, onClick = onPlaces, modifier = toMap(up = true))
            if (onFly != null) FlyButton(onFly, toMap(up = true))
            if (onTakeOff != null) TakeOffButton(onTakeOff, flying, toMap(up = true))
            if (awayFromPlayer) OptionButton(tr("ME"), m, onClick = onMe, emphasis = true, modifier = toMap(up = true))
        }
        return
    }
    // ME on its own row (nearer the edge), so the row beside the label stays short.
    Column(
        horizontalAlignment = Alignment.End,
        verticalArrangement = Arrangement.spacedBy(m.u * 4),
        modifier = Modifier.align(Alignment.TopEnd).padding(m.u * 8).focusRequester(group),
    ) {
        val lowest = when {
            awayFromPlayer -> "ME"
            onTakeOff != null -> "TAKE OFF"
            onFly != null -> "FLY"
            else -> "TOP"
        }
        Row(horizontalArrangement = Arrangement.spacedBy(m.u * 4)) {
            if (regionLabel != null) OptionButton(regionLabel, m, onClick = onRegion, modifier = toMap(left = true, down = lowest == "TOP"))
            OptionButton(tr("PLACES"), m, onClick = onPlaces, modifier = toMap(left = regionLabel == null, down = lowest == "TOP"))
        }
        if (onFly != null) FlyButton(onFly, toMap(left = true, down = lowest == "FLY"))
        if (onTakeOff != null) TakeOffButton(onTakeOff, flying, toMap(left = true, down = lowest == "TAKE OFF"))
        if (awayFromPlayer) OptionButton(tr("ME"), m, onClick = onMe, emphasis = true, modifier = toMap(left = true, down = true))
    }
}

/** FLY in the party menu's field-move blue, at the companion's full text size (the other map buttons are the denser
 * 1x): the button that does something in the game. */
@Composable
private fun FlyButton(onFly: () -> Unit, modifier: Modifier = Modifier) {
    val big = rememberGbaTextMetrics()
    OptionButton(
        tr("FLY"), big, onClick = onFly, modifier = modifier.widthIn(min = big.u * 56),
        labelColors = OptionColors.fieldMove to OptionColors.fieldMoveShadow, frameLayers = OptionColors.fieldMoveLayers,
    )
}

/** FREE FLIGHT's TAKE OFF (LAND while up), FLY's blue at its size. */
@Composable
private fun TakeOffButton(onClick: () -> Unit, flying: Boolean, modifier: Modifier = Modifier) {
    val big = rememberGbaTextMetrics()
    OptionButton(
        if (flying) tr("LAND") else tr("TAKE OFF"), big, onClick = onClick, modifier = modifier.widthIn(min = big.u * 56),
        labelColors = OptionColors.fieldMove to OptionColors.fieldMoveShadow, frameLayers = OptionColors.fieldMoveLayers,
    )
}

/**
 * Every place on the game's maps, to find one a guide names: towns and
 * cities in the game's own order, routes by number, the rest A-Z. Picking
 * one shows it on its map with the cursor on it.
 */
@Composable
private fun PlacesList(model: RegionMapModel, onPick: (Int) -> Unit, onMe: () -> Unit, onDismiss: () -> Unit) {
    val m = rememberGbaTextMetrics()
    val groups = remember(model) { model.places() }
    OptionOverlay(onDismiss, Modifier.widthIn(max = 560.dp).fillMaxHeight()) {
        Column {
            OptionTitleWindow(tr("PLACES"), m, onBack = onDismiss)
            Spacer(Modifier.height(m.u * 4))
            OptionListWindow(m, Modifier.fillMaxWidth().weight(1f)) {
                LazyColumn(Modifier.fillMaxSize()) {
                    item { OptionLine(tr("YOUR LOCATION"), null, selected = false, m, divider = true, onClick = onMe) }
                    for ((title, ids) in groups) {
                        item(key = title) {
                            GbaText(
                                placesGroupTitle(title), OptionColors.value, OptionColors.valueShadow, m,
                                modifier = Modifier.padding(start = m.u * 8, top = m.u * 8, bottom = m.u * 2),
                            )
                        }
                        items(ids, key = { it }) { id ->
                            val s = model.sections.getValue(id)
                            OptionLine(
                                // Only places off the first map say which one (FireRed's Sevii Islands).
                                gameCase(s.name), if (s.region > 0) regionName(model.images, s.region) else null,
                                selected = false, m, labelWeight = 0.62f, divider = true,
                            ) { onPick(id) }
                        }
                    }
                }
            }
        }
    }
}

/** MOVE_FLY: the same id in every game the companion flies in. */
internal const val FLY_MOVE = 19

/**
 * FLY's destinations: the towns the game's Fly map would take - visited ones, in the map's own order - each a row
 * that flies there; [flyer] (the Pokémon that knows Fly) carries the player, under the title.
 */
@Composable
private fun FlyList(model: RegionMapModel?, targets: com.pokedaisy.app.companion.data.FlyTargets, flyer: String?, onPick: (Int) -> Unit, onDismiss: () -> Unit) {
    val m = rememberGbaTextMetrics()
    OptionOverlay(onDismiss, Modifier.widthIn(max = 560.dp).fillMaxHeight()) {
        Column {
            OptionTitleWindow(tr("FLY"), m, onBack = onDismiss, trailing = flyer)
            Spacer(Modifier.height(m.u * 4))
            OptionListWindow(m, Modifier.fillMaxWidth().weight(1f)) {
                if (targets.destinations.isEmpty()) {
                    GbaText(tr("No town to fly to yet."), OptionColors.label, OptionColors.labelShadow, m, modifier = Modifier.padding(m.u * 8))
                } else LazyColumn(Modifier.fillMaxSize()) {
                    items(targets.destinations, key = { it }) { mapsec ->
                        // A DS game's destinations are its fly spots (spawn ids), named by their town.
                        val ds = if (activeGame.isDsGame) com.pokedaisy.app.companion.data.gen4ConfigFor(activeGame)?.flySpotName(mapsec) else null
                        val s = if (ds != null) null else model?.sections?.get(mapsec)
                        OptionLine(
                            gameCase(ds ?: s?.name ?: "#$mapsec"), s?.takeIf { it.region > 0 && model != null }?.let { regionName(model!!.images, it.region) },
                            selected = false, m, labelWeight = 0.62f, divider = true,
                        ) { onPick(mapsec) }
                    }
                }
            }
        }
    }
}

/** Why FLY didn't start, in the companion's words. */
internal fun flyMessage(r: com.pokedaisy.app.companion.data.FlyResult): String = when (r) {
    com.pokedaisy.app.companion.data.FlyResult.NOT_NOW -> tr("Not now: close the game's menus and battles first.")
    com.pokedaisy.app.companion.data.FlyResult.IN_BATTLE -> tr("You can't do that during a battle.")
    com.pokedaisy.app.companion.data.FlyResult.NO_BADGE -> tr("FLY needs a new BADGE first.")
    com.pokedaisy.app.companion.data.FlyResult.CANT_FLY_HERE -> tr("You can't FLY from here.")
    com.pokedaisy.app.companion.data.FlyResult.PARTNER -> tr("You can't FLY while someone is traveling with you.")
    com.pokedaisy.app.companion.data.FlyResult.NO_FLYER -> tr("Nobody in the party knows FLY.")
    com.pokedaisy.app.companion.data.FlyResult.NO_ROUTE -> tr("No way there over the map from here.")
    else -> tr("It can't be used here.")
}

/** [RegionMapModel.places]' group titles (English ids, also list keys) in the app's language. */
private fun placesGroupTitle(title: String): String = when (title) {
    "TOWNS & CITIES" -> tr("TOWNS & CITIES")
    "ROUTES" -> tr("ROUTES")
    "OTHER PLACES" -> tr("OTHER PLACES")
    else -> title
}

/** [directionNames]' words in the app's language. */
private fun directionText(dir: String): String = when (dir) {
    "South" -> tr("South")
    "North" -> tr("North")
    "West" -> tr("West")
    "East" -> tr("East")
    "Southwest" -> tr("Southwest")
    "Southeast" -> tr("Southeast")
    "Northwest" -> tr("Northwest")
    "Northeast" -> tr("Northeast")
    else -> dir
}

/** No map image for this game or area: the name and position in a label instead. */
@Composable
private fun BoxScope.NoRegionMap(snapshot: SnapshotView) {
    val location = snapshot.location
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .align(Alignment.Center)
            .padding(24.dp)
            // Readable on every game's backdrop.
            .background(Color.Black.copy(alpha = 0.55f), PixelRoundedShape(6.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        if (location.mapSecName.isNotEmpty()) {
            Text(location.mapSecName, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp, textAlign = TextAlign.Center)
        }
        Text(
            tr("({0}, {1}) facing {2}", snapshot.x, snapshot.y, directionText(snapshot.facingDirection)),
            color = Color.White.copy(alpha = 0.85f), fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp, bottom = 10.dp),
        )
        Text(
            // No name at all = the game's own region isn't mapped yet (Odyssey
            // before RomRegionMap has read its ROM).
            if (location.mapSecName.isEmpty()) tr("(no region map for this game yet)") else tr("(no region map for this area)"),
            color = Color.White.copy(alpha = 0.7f),
            fontSize = 13.sp,
            textAlign = TextAlign.Center,
        )
    }
}

private const val CURSOR_FRAME_MS = 20 * 1000 / 60
/** The tab's frame around the map (2 + 1 + 2 lines). */
private val MAP_FRAME = 5.dp

/** How far the map may be stretched (either way) to fill the tab before it keeps its shape instead. */
private const val MAX_MAP_STRETCH = 1.2f
private const val TILE = 8
private const val HEAD = 16
private val FR_STRIP = Color.Black.copy(alpha = 6f / 16f)
private val FR_SHADOW = Color(0xFF636363)

/**
 * FireRed's map cursor around the map pixels [left]..[right) x [top]..[bottom)
 * (a tile, or a whole map section): four white corners 2 pixels thick and 5
 * long, 2 pixels out from it, or 3 on the [big] frame - as the cursor sprite
 * draws them around its tile. [sx]/[sy] are screen pixels per map pixel.
 */
internal fun DrawScope.drawMapCursor(left: Int, top: Int, right: Int, bottom: Int, sx: Float, sy: Float, big: Boolean, color: Color = Color.White) {
    val out = if (big) 3 else 2
    val x0 = left - out
    val y0 = top - out
    val x1 = right + out
    val y1 = bottom + out
    fun px(x: Int, y: Int, w: Int, h: Int) =
        drawRect(color, Offset(x * sx, y * sy), Size(w * sx, h * sy))
    for ((cx, cy) in listOf(x0 to y0, x1 - CURSOR_ARM to y0, x0 to y1 - CURSOR_ARM, x1 - CURSOR_ARM to y1 - CURSOR_ARM)) {
        // Each corner: a 5x2 bar on the box's edge row and a 2x5 one on its edge column.
        val barY = if (cy == y0) y0 else y1 - CURSOR_THICK
        val barX = if (cx == x0) x0 else x1 - CURSOR_THICK
        px(cx, barY, CURSOR_ARM, CURSOR_THICK)
        px(barX, cy, CURSOR_THICK, CURSOR_ARM)
    }
}

private const val CURSOR_ARM = 5
private const val CURSOR_THICK = 2

/**
 * The part of a region-map image that's actually map: trims whole rows/columns
 * of solid black or white off each edge (FireRed's region-map screen has a
 * 16px black border plus an 8px white strip each side; Emerald's has none).
 * Only near-black/near-white lines count, so a map edge that's all sea (blue)
 * is never mistaken for border, and at most a quarter of each side goes.
 */
fun regionMapContentBounds(bitmap: Bitmap): IntRect {
    val w = bitmap.width
    val h = bitmap.height
    val px = IntArray(w * h).also { bitmap.getPixels(it, 0, w, 0, 0, w, h) }
    fun border(c: Int): Boolean {
        val r = (c shr 16) and 0xFF
        val g = (c shr 8) and 0xFF
        val b = c and 0xFF
        return (r < 24 && g < 24 && b < 24) || (r > 240 && g > 240 && b > 240)
    }
    fun col(x: Int, top: Int, bottom: Int) = (top until bottom).all { border(px[it * w + x]) }
    fun row(y: Int, left: Int, right: Int) = (left until right).all { border(px[y * w + it]) }
    var left = 0
    var right = w
    var top = 0
    var bottom = h
    while (left < w / 4 && col(left, top, bottom)) left++
    while (right > w - w / 4 && col(right - 1, top, bottom)) right--
    while (top < h / 4 && row(top, left, right)) top++
    while (bottom > h - h / 4 && row(bottom - 1, left, right)) bottom--
    return IntRect(left, top, right, bottom)
}

/** HeartGold / SoulSilver's map moves this many map pixels a second with the stick all the way over (the map is 376
 * across: about three seconds end to end). */
private const val POKEGEAR_STICK_SPEED = 120f
private const val POKEGEAR_STICK_DEAD = 0.2f

/**
 * HeartGold / SoulSilver's Pokégear map, as the game shows it: the map as tall as the tab (or twice that, ZOOM / X),
 * scrolled to keep the cursor in the middle, with the game's own red cursor - free to roam, smoothly, with the left stick
 * (the companion's window), a cell at a time with the D-pad, or where a tap puts it; a drag pans the map and leaves the
 * cursor be (the view follows the cursor again once it moves). The buttons
 * stand in a column on its right, as the game's do. The cursor names the cell it's on through [onCell] (the caller's
 * selection, like a tap); it comes to rest on a cell's middle, and follows [cell] when the caller moves it (PLACES, ME).
 */
@Composable
private fun PokegearMapView(
    bitmap: Bitmap,
    style: MapLabelStyle,
    head: Bitmap?,
    playerTile: Pair<Int, Int>?,
    cursorSprite: Pair<Bitmap, Bitmap>?,
    tile: Int,
    cell: Pair<Int, Int>?,
    label: String,
    onCell: (Int, Int) -> Unit,
    self: FocusRequester,
    buttons: @Composable ColumnScope.(zoomed: Boolean, toggleZoom: () -> Unit) -> Unit,
) {
    val image = remember(bitmap) { bitmap.asImageBitmap() }
    val headImage = remember(head) { head?.asImageBitmap() }
    val cursorImages = remember(cursorSprite) { cursorSprite?.let { (a, b) -> a.asImageBitmap() to b.asImageBitmap() } }
    val bigCursor = rememberBlink(CURSOR_FRAME_MS, enabled = CompanionTweaks[CompanionTweaks.Tweak.MAP_CURSOR_BLINK])
    val mapW = bitmap.width.toFloat()
    val mapH = bitmap.height.toFloat()
    fun centreOf(c: Pair<Int, Int>) = Offset(c.first * tile + tile / 2f, c.second * tile + tile / 2f)
    fun cellOf(p: Offset) = floor(p.x / tile).toInt().coerceIn(0, (bitmap.width - 1) / tile) to
        floor(p.y / tile).toInt().coerceIn(0, (bitmap.height - 1) / tile)
    // The cursor in map pixels: on the caller's cell (the player's, at first).
    val start = cell ?: playerTile
    var pos by remember { mutableStateOf(start?.let(::centreOf) ?: Offset(mapW / 2, mapH / 2)) }
    var zoomed by remember { mutableStateOf(false) }
    // Where a drag left the view (its middle, in map pixels); null = on the cursor.
    var panned by remember { mutableStateOf<Offset?>(null) }
    val report by rememberUpdatedState(onCell)
    // The last cell this view named: the caller echoing it back isn't a move (the cursor may be cells further on by then).
    val named = remember { arrayOfNulls<Pair<Int, Int>>(1) }
    fun emit(x: Int, y: Int) { named[0] = x to y; report(x, y) }
    // The caller moved it (PLACES, ME, the player landing elsewhere): there it goes.
    LaunchedEffect(cell, playerTile) {
        val to = cell ?: playerTile ?: return@LaunchedEffect
        if (to != named[0] && cellOf(pos) != to) { pos = centreOf(to); panned = null }
    }
    fun moveTo(p: Offset) {
        val clamped = Offset(p.x.coerceIn(0f, mapW - 1), p.y.coerceIn(0f, mapH - 1))
        val before = cellOf(pos)
        pos = clamped
        val now = cellOf(clamped)
        if (now != before) emit(now.first, now.second)
    }
    fun rest() {
        val c = cellOf(pos)
        pos = centreOf(c)
        emit(c.first, c.second)
    }
    // The stick: a velocity while it's tilted, the cursor resting on its cell once it's let go.
    var stick by remember { mutableStateOf(Offset.Zero) }
    val tilted = stick.getDistance() > POKEGEAR_STICK_DEAD
    CompanionStickHandler { x, y -> stick = Offset(x, y); true }
    LaunchedEffect(tilted) {
        if (!tilted) return@LaunchedEffect
        var last = withFrameNanos { it }
        try {
            while (true) {
                val now = withFrameNanos { it }
                val dt = (now - last) / 1e9f
                last = now
                val s = stick
                val mag = ((s.getDistance() - POKEGEAR_STICK_DEAD) / (1 - POKEGEAR_STICK_DEAD)).coerceIn(0f, 1f)
                if (mag > 0f) { panned = null; moveTo(pos + s / s.getDistance() * (mag * POKEGEAR_STICK_SPEED * zoomFactor(zoomed) * dt)) }
            }
        } finally {
            rest()
        }
    }
    CompanionKeyHandler { key, repeat -> if (key == MenuKey.X && !repeat) { zoomed = !zoomed; true } else false }
    val sound = LocalClickSound.current
    val keys = remember { MutableInteractionSource() }
    val focused by keys.collectIsFocusedAsState()
    val shape = PixelRoundedShape(12.dp)
    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        BoxWithConstraints(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .focusStart(true)
                .onPreviewKeyEvent { e ->
                    if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    val (dx, dy) = when (e.key) {
                        Key.DirectionLeft -> -1 to 0
                        Key.DirectionRight -> 1 to 0
                        Key.DirectionUp -> 0 to -1
                        Key.DirectionDown -> 0 to 1
                        else -> return@onPreviewKeyEvent false
                    }
                    val (x, y) = cellOf(pos)
                    val tx = x + dx
                    val ty = y + dy
                    // Off the map's right edge: on to the buttons.
                    if (tx !in 0..((bitmap.width - 1) / tile) || ty !in 0..((bitmap.height - 1) / tile)) return@onPreviewKeyEvent false
                    pos = centreOf(tx to ty)
                    panned = null
                    emit(tx, ty)
                    true
                }
                .gbaFocusRing(focused, shape)
                .focusRequester(self)
                .focusable(interactionSource = keys)
                .pixelClip(shape)
                .drawWithContent {
                    drawContent()
                    val px = (MAP_FRAME / 5).toPx().coerceAtLeast(1f)
                    drawLayeredFrame(
                        listOf(OptionColors.frameDark to 2 * px, OptionColors.frameLight to px, (if (OptionColors.dark) OptionColors.titleFill else Color.White) to 2 * px),
                        radius = 12.dp.toPx(),
                    )
                },
        ) {
            val density = LocalDensity.current
            val viewW = with(density) { maxWidth.toPx() }
            val viewH = with(density) { maxHeight.toPx() }
            // Screen pixels per map pixel: the map as tall as the view, twice that zoomed.
            val scale = viewH / mapH * zoomFactor(zoomed)
            // The view's top-left in map pixels: the cursor (or where a drag left it) in the middle, the map's edges never
            // past the view's.
            fun camera(): Offset {
                val w = viewW / scale
                val h = viewH / scale
                val c = panned ?: pos
                val x = if (w >= mapW) (mapW - w) / 2 else (c.x - w / 2).coerceIn(0f, mapW - w)
                val y = if (h >= mapH) (mapH - h) / 2 else (c.y - h / 2).coerceIn(0f, mapH - h)
                return Offset(x, y)
            }
            fun viewMiddle() = camera() + Offset(viewW / scale / 2, viewH / scale / 2)
            Canvas(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(scale) {
                        detectTapGestures { p ->
                            sound()
                            // The view stays where it is: the cursor goes to the tapped cell in it.
                            panned = viewMiddle()
                            moveTo(camera() + p / scale)
                            rest()
                        }
                    }
                    .pointerInput(scale) {
                        // Dragging pans the map with the finger; the cursor stays on its place.
                        detectDragGestures { change, drag ->
                            change.consume()
                            panned = viewMiddle() - drag / scale
                            // Held at the map's edges, so the next drag back moves it at once.
                            panned = viewMiddle()
                        }
                    },
            ) {
                val cam = camera()
                fun at(x: Float, y: Float) = IntOffset(((x - cam.x) * scale).toInt(), ((y - cam.y) * scale).toInt())
                drawImage(
                    image,
                    dstOffset = at(0f, 0f),
                    dstSize = IntSize((mapW * scale).toInt(), (mapH * scale).toInt()),
                    filterQuality = FilterQuality.None,
                )
                if (headImage != null && playerTile != null) {
                    val c = centreOf(playerTile)
                    drawImage(
                        headImage,
                        dstOffset = at(c.x - HEAD / 2, c.y - HEAD / 2),
                        dstSize = IntSize((HEAD * scale).toInt(), (HEAD * scale).toInt()),
                        filterQuality = FilterQuality.None,
                    )
                }
                // On whole map pixels, as a sprite is.
                val cx = floor(pos.x)
                val cy = floor(pos.y)
                if (cursorImages != null) {
                    val img = if (bigCursor.value) cursorImages.second else cursorImages.first
                    drawImage(
                        img,
                        dstOffset = at(cx - img.width / 2 + 0.5f, cy - img.height / 2 + 0.5f),
                        dstSize = IntSize((img.width * scale).toInt(), (img.height * scale).toInt()),
                        filterQuality = FilterQuality.None,
                    )
                } else {
                    val (tx, ty) = cellOf(pos)
                    drawMapCursor(
                        left = tx * tile - cam.x.toInt(), top = ty * tile - cam.y.toInt(),
                        right = (tx + 1) * tile - cam.x.toInt(), bottom = (ty + 1) * tile - cam.y.toInt(),
                        sx = scale, sy = scale, big = bigCursor.value,
                    )
                }
            }
            // The place name over the view's top left, near the map's own pixel size (its font stays whole pixels).
            val labelScale = viewH / mapH * 0.9f
            MapLabel(style, label, null, IntRect(0, 0, (viewW / labelScale).toInt(), mapH.toInt()), labelScale, labelScale)
        }
        Column(
            verticalArrangement = Arrangement.spacedBy(8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.fillMaxHeight(),
        ) {
            buttons(zoomed) { zoomed = !zoomed }
        }
    }
}

/** How much nearer the map is zoomed in (ZOOM / X). */
private fun zoomFactor(zoomed: Boolean) = if (zoomed) 2f else 1f
