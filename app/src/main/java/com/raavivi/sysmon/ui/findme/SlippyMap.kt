package com.raavivi.sysmon.ui.findme

import android.graphics.drawable.BitmapDrawable
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.FitScreen
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import coil.ImageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import com.raavivi.sysmon.ui.theme.SysMon
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.roundToInt

/** One device marker: [accuracyM] draws the accuracy circle, [color] is the device's identity hue. */
data class MapPin(
    val id: String,
    val point: LatLon,
    val accuracyM: Double?,
    val color: Color,
)

/** Pan/zoom state of a [SlippyMap]; hoisted so the screen can fit and centre it. */
@Stable
class MapState(initial: MapView = MapView(LatLon(0.0, 0.0), 2.0)) {
    var view by mutableStateOf(initial)
    var width by mutableIntStateOf(0)
    var height by mutableIntStateOf(0)

    val sized: Boolean get() = width > 0 && height > 0

    /** Fits [points]; returns false until the canvas has a size. */
    fun fit(points: List<LatLon>): Boolean {
        if (!sized) return false
        MapMath.fitBounds(points, width.toDouble(), height.toDouble())?.let { view = it }
        return true
    }

    fun centerOn(point: LatLon, minZoom: Double = 14.0) {
        view = MapView(point, maxOf(view.zoom, minZoom))
    }

    fun zoomBy(dz: Double) {
        view = MapMath.zoomAround(view, dz, 0.0, 0.0)
    }
}

private data class TileKey(val z: Int, val x: Int, val y: Int)

private const val MAX_CACHED_TILES = 180
private const val MAX_TILES_PER_FRAME = 64
private const val FAILED_RETRY_MS = 10_000L

/** Inverts lightness (keeping hue) and tones it down, as the web map's CSS filter does. */
private val DarkTiles: ColorFilter = run {
    // CSS hue-rotate(180deg) matrix and saturate(0.6) matrix.
    val hue = arrayOf(
        floatArrayOf(-0.574f, 1.430f, 0.144f),
        floatArrayOf(0.426f, 0.430f, 0.144f),
        floatArrayOf(0.426f, 1.430f, -0.856f),
    )
    val s = 0.6f
    val sat = arrayOf(
        floatArrayOf(0.213f + 0.787f * s, 0.715f - 0.715f * s, 0.072f - 0.072f * s),
        floatArrayOf(0.213f - 0.213f * s, 0.715f + 0.285f * s, 0.072f - 0.072f * s),
        floatArrayOf(0.213f - 0.213f * s, 0.715f - 0.715f * s, 0.072f + 0.928f * s),
    )
    val brightness = 0.85f
    val m = FloatArray(20)
    for (r in 0 until 3) {
        for (c in 0 until 3) {
            // out = brightness * sat * (1 - hue * in): linear part is -brightness * sat * hue.
            var v = 0f
            for (k in 0 until 3) v += sat[r][k] * hue[k][c]
            m[r * 5 + c] = -brightness * v
        }
        m[r * 5 + 4] = brightness * 255f
    }
    m[18] = 1f
    ColorFilter.colorMatrix(ColorMatrix(m))
}

/**
 * A slippy map over Web-Mercator tiles fetched through [imageLoader] (so the
 * bearer is sent). Drag pans, pinch and the +/- buttons zoom, tapping a pin
 * calls [onSelect]. The selected device's [trail] is drawn as an unsmoothed
 * polyline with a dot per fix.
 */
