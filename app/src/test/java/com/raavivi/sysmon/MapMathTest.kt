package com.raavivi.sysmon

import com.raavivi.sysmon.ui.findme.LatLon
import com.raavivi.sysmon.ui.findme.MapMath
import com.raavivi.sysmon.ui.findme.MapView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MapMathTest {

    @Test
    fun `projection round trip`() {
        for (zoom in listOf(2.0, 7.5, 16.0)) {
            for ((lat, lon) in listOf(-33.9249 to 18.4241, 51.5074 to -0.1278, 0.0 to 0.0, 70.0 to 170.0)) {
                val x = MapMath.lonToX(lon, zoom)
                val y = MapMath.latToY(lat, zoom)
                assertEquals(lat, MapMath.yToLat(y, zoom), 1e-6)
                assertEquals(lon, MapMath.xToLon(x, zoom), 1e-6)
            }
        }
    }

    @Test
    fun `equator and prime meridian are the world centre`() {
        assertEquals(256.0, MapMath.lonToX(0.0, 0.0) * 2, 1e-9)
        assertEquals(128.0, MapMath.latToY(0.0, 0.0), 1e-9)
        assertEquals(512.0, MapMath.lonToX(180.0, 1.0), 1e-9)
    }

    @Test
    fun `tile level switches at 1_5x and 0_75x`() {
        assertEquals(10, MapMath.tileLevel(10.0))
        // Up to just below 1.5x scale (zoom 10.58) the level stays.
        assertEquals(10, MapMath.tileLevel(10.5))
        assertEquals(11, MapMath.tileLevel(10.6))
        // Down to 0.75x (zoom 9.585) the level stays; below it the lower level takes over.
        assertEquals(10, MapMath.tileLevel(9.6))
        assertEquals(9, MapMath.tileLevel(9.5))
        for (z in listOf(3.2, 8.77, 12.43, 15.99)) {
            val scale = MapMath.tileScale(z, MapMath.tileLevel(z))
            assertTrue("scale $scale at $z", scale >= 0.75 && scale < 1.5)
        }
    }

    @Test
    fun `tile range at an integer zoom`() {
        // Zoom 1 is a 2x2 tile world; a 256 px canvas at the centre straddles all four tiles.
        val r = MapMath.tileRange(MapView(LatLon(0.0, 0.0), 1.0), 256.0, 256.0, 1)
        assertEquals(0, r.minX); assertEquals(1, r.maxX)
        assertEquals(0, r.minY); assertEquals(1, r.maxY)
        assertEquals(4, r.count)
    }

    @Test
    fun `tile range is clamped to the world`() {
        val r = MapMath.tileRange(MapView(LatLon(0.0, 0.0), 2.0), 4000.0, 4000.0, 2)
        assertEquals(0, r.minX); assertEquals(3, r.maxX)
        assertEquals(0, r.minY); assertEquals(3, r.maxY)
    }

    @Test
    fun `tile range follows the centre`() {
        // Cape Town at level 10: x ~ 548, y ~ 585 in tile units.
        val r = MapMath.tileRange(MapView(LatLon(-33.9249, 18.4241), 10.0), 100.0, 100.0, 10)
        val cx = (MapMath.lonToX(18.4241, 10.0) / 256).toInt()
        val cy = (MapMath.latToY(-33.9249, 10.0) / 256).toInt()
        assertTrue(cx in r.minX..r.maxX)
        assertTrue(cy in r.minY..r.maxY)
    }

    @Test
    fun `fit bounds with no points is null`() {
        assertNull(MapMath.fitBounds(emptyList(), 800.0, 600.0))
    }

    @Test
    fun `fit bounds for one point centres on it at the single zoom`() {
        val v = MapMath.fitBounds(listOf(LatLon(-33.9, 18.4)), 800.0, 600.0)!!
        assertEquals(-33.9, v.center.lat, 1e-6)
        assertEquals(18.4, v.center.lon, 1e-6)
        assertEquals(16.0, v.zoom, 1e-9)
    }

    @Test
    fun `fit bounds for many points shows them all inside the padding`() {
        val pts = listOf(LatLon(-33.9, 18.4), LatLon(-26.2, 28.0), LatLon(-29.8, 31.0))
        val view = MapMath.fitBounds(pts, 800.0, 600.0, paddingPx = 48.0)!!
        assertTrue(view.zoom < 16.0 && view.zoom >= MapMath.MIN_ZOOM)
        for (p in pts) {
            val (dx, dy) = MapMath.offsetFromCenter(view, p)
            assertTrue("dx $dx", Math.abs(dx) <= 400.0 - 48.0 + 1e-6)
            assertTrue("dy $dy", Math.abs(dy) <= 300.0 - 48.0 + 1e-6)
        }
        // A little further out would overflow the padded box on at least one axis.
        val tighter = MapView(view.center, view.zoom + 0.2)
        assertTrue(pts.any { p ->
            val (dx, dy) = MapMath.offsetFromCenter(tighter, p)
            Math.abs(dx) > 400.0 - 48.0 || Math.abs(dy) > 300.0 - 48.0
        })
    }

    @Test
    fun `pan moves the centre against the drag`() {
        val v = MapView(LatLon(0.0, 0.0), 3.0)
        val panned = MapMath.pan(v, 100.0, 0.0)
        assertTrue(panned.center.lon < 0.0)
        assertEquals(0.0, panned.center.lat, 1e-9)
        // Panning back restores the centre.
        val back = MapMath.pan(panned, -100.0, 0.0)
        assertEquals(0.0, back.center.lon, 1e-9)
    }

    @Test
    fun `zoom around the centre keeps it and clamps to the limits`() {
        val v = MapView(LatLon(10.0, 20.0), 8.0)
        val z = MapMath.zoomAround(v, 1.0, 0.0, 0.0)
        assertEquals(9.0, z.zoom, 1e-9)
        assertEquals(10.0, z.center.lat, 1e-9)
        assertEquals(20.0, z.center.lon, 1e-9)
        assertEquals(MapMath.MAX_ZOOM, MapMath.zoomAround(v, 50.0, 0.0, 0.0).zoom, 1e-9)
        assertEquals(MapMath.MIN_ZOOM, MapMath.zoomAround(v, -50.0, 0.0, 0.0).zoom, 1e-9)
    }

    @Test
    fun `zoom around a focal point keeps that point fixed on screen`() {
        val v = MapView(LatLon(10.0, 20.0), 8.0)
        val focal = 120.0 to -80.0
        // The point under the focal pixel before zooming.
        val atFocal = LatLon(
            MapMath.yToLat(MapMath.latToY(v.center.lat, v.zoom) + focal.second, v.zoom),
            MapMath.xToLon(MapMath.lonToX(v.center.lon, v.zoom) + focal.first, v.zoom),
        )
        val z = MapMath.zoomAround(v, 1.0, focal.first, focal.second)
        val (dx, dy) = MapMath.offsetFromCenter(z, atFocal)
        assertEquals(focal.first, dx, 1e-6)
        assertEquals(focal.second, dy, 1e-6)
    }

    @Test
    fun `metres per pixel halves per zoom level and shrinks with latitude`() {
        val a = MapMath.metersPerPixel(0.0, 10.0)
        assertEquals(a / 2, MapMath.metersPerPixel(0.0, 11.0), 1e-9)
        assertEquals(a / 2, MapMath.metersPerPixel(60.0, 10.0), 1e-6)
        assertNotNull(MapMath.metersPerPixel(89.0, 5.0))
    }
}
