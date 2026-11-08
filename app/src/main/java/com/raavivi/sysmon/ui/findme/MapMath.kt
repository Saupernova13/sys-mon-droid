package com.raavivi.sysmon.ui.findme

import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sinh
import kotlin.math.tan

/** A WGS84 point in degrees. */
data class LatLon(val lat: Double, val lon: Double)

/** What the map shows: its centre and a continuous (fractional) zoom. */
data class MapView(val center: LatLon, val zoom: Double)

/** Inclusive tile index range at integer level [z]. */
data class TileRange(val z: Int, val minX: Int, val maxX: Int, val minY: Int, val maxY: Int) {
    val count: Int get() = (maxX - minX + 1).coerceAtLeast(0) * (maxY - minY + 1).coerceAtLeast(0)
}

/**
 * Web-Mercator helpers for the slippy map (256 px tiles). World pixels at zoom
 * `z` run 0..256 * 2^z from the top-left of the map; zoom may be fractional.
 */
object MapMath {
    const val TILE = 256.0
    const val MIN_ZOOM = 2.0
    const val MAX_ZOOM = 19.0
    const val MAX_LAT = 85.05112878

    /** A level is shown from 0.75x to 1.5x; below 0.75x the next lower level takes over. */
    private const val SWITCH_DOWN = 0.75

    /** Metres per pixel at the equator at zoom 0. */
    private const val EQUATOR_M_PER_PX = 156543.03392

    fun worldSize(zoom: Double): Double = TILE * Math.pow(2.0, zoom)

    fun lonToX(lon: Double, zoom: Double): Double = (lon + 180.0) / 360.0 * worldSize(zoom)

    fun latToY(lat: Double, zoom: Double): Double {
        val rad = lat.coerceIn(-MAX_LAT, MAX_LAT) * PI / 180.0
        return (1.0 - ln(tan(rad) + 1.0 / cos(rad)) / PI) / 2.0 * worldSize(zoom)
    }

    fun xToLon(x: Double, zoom: Double): Double = x / worldSize(zoom) * 360.0 - 180.0

    fun yToLat(y: Double, zoom: Double): Double {
        val n = PI * (1.0 - 2.0 * y / worldSize(zoom))
        return atan(sinh(n)) * 180.0 / PI
    }

    /**
     * Integer tile level for [zoom]: tiles are scaled between levels and the
     * level changes when the scale would pass 1.5x (up) or 0.75x (down).
     */
    fun tileLevel(zoom: Double): Int =
        floor(zoom - ln(SWITCH_DOWN) / ln(2.0)).toInt().coerceIn(0, 19)

    /** On-screen size factor of a level-[z] tile at [zoom]; stays within 0.75..1.5 for [tileLevel]. */
    fun tileScale(zoom: Double, z: Int): Double = Math.pow(2.0, zoom - z)

    /** Tiles covering a [widthPx] x [heightPx] canvas centred on [view], at level [z]. */
    fun tileRange(view: MapView, widthPx: Double, heightPx: Double, z: Int): TileRange {
        val scale = tileScale(view.zoom, z)
        val cx = lonToX(view.center.lon, z.toDouble())
        val cy = latToY(view.center.lat, z.toDouble())
        val halfW = widthPx / 2.0 / scale
        val halfH = heightPx / 2.0 / scale
        val last = (1 shl z) - 1
        return TileRange(
            z = z,
            minX = floor((cx - halfW) / TILE).toInt().coerceIn(0, last),
            maxX = floor((cx + halfW) / TILE).toInt().coerceIn(0, last),
            minY = floor((cy - halfH) / TILE).toInt().coerceIn(0, last),
            maxY = floor((cy + halfH) / TILE).toInt().coerceIn(0, last),
        )
    }

    /** Metres covered by one screen pixel at [lat] and [zoom]. */
    fun metersPerPixel(lat: Double, zoom: Double): Double =
        EQUATOR_M_PER_PX * cos(lat.coerceIn(-MAX_LAT, MAX_LAT) * PI / 180.0) / Math.pow(2.0, zoom)

    /**
     * View that shows every point inside [widthPx] x [heightPx] with [paddingPx]
     * around it. One point (or points on top of each other) centres at [singleZoom].
     */
    fun fitBounds(
        points: List<LatLon>,
        widthPx: Double,
        heightPx: Double,
        paddingPx: Double = 48.0,
        singleZoom: Double = 16.0,
    ): MapView? {
        if (points.isEmpty()) return null
        val xs = points.map { lonToX(it.lon, 0.0) }
        val ys = points.map { latToY(it.lat, 0.0) }
        val spanX = xs.max() - xs.min()
        val spanY = ys.max() - ys.min()
        val center = LatLon(
            yToLat((ys.max() + ys.min()) / 2.0, 0.0),
            xToLon((xs.max() + xs.min()) / 2.0, 0.0),
        )
        if (spanX < 1e-9 && spanY < 1e-9) return MapView(center, singleZoom)
        val availW = max(1.0, widthPx - 2 * paddingPx)
        val availH = max(1.0, heightPx - 2 * paddingPx)
        val zx = if (spanX < 1e-9) Double.MAX_VALUE else ln(availW / spanX) / ln(2.0)
        val zy = if (spanY < 1e-9) Double.MAX_VALUE else ln(availH / spanY) / ln(2.0)
        return MapView(center, min(zx, zy).coerceIn(MIN_ZOOM, singleZoom))
    }

    /** [view] dragged by [dxPx], [dyPx] screen pixels (content follows the finger). */
    fun pan(view: MapView, dxPx: Double, dyPx: Double): MapView {
        val size = worldSize(view.zoom)
        val x = (lonToX(view.center.lon, view.zoom) - dxPx).coerceIn(0.0, size)
        val y = (latToY(view.center.lat, view.zoom) - dyPx).coerceIn(0.0, size)
        return MapView(LatLon(yToLat(y, view.zoom), xToLon(x, view.zoom)), view.zoom)
    }

    /**
     * [view] zoomed by [dz] levels keeping the point under the focal offset
     * ([focalDx], [focalDy] from the canvas centre, in pixels) fixed.
     */
    fun zoomAround(view: MapView, dz: Double, focalDx: Double, focalDy: Double): MapView {
        val newZoom = (view.zoom + dz).coerceIn(MIN_ZOOM, MAX_ZOOM)
        val factor = Math.pow(2.0, newZoom - view.zoom)
        val px = lonToX(view.center.lon, view.zoom) + focalDx
        val py = latToY(view.center.lat, view.zoom) + focalDy
        val size = worldSize(newZoom)
        val cx = (px * factor - focalDx).coerceIn(0.0, size)
        val cy = (py * factor - focalDy).coerceIn(0.0, size)
        return MapView(LatLon(yToLat(cy, newZoom), xToLon(cx, newZoom)), newZoom)
    }

    /** Screen offset from the canvas centre of [p] when the map shows [view]. */
    fun offsetFromCenter(view: MapView, p: LatLon): Pair<Double, Double> =
        (lonToX(p.lon, view.zoom) - lonToX(view.center.lon, view.zoom)) to
            (latToY(p.lat, view.zoom) - latToY(view.center.lat, view.zoom))
}