@Composable
fun SlippyMap(
    state: MapState,
    pins: List<MapPin>,
    selectedId: String?,
    trail: List<LatLon>,
    imageLoader: ImageLoader,
    tileUrl: (z: Int, x: Int, y: Int) -> String,
    onSelect: (String) -> Unit,
    onFitAll: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val tiles = remember { mutableStateMapOf<TileKey, ImageBitmap>() }
    val inFlight = remember { HashSet<TileKey>() }
    val failed = remember { HashSet<TileKey>() }
    val currentPins by rememberUpdatedState(pins)
    val currentSelect by rememberUpdatedState(onSelect)

    val view = state.view
    val level = MapMath.tileLevel(view.zoom)
    val range = if (state.sized) {
        MapMath.tileRange(view, state.width.toDouble(), state.height.toDouble(), level)
    } else {
        null
    }

    LaunchedEffect(range) {
        val r = range ?: return@LaunchedEffect
        if (r.count > MAX_TILES_PER_FRAME) return@LaunchedEffect
        for (x in r.minX..r.maxX) for (y in r.minY..r.maxY) {
            val key = TileKey(r.z, x, y)
            if (key in tiles || key in inFlight || key in failed) continue
            inFlight += key
            scope.launch {
                val req = ImageRequest.Builder(context)
                    .data(tileUrl(r.z, x, y))
                    .allowHardware(false)
                    .build()
                val result = imageLoader.execute(req)
                val bitmap = ((result as? SuccessResult)?.drawable as? BitmapDrawable)?.bitmap
                inFlight -= key
                if (bitmap != null) {
                    tiles[key] = bitmap.asImageBitmap()
                    if (tiles.size > MAX_CACHED_TILES) {
                        val stale = tiles.keys.filter { it.z != r.z }
                        (stale.ifEmpty { tiles.keys.toList() }).take(tiles.size - MAX_CACHED_TILES + 20)
                            .forEach { tiles.remove(it) }
                    }
                } else {
                    failed += key
                    delay(FAILED_RETRY_MS)
                    failed -= key
                }
            }
        }
    }

    val ground = MaterialTheme.colorScheme.surfaceVariant
    val ring = MaterialTheme.colorScheme.background

    Box(modifier.clipToBounds()) {
        Canvas(
            Modifier
                .fillMaxSize()
                .onSizeChanged { state.width = it.width; state.height = it.height }
                .pointerInput(Unit) {
                    detectTransformGestures { centroid, pan, zoom, _ ->
                        val dz = if (zoom > 0f) ln(zoom.toDouble()) / ln(2.0) else 0.0
                        var v = MapMath.pan(state.view, pan.x.toDouble(), pan.y.toDouble())
                        v = MapMath.zoomAround(
                            v,
                            dz,
                            (centroid.x - size.width / 2f).toDouble(),
                            (centroid.y - size.height / 2f).toDouble(),
                        )
                        state.view = v
                    }
                }
                .pointerInput(Unit) {
                    detectTapGestures { tap ->
                        val v = state.view
                        val hit = currentPins.minByOrNull { pin ->
                            val (dx, dy) = MapMath.offsetFromCenter(v, pin.point)
                            hypot(size.width / 2f + dx - tap.x, size.height / 2f + dy - tap.y)
                        }
                        if (hit != null) {
                            val (dx, dy) = MapMath.offsetFromCenter(v, hit.point)
                            val d = hypot(size.width / 2f + dx - tap.x, size.height / 2f + dy - tap.y)
                            if (d <= 28.dp.toPx()) currentSelect(hit.id)
                        }
                    }
                },
        ) {
            drawRect(ground)
            drawTiles(view, level, tiles)
            drawTrail(view, trail, pins.firstOrNull { it.id == selectedId }?.color ?: Color.Unspecified)
            // Selected last so it sits on top.
            pins.sortedBy { it.id == selectedId }.forEach { drawPin(view, it, it.id == selectedId, ring) }
        }

        Text(
            "(c) OpenStreetMap contributors",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .align(Alignment.BottomStart)
                .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.8f))
                .padding(horizontal = 6.dp, vertical = 2.dp),
        )

        Column(
            Modifier
                .align(Alignment.BottomEnd)
                .padding(8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            MapButton(Icons.Filled.FitScreen, "Fit all", onFitAll)
            MapButton(Icons.Filled.Add, "Zoom in") { state.zoomBy(1.0) }
            MapButton(Icons.Filled.Remove, "Zoom out") { state.zoomBy(-1.0) }
        }
    }
}

@Composable
private fun MapButton(icon: ImageVector, label: String, onClick: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f),
        shape = RoundedCornerShape(minOf(SysMon.palette.cardRadius, 8.dp)),
        tonalElevation = 2.dp,
    ) {
        IconButton(onClick = onClick, modifier = Modifier.size(40.dp)) {
            Icon(icon, contentDescription = label)
        }
    }
}

private fun DrawScope.drawTiles(view: MapView, level: Int, tiles: Map<TileKey, ImageBitmap>) {
    val w = size.width.toDouble()
    val h = size.height.toDouble()
    val range = MapMath.tileRange(view, w, h, level)
    val scale = MapMath.tileScale(view.zoom, level)
    val tilePx = MapMath.TILE * scale
    val cx = MapMath.lonToX(view.center.lon, level.toDouble())
    val cy = MapMath.latToY(view.center.lat, level.toDouble())
    for (tx in range.minX..range.maxX) for (ty in range.minY..range.maxY) {
        val left = w / 2 + (tx * MapMath.TILE - cx) * scale
        val top = h / 2 + (ty * MapMath.TILE - cy) * scale
        val dstX = left.roundToInt()
        val dstY = top.roundToInt()
        val dst = IntSize((left + tilePx).roundToInt() - dstX, (top + tilePx).roundToInt() - dstY)
        val image = tiles[TileKey(level, tx, ty)]
        if (image != null) {
            drawImage(
                image,
                dstOffset = IntOffset(dstX, dstY),
                dstSize = dst,
                colorFilter = DarkTiles,
            )
        } else if (level > 0) {
            // Stand-in while the tile loads: the quarter of its parent that covers it.
            val parent = tiles[TileKey(level - 1, tx / 2, ty / 2)] ?: continue
            val half = parent.width / 2
            drawImage(
                parent,
                srcOffset = IntOffset((tx % 2) * half, (ty % 2) * half),
                srcSize = IntSize(half, half),
                dstOffset = IntOffset(dstX, dstY),
                dstSize = dst,
                colorFilter = DarkTiles,
            )
        }
    }
}

private fun DrawScope.screenPoint(view: MapView, p: LatLon): Offset {
    val (dx, dy) = MapMath.offsetFromCenter(view, p)
    return Offset(size.width / 2f + dx.toFloat(), size.height / 2f + dy.toFloat())
}

private fun DrawScope.drawTrail(view: MapView, trail: List<LatLon>, color: Color) {
    if (trail.isEmpty() || color == Color.Unspecified) return
    val pts = trail.map { screenPoint(view, it) }
    for (i in 1 until pts.size) {
        drawLine(color.copy(alpha = 0.7f), pts[i - 1], pts[i], strokeWidth = 2.dp.toPx())
    }
    pts.forEach { drawCircle(color, radius = 2.5.dp.toPx(), center = it) }
}

private fun DrawScope.drawPin(view: MapView, pin: MapPin, selected: Boolean, ring: Color) {
    val c = screenPoint(view, pin.point)
    pin.accuracyM?.takeIf { it > 0 }?.let { acc ->
        val r = (acc / MapMath.metersPerPixel(pin.point.lat, view.zoom)).toFloat()
        if (r > 4.dp.toPx()) {
            drawCircle(pin.color.copy(alpha = 0.15f), radius = r, center = c)
            drawCircle(pin.color.copy(alpha = 0.5f), radius = r, center = c, style = Stroke(1.dp.toPx()))
        }
    }
    val radius = (if (selected) 9.dp else 7.dp).toPx()
    drawCircle(ring, radius = radius + 2.dp.toPx(), center = c)
    drawCircle(pin.color, radius = radius, center = c)
    if (selected) drawCircle(ring, radius = 3.dp.toPx(), center = c)
}
